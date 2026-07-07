package com.devin.ts_camera.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devin.ts_camera.model.CompressionLevel
import com.devin.ts_camera.model.SaveOptions
import com.devin.ts_camera.model.VideoResolution
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom-sheet content for configuring save options before persisting media.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveOptionsSheet(
    isVideo: Boolean,
    durationMs: Long,
    thumbnails: List<Bitmap>,
    onSave: (SaveOptions) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val defaultName = remember {
        val prefix = if (isVideo) "VID" else "IMG"
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        "${prefix}_${dateStr}"
    }

    var filename by remember { mutableStateOf(defaultName) }
    var selectedResolution by remember { mutableStateOf(VideoResolution.R1080P) }
    var selectedCompression by remember { mutableStateOf(CompressionLevel.LOW) }
    var trimStartMs by remember { mutableStateOf(0L) }
    var trimEndMs by remember { mutableStateOf(0L) }
    var muteAudio by remember { mutableStateOf(false) }
    var selectedFps by remember { mutableStateOf(0) }

    // Computed bitrate from resolution + compression
    val effectiveBitrate = ((selectedResolution.defaultBitrate * selectedCompression.fraction).toInt())
        .coerceIn(selectedResolution.minBitrate, selectedResolution.maxBitrate)

    // Estimated file size
    val effectiveDuration = if (trimEndMs > 0) trimEndMs - trimStartMs else durationMs
    val estimatedBytes = if (isVideo && effectiveDuration > 0) {
        effectiveBitrate.toLong() * effectiveDuration / 1000L / 8L
    } else {
        // Photo: rough estimate ~1.5 bits per pixel @ JPEG quality 92
        (selectedResolution.width * selectedResolution.height * 1.5).toLong() / 8L
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title
            Text(
                text = "保存选项",
                style = MaterialTheme.typography.titleLarge
            )

            // ---- Filename -------------------------------------------------
            OutlinedTextField(
                value = filename,
                onValueChange = { filename = it },
                label = { Text("文件名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                suffix = {
                    Text(
                        text = if (isVideo) ".mp4" else ".jpg",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            // ---- Video trimming (video only) ------------------------------
            if (isVideo && durationMs > 0) {
                Text(
                    text = "时间裁剪",
                    style = MaterialTheme.typography.titleMedium
                )
                VideoTrimmerView(
                    durationMs = durationMs,
                    startMs = trimStartMs,
                    endMs = trimEndMs,
                    thumbnails = thumbnails,
                    onTrimChange = { start, end ->
                        trimStartMs = start
                        trimEndMs = end
                    }
                )
            }

            // ---- Resolution -----------------------------------------------
            if (isVideo) {
                Text(
                    text = "清晰度",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VideoResolution.entries.forEach { res ->
                        FilterChip(
                            selected = selectedResolution == res,
                            onClick = { selectedResolution = res },
                            label = { Text(res.label) }
                        )
                    }
                }
            }

            // ---- Compression level ----------------------------------------
            if (isVideo) {
                Text(
                    text = "压缩率",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompressionLevel.entries.forEach { level ->
                        FilterChip(
                            selected = selectedCompression == level,
                            onClick = { selectedCompression = level },
                            label = { Text(level.label) }
                        )
                    }
                }

                // Compression slider (fine-tuning bitrate)
                val effectiveBitrate = ((selectedResolution.defaultBitrate * selectedCompression.fraction).toInt())
                    .coerceIn(selectedResolution.minBitrate, selectedResolution.maxBitrate)
                val bitrateMbps = effectiveBitrate / 1_000_000f
                Text(
                    text = "码率: %.1f Mbps".format(bitrateMbps),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = effectiveBitrate.toFloat(),
                    onValueChange = { newBitrate ->
                        // Map slider to nearest compression level
                        val fraction = newBitrate / selectedResolution.defaultBitrate.toFloat()
                        selectedCompression = when {
                            fraction < 0.45f -> CompressionLevel.HIGH
                            fraction < 0.8f -> CompressionLevel.MEDIUM
                            else -> CompressionLevel.LOW
                        }
                    },
                    valueRange = selectedResolution.minBitrate.toFloat()..selectedResolution.maxBitrate.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- Mute audio (video only) ----------------------------------
            if (isVideo) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { muteAudio = !muteAudio }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "🔇 静音",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "保存的视频将不含声音",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = muteAudio,
                        onCheckedChange = { muteAudio = it }
                    )
                }
            }

            // ---- Frame rate (video only) -----------------------------------
            if (isVideo) {
                Text("帧率", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0 to "原帧率", 24 to "24", 30 to "30", 60 to "60").forEach { (v, l) ->
                        FilterChip(
                            selected = selectedFps == v,
                            onClick = { selectedFps = v },
                            label = { Text(l) }
                        )
                    }
                }
            }

            // ---- Estimated file size --------------------------------------
            Text(
                text = "预计保存大小: ${formatFileSize(estimatedBytes)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = buildString {
                    if (isVideo) {
                        append("分辨率: ${selectedResolution.label} (${selectedResolution.width}×${selectedResolution.height})")
                        append("  |  ")
                        append("码率: %.1f Mbps".format(effectiveBitrate / 1_000_000f))
                        if (effectiveDuration > 0) {
                            append("  |  ")
                            append("时长: ${formatDurationHMS(effectiveDuration)}")
                        }
                    } else {
                        append("JPEG 图片")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- Action buttons -------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        onSave(
                            SaveOptions(
                                filename = filename,
                                trimStartMs = trimStartMs,
                                trimEndMs = trimEndMs,
                                resolution = selectedResolution,
                                compressionLevel = selectedCompression,
                                muteAudio = muteAudio,
                                fps = selectedFps
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存")
                }
            }
        }
    }
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
