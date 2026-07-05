package com.devin.ts_camera.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import com.devin.ts_camera.model.CaptureMode
import com.devin.ts_camera.ui.components.*
import com.devin.ts_camera.ui.viewmodel.CameraViewModel

/**
 * Main camera screen with full-screen preview and overlay controls.
 */
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onNavigateToPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // PreviewView reference — set after the view is attached
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // ---- Permissions --------------------------------------------------

    val allPermissions = remember {
        mutableListOf(Manifest.permission.CAMERA).also {
            it.add(Manifest.permission.RECORD_AUDIO)
            it.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                it.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onCameraPermissionGranted(
            results[Manifest.permission.CAMERA] == true
        )
        viewModel.onAudioPermissionGranted(
            results[Manifest.permission.RECORD_AUDIO] == true
        )
        viewModel.onLocationPermissionGranted(
            results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        )
    }

    // Check and request permissions
    LaunchedEffect(Unit) {
        val allGranted = allPermissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) {
            permissionLauncher.launch(allPermissions)
        } else {
            viewModel.onCameraPermissionGranted(true)
            viewModel.onAudioPermissionGranted(true)
            viewModel.onLocationPermissionGranted(true)
        }
    }

    // ---- UI -----------------------------------------------------------

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {

        // Camera preview
        if (uiState.hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        // Defer state update to avoid writing during composition
                        post { previewViewRef = this }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Bind camera once PreviewView is ready
            LaunchedEffect(previewViewRef) {
                previewViewRef?.let { pv ->
                    viewModel.startCamera(lifecycleOwner, pv)
                }
            }
        } else {
            // Permission placeholder
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "需要相机权限",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permissionLauncher.launch(allPermissions) }) {
                        Text("授予权限")
                    }
                }
            }
        }

        // ---- Overlays -------------------------------------------------

        // Top controls
        TopControlsBar(
            captureMode = uiState.captureMode,
            flashMode = uiState.flashMode,
            onFlashClick = { viewModel.toggleFlash() },
            onSwitchCamera = { viewModel.toggleCamera() },
            onModeChange = { viewModel.setCaptureMode(it) },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Recording indicator (video only)
        if (uiState.isRecording) {
            RecordingOverlay(
                durationMs = uiState.recordingDurationMs,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
            )
        }

        // Real-time clock — always visible on the viewfinder
        TimestampOverlay(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 16.dp)
        )

        // Bottom bar
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BottomInfoBar(
                locationText = uiState.location?.let {
                    "📍 ${it.toDisplayString()}"
                } ?: "📍 无位置权限"
            )

            CaptureButton(
                mode = uiState.captureMode,
                isRecording = uiState.isRecording,
                enabled = uiState.hasCameraPermission && !uiState.isProcessing,
                onClick = {
                    when (uiState.captureMode) {
                        CaptureMode.PHOTO -> viewModel.capturePhoto()
                        CaptureMode.VIDEO -> {
                            if (uiState.isRecording)
                                viewModel.stopVideoRecording()
                            else
                                viewModel.startVideoRecording()
                        }
                    }
                },
                modifier = Modifier.padding(bottom = 32.dp)
            )
        }

        // Processing overlay
        if (uiState.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("处理中…", color = Color.White)
                }
            }
        }

        // Error toast
        uiState.errorMessage?.let { msg ->
            LaunchedEffect(msg) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        // Navigate to preview when capture completes
        LaunchedEffect(uiState.lastPhoto, uiState.lastVideo) {
            if (uiState.hasPendingResult && !uiState.isRecording) {
                onNavigateToPreview()
            }
        }
    }
}
