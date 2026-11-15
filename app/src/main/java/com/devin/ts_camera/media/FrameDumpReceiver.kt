package com.devin.ts_camera.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Debug-only: pulls a frame out of a video so the burned-in timestamp can be
 * inspected, since there is no ffmpeg on the host.
 *
 * Writes `<outDir>/frame_<n>s.png` for each requested second.
 */
class FrameDumpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("video")
        if (path == null) {
            Log.w(TAG, "No video supplied")
            return
        }
        val seconds = intent.getStringExtra("seconds")
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: listOf(0, 1, 2)

        Thread {
            val out = File(context.getExternalFilesDir(null), "frames").apply { mkdirs() }
            seconds.forEach { second ->
                val bmp = VideoProcessor.getFrameAtTime(
                    context, android.net.Uri.fromFile(File(path)), second * 1000L
                )
                if (bmp == null) {
                    Log.w(TAG, "No frame at ${second}s")
                    return@forEach
                }
                val file = File(out, "frame_${second}s.png")
                FileOutputStream(file).use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                Log.i(TAG, "Wrote ${file.absolutePath} (${bmp.width}x${bmp.height})")
                bmp.recycle()
            }
            Log.i(TAG, "Frame dump finished at ${out.absolutePath}")
        }.start()
    }

    private companion object {
        const val TAG = "FrameDump"
    }
}
