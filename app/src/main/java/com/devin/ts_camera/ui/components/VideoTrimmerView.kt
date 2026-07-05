package com.devin.ts_camera.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Two-handle video trimmer bar.
 *
 * @param durationMs   Total duration of the source video.
 * @param startMs      Current trim-start position (0..durationMs).
 * @param endMs        Current trim-end position (0..durationMs).  0 = use full duration.
 * @param thumbnails   Optional pre-extracted frame images.
 * @param onTrimChange Called with (newStartMs, newEndMs) when the user drags a handle.
 */
@Composable
fun VideoTrimmerView(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    thumbnails: List<Bitmap> = emptyList(),
    onTrimChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0) return

    val effectiveEndMs = if (endMs > 0) endMs else durationMs
    val barHeight = 56.dp
    val handleWidth = 12.dp

    var barWidthPx by remember { mutableStateOf(0f) }
    val handleWidthPx = with(LocalDensity.current) { handleWidth.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        // Time labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDurationHMS(startMs),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
            Text(
                text = formatDurationHMS(effectiveEndMs),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
        }

        Spacer(Modifier.height(4.dp))

        // Timeline bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .onSizeChanged { barWidthPx = it.width.toFloat() }
        ) {
            val density = LocalDensity.current

            // Background with thumbnails
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.DarkGray, RoundedCornerShape(6.dp))
            ) {
                // Draw thumbnails
                if (thumbnails.isNotEmpty()) {
                    val thumbWidth = size.width / thumbnails.size
                    thumbnails.forEachIndexed { i, bmp ->
                        val img = bmp.asImageBitmap()
                        drawImage(
                            image = img,
                            dstOffset = IntOffset(
                                (i * thumbWidth).toInt(), 0
                            ),
                            dstSize = IntSize(
                                thumbWidth.toInt(), size.height.toInt()
                            )
                        )
                    }
                }

                // Draw trimmed-region overlay (dim outside, bright inside)
                val startX = (startMs.toFloat() / durationMs) * size.width
                val endX = (effectiveEndMs.toFloat() / durationMs) * size.width

                // Left dim region
                drawRect(
                    color = Color.Black.copy(alpha = 0.6f),
                    topLeft = Offset.Zero,
                    size = Size(startX, size.height)
                )
                // Right dim region
                drawRect(
                    color = Color.Black.copy(alpha = 0.6f),
                    topLeft = Offset(endX, 0f),
                    size = Size(size.width - endX, size.height)
                )

                // Start handle
                drawRect(
                    color = Color.White,
                    topLeft = Offset(startX - 2.dp.toPx(), 0f),
                    size = Size(4.dp.toPx(), size.height)
                )
                // End handle
                drawRect(
                    color = Color.White,
                    topLeft = Offset(endX - 2.dp.toPx(), 0f),
                    size = Size(4.dp.toPx(), size.height)
                )
            }

            // ---- draggable handles ----------------------------------------

            // Start handle
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) {
                            val rawPx =
                                (startMs.toFloat() / durationMs) * barWidthPx - handleWidthPx / 2
                            rawPx.coerceIn(0f, max(0f, barWidthPx - handleWidthPx)).toDp()
                        }
                    )
                    .width(handleWidth)
                    .height(barHeight)
                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                    .pointerInput(durationMs) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            val deltaMs =
                                (dragAmount / barWidthPx * durationMs).roundToInt().toLong()
                            val newStart = (startMs + deltaMs)
                                .coerceIn(0L, max(0L, effectiveEndMs - 200L)) // min 200ms selection
                            onTrimChange(newStart, effectiveEndMs)
                        }
                    }
            )

            // End handle
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) {
                            val rawPx =
                                (effectiveEndMs.toFloat() / durationMs) * barWidthPx - handleWidthPx / 2
                            rawPx.coerceIn(0f, max(0f, barWidthPx - handleWidthPx)).toDp()
                        }
                    )
                    .width(handleWidth)
                    .height(barHeight)
                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                    .pointerInput(durationMs) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            val deltaMs =
                                (dragAmount / barWidthPx * durationMs).roundToInt().toLong()
                            val newEnd = (effectiveEndMs + deltaMs)
                                .coerceIn(minOf(startMs + 200L, durationMs), durationMs)
                            onTrimChange(startMs, newEnd)
                        }
                    }
            )
        }

        Spacer(Modifier.height(4.dp))

        // Selected range info
        Text(
            text = "选中: ${formatDurationHMS(startMs)} - ${formatDurationHMS(effectiveEndMs)} (${formatDurationHMS(effectiveEndMs - startMs)})",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}
