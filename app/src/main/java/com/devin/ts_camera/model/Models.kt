package com.devin.ts_camera.model

import android.net.Uri
import java.io.File

/**
 * GPS location data captured with media.
 */
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toDisplayString(): String = "%.6f, %.6f".format(latitude, longitude)

    fun isValid(): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/**
 * Supported video output resolutions.
 */
enum class VideoResolution(
    val label: String,
    val width: Int,
    val height: Int,
    val defaultBitrate: Int,   // bps
    val minBitrate: Int,
    val maxBitrate: Int
) {
    R480P("480p", 720, 480, 2_500_000, 1_000_000, 5_000_000),
    R720P("720p", 1280, 720, 5_000_000, 2_000_000, 10_000_000),
    R1080P("1080p", 1920, 1080, 8_000_000, 4_000_000, 20_000_000),
    R4K("4K", 3840, 2160, 20_000_000, 10_000_000, 50_000_000)
}

/**
 * Compression / quality level presented to the user.
 */
enum class CompressionLevel(val label: String, val fraction: Float) {
    HIGH("高压缩", 0.3f),       // 30 % of default bitrate → smallest file
    MEDIUM("中等", 0.6f),
    LOW("低压缩", 1.0f)         // 100 % of default bitrate → best quality
}

/**
 * Save options the user configures before persisting media.
 */
data class SaveOptions(
    val filename: String = "",
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,          // 0 means "use full duration"
    val resolution: VideoResolution = VideoResolution.R1080P,
    val compressionLevel: CompressionLevel = CompressionLevel.LOW,
    val muteAudio: Boolean = false,     // remove audio track from saved video
    val fps: Int = 0                   // 0 = keep original; >0 = force output frame rate
) {
    /** Actual bitrate after applying compression level to the resolution default. */
    fun effectiveBitrate(): Int =
        (resolution.defaultBitrate * compressionLevel.fraction).toInt()
            .coerceIn(resolution.minBitrate, resolution.maxBitrate)

    /**
     * Estimated output file size in bytes.
     * @param durationMs trimmed duration (endMs - startMs).  If <= 0 the full
     *        source duration is assumed.
     */
    fun estimatedFileSize(durationMs: Long): Long {
        val effectiveDuration = if (durationMs > 0) durationMs else (trimEndMs - trimStartMs)
        if (effectiveDuration <= 0) return 0L
        // file_bytes ≈ bitrate_bps * duration_seconds / 8
        return effectiveBitrate().toLong() * effectiveDuration / 1000L / 8L
    }
}

/**
 * Capture mode — photo or video.
 */
enum class CaptureMode { PHOTO, VIDEO }

/**
 * Flash mode state.
 */
enum class FlashMode(val label: String) {
    OFF("关闭"),
    ON("打开"),
    AUTO("自动")
}

/**
 * Result of a photo capture.
 */
data class PhotoResult(
    val uri: Uri,
    val file: File,
    val timestamp: Long = System.currentTimeMillis(),
    val location: LocationData? = null
)

/**
 * Result of a video recording.
 */
data class VideoResult(
    val uri: Uri,
    val file: File,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val location: LocationData? = null
)

/**
 * Top-level camera UI state held in the ViewModel.
 */
data class CameraUiState(
    val isRecording: Boolean = false,
    val recordingDurationMs: Long = 0L,
    val lastPhoto: PhotoResult? = null,
    val lastVideo: VideoResult? = null,
    val location: LocationData? = null,
    val hasCameraPermission: Boolean = false,
    val hasAudioPermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val cameraFacingFront: Boolean = false,
    val flashMode: FlashMode = FlashMode.OFF,
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val isProcessing: Boolean = false,          // true while trimming/compressing
    val processingProgress: Float = 0f,         // 0..1
    val errorMessage: String? = null
) {
    /** Convenience: is there a captured result ready to preview? */
    val hasPendingResult: Boolean
        get() = lastPhoto != null || lastVideo != null
}
