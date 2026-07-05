package com.devin.ts_camera.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
     * Read [sourceFile] (JPEG), draw a large timestamp in the bottom-right
     * corner, and write the result to a new temp file.  Returns the watermarked
     * file (in cacheDir), or the original if anything fails.
     */
    fun applyToPhoto(sourceFile: File, cacheDir: File, timestamp: Long): File {
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val src = BitmapFactory.decodeFile(sourceFile.absolutePath, opts)
                ?: return sourceFile  // can't decode → return original

            val canvas = Canvas(src)
            val w = src.width.toFloat()
            val h = src.height.toFloat()

            // Paint: large white text with dark shadow
            val fontSize = (w * 0.065f).coerceIn(40f, 200f)  // ~6.5% of width

            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 0, 0, 0)
                textSize = fontSize
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.RIGHT
            }

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(230, 255, 255, 255)
                textSize = fontSize
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.RIGHT
            }

            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            val textLines = listOf(dateStr, timeStr)

            val margin = (w * 0.04f).coerceAtLeast(24f)
            val lineHeight = fontSize * 1.25f
            val totalHeight = lineHeight * textLines.size
            val x = w - margin
            val shadowOffset = fontSize * 0.06f

            textLines.forEachIndexed { i, line ->
                val y = h - margin - totalHeight + lineHeight * (i + 1)
                // Shadow
                canvas.drawText(line, x + shadowOffset, y + shadowOffset, shadowPaint)
                // Text
                canvas.drawText(line, x, y, textPaint)
            }

            // Write watermarked bitmap to temp file
            val outFile = File.createTempFile("photo_ts_", ".jpg", cacheDir)
            FileOutputStream(outFile).use { fos ->
                src.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            src.recycle()
            android.util.Log.d(TAG, "Photo watermark applied: ${outFile.length()} bytes")
            outFile
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to apply photo watermark", e)
            sourceFile  // fallback to original
        }
    }

    // ---- Video overlay bitmap ---------------------------------------------

    /**
     * Create a semi-transparent overlay bitmap suitable for Media3 OverlayEffect.
     * The bitmap is sized for a video frame; the timestamp text is drawn
     * at the bottom-right corner.
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
        val canvas = Canvas(bmp)

        val fontSize = (w * 0.06f).coerceIn(36f, 160f)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
            textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
        }

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val textLines = listOf(dateStr, timeStr)

        val margin = (w * 0.04f).coerceAtLeast(20f)
        val lineHeight = fontSize * 1.25f
        val totalHeight = lineHeight * textLines.size
        val x = w - margin
        val shadowOffset = fontSize * 0.06f

        textLines.forEachIndexed { i, line ->
            val y = h - margin - totalHeight + lineHeight * (i + 1)
            canvas.drawText(line, x + shadowOffset, y + shadowOffset, shadowPaint)
            canvas.drawText(line, x, y, textPaint)
        }

        return bmp
    }
}
