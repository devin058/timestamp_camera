package com.devin.ts_camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devin.ts_camera.ui.screen.CameraScreen
import com.devin.ts_camera.ui.screen.PreviewScreen
import com.devin.ts_camera.ui.theme.TS_cameraTheme
import com.devin.ts_camera.ui.viewmodel.CameraViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TS_cameraTheme {
                CameraApp()
            }
        }
    }
}

/**
 * Top-level navigation host.
 * Switches between CameraScreen and PreviewScreen based on app state.
 */
@Composable
fun CameraApp() {
    val viewModel: CameraViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    // Simple navigation: preview screen when there's a pending result
    var showPreview by remember { mutableStateOf(false) }

    // React to new captures
    LaunchedEffect(uiState.lastPhoto, uiState.lastVideo) {
        if (uiState.hasPendingResult) {
            showPreview = true
        }
    }

    if (showPreview && uiState.hasPendingResult) {
        PreviewScreen(
            viewModel = viewModel,
            onNavigateBack = { showPreview = false }
        )
    } else {
        CameraScreen(
            viewModel = viewModel,
            onNavigateToPreview = { showPreview = true }
        )
    }
}
