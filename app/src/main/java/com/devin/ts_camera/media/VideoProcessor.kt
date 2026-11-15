package com.devin.ts_camera.media

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.devin.ts_camera.model.CompressionLevel
import com.devin.ts_camera.model.VideoResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Video post-processing via FFmpeg — trim, scale, timestamp overlay, mute and
 * frame-rate control in a single decode→filter→encode pass.
 *
 * Encoding prefers hardware H.264 (`h264_mediacodec`) and falls back to
 * software `mpeg4` when the device has no usable encoder or the hardware
 * attempt fails, so one bad codec can never cost the user their recording.
 *
 * The timestamp is baked into one PNG per second and composited with
 * `overlay`. Doing it with `drawtext` re-shapes the glyphs for every frame,
 * which dominated the cost of the old pipeline.
 */
object VideoProcessor {

    private const val TAG = "VideoProcessor"

    /**
     * How much wall-clock time one pass may spend per second of footage before
     * the watchdog gives up. 3x realtime is comfortably above what hardware
     * H.264 achieves even at 1080p with the overlay, so tripping it means
     * something is genuinely wrong rather than merely slow.
     */
    private const val MAX_ENCODE_TO_SOURCE_RATIO = 3L

    /** Floor so short clips still get room for encoder start-up. */
    private const val MIN_SESSION_BUDGET_MS = 5 * 60 * 1000L

    private fun sessionBudgetMs(mediaDurationMs: Long): Long =
        (mediaDurationMs * MAX_ENCODE_TO_SOURCE_RATIO)
            .coerceAtLeast(MIN_SESSION_BUDGET_MS)

    /** Raised when an encode outlives its budget, so callers can tell this
     *  apart from a codec that simply isn't supported. */
    class TimeoutException(message: String) : IllegalStateException(message)

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

    /** Rotation the player applies, in degrees (0/90/180/270). */
    fun getRotation(uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(uri.path)
            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            retriever.release()
        }
    }

    data class VideoSize(val width: Int, val height: Int)

    /**
     * Frame size as displayed. CameraX stores a landscape buffer plus a
     * rotation flag, so raw metadata often reads 1920x1080 for a portrait
     * recording; the overlay must be built for the rotated shape.
     */
    fun getDisplaySize(uri: Uri): VideoSize? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(uri.path)
            val w = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: return null
            val h = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: return null
            if (w <= 0 || h <= 0) return null
            when (getRotation(uri)) {
                90, 270 -> VideoSize(h, w)
                else -> VideoSize(w, h)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get size", e)
            null
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
        /** 0 means "to the end of the source". */
        val trimEndMs: Long = 0L,
        /** Wall-clock time of the source's first frame, or 0 to omit the stamp. */
        val videoStartWallTimeMs: Long = 0L,
        val muteAudio: Boolean = false,
        /** 0 = keep the source frame rate. */
        val fps: Int = 0
    )

    /**
     * Transcode in a single pass: trim + scale + timestamp + mute + fps.
     *
     * @throws IllegalStateException if neither the hardware nor the software
     *         encoder produced a usable file.
     */
    suspend fun transcode(
        context: Context,
        params: TranscodeParams,
        onProgress: (Float) -> Unit = {}
    ): Uri = withContext(Dispatchers.IO) {

        val sourceDurationMs = getDurationMs(context, params.inputUri)
        val startMs = params.trimStartMs.coerceIn(0L, sourceDurationMs)
        val endMs = when {
            params.trimEndMs > 0 -> params.trimEndMs.coerceIn(startMs, sourceDurationMs)
            else -> sourceDurationMs
        }
        val effectiveDurationMs = endMs - startMs
        if (effectiveDurationMs <= 0L) {
            throw IllegalArgumentException("输入视频时长为 0 或裁剪范围无效")
        }

        val inputFile = params.inputUri.path?.let(::File)
        if (inputFile == null || !inputFile.exists() || inputFile.length() == 0L) {
            throw IllegalStateException("输入视频不存在或为空")
        }

        val outputFile = params.outputFile
        if (outputFile.exists()) outputFile.delete()

        // Must use the resolved range, not the raw params: the service passes
        // trimEndMs = 0 meaning "to the end", and feeding that straight to the
        // overlay produced a single PNG that `eof_action=repeat` then held for
        // the whole video.
        val overlay = createTimestampOverlay(context, params, startMs, endMs)
        val outputSize = outputSizeFor(params)

        // Nothing would change if we re-encoded this: the recorder already
        // burned the stamp in, the source is the requested size, no trim, the
        // frame rate is untouched, and audio isn't being dropped. Re-encoding
        // that only costs time and quality, so pass the file through.
        if (overlay == null && canPassThrough(params, outputSize, startMs, endMs, sourceDurationMs)) {
            Log.d(TAG, "Recording already matches the requested output; copying")
            onProgress(1f)
            inputFile.copyTo(outputFile, overwrite = true)
            return@withContext Uri.fromFile(outputFile)
        }

        val sourceSize = getDisplaySize(params.inputUri)
        val sourceMatchesOutput = sourceSize != null &&
                sourceSize.width == outputSize.width &&
                sourceSize.height == outputSize.height

        val base = BuildSpec(
            inputPath = inputFile.absolutePath,
            outputPath = outputFile.absolutePath,
            overlay = overlay,
            startMs = startMs,
            endMs = endMs,
            width = outputSize.width,
            height = outputSize.height,
            compression = params.compressionLevel,
            muteAudio = params.muteAudio,
            fps = params.fps,
            sourceMatchesOutput = sourceMatchesOutput
        )

        var triedHardware = false
        try {
            if (isHardwareH264EncoderAvailable()) {
                triedHardware = true
                runFfmpeg(base.copy(hardwareEncoder = true), effectiveDurationMs, onProgress)
                Log.d(TAG, "Hardware encode OK, ${outputFile.length()} bytes")
            } else {
                Log.w(TAG, "No H.264 hardware encoder; using software")
                runFfmpeg(base.copy(hardwareEncoder = false), effectiveDurationMs, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: VideoProcessor.TimeoutException) {
            // A timeout means the encode wedged, not that the encoder is
            // unsupported. Retrying in software is several times slower and
            // would almost certainly time out too, so surface it instead.
            Log.e(TAG, "Encode exceeded its budget; not retrying in software", e)
            throw e
        } catch (e: Exception) {
            // Only fall back if the failure came from the hardware attempt —
            // retrying software after software already failed just wastes time.
            if (!triedHardware) throw e
            Log.w(TAG, "Hardware encode failed, retrying in software", e)
            outputFile.delete()
            runFfmpeg(base.copy(hardwareEncoder = false), effectiveDurationMs, onProgress)
        } finally {
            overlay?.deleteRecursively()
        }

        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw IllegalStateException("转码未产生有效文件")
        }

        onProgress(1f)
        Uri.fromFile(outputFile)
    }

    /**
     * Last-resort export: remux/transcode with a plain filter chain and no
     * timestamp, used when the normal pass cannot produce a file at all.
     *
     * A recording without the burned-in stamp is far better than no recording,
     * so this never throws — the caller decides what to tell the user.
     */
    suspend fun transcodeWithoutStamp(
        context: Context,
        params: TranscodeParams,
        onProgress: (Float) -> Unit = {}
    ): Uri? = withContext(Dispatchers.IO) {
        val inputFile = params.inputUri.path?.let(::File) ?: return@withContext null
        if (!inputFile.exists()) return@withContext null

        try {
            transcode(context, params.copy(videoStartWallTimeMs = 0L), onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fallback transcode without timestamp failed", e)
            null
        }
    }

    /** Everything needed to build one FFmpeg command line. */
    private data class BuildSpec(
        val inputPath: String,
        val outputPath: String,
        val overlay: File?,
        val startMs: Long,
        val endMs: Long,
        val width: Int,
        val height: Int,
        val compression: CompressionLevel,
        val muteAudio: Boolean,
        val fps: Int,
        val hardwareEncoder: Boolean = true,
        val sourceMatchesOutput: Boolean = false
    )

    private fun buildCommand(spec: BuildSpec): String =
        TranscodeCommand.build(
            TranscodeCommand.Spec(
                inputPath = spec.inputPath,
                outputPath = spec.outputPath,
                overlay = spec.overlay,
                startMs = spec.startMs,
                endMs = spec.endMs,
                width = spec.width,
                height = spec.height,
                compression = spec.compression,
                muteAudio = spec.muteAudio,
                fps = spec.fps,
                hardwareEncoder = spec.hardwareEncoder,
                sourceMatchesOutput = spec.sourceMatchesOutput
            )
        )

    // ---- FFmpeg session ---------------------------------------------------

    private fun runFfmpeg(
        spec: BuildSpec,
        durationMs: Long,
        onProgress: (Float) -> Unit
    ) {
        val cmd = buildCommand(spec)
        Log.d(TAG, "FFmpeg: $cmd")

        // This FFmpegKit build has no session.await(), so the completion
        // callback is the only reliable "process exited" signal — polling
        // getState() can report COMPLETED while output is still flushing.
        val finished = CountDownLatch(1)
        var exitCode: Int = Int.MIN_VALUE
        val session = FFmpegKit.executeAsync(
            cmd,
            { completed ->
                exitCode = completed.returnCode?.value ?: Int.MIN_VALUE
                finished.countDown()
            },
            { },
            { stats ->
                if (durationMs > 0 && stats.time > 0) {
                    onProgress((stats.time.toFloat() / durationMs).coerceIn(0f, 0.99f))
                }
            }
        )

        // The budget scales with the footage so a long recording is never
        // killed mid-encode just for being long.
        val budgetMs = sessionBudgetMs(durationMs)
        if (!finished.await(budgetMs, TimeUnit.MILLISECONDS)) {
            FFmpegKit.cancel(session.sessionId)
            throw TimeoutException("转码超时（预算 ${budgetMs / 60_000} 分钟）")
        }

        if (exitCode == Int.MIN_VALUE) {
            val logs = session.allLogsAsString ?: ""
            Log.e(TAG, "FFmpeg produced no exit code: $logs")
            throw IllegalStateException("转码异常结束")
        }
        if (!ReturnCode.isSuccess(ReturnCode(exitCode))) {
            val logs = session.allLogsAsString ?: ""
            Log.e(TAG, "FFmpeg failed ($exitCode): $logs")
            val reason = logs.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.isNotEmpty() && !it.startsWith("frame=") }
            throw RuntimeException("FFmpeg 失败: ${reason ?: exitCode}")
        }
    }

    private fun createTimestampOverlay(
        context: Context,
        params: TranscodeParams,
        startMs: Long,
        endMs: Long
    ): File? {
        if (params.videoStartWallTimeMs <= 0L) return null
        return try {
            val display = getDisplaySize(params.inputUri)
                ?: VideoSize(params.resolution.width, params.resolution.height)

            // Scale the stamp to the output size, not the source size.
            val scale = minOf(
                params.resolution.width.toFloat() / display.width,
                params.resolution.height.toFloat() / display.height
            ).coerceAtMost(1f)
            val outW = (display.width * scale).toInt().coerceAtLeast(16)
            val outH = (display.height * scale).toInt().coerceAtLeast(16)

            TimestampOverlay.renderToFile(
                cacheDir = context.cacheDir,
                width = outW,
                height = outH,
                firstStampWallTimeMs = params.videoStartWallTimeMs + startMs,
                durationMs = endMs - startMs
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not build timestamp overlay; continuing without it", e)
            null
        }
    }

    /**
     * Whether the source can be published as-is.
     *
     * Only true when the transcode would be a no-op: full length (no trim),
     * source dimensions already equal to the requested output, frame rate
     * untouched, and audio kept. Anything else still needs a real encode.
     */
    private fun canPassThrough(
        params: TranscodeParams,
        outputSize: VideoSize,
        startMs: Long,
        endMs: Long,
        sourceDurationMs: Long
    ): Boolean {
        if (startMs > 0L || endMs < sourceDurationMs) return false
        if (params.fps > 0) return false

        val source = getDisplaySize(params.inputUri) ?: return false
        return source.width == outputSize.width && source.height == outputSize.height
    }

    /**
     * Output dimensions with the source's orientation preserved.
     *
     * The resolution presets are landscape (720x480, 1920x1080…). Feeding a
     * portrait recording through them letterboxes it inside a landscape frame,
     * which is not what the user saw while shooting.
     */
    private fun outputSizeFor(
        params: TranscodeParams
    ): VideoSize {
        val display = getDisplaySize(params.inputUri)
            ?: return VideoSize(params.resolution.width, params.resolution.height)

        val preset = params.resolution
        val longEdge = maxOf(preset.width, preset.height)
        val shortEdge = minOf(preset.width, preset.height)

        return if (display.height > display.width) {
            VideoSize(shortEdge, longEdge)
        } else {
            VideoSize(longEdge, shortEdge)
        }
    }

    /**
     * Whether the device exposes an H.264 encoder usable by FFmpeg's
     * MediaCodec wrapper. Checked up front so an unsupported device falls
     * straight to software instead of wasting time on a doomed encode.
     */
    private fun isHardwareH264EncoderAvailable(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals("video/avc", ignoreCase = true)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not query encoders", e)
        false
    }

    private fun fmtSec(ms: Long) = "%.3f".format(ms / 1000.0)
}
