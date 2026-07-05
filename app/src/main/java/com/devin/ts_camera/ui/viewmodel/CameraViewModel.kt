package com.devin.ts_camera.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.devin.ts_camera.camera.CameraController
import com.devin.ts_camera.location.LocationProvider
import com.devin.ts_camera.media.MediaStoreSaver
import com.devin.ts_camera.media.TimestampWatermark
import com.devin.ts_camera.media.VideoProcessor
import com.devin.ts_camera.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    val cameraController = CameraController(application)

    private val locationProvider = LocationProvider(application)
    private var recordingTimerJob: Job? = null
    private var recordingStartTimeMs: Long = 0L
    private var videoRecordingStartWallTimeMs: Long = 0L  // wall-clock time when recording STARTED
    private var lastRecordedVideoFile: File? = null

    private var captureSequence = 0

    init {
        viewModelScope.launch {
            locationProvider.location.collect { loc ->
                _uiState.update { it.copy(location = loc) }
            }
        }
    }

    // ---- permissions -------------------------------------------------------

    fun onCameraPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
    }

    fun onAudioPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(hasAudioPermission = granted) }
    }

    fun onLocationPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(hasLocationPermission = granted) }
    }

    // ---- camera lifecycle --------------------------------------------------

    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraController.start(lifecycleOwner, previewView, _uiState.value.cameraFacingFront)
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.release()
        locationProvider.stop()
    }

    // ---- mode / controls ---------------------------------------------------

    fun setCaptureMode(mode: CaptureMode) {
        _uiState.update { it.copy(captureMode = mode) }
    }

    fun toggleCamera() {
        cameraController.toggleCamera()
        _uiState.update { it.copy(cameraFacingFront = !it.cameraFacingFront) }
    }

    fun toggleFlash() {
        val next = when (_uiState.value.flashMode) {
            FlashMode.OFF  -> FlashMode.ON
            FlashMode.ON   -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.OFF
        }
        cameraController.setFlashMode(next)
        _uiState.update { it.copy(flashMode = next) }
    }

    // ---- photo capture -----------------------------------------------------

    fun capturePhoto() {
        if (_uiState.value.isRecording) return
        _uiState.update { it.copy(isProcessing = true) }

        cameraController.takePhoto(
            onResult = { file ->
                val app = getApplication<Application>()
                val ts = System.currentTimeMillis()
                // Burn timestamp watermark into the photo immediately so the
                // preview shows it, not just the raw capture.
                val watermarked = TimestampWatermark.applyToPhoto(
                    sourceFile = file,
                    cacheDir = app.cacheDir,
                    timestamp = ts
                )
                val result = PhotoResult(
                    uri = Uri.fromFile(watermarked),
                    file = watermarked,
                    timestamp = ts,
                    location = _uiState.value.location
                )
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        lastPhoto = result,
                        lastVideo = null
                    )
                }
            },
            onError = { msg ->
                _uiState.update { it.copy(isProcessing = false, errorMessage = msg) }
            }
        )
    }

    // ---- video recording ---------------------------------------------------

    fun startVideoRecording() {
        if (_uiState.value.isRecording) return
        _uiState.update { it.copy(errorMessage = null) }

        cameraController.startRecording(
            onStarted = { videoFile ->
                lastRecordedVideoFile = videoFile
                recordingStartTimeMs = SystemClock.elapsedRealtime()
                videoRecordingStartWallTimeMs = System.currentTimeMillis()
                _uiState.update { it.copy(isRecording = true, recordingDurationMs = 0L) }
                startRecordingTimer()
            },
            onComplete = { videoFile ->
                recordingTimerJob?.cancel()
                val durationMs = SystemClock.elapsedRealtime() - recordingStartTimeMs
                val result = VideoResult(
                    uri = Uri.fromFile(videoFile),
                    file = videoFile,
                    durationMs = durationMs,
                    timestamp = System.currentTimeMillis(),
                    location = _uiState.value.location
                )
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        recordingDurationMs = durationMs,
                        lastVideo = result,
                        lastPhoto = null
                    )
                }
            },
            onError = { msg ->
                recordingTimerJob?.cancel()
                _uiState.update { it.copy(isRecording = false, errorMessage = msg) }
            }
        )
    }

    fun stopVideoRecording() {
        if (!_uiState.value.isRecording) return
        // Stop the timer immediately; onComplete handles the rest
        recordingTimerJob?.cancel()
        cameraController.stopRecording()
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(50L)
                val elapsed = SystemClock.elapsedRealtime() - recordingStartTimeMs
                _uiState.update { it.copy(recordingDurationMs = elapsed) }
            }
        }
    }

    // ---- clear -------------------------------------------------------------

    fun dismissPreview() {
        _uiState.update { it.copy(lastPhoto = null, lastVideo = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ---- save with options -------------------------------------------------

    fun saveWithOptions(options: SaveOptions, onComplete: (Uri, saveLocation: String) -> Unit) {
        val state = _uiState.value
        _uiState.update { it.copy(isProcessing = true, processingProgress = 0f) }

        viewModelScope.launch {
            try {
                when {
                    state.lastPhoto != null -> {
                        val finalUri = savePhoto(state.lastPhoto, options)
                        _uiState.update { it.copy(isProcessing = false, lastPhoto = null) }
                        onComplete(finalUri, MediaStoreSaver.getPhotoSaveLocationLabel())
                    }
                    state.lastVideo != null -> {
                        val finalUri = saveVideo(state.lastVideo, options)
                        _uiState.update { it.copy(isProcessing = false, lastVideo = null) }
                        onComplete(finalUri, MediaStoreSaver.getVideoSaveLocationLabel())
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isProcessing = false, errorMessage = e.message ?: "保存失败")
                }
            }
        }
    }

    private suspend fun savePhoto(photo: PhotoResult, options: SaveOptions): Uri {
        val app = getApplication<Application>()
        val filename = options.filename.ifBlank {
            generateDefaultFilename("IMG")
        }

        // Re-apply watermark (belt-and-suspenders — capture might have missed it)
        val watermarked = TimestampWatermark.applyToPhoto(
            sourceFile = photo.file,
            cacheDir = app.cacheDir,
            timestamp = photo.timestamp
        )

        // Write EXIF to the watermarked file before copying to MediaStore
        writeExifMetadata(watermarked, photo.location, photo.timestamp)

        return MediaStoreSaver.savePhoto(
            context = app,
            sourceFile = watermarked,
            displayName = filename
        )
    }

    private suspend fun saveVideo(video: VideoResult, options: SaveOptions): Uri {
        val app = getApplication<Application>()
        val filename = options.filename.ifBlank {
            generateDefaultFilename("VID")
        }

        // Always transcode so the timestamp watermark can be burned into the video.
        // Transcode writes to a temp file in cacheDir, then we copy to MediaStore.
        val transcodedFile = File.createTempFile("VID_transcode_", ".mp4", app.cacheDir)
        VideoProcessor.transcode(
            context = app,
            params = VideoProcessor.TranscodeParams(
                inputUri = video.uri,
                outputFile = transcodedFile,
                resolution = options.resolution,
                compressionLevel = options.compressionLevel,
                trimStartMs = options.trimStartMs,
                trimEndMs = if (options.trimEndMs > 0) options.trimEndMs else video.durationMs,
                overlayTimestampMs = videoRecordingStartWallTimeMs,
                videoStartWallTimeMs = videoRecordingStartWallTimeMs
            ),
            onProgress = { progress ->
                _uiState.update { it.copy(processingProgress = progress) }
            }
        )

        val uri = MediaStoreSaver.saveVideo(
            context = app,
            sourceFile = transcodedFile,
            displayName = filename
        )

        // Clean up transcode temp file
        transcodedFile.delete()

        // Write the metadata JSON sidecar to app-private storage
        writeVideoMetadataSidecar(app, filename, video, options)

        return uri
    }

    // ---- metadata helpers --------------------------------------------------

    private fun writeExifMetadata(file: File, location: LocationData?, timestamp: Long) {
        try {
            val exif = androidx.exifinterface.media.ExifInterface(file)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_DATETIME,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(timestamp))
            )
            if (location != null && location.isValid()) {
                val loc = android.location.Location("").apply {
                    latitude = location.latitude
                    longitude = location.longitude
                }
                exif.setGpsInfo(loc)
            }
            exif.saveAttributes()
        } catch (e: Exception) {
            android.util.Log.w("CameraViewModel", "EXIF write failed", e)
        }
    }

    private fun writeVideoMetadataSidecar(
        app: Application,
        filenameBase: String,
        video: VideoResult,
        options: SaveOptions
    ) {
        try {
            val metaDir = File(app.filesDir, "metadata")
            if (!metaDir.exists()) metaDir.mkdirs()
            val metaFile = File(metaDir, filenameBase + "_meta.json")
            val effectiveDuration = if (options.trimEndMs > 0) {
                options.trimEndMs - options.trimStartMs
            } else {
                video.durationMs
            }
            val meta = buildString {
                appendLine("{")
                appendLine("  \"filename\": \"$filenameBase.mp4\",")
                appendLine("  \"timestamp\": ${video.timestamp},")
                appendLine("  \"datetime\": \"${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(video.timestamp))}\",")
                appendLine("  \"durationMs\": $effectiveDuration,")
                if (video.location != null) {
                    appendLine("  \"latitude\": ${video.location.latitude},")
                    appendLine("  \"longitude\": ${video.location.longitude},")
                }
                appendLine("  \"resolution\": \"${options.resolution.label}\",")
                appendLine("  \"compression\": \"${options.compressionLevel.label}\"")
                appendLine("}")
            }
            metaFile.writeText(meta)
        } catch (e: Exception) {
            android.util.Log.w("CameraViewModel", "Metadata write failed", e)
        }
    }

    private fun generateDefaultFilename(prefix: String): String {
        captureSequence++
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${prefix}_${dateStr}_$captureSequence"
    }
}
