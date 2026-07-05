package com.devin.ts_camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.devin.ts_camera.model.CaptureMode
import com.devin.ts_camera.model.FlashMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top control bar: flash toggle, camera switch, mode selector.
 */
@Composable
fun TopControlsBar(
    captureMode: CaptureMode,
    flashMode: FlashMode,
    onFlashClick: () -> Unit,
    onSwitchCamera: () -> Unit,
    onModeChange: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flash button (only for photo mode)
        if (captureMode == CaptureMode.PHOTO) {
            ControlChip(
                icon = when (flashMode) {
                    FlashMode.OFF  -> Icons.Filled.FlashOff
                    FlashMode.ON   -> Icons.Filled.FlashOn
                    FlashMode.AUTO -> Icons.Filled.FlashOn
                },
                label = flashMode.label,
                onClick = onFlashClick
            )
        } else {
            Spacer(Modifier.width(64.dp))
        }

        // Mode toggle
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            ModeChip(
                text = "拍照",
                selected = captureMode == CaptureMode.PHOTO,
                onClick = { onModeChange(CaptureMode.PHOTO) }
            )
            ModeChip(
                text = "录像",
                selected = captureMode == CaptureMode.VIDEO,
                onClick = { onModeChange(CaptureMode.VIDEO) }
            )
        }

        // Switch camera
        ControlChip(
            icon = Icons.Filled.Refresh,
            label = "翻转",
            onClick = onSwitchCamera
        )
    }
}

@Composable
private fun ControlChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = if (selected) Color.Black else Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

// ---- Recording indicator -----------------------------------------------

@Composable
fun RecordingOverlay(durationMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Red.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Blinking red dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
        Text(
            text = formatDuration(durationMs),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

// ---- Real-time timestamp overlay ---------------------------------------

/**
 * Shows the current date / time as a real-time overlay, updating every second.
 * Useful as a timestamp watermark during video recording.
 */
@Composable
fun TimestampOverlay(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L - (System.currentTimeMillis() % 1000))
            now = System.currentTimeMillis()
        }
    }

    val dateStr = remember(now) {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
    }
    val timeStr = remember(now) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = dateStr,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = timeStr,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

// ---- Capture button ----------------------------------------------------

@Composable
fun CaptureButton(
    mode: CaptureMode,
    isRecording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val outerSize = 76.dp
    val innerSize = 62.dp
    val recordInnerSize = 28.dp

    Box(
        modifier = modifier
            .size(outerSize)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (mode == CaptureMode.VIDEO && isRecording) recordInnerSize else innerSize)
                .clip(
                    if (mode == CaptureMode.VIDEO && isRecording)
                        RoundedCornerShape(4.dp)
                    else
                        CircleShape
                )
                .background(
                    when {
                        !enabled -> Color.Gray
                        mode == CaptureMode.VIDEO && isRecording -> Color.Red
                        mode == CaptureMode.VIDEO -> Color.Red
                        else -> Color.White
                    }
                )
        )
    }
}

// ---- Bottom info bar ---------------------------------------------------

@Composable
fun BottomInfoBar(
    locationText: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = locationText,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

// ---- Utility -----------------------------------------------------------

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val centis = (ms % 1000) / 10
    return "%02d:%02d.%02d".format(min, sec, centis)
}

fun formatDurationHMS(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
