package com.devin.ts_camera.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.devin.ts_camera.model.FlashMode
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Thin wrapper around CameraX that owns the Preview, ImageCapture, and
 * VideoCapture use-cases.
 */
class CameraController(private val context: Context) {

    companion object {
        private const val TAG = "CameraController"
        private const val FILENAME_PHOTO_PREFIX = "IMG_"
        private const val FILENAME_VIDEO_PREFIX = "VID_"
        private const val FILE_EXT_PHOTO = ".jpg"
        private const val FILE_EXT_VIDEO = ".mp4"
    }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor get() = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    var imageCapture: ImageCapture? = null
        private set
    var videoCapture: VideoCapture<Recorder>? = null
        private set
    private var recorder: Recorder? = null

    private var currentRecording: Recording? = null
    private var pendingVideoFile: File? = null
    private var onVideoStarted: ((File) -> Unit)? = null
    private var onVideoFinalized: ((File, errorMsg: String?) -> Unit)? = null

    private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var currentPreviewView: PreviewView? = null
    private var currentLifecycleOwner: LifecycleOwner? = null

    var isStarted: Boolean = false
        private set

    // ---- lifecycle --------------------------------------------------------

    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacingFront: Boolean = false
    ) {
        currentLifecycleOwner = lifecycleOwner
        currentPreviewView = previewView
        currentLensFacing = if (lensFacingFront) CameraSelector.LENS_FACING_FRONT
                            else CameraSelector.LENS_FACING_BACK

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindUseCases()
                isStarted = true
            },
            mainExecutor
        )
    }

    fun stop() {
        cameraProvider?.unbindAll()
        isStarted = false
    }

    fun release() {
        currentRecording?.close()
        currentRecording = null
        stop()
        cameraExecutor.shutdown()
    }

    // ---- lens / flash -----------------------------------------------------

    fun toggleCamera() {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT
        else
            CameraSelector.LENS_FACING_BACK
        bindUseCases()
    }

    fun setFlashMode(mode: FlashMode) {
        imageCapture?.flashMode = when (mode) {
            FlashMode.OFF  -> ImageCapture.FLASH_MODE_OFF
            FlashMode.ON   -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        }
    }

    // ---- photo ------------------------------------------------------------

    fun takePhoto(onResult: (File) -> Unit, onError: (String) -> Unit) {
        val imageCapture = this.imageCapture ?: run {
            onError("相机未就绪")
            return
        }
        val photoFile = createTempFile(FILENAME_PHOTO_PREFIX, FILE_EXT_PHOTO)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo saved to ${photoFile.absolutePath}")
                    onResult(photoFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture error", exception)
                    onError(exception.message ?: "拍照失败")
                }
            }
        )
    }

    // ---- video ------------------------------------------------------------

    /**
     * Start recording video.
     *
     * @param onStarted  Called when recording actually begins.
     * @param onComplete Called when recording is finalized and the file is ready.
     * @param onError    Called on recording error.
     */
    fun startRecording(
        onStarted: (File) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val rec = recorder ?: run {
            onError("相机未就绪")
            return
        }
        val videoFile = createTempFile(FILENAME_VIDEO_PREFIX, FILE_EXT_VIDEO)
        pendingVideoFile = videoFile
        onVideoStarted = onStarted
        onVideoFinalized = { file, errorMsg ->
            if (errorMsg != null) onError(errorMsg)
            else onComplete(file)
        }

        val outputOptions = FileOutputOptions.Builder(videoFile).build()
        val pending: PendingRecording = rec.prepareRecording(context, outputOptions)
            .withAudioEnabled()

        currentRecording = pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    Log.d(TAG, "Recording started")
                    onVideoStarted?.invoke(videoFile)
                }
                is VideoRecordEvent.Finalize -> {
                    currentRecording?.close()
                    currentRecording = null
                    val cb = onVideoFinalized
                    onVideoStarted = null
                    onVideoFinalized = null
                    pendingVideoFile = null

                    if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                        Log.d(TAG, "Video finalized: ${videoFile.absolutePath}")
                        cb?.invoke(videoFile, null)
                    } else {
                        val msg = event.cause?.message ?: "录像失败 (code=${event.error})"
                        Log.e(TAG, "Video error: $msg", event.cause)
                        cb?.invoke(videoFile, msg)
                        videoFile.delete()
                    }
                }
                else -> { /* Status / Pause / Resume */ }
            }
        }
    }

    fun stopRecording() {
        currentRecording?.stop()
    }

    // ---- internals --------------------------------------------------------

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val lifecycleOwner = currentLifecycleOwner ?: return
        val previewView = currentPreviewView ?: return

        currentRecording?.close()
        currentRecording = null

        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val r = Recorder.Builder()
            .build()
        recorder = r
        videoCapture = VideoCapture.Builder(r)
            .build()

        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )
        } catch (e: Exception) {
            Log.e(TAG, "Use-case binding failed", e)
        }
    }

    private fun createTempFile(prefix: String, suffix: String): File {
        val dir = context.cacheDir
        return File.createTempFile(prefix, suffix, dir)
    }
}
