package com.devin.ts_camera.camera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.camera.core.CameraEffect
import androidx.arch.core.util.Function as OnDrawListener
import androidx.core.util.Consumer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Burns a live wall-clock timestamp into recorded frames.
 *
 * Doing this while recording means the saved file is already stamped, so the
 * post-processing pass only has to scale and compress — no per-frame overlay
 * compositing. That removes the dominant cost of transcoding a long take.
 *
 * Each frame is stamped with the time it was captured, derived from the
 * frame's own timestamp rather than "now", so a slow frame does not drift.
 */
class TimestampBurner private constructor(
    private val onError: (Throwable) -> Unit
) : AutoCloseable {

    private val handler = Handler(Looper.getMainLooper())

    // Targets VIDEO_CAPTURE: only recorded frames are stamped, the viewfinder
    // keeps showing a clean preview (the UI draws its own clock overlay).
    val effect: OverlayEffect = OverlayEffect(
        CameraEffect.VIDEO_CAPTURE,
        CameraEffect.TRANSFORMATION_CAMERA_AND_SURFACE_ROTATION,
        handler,
        Consumer<Throwable> { throwable ->
            Log.e(TAG, "OverlayEffect error", throwable)
            onError(throwable)
        }
    ).apply {
        setOnDrawListener(OnDrawListener { frame ->
            drawTimestamp(frame)
            true
        })
    }

    /** Wall-clock time of the first recorded frame, set when recording starts. */
    @Volatile
    private var firstFrameWallTimeMs = 0L
    @Volatile
    private var firstFrameTimestampNanos = -1L

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var paint: Paint? = null
    private var textSize = 0f
    private var margin = 0f

    /**
     * Anchors the wall clock to the first frame, so the stamp reflects capture
     * time even if encoding starts slightly later.
     */
    fun anchor(startWallTimeMs: Long) {
        firstFrameWallTimeMs = startWallTimeMs
        firstTimestampFallbackMs = startWallTimeMs
        firstFrameTimestampNanos = -1L
    }

    @Volatile
    private var firstTimestampFallbackMs = 0L

    private fun drawTimestamp(frame: Frame) {
        val canvas = frame.getOverlayCanvas()
        val w = frame.size.width
        val h = frame.size.height
        if (w <= 0 || h <= 0) return

        val wallTime = wallTimeFor(frame)
        val p = paintFor(minOf(w, h).toFloat())
        val text = formatter.format(Date(wallTime))

        // The overlay canvas is in sensor orientation: the recorder writes a
        // landscape buffer plus a rotation flag, so text drawn upright here
        // ends up sideways in the played-back file. Rotate into the frame's
        // display orientation before drawing.
        //
        // The sign is inverted relative to the sensor rotation because
        // canvases receive the buffer un-rotated — measured on an LG V300L,
        // whose sensor rotation is 90 and which needs a -90 canvas turn. Only
        // verified for 90 and 270; if zero-copy rotation is ever used on a
        // device reporting 0/180 this needs checking again.
        val rotation = frame.rotationDegrees
        val turn = (360 - rotation) % 360
        val saveCount = canvas.save()
        try {
            when (turn) {
                90 -> {
                    canvas.translate(w.toFloat(), 0f)
                    canvas.rotate(90f)
                }
                180 -> {
                    canvas.translate(w.toFloat(), h.toFloat())
                    canvas.rotate(180f)
                }
                270 -> {
                    canvas.translate(0f, h.toFloat())
                    canvas.rotate(270f)
                }
            }

            // After the transform the drawable area is h x w for quarter turns.
            val quarterTurn = turn == 90 || turn == 270
            val drawW = if (quarterTurn) h.toFloat() else w.toFloat()
            val drawH = if (quarterTurn) w.toFloat() else h.toFloat()

            val x = drawW / 2f
            val y = drawH - margin - p.descent()
            canvas.drawText(text, x, y, p)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun wallTimeFor(frame: Frame): Long {
        val nanos = frame.timestampNanos
        if (firstFrameTimestampNanos < 0L) {
            firstFrameTimestampNanos = nanos
        }
        val elapsedMs = (nanos - firstFrameTimestampNanos) / 1_000_000L
        return firstFrameWallTimeMs + elapsedMs
    }

    private fun paintFor(shortEdge: Float): Paint {
        val size = (shortEdge * TEXT_HEIGHT_FRACTION).coerceIn(10f, 72f)
        val existing = paint
        if (existing != null && size == textSize) return existing

        textSize = size
        margin = (shortEdge * 0.03f).coerceAtLeast(6f)
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
            setShadowLayer((size * 0.12f).coerceAtLeast(1.5f), 0f, 0f, Color.BLACK)
        }.also { paint = it }
    }

    /** Clears the anchor so the next recording starts its own timeline. */
    fun reset() {
        firstFrameTimestampNanos = -1L
    }

    override fun close() {
        try {
            clearOnDrawListenerQuietly()
            effect.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close OverlayEffect", e)
        }
    }

    private fun clearOnDrawListenerQuietly() {
        try {
            effect.clearOnDrawListener()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "TimestampBurner"

        /** Text height as a fraction of the frame's short edge. */
        private const val TEXT_HEIGHT_FRACTION = 0.032f

        fun create(onError: (Throwable) -> Unit): TimestampBurner? = try {
            TimestampBurner(onError)
        } catch (e: Exception) {
            // Devices without the required GL support fall back to the
            // post-processing overlay path.
            Log.w(TAG, "OverlayEffect unavailable; will stamp during transcode", e)
            null
        }
    }
}
