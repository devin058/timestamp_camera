package com.devin.ts_camera.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.devin.ts_camera.model.CompressionLevel
import com.devin.ts_camera.model.VideoResolution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

/**
 * Video post-processing: duration queries, trimming, and compression via
 * Media3 Transformer.
 *
 * Supports dynamic per-second timestamp overlays so that the timestamp burned
 * into the video changes over time — like a real dashcam / surveillance camera.
 */
object VideoProcessor {

    private const val TAG = "VideoProcessor"

    /**
     * Retrieve the duration of a video file in milliseconds.
     */
    fun getDurationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val timeStr = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )
            timeStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get duration", e)
            0L
        } finally {
            retriever.release()
        }
    }

    /**
     * Extract a single frame thumbnail at the given [timeMs].
     * Returns null on failure.
     */
    fun getFrameAtTime(context: Context, uri: Uri, timeMs: Long): android.graphics.Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(
                timeMs * 1000L,  // microseconds
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract frame", e)
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Extract [count] evenly-spaced thumbnails across the video duration.
     */
    fun getThumbnails(
        context: Context,
        uri: Uri,
        durationMs: Long,
        count: Int = 10
    ): List<android.graphics.Bitmap> {
        if (durationMs <= 0) return emptyList()
        val step = durationMs / (count + 1)
        return (1..count).mapNotNull { i ->
            getFrameAtTime(context, uri, step * i)
        }
    }

    // ---- Transcoding (trim + compress) ------------------------------------

    data class TranscodeParams(
        val inputUri: Uri,
        val outputFile: File,
        val resolution: VideoResolution = VideoResolution.R1080P,
        val compressionLevel: CompressionLevel = CompressionLevel.LOW,
        val trimStartMs: Long = 0L,
        val trimEndMs: Long = 0L,            // 0 = until end
        val overlayTimestampMs: Long = 0L,   // 0 = no overlay; >0 = static overlay (legacy)
        val videoStartWallTimeMs: Long = 0L  // wall-clock when recording STARTED (for dynamic overlay)
    )

    /**
     * Transcode a video — trim / compress / burn dynamic timestamp — and write
     * the result to [params.outputFile].  Suspends until complete, then returns
     * the output [Uri].
     *
     * When [TranscodeParams.videoStartWallTimeMs] > 0, the video is split into
     * 1-second segments, each with its own timestamp overlay that reflects the
     * wall-clock time at that point in the recording.  This produces a "live"
     * timestamp that changes as the video plays, like security-camera footage.
     */
    suspend fun transcode(
        context: Context,
        params: TranscodeParams,
        onProgress: (Float) -> Unit = {}
    ): Uri = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Uri>()

        // Determine actual duration (trim range or full video)
        val effectiveDurationMs: Long = if (params.trimEndMs > 0) {
            params.trimEndMs - params.trimStartMs
        } else {
            // Query the real duration so we can segment for dynamic overlay
            val full = getDurationMs(context, params.inputUri)
            (full - params.trimStartMs).coerceAtLeast(0)
        }

        // ---- Decide: dynamic per-second segments or single static overlay ----

        val useDynamic = params.videoStartWallTimeMs > 0
                && params.overlayTimestampMs > 0
                && effectiveDurationMs > 0

        val composition = if (useDynamic && effectiveDurationMs > 0) {
            val segDurationMs = 1000L // 1-second segments — timestamp changes every second
            val totalSegs = ceil(effectiveDurationMs / segDurationMs.toDouble())
                .toInt().coerceAtLeast(1)
            Log.d(TAG, "Building $totalSegs × ${segDurationMs}ms segments for dynamic timestamp overlay")

            val segments = mutableListOf<EditedMediaItem>()

            for (seg in 0 until totalSegs) {
                val segStartMs = (seg * segDurationMs)
                // Guard: ceil() may produce an extra segment that starts beyond the video
                if (segStartMs >= effectiveDurationMs) break
                val segEndMs   = (segStartMs + segDurationMs).coerceAtMost(effectiveDurationMs)

                // Wall-clock time for this segment (millisecond precision)
                val segWallTimeMs = params.videoStartWallTimeMs + segStartMs

                // Full-frame overlay with timestamp at bottom-center
                val overlayBitmap = TimestampWatermark.createOverlayBitmap(
                    videoWidth = params.resolution.width,
                    videoHeight = params.resolution.height,
                    timestamp = segWallTimeMs,
                    resolution = params.resolution
                )
                val bitmapOverlay =
                    BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)
                val overlayEffect = OverlayEffect(listOf(bitmapOverlay))
                val effects = Effects(
                    /* audioProcessors = */ emptyList(),
                    /* videoEffects = */ listOf(overlayEffect)
                )

                // Clip this segment from the source
                val clip = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(params.trimStartMs + segStartMs)
                    .setEndPositionMs(params.trimStartMs + segEndMs)
                    .build()

                val segmentMediaItem = MediaItem.Builder()
                    .setUri(params.inputUri)
                    .setClippingConfiguration(clip)
                    .build()

                val edited = EditedMediaItem.Builder(segmentMediaItem)
                    .setEffects(effects)
                    .build()

                segments.add(edited)
            }

            Composition.Builder(EditedMediaItemSequence(segments)).build()
        } else if (params.overlayTimestampMs > 0) {
            // ---- Legacy static overlay (no dynamic start time) ----------------
            val overlayBitmap = TimestampWatermark.createOverlayBitmap(
                videoWidth = params.resolution.width,
                videoHeight = params.resolution.height,
                timestamp = params.overlayTimestampMs,
                resolution = params.resolution
            )
            val bitmapOverlay =
                BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)
            val overlayEffect = OverlayEffect(listOf(bitmapOverlay))
            val effects = Effects(
                /* audioProcessors = */ emptyList(),
                /* videoEffects = */ listOf(overlayEffect)
            )

            val mediaItemBuilder = MediaItem.Builder()
                .setUri(params.inputUri)
            if (params.trimStartMs > 0 || params.trimEndMs > 0) {
                val clipBuilder = MediaItem.ClippingConfiguration.Builder()
                if (params.trimStartMs > 0) {
                    clipBuilder.setStartPositionMs(params.trimStartMs)
                }
                if (params.trimEndMs > 0) {
                    clipBuilder.setEndPositionMs(params.trimEndMs)
                }
                mediaItemBuilder.setClippingConfiguration(clipBuilder.build())
            }
            val clippedItem = mediaItemBuilder.build()

            val edited = EditedMediaItem.Builder(clippedItem)
                .setEffects(effects)
                .build()

            Composition.Builder(EditedMediaItemSequence(listOf(edited))).build()
        } else {
            // ---- No overlay — just trim --------------------------------
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(params.inputUri)
            if (params.trimStartMs > 0 || params.trimEndMs > 0) {
                val clipBuilder = MediaItem.ClippingConfiguration.Builder()
                if (params.trimStartMs > 0) {
                    clipBuilder.setStartPositionMs(params.trimStartMs)
                }
                if (params.trimEndMs > 0) {
                    clipBuilder.setEndPositionMs(params.trimEndMs)
                }
                mediaItemBuilder.setClippingConfiguration(clipBuilder.build())
            }
            val clippedItem = mediaItemBuilder.build()
            Composition.Builder(EditedMediaItemSequence(listOf(EditedMediaItem.Builder(clippedItem).build()))).build()
        }

        // ---- Build Transformer ------------------------------------------

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.d(TAG, "Transcode completed: ${params.outputFile.absolutePath}")
                    deferred.complete(Uri.fromFile(params.outputFile))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.e(TAG, "Transcode failed", exportException)
                    deferred.completeExceptionally(exportException)
                }
            })
            .build()

        transformer.start(composition, params.outputFile.absolutePath)
        deferred.await()
    }
}
