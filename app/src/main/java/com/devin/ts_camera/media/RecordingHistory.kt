package com.devin.ts_camera.media

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One row in the history sheet: either a finished recording or one still
 * being processed.
 *
 * @param uri the MediaStore entry, or the raw file while processing
 * @param durationMs recorded length in milliseconds
 * @param savedAt when the transcode finished, or when it was recorded if the
 *        entry is still pending
 * @param address reverse-geocoded location, when one was available
 * @param pending true while the recording is still being transcoded
 */
data class RecordingEntry(
    val id: Long,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val savedAt: Long,
    val sizeBytes: Long,
    val address: String? = null,
    val pending: Boolean = false
)

/**
 * Append-only log of finished recordings, backing the history sheet.
 *
 * Kept separate from [JobStore]: that one tracks work still to do and is
 * pruned as jobs complete, whereas this one is a durable record of output the
 * user may want to find again.
 */
object RecordingHistory {

    private const val TAG = "RecordingHistory"
    private const val FILE_NAME = "recording_history.json"

    /** Beyond this the oldest entries are dropped. */
    private const val MAX_ENTRIES = 200

    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Newest first. */
    fun all(context: Context): List<RecordingEntry> = synchronized(lock) {
        read(context).sortedByDescending { it.savedAt }
    }

    /**
     * Finished recordings plus anything still queued or encoding, newest
     * first.
     *
     * Pending entries live in [JobStore] rather than this log — a job is only
     * written here once it reaches MediaStore — but the sheet should show
     * them so a long take is visible while it is still being worked on.
     *
     * Pending rows use negative ids so they cannot collide with the stored
     * ones, and are ordered by their start time.
     */
    fun allWithPending(context: Context): List<RecordingEntry> {
        val finished = all(context)
        val pending = JobStore.all(context)
            .filter { it.state != JobState.FAILED }
            .map { job ->
                RecordingEntry(
                    id = -job.id,
                    uri = Uri.fromFile(File(job.inputPath)).toString(),
                    displayName = "VID_${job.startWallTimeMs}",
                    durationMs = job.durationMs,
                    savedAt = job.startWallTimeMs,
                    // The raw file is still on disk; show its current size.
                    sizeBytes = File(job.inputPath).length(),
                    pending = true
                )
            }
        return (finished + pending).sortedByDescending { it.savedAt }
    }

    fun add(
        context: Context,
        uri: Uri,
        displayName: String,
        durationMs: Long,
        sizeBytes: Long,
        address: String? = null
    ): RecordingEntry = synchronized(lock) {
        val existing = read(context).toMutableList()
        val id = (existing.maxOfOrNull { it.id } ?: 0L) + 1L
        val entry = RecordingEntry(
            id = id,
            uri = uri.toString(),
            displayName = displayName,
            durationMs = durationMs,
            savedAt = System.currentTimeMillis(),
            sizeBytes = sizeBytes,
            address = address
        )
        existing.add(entry)

        // Oldest first in storage order, so trimming drops from the front.
        val trimmed = existing
            .sortedByDescending { it.savedAt }
            .take(MAX_ENTRIES)
            .sortedBy { it.savedAt }
        write(context, trimmed)
        entry
    }

    fun remove(context: Context, id: Long) = synchronized(lock) {
        write(context, read(context).filterNot { it.id == id })
    }

    fun clear(context: Context) = synchronized(lock) {
        write(context, emptyList())
    }

    // ---- Serialization ----------------------------------------------------

    private fun read(context: Context): List<RecordingEntry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val array = JSONArray(f.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri")
                if (uri.isBlank()) return@mapNotNull null
                RecordingEntry(
                    id = o.optLong("id"),
                    uri = uri,
                    displayName = o.optString("displayName"),
                    durationMs = o.optLong("durationMs"),
                    savedAt = o.optLong("savedAt"),
                    sizeBytes = o.optLong("sizeBytes"),
                    address = o.optString("address").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read recording history", e)
            emptyList()
        }
    }

    private fun write(context: Context, entries: List<RecordingEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("uri", entry.uri)
                    put("displayName", entry.displayName)
                    put("durationMs", entry.durationMs)
                    put("savedAt", entry.savedAt)
                    put("sizeBytes", entry.sizeBytes)
                    entry.address?.let { put("address", it) }
                }
            )
        }
        try {
            // Write-then-rename so a kill mid-write can't truncate the log.
            val target = file(context)
            val temp = File(target.parentFile, "$FILE_NAME.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                target.writeText(array.toString())
                temp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist recording history", e)
        }
    }
}
