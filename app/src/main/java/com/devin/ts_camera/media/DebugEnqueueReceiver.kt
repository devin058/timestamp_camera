package com.devin.ts_camera.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only entry point that feeds a file already on the device through the
 * real [VideoProcessingService] path — queue, transcode, MediaStore — so the
 * production pipeline can be exercised without recording first.
 *
 * ```
 * adb shell am broadcast -a com.devin.ts_camera.DEBUG_ENQUEUE \
 *     -n com.devin.ts_camera/.media.DebugEnqueueReceiver \
 *     --es input /data/user/0/com.devin.ts_camera/cache/VID_x.mp4 \
 *     --el startWall 1794728538226 --el duration 30000
 * ```
 */
class DebugEnqueueReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val input = intent.getStringExtra("input")
        if (input == null) {
            Log.w(TAG, "No input supplied")
            return
        }
        val startWall = intent.getLongExtra("startWall", System.currentTimeMillis())
        val duration = intent.getLongExtra("duration", 0L)

        Log.i(TAG, "Enqueueing $input (startWall=$startWall, duration=$duration)")
        VideoProcessingService.start(
            context = context,
            inputUri = android.net.Uri.fromFile(java.io.File(input)),
            startWallTimeMs = startWall,
            durationMs = duration
        )
    }

    private companion object {
        const val TAG = "DebugEnqueue"
    }
}
