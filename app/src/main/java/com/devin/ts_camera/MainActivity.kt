package com.devin.ts_camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devin.ts_camera.ui.screen.CameraScreen
import com.devin.ts_camera.ui.screen.PreviewScreen
import com.devin.ts_camera.ui.theme.TS_cameraTheme
import com.devin.ts_camera.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.delay

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
 * Splash screen shown at app launch.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2500L)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App icon
            Image(
                painter = painterResource(id = R.drawable.app_icon),
                contentDescription = "App icon",
                modifier = Modifier.size(120.dp),
                contentScale = ContentScale.Fit
            )

            // App name
            Text(
                text = "TS Camera",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            // Description
            Text(
                text = "Timestamp camera — capture photos & videos\nwith real-time date/time watermark",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Author
            Text(
                text = "devin shaw",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp
            )

            // GitHub
            Text(
                text = "github.com/devin058/timestamp_camera",
                color = Color(0xFF64B5F6).copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Top-level navigation host.
 */
@Composable
fun CameraApp() {
    val viewModel: CameraViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    var showSplash by remember { mutableStateOf(true) }
    var showPreview by remember { mutableStateOf(false) }

    // React to new captures
    LaunchedEffect(uiState.lastPhoto, uiState.lastVideo) {
        if (uiState.hasPendingResult) {
            showPreview = true
        }
    }

    if (showSplash) {
        SplashScreen(onDone = { showSplash = false })
    } else if (showPreview && uiState.hasPendingResult) {
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
