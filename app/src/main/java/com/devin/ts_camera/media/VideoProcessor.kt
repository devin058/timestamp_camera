package com.devin.ts_camera.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.Transformer
import com.devin.ts_camera.model.CompressionLevel
import com.devin.ts_camera.model.VideoResolution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Video post-processing: duration queries, trimming, and compression via
 * Media3 Transformer.
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
        val overlayTimestampMs: Long = 0L,   // 0 = no overlay; >0 = burn timestamp watermark
    )

    /**
     * Transcode a video — trim and/or compress — and write the result to
     * [params.outputFile].  Suspends until complete, then returns the output
     * [Uri].
     *
     * Uses Media3 Transformer which handles both clipping and re-encoding.
     */
    suspend fun transcode(
        context: Context,
        params: TranscodeParams,
        onProgress: (Float) -> Unit = {}
    ): Uri = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Uri>()

        // Build the MediaItem with optional clipping
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

        val mediaItem = mediaItemBuilder.build()

        // Wrap in EditedMediaItem if timestamp overlay is requested
        val editedMediaItem: EditedMediaItem? =
            if (params.overlayTimestampMs > 0) {
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
                EditedMediaItem.Builder(mediaItem)
                    .setEffects(effects)
                    .build()
            } else {
                null
            }

        // Build Transformer with encoder settings
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
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

        if (editedMediaItem != null) {
            transformer.start(editedMediaItem, params.outputFile.absolutePath)
        } else {
            transformer.start(mediaItem, params.outputFile.absolutePath)
        }
        deferred.await()
    }
}
