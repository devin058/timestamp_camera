package com.devin.ts_camera.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import com.devin.ts_camera.model.CaptureMode
import kotlinx.coroutines.delay
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
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // Keep screen on only during video recording; allow timeout otherwise
    DisposableEffect(uiState.isRecording) {
        val activity = context as? android.app.Activity
        if (uiState.isRecording) {
            activity?.window?.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        onDispose {
            activity?.window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    // ---- Power-save dim during long recordings -----------------------
    // After every [dimThresholdMs] of recording the screen goes black to save
    // power. Tapping while dimmed wakes the screen and restarts the timer;
    // the tap must not also press whatever control sits underneath, or the
    // user can't stop the recording.

    val dimThresholdMs = 120_000L
    var isDimmed by remember { mutableStateOf(false) }
    // Waking the screen snoozes the dim until this instant, so a tap neither
    // flickers straight back nor disables the dim for the rest of the take.
    var dimSnoozeUntilMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(uiState.isRecording) {
        if (!uiState.isRecording) {
            isDimmed = false
            dimSnoozeUntilMs = 0L
        }
    }
    // Deliberately NOT keyed on `isDimmed`: doing so made the effect re-run the
    // moment a tap cleared the flag, see the duration still over the threshold
    // and dim again immediately — which looked like a flicker, not a wake.
    LaunchedEffect(uiState.isRecording, uiState.recordingDurationMs) {
        if (uiState.isRecording &&
            !isDimmed &&
            uiState.recordingDurationMs >= dimThresholdMs &&
            android.os.SystemClock.elapsedRealtime() >= dimSnoozeUntilMs
        ) {
            isDimmed = true
        }
    }

    // PreviewView reference — set after the view is attached
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // ---- Permissions --------------------------------------------------

    val allPermissions = remember {
        mutableListOf(Manifest.permission.CAMERA).also {
            it.add(Manifest.permission.RECORD_AUDIO)
            it.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                it.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                it.add(Manifest.permission.POST_NOTIFICATIONS)
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                var totalX = 0f
                var totalY = 0f
                detectDragGestures(
                    onDragEnd = {
                        // Fire action based on accumulated drag direction & distance
                        val absX = kotlin.math.abs(totalX)
                        val absY = kotlin.math.abs(totalY)
                        if (absX > absY && absX > 80f) {
                            // Horizontal swipe — switch mode
                            viewModel.setCaptureMode(
                                if (totalX > 0) CaptureMode.VIDEO else CaptureMode.PHOTO
                            )
                        } else if (absY > absX && absY > 80f) {
                            // Vertical swipe — toggle camera
                            viewModel.toggleCamera()
                        }
                        totalX = 0f; totalY = 0f
                    },
                    onDragCancel = { totalX = 0f; totalY = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    totalX += dragAmount.x
                    totalY += dragAmount.y
                }
            }
    ) {

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

        // Settings button (bottom-left, away from other controls)
        IconButton(
            onClick = { showSettings = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 120.dp, start = 12.dp)
        ) {
            Text("⚙", color = Color.White.copy(alpha = 0.5f), fontSize = 26.sp)
        }

        // Recording history (bottom-left, below settings)
        IconButton(
            onClick = { showHistory = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 172.dp, start = 12.dp)
        ) {
            Text("🕘", color = Color.White.copy(alpha = 0.5f), fontSize = 24.sp)
        }

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

        // ---- Power-save dim during long recordings -----------------------

        if (isDimmed && uiState.isRecording) {
            // Full-screen scrim that swallows the tap — waking the screen must
            // not also press the capture button underneath.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isDimmed = false
                        // Snooze rather than disable: another full threshold of
                        // recording should dim again.
                        dimSnoozeUntilMs =
                            android.os.SystemClock.elapsedRealtime() + dimThresholdMs
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Red recording dot
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "视频录制中",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "单击返回",
                        color = Color.White.copy(alpha = 0.35f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = formatDuration(uiState.recordingDurationMs),
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
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

        // Navigate to preview for PHOTOS only; videos are processed in background
        LaunchedEffect(uiState.lastPhoto) {
            if (uiState.lastPhoto != null && !uiState.isRecording) {
                onNavigateToPreview()
            }
        }

        // Settings bottom sheet
        if (showSettings) {
            SettingsSheet(onDismiss = { showSettings = false })
        }

        // Recording history bottom sheet
        if (showHistory) {
            HistorySheet(onDismiss = { showHistory = false })
        }
    }
}
