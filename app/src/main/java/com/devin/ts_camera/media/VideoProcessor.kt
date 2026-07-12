package com.devin.ts_camera.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import com.devin.ts_camera.model.CompressionLevel
import com.devin.ts_camera.model.VideoResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Video post-processing via FFmpeg — trim, compress, dynamic timestamp,
 * mute, and frame-rate control in a single pass.
 */
object VideoProcessor {

    private const val TAG = "VideoProcessor"

    fun getDurationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get duration", e)
            0L
        } finally {
            retriever.release()
        }
    }

    fun getFrameAtTime(context: Context, uri: Uri, timeMs: Long): android.graphics.Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract frame", e)
            null
        } finally {
            retriever.release()
        }
    }

    fun getThumbnails(
        context: Context, uri: Uri, durationMs: Long, count: Int = 10
    ): List<android.graphics.Bitmap> {
        if (durationMs <= 0) return emptyList()
        val step = durationMs / (count + 1)
        return (1..count).mapNotNull { i -> getFrameAtTime(context, uri, step * i) }
    }

    // ---- Transcoding -----------------------------------------------------

    data class TranscodeParams(
        val inputUri: Uri,
        val outputFile: File,
        val resolution: VideoResolution = VideoResolution.R1080P,
        val compressionLevel: CompressionLevel = CompressionLevel.LOW,
        val trimStartMs: Long = 0L,
        val trimEndMs: Long = 0L,
        val overlayTimestampMs: Long = 0L,
        val videoStartWallTimeMs: Long = 0L,
        val muteAudio: Boolean = false,
        val fps: Int = 0
    )

    /**
     * Transcode via FFmpeg — one-pass: trim + scale + dynamic timestamp + mute + fps.
     */
    suspend fun transcode(
        context: Context,
        params: TranscodeParams,
        onProgress: (Float) -> Unit = {}
    ): Uri = withContext(Dispatchers.IO) {

        val realDurationMs = if (params.trimEndMs > 0) {
            val raw = getDurationMs(context, params.inputUri)
            params.trimEndMs.coerceAtMost(raw)
        } else {
            getDurationMs(context, params.inputUri)
        }
        val effectiveDurationMs = (realDurationMs - params.trimStartMs).coerceAtLeast(0)

        // —— Build command ——
        val useDynamic = params.videoStartWallTimeMs > 0
                && params.overlayTimestampMs > 0
                && effectiveDurationMs in 1000..300_000

        val cmd = buildCommand(
            inputPath = params.inputUri.path!!,
            outputPath = params.outputFile.absolutePath,
            useDrawtext = useDynamic,
            startEpochSec = if (useDynamic) params.videoStartWallTimeMs / 1000 else 0,
            trimStartMs = params.trimStartMs,
            trimEndMs = realDurationMs,
            isTrimming = params.trimStartMs > 0 || params.trimEndMs > 0,
            resolution = params.resolution,
            compression = params.compressionLevel,
            muteAudio = params.muteAudio,
            fps = params.fps
        )

        Log.d(TAG, "FFmpeg: $cmd")

        // —— Execute (async with progress via StatisticsCallback) ——
        val progressLock = Object()
        var lastProgressMs = 0.0
        val session = FFmpegKit.executeAsync(cmd, { }, { }, { stats ->
            synchronized(progressLock) {
                lastProgressMs = stats.time
            }
        })
        try {
            // Poll progress until FFmpeg finishes
            while (session.state != SessionState.COMPLETED
                && session.state != SessionState.FAILED
            ) {
                val currentMs = synchronized(progressLock) { lastProgressMs }
                if (currentMs > 0.0 && effectiveDurationMs > 0) {
                    onProgress((currentMs.toFloat() / effectiveDurationMs).coerceIn(0f, 0.99f))
                }
                kotlinx.coroutines.delay(200L)
            }
        } finally {
            session.cancel()
        }

        if (ReturnCode.isSuccess(session.returnCode)) {
            onProgress(1f)
        } else {
            Log.e(TAG, "FFmpeg failed: ${session.allLogsAsString}")
            throw RuntimeException("视频转码失败")
        }

        Uri.fromFile(params.outputFile)
    }

    // ---- Command builder -------------------------------------------------

    private fun buildCommand(
        inputPath: String, outputPath: String, useDrawtext: Boolean, startEpochSec: Long,
        trimStartMs: Long, trimEndMs: Long, isTrimming: Boolean,
        resolution: VideoResolution, compression: CompressionLevel,
        muteAudio: Boolean, fps: Int
    ): String = buildString {
        append("-y ")

        // Trim (output option — accurate)
        if (isTrimming) {
            append("-ss ${fmtSec(trimStartMs)} ")
            append("-to ${fmtSec(trimEndMs)} ")
        }
        append("-i \"$inputPath\" ")

        // Video filter chain
        val filters = mutableListOf<String>()
        // Dynamic timestamp via drawtext (no external file needed)
        if (useDrawtext) {
            // %{localtime\:<epoch>+t} → wall-clock time at each frame
            filters.add(
                "drawtext=text='%{localtime}':fontfile=/system/fonts/Roboto-Regular.ttf:" +
                "fontsize=36:fontcolor=white@0.9:" +
                "box=1:boxcolor=black@0.4:boxborderw=6:" +
                "x=(w-text_w)/2:y=h-th-30"
            )
        }
        filters.add("format=yuv420p")
        append("-vf \"${filters.joinToString(",")}\" ")

        // Frame rate
        if (fps > 0) {
            append("-r $fps ")
        }

        // Video codec — software mpeg4
        append("-c:v mpeg4 ")
        append("-q:v ${when (compression) {
            CompressionLevel.HIGH -> "10"; CompressionLevel.MEDIUM -> "7"; CompressionLevel.LOW -> "5"
        }} ")

        // Audio
        if (muteAudio) {
            append("-an ")
        } else {
            append("-c:a aac -b:a 128k ")
        }

        // Output
        append("\"$outputPath\"")
    }

    private fun fmtSec(ms: Long) = "%.3f".format(ms / 1000.0)

    private fun parseTimeToMs(s: String): Long {
        val p = s.split(":", ".")
        if (p.size < 3) return 0
        return (p[0].toLongOrNull()?.times(3600_000) ?: 0) +
                (p[1].toLongOrNull()?.times(60_000) ?: 0) +
                (p[2].toLongOrNull()?.times(1000) ?: 0) +
                (p.getOrNull(3)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0)
    }
}
