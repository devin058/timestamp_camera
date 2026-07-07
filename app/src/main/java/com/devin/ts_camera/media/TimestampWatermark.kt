package com.devin.ts_camera.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.FontMetrics
import android.graphics.RectF
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
                inMutable = true    // required — Canvas() only works on a mutable bitmap
            }
            var src = BitmapFactory.decodeFile(sourceFile.absolutePath, opts)
            if (src == null) {
                Log.e(TAG, "Failed to decode photo: ${sourceFile.absolutePath}")
                return sourceFile
            }

            // Double-check mutability — some devices return immutable bitmaps anyway
            if (!src.isMutable) {
                Log.d(TAG, "Bitmap is immutable, copying to mutable")
                val copy = src.copy(Bitmap.Config.ARGB_8888, true)
                src.recycle()
                src = copy
            }

            Log.d(TAG, "Photo decoded: ${src.width}x${src.height}, mutable=${src.isMutable}")
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
     * FFmpeg subtitle-based watermarking.
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

    /**
     * Create a small transparent bitmap containing ONLY the timestamp text.
     * Useful for preview thumbnails or custom compositing.
     */
    fun createTimestampTextBitmap(
        timestamp: Long,
        fontSize: Float = 36f
    ): Bitmap {
        val textStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val textW = textPaint.measureText(textStr)

        val fm = FontMetrics()
        textPaint.getFontMetrics(fm)
        val textH = fm.bottom - fm.top

        val padH = 20f
        val padV = 10f
        val bmpW = (textW + padH * 2f + 4f).toInt().coerceAtLeast(1)
        // Extra space for the rounded background
        val bmpH = (textH + padV * 2f + 6f).toInt().coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(bmp)

        // Semi-transparent rounded background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(64, 0, 0, 0)   // 25 % dark backdrop
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat()),
            12f, 12f, bgPaint
        )

        // Shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 0, 0, 0)
            this.textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        // Text
        val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)  // ~78 % opaque
            this.textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }

        // Baseline: padV + abs(fm.top) to center text vertically in the bitmap
        val baseX = padH + 2f
        val baseY = padV + 3f - fm.top       // fm.top is negative

        canvas.drawText(textStr, baseX + 2f, baseY + 2f, shadowPaint)
        canvas.drawText(textStr, baseX, baseY, fgPaint)

        return bmp
    }

    // ---- Shared drawing ---------------------------------------------------

    /**
     * Draw a single-line timestamp ("yyyy-MM-dd HH:mm:ss.SSS") at the
     * bottom-center of the canvas.  Uses [Paint.FontMetrics] to guarantee
     * the text stays within the frame regardless of device font metrics.
     */
    private fun drawTimestamp(canvas: Canvas, w: Float, h: Float, timestamp: Long) {
        // Font size: 3% of frame width — visible even on high-res photos
        val fontSize = (w * 0.03f).coerceIn(36f, 72f)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0)    // solid shadow for readability
            textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255)  // ~90 % opaque — clearly visible
            textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val fm = FontMetrics()
        textPaint.getFontMetrics(fm)
        // fm.top is negative (distance from baseline to top of glyph)
        // fm.bottom is positive (distance from baseline to bottom)

        val textStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))

        val margin = (w * 0.05f).coerceAtLeast(20f)   // 5% margin from edges
        val shadowOffset = fontSize * 0.04f
        val x = w / 2f

        // Baseline so that fm.bottom (descent) lands at h-margin
        val y = h - margin - fm.bottom

        canvas.drawText(textStr, x + shadowOffset, y + shadowOffset, shadowPaint)
        canvas.drawText(textStr, x, y, textPaint)
    }
}
