package com.devin.ts_camera.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.FontMetrics
import android.graphics.Typeface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for burning timestamp text into photos and generating
 * overlay bitmaps for video watermarking.
 */
object TimestampWatermark {

    private const val TAG = "TimestampWatermark"

    // ---- Photo watermark --------------------------------------------------

    /**
     * Read [sourceFile] (JPEG), draw a timestamp in the bottom-right
     * corner, and write the result to a new temp file.  Returns the watermarked
     * file (in cacheDir), or the original if anything fails.
     */
    fun applyToPhoto(sourceFile: File, cacheDir: File, timestamp: Long): File {
        try {
            Log.d(TAG, "Applying photo watermark, source=${sourceFile.absolutePath} size=${sourceFile.length()}")

            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val src = BitmapFactory.decodeFile(sourceFile.absolutePath, opts)
            if (src == null) {
                Log.e(TAG, "Failed to decode photo: ${sourceFile.absolutePath}")
                return sourceFile
            }

            Log.d(TAG, "Photo decoded: ${src.width}x${src.height}")
            drawTimestamp(Canvas(src), src.width.toFloat(), src.height.toFloat(), timestamp)

            val outFile = File.createTempFile("photo_ts_", ".jpg", cacheDir)
            FileOutputStream(outFile).use { fos ->
                src.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            src.recycle()
            Log.d(TAG, "Photo watermark done: ${outFile.length()} bytes")
            return outFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply photo watermark", e)
            return sourceFile
        }
    }

    // ---- Video overlay bitmap ---------------------------------------------

    /**
     * Create a transparent overlay bitmap with timestamp text for
     * Media3 [androidx.media3.effect.BitmapOverlay].
     */
    fun createOverlayBitmap(
        videoWidth: Int,
        videoHeight: Int,
        timestamp: Long,
        resolution: com.devin.ts_camera.model.VideoResolution? = null
    ): Bitmap {
        val w = resolution?.width ?: videoWidth
        val h = resolution?.height ?: videoHeight

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawTimestamp(Canvas(bmp), w.toFloat(), h.toFloat(), timestamp)
        return bmp
    }

    // ---- Shared drawing ---------------------------------------------------

    /**
     * Draw a two-line timestamp (date + time) at the bottom-right of the
     * canvas.  Uses [Paint.FontMetrics] to guarantee the text stays within
     * the frame regardless of device font metrics.
     */
    private fun drawTimestamp(canvas: Canvas, w: Float, h: Float, timestamp: Long) {
        // Font size: 4% of frame width, clamped to reasonable range
        val fontSize = (w * 0.04f).coerceIn(32f, 120f)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
            textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
        }

        val fm = FontMetrics()
        textPaint.getFontMetrics(fm)
        // fm.top is negative (distance from baseline to top of glyph)
        // fm.bottom is positive (distance from baseline to bottom)
        val lineHeight = fm.bottom - fm.top       // total height one text line occupies
        val lineSpacing = lineHeight * 0.3f        // extra gap between the two lines

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

        val margin = (w * 0.05f).coerceAtLeast(20f)   // 5% margin from edges
        val shadowOffset = fontSize * 0.04f
        val x = w - margin

        // Bottom line (time) — baseline so that fm.bottom (descent) lands at h-margin
        val yTime = h - margin - fm.bottom
        // Top line (date)
        val yDate = yTime - lineHeight - lineSpacing

        canvas.drawText(dateStr, x + shadowOffset, yDate + shadowOffset, shadowPaint)
        canvas.drawText(dateStr, x, yDate, textPaint)

        canvas.drawText(timeStr, x + shadowOffset, yTime + shadowOffset, shadowPaint)
        canvas.drawText(timeStr, x, yTime, textPaint)
    }
}
