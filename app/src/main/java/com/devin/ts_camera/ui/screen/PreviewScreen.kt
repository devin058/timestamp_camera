package com.devin.ts_camera.ui.screen

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.devin.ts_camera.media.VideoProcessor
import com.devin.ts_camera.ui.components.SaveOptionsSheet
import com.devin.ts_camera.ui.components.formatDurationHMS
import com.devin.ts_camera.ui.components.formatFileSize
import com.devin.ts_camera.ui.viewmodel.CameraViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Preview screen shown after a photo or video is captured.
 * Displays the media with overlaid metadata and provides access to
 * the save-options bottom sheet.
 */
@Composable
fun PreviewScreen(
    viewModel: CameraViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showSaveSheet by remember { mutableStateOf(false) }
    var videoThumbnails by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var videoDurationMs by remember { mutableStateOf(0L) }

    // Generate thumbnails for video trimming
    LaunchedEffect(uiState.lastVideo) {
        val video = uiState.lastVideo ?: return@LaunchedEffect
        val duration = VideoProcessor.getDurationMs(context, video.uri)
        videoDurationMs = if (duration > 0) duration else video.durationMs
        videoThumbnails = VideoProcessor.getThumbnails(
            context, video.uri, videoDurationMs, count = 10
        )
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {

        // Media preview
        when {
            uiState.lastPhoto != null -> {
                AsyncImage(
                    model = uiState.lastPhoto!!.uri,
                    contentDescription = "照片预览",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            uiState.lastVideo != null -> {
                AsyncImage(
                    model = uiState.lastVideo!!.uri,
                    contentDescription = "视频预览",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // ---- Metadata overlay -----------------------------------------

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Timestamp
            val timestamp = uiState.lastPhoto?.timestamp
                ?: uiState.lastVideo?.timestamp
                ?: System.currentTimeMillis()
            Text(
                text = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                ).format(Date(timestamp)),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(4.dp))

            // GPS
            val location = uiState.lastPhoto?.location
                ?: uiState.lastVideo?.location
            if (location != null && location.isValid()) {
                Text(
                    text = "📍 %.6f, %.6f".format(
                        location.latitude, location.longitude
                    ),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    text = "📍 无位置信息",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Duration (video only)
            val video = uiState.lastVideo
            if (video != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "⏱ 录制时长: ${formatDurationHMS(video.durationMs)}",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(4.dp))

            // File info
            val fileSize = when {
                uiState.lastPhoto != null -> uiState.lastPhoto!!.file.length()
                uiState.lastVideo != null -> uiState.lastVideo!!.file.length()
                else -> 0L
            }
            Text(
                text = "原始大小: ${formatFileSize(fileSize)}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // ---- Bottom action bar ----------------------------------------

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Back / Discard
            Button(
                onClick = {
                    viewModel.dismissPreview()
                    onNavigateBack()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.DarkGray
                )
            ) {
                Text("← 返回")
            }

            // Save
            Button(
                onClick = { showSaveSheet = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("✔ 保存")
            }
        }

        // Processing overlay
        if (uiState.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("正在处理…", color = Color.White)
                }
            }
        }
    }

    // ---- Save Options Bottom Sheet ------------------------------------

    if (showSaveSheet) {
        SaveOptionsSheet(
            isVideo = uiState.lastVideo != null,
            durationMs = videoDurationMs,
            thumbnails = videoThumbnails,
            onSave = { options ->
                showSaveSheet = false
                viewModel.saveWithOptions(options) { _, saveLocation ->
                    Toast.makeText(
                        context,
                        "已保存至 $saveLocation",
                        Toast.LENGTH_LONG
                    ).show()
                    onNavigateBack()
                }
            },
            onCancel = { showSaveSheet = false }
        )
    }
}
