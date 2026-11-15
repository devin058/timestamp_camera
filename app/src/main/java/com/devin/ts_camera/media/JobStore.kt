package com.devin.ts_camera.media

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Lifecycle of one recording through the transcode queue. */
enum class JobState { PENDING, RUNNING, FAILED }

/**
 * One recording waiting to be transcoded.
 *
 * @param inputPath absolute path of the raw CameraX output in the cache dir
 * @param startWallTimeMs wall-clock instant of the first recorded frame
 * @param durationMs how long the recording ran, in milliseconds
 * @param attempts how many transcode attempts have already failed
 */
data class VideoJob(
    val id: Long,
    val inputPath: String,
    val startWallTimeMs: Long,
    val durationMs: Long,
    val attempts: Int = 0,
    val state: JobState = JobState.PENDING,
    /**
     * True when the recorder already burned the timestamp into every frame,
     * so the transcode pass must not add a second one.
     */
    val stampedDuringRecording: Boolean = false
)

/**
 * On-disk queue of [VideoJob]s.
 *
 * The queue lives in app-private storage so jobs survive the process being
 * killed (the transcode takes minutes, and Android is free to reclaim the app
 * while it is in the background). Every read/write is small and infrequent, so
 * a plain JSON file with synchronized access is enough.
 */
object JobStore {

    private const val TAG = "JobStore"
    private const val FILE_NAME = "video_jobs.json"

    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun all(context: Context): List<VideoJob> = synchronized(lock) {
        read(context)
    }

    fun pendingCount(context: Context): Int =
        all(context).count { it.state != JobState.FAILED }

    fun nextPending(context: Context): VideoJob? = synchronized(lock) {
        val jobs = read(context)
        jobs.firstOrNull { it.state == JobState.PENDING }
    }

    fun enqueue(
        context: Context,
        inputPath: String,
        startWallTimeMs: Long,
        durationMs: Long,
        stampedDuringRecording: Boolean = false
    ): VideoJob = synchronized(lock) {
        val jobs = read(context).toMutableList()
        val id = (jobs.maxOfOrNull { it.id } ?: 0L) + 1L
        val job = VideoJob(
            id = id,
            inputPath = inputPath,
            startWallTimeMs = startWallTimeMs,
            durationMs = durationMs,
            stampedDuringRecording = stampedDuringRecording
        )
        jobs.add(job)
        write(context, jobs)
        job
    }

    fun update(context: Context, job: VideoJob) = synchronized(lock) {
        val jobs = read(context).toMutableList()
        val index = jobs.indexOfFirst { it.id == job.id }
        if (index >= 0) jobs[index] = job else jobs.add(job)
        write(context, jobs)
    }

    fun remove(context: Context, id: Long) = synchronized(lock) {
        val jobs = read(context).filterNot { it.id == id }
        write(context, jobs)
    }

    // ---- Serialization ----------------------------------------------------

    private fun read(context: Context): List<VideoJob> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val array = JSONArray(f.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val path = o.optString("inputPath")
                if (path.isBlank()) return@mapNotNull null
                VideoJob(
                    id = o.optLong("id"),
                    inputPath = path,
                    startWallTimeMs = o.optLong("startWallTimeMs"),
                    durationMs = o.optLong("durationMs"),
                    attempts = o.optInt("attempts"),
                    state = runCatching {
                        JobState.valueOf(o.optString("state", JobState.PENDING.name))
                    }.getOrDefault(JobState.PENDING),
                    stampedDuringRecording = o.optBoolean("stampedDuringRecording", false)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read job queue; starting empty", e)
            emptyList()
        }
    }

    private fun write(context: Context, jobs: List<VideoJob>) {
        val array = JSONArray()
        jobs.forEach { job ->
            array.put(
                JSONObject().apply {
                    put("id", job.id)
                    put("inputPath", job.inputPath)
                    put("startWallTimeMs", job.startWallTimeMs)
                    put("durationMs", job.durationMs)
                    put("attempts", job.attempts)
                    put("state", job.state.name)
                    put("stampedDuringRecording", job.stampedDuringRecording)
                }
            )
        }
        try {
            // Write-then-rename so a kill mid-write can't truncate the queue.
            val target = file(context)
            val temp = File(target.parentFile, "$FILE_NAME.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                target.writeText(array.toString())
                temp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist job queue", e)
        }
    }
}
