package com.devin.ts_camera.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.devin.ts_camera.media.SettingsManager
import com.devin.ts_camera.model.CompressionLevel
import com.devin.ts_camera.model.VideoResolution

/**
 * Bottom sheet for global video processing settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current

    var resolution by remember { mutableStateOf(SettingsManager.getResolution(ctx)) }
    var compression by remember { mutableStateOf(SettingsManager.getCompression(ctx)) }
    var mute by remember { mutableStateOf(SettingsManager.isMuted(ctx)) }
    var fps by remember { mutableStateOf(SettingsManager.getFps(ctx)) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("视频处理设置", style = MaterialTheme.typography.titleLarge)

            // Resolution
            Text("清晰度", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VideoResolution.entries.forEach { r ->
                    FilterChip(
                        selected = resolution == r,
                        onClick = { resolution = r; SettingsManager.setResolution(ctx, r) },
                        label = { Text(r.label) }
                    )
                }
            }

            // Compression
            Text("压缩率", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompressionLevel.entries.forEach { c ->
                    FilterChip(
                        selected = compression == c,
                        onClick = { compression = c; SettingsManager.setCompression(ctx, c) },
                        label = { Text(c.label) }
                    )
                }
            }

            // Mute
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("🔇 静音", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = mute,
                    onCheckedChange = { mute = it; SettingsManager.setMuted(ctx, it) }
                )
            }

            // Frame rate
            Text("帧率", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0 to "原", 24 to "24", 30 to "30", 60 to "60").forEach { (v, l) ->
                    FilterChip(
                        selected = fps == v,
                        onClick = { fps = v; SettingsManager.setFps(ctx, v) },
                        label = { Text(l) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
