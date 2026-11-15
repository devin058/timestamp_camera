package com.devin.ts_camera.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.devin.ts_camera.media.JobState
import com.devin.ts_camera.media.RecordingEntry
import com.devin.ts_camera.media.RecordingHistory
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom sheet listing recordings that finished processing.
 *
 * Each row opens the system player, so the app never has to ship its own
 * playback UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(RecordingHistory.allWithPending(context)) }

    // Pending rows change while the sheet is open — jobs finish and new ones
    // are queued — so refresh while it is visible.
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_INTERVAL_MS)
            entries = RecordingHistory.allWithPending(context)
        }
    }

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
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("录制历史", style = MaterialTheme.typography.titleLarge)

                if (entries.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            RecordingHistory.clear(context)
                            entries = RecordingHistory.allWithPending(context)
                        }
                    ) {
                        Text("清空列表")
                    }
                }
            }

            if (entries.isEmpty()) {
                Text(
                    text = "还没有处理完成的录像",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
                return@Column
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(entries, key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onClick = {
                            // A pending entry has no MediaStore item yet, and
                            // its file is being rewritten by FFmpeg.
                            if (!entry.pending) play(context, entry)
                        },
                        onDelete = {
                            // Pending rows are owned by the job queue, so they
                            // can't be removed from here.
                            if (!entry.pending) {
                                RecordingHistory.remove(context, entry.id)
                                entries = RecordingHistory.allWithPending(context)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: RecordingEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(enabled = !entry.pending, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Coil renders a video frame for a video URI. A pending file is still
        // being written, so decoding it would be unreliable.
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (entry.pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                AsyncImage(
                    model = entry.uri,
                    contentDescription = entry.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(entry.savedAt)),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${formatDurationHMS(entry.durationMs)} · ${formatFileSize(entry.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (entry.pending) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "⏳ 处理中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            entry.address?.takeIf { !entry.pending }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "📍 $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }

        if (!entry.pending) {
            TextButton(onClick = onDelete) {
                Text("删除", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private const val REFRESH_INTERVAL_MS = 2_000L

private fun play(context: android.content.Context, entry: RecordingEntry) {    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(entry.uri), "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast
            .makeText(context, "没有可用的视频播放器", android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}
