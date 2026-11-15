package com.devin.ts_camera.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the burned-in timestamp as PNG frames for FFmpeg's `overlay` filter.
 *
 * `drawtext` re-shapes the glyphs for every single frame, which dominates the
 * cost of a software transcode. Pre-rendering a small strip per second and
 * swapping it with `overlay` keeps the wall-clock stamp truthful to the second
 * while making rendering a one-time cost.
 *
 * Frames are numbered from zero — `stamp_000000.png` is the first second —
 * because FFmpeg starts the image sequence at zero. A caller trimming into the
 * middle of a recording offsets [firstStampWallTimeMs] instead, so the strip
 * index and the stamp time stay in step.
 */
object TimestampOverlay {

    private const val TAG = "TimestampOverlay"

    /** Hard cap so a pathological duration can't fill the cache dir. */
    private const val MAX_FRAMES = 6 * 60 * 60

    /** Text height as a fraction of the frame's short edge. */
    private const val TEXT_HEIGHT_FRACTION = 0.024f

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Writes one PNG per second of [durationMs] into a fresh temp directory.
     *
     * @param firstStampWallTimeMs wall-clock time shown on the first frame
     * @return the directory holding `stamp_%06d.png`, or null if nothing was
     *         written (in which case the caller should skip the overlay)
     */
    fun renderToFile(
        cacheDir: File,
        width: Int,
        height: Int,
        firstStampWallTimeMs: Long,
        durationMs: Long
    ): File? {
        if (width <= 0 || height <= 0) return null

        val frameCount = ((durationMs.coerceAtLeast(0L) + 999L) / 1000L)
            .toInt()
            .coerceIn(1, MAX_FRAMES)

        val dir = File(cacheDir, "stamp_${System.currentTimeMillis()}")
        if (!dir.mkdirs()) {
            Log.w(TAG, "Could not create overlay dir $dir")
            return null
        }

        return try {
            val layout = Layout.forFrame(width, height)
            val strip = Bitmap.createBitmap(
                layout.stripWidth, layout.stripHeight, Bitmap.Config.ARGB_8888
            )
            try {
                for (i in 0 until frameCount) {
                    strip.eraseColor(Color.TRANSPARENT)
                    drawStrip(Canvas(strip), layout, firstStampWallTimeMs + i * 1000L)
                    FileOutputStream(File(dir, fileName(i))).use { out ->
                        strip.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            } finally {
                strip.recycle()
            }
            dir
        } catch (e: Exception) {
            Log.w(TAG, "Overlay rendering failed", e)
            dir.deleteRecursively()
            null
        }
    }

    /** `%d`-style name of the strip for the given second. */
    fun fileName(secondIndex: Int): String = "stamp_%06d.png".format(secondIndex)

    // ---- Layout / drawing -------------------------------------------------

    private data class Layout(
        val frameWidth: Int,
        val frameHeight: Int,
        val stripWidth: Int,
        val stripHeight: Int,
        val textSize: Float,
        val margin: Float
    ) {
        companion object {
            fun forFrame(width: Int, height: Int): Layout {
                val shortEdge = minOf(width, height).toFloat()
                val textSize = (shortEdge * TEXT_HEIGHT_FRACTION)
                    .coerceIn(12f, 96f)
                val margin = (shortEdge * 0.04f).coerceAtLeast(6f)

                // Measure a representative stamp so every strip shares one size
                // and the animation never jitters.
                val paint = textPaint(textSize)
                val widthPx = paint.measureText("0000-00-00 00:00:00")
                val stackHeight = textSize * 1.9f

                return Layout(
                    frameWidth = width,
                    frameHeight = height,
                    stripWidth = (widthPx + margin * 2f).toInt().coerceIn(1, width),
                    stripHeight = (stackHeight + margin * 2f).toInt().coerceIn(1, height),
                    textSize = textSize,
                    margin = margin
                )
            }

            fun textPaint(textSize: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
        }
    }

    private fun drawStrip(canvas: Canvas, layout: Layout, timestamp: Long) {
        val paint = Layout.textPaint(layout.textSize)
        val text = formatter.format(Date(timestamp))

        // Shadow first, then the fill, so the stamp survives a bright sky.
        val shadow = Paint(paint).apply {
            color = Color.argb(190, 0, 0, 0)
        }
        paint.color = Color.argb(235, 255, 255, 255)

        val shadowOffset = (layout.textSize * 0.05f).coerceAtLeast(1f)
        val x = layout.stripWidth / 2f
        val y = layout.margin + layout.textSize

        canvas.drawText(text, x + shadowOffset, y + shadowOffset, shadow)
        canvas.drawText(text, x, y, paint)
    }
}
