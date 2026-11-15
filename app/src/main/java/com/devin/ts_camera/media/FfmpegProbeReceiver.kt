package com.devin.ts_camera.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Debug-only harness for probing which FFmpeg encoder/filter combinations the
 * device actually supports.
 *
 * Enabled only for `debuggable` builds (see the manifest). Usage:
 *
 * ```
 * adb shell am broadcast -a com.devin.ts_camera.DEBUG_FFMPEG \
 *     -n com.devin.ts_camera/.media.FfmpegProbeReceiver \
 *     --es input /data/user/0/com.devin.ts_camera/cache/VID_x.mp4
 * ```
 *
 * Results are written to logcat under the `FfmpegProbe` tag.
 */
class FfmpegProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val input = intent.getStringExtra("input") ?: run {
            Log.w(TAG, "No --es input path supplied")
            return
        }
        if (!File(input).exists()) {
            Log.w(TAG, "Input does not exist: $input")
            return
        }

        // Broadcast receivers get ~10s on the main thread, so run the probe on
        // a background thread and let the result land in logcat.
        // Duration of 0 means "whole file", which is what matters for the real
        // pipeline where throughput, not startup latency, dominates.
        val seconds = intent.getIntExtra("seconds", 0)
        Thread {
            probe(context, input, seconds)
        }.start()
    }

    private fun probe(context: Context, input: String, seconds: Int) {
        val outDir = File(context.cacheDir, "probe").apply { mkdirs() }
        val durationArg = if (seconds > 0) "-t $seconds " else ""

        // Each entry is (label, video args, filter chain override).
        // `null` for the filter means "infer from the args" (see below).
        val candidates = listOf(
            Triple(
                "baseline 10M g30 bf0",
                "-c:v h264_mediacodec -pix_fmt nv12 -b:v 10M -g 30 -bf 0",
                null
            ),
            Triple(
                "bitrate 4M",
                "-c:v h264_mediacodec -pix_fmt nv12 -b:v 4M -g 30 -bf 0",
                null
            ),
            Triple(
                "bitrate 2M",
                "-c:v h264_mediacodec -pix_fmt nv12 -b:v 2M -g 30 -bf 0",
                null
            ),
            Triple(
                "gop 60",
                "-c:v h264_mediacodec -pix_fmt nv12 -b:v 10M -g 60 -bf 0",
                null
            ),
            Triple(
                "no -bf flag",
                "-c:v h264_mediacodec -pix_fmt nv12 -b:v 10M -g 30",
                null
            ),
            Triple(
                "no scale filter",
                "-c:v h264_mediacodec -pix_fmt nv12 -b:v 10M -g 30 -bf 0",
                "setsar=1,format=nv12"
            ),
            Triple(
                "stream copy (no encode)",
                "-c:v copy",
                null
            )
        )

        candidates.forEachIndexed { index, (label, args, filterOverride) ->
            val out = File(outDir, "probe_$index.mp4")
            out.delete()

            // Mirror the real pipeline unless the case overrides the chain.
            val filter = filterOverride ?: run {
                val pixelFormat = if (args.contains("nv12")) "nv12" else "yuv420p"
                "scale=720:480:force_original_aspect_ratio=decrease," +
                        "pad=720:480:(ow-iw)/2:(oh-ih)/2:color=black," +
                        "setsar=1,format=$pixelFormat"
            }
            val cmd = "-y $durationArg-i \"$input\" " +
                    "-vf \"$filter\" " +
                    "$args -an -f mp4 \"$out\""

            val started = System.currentTimeMillis()
            val result = runAndWait(cmd)
            val elapsed = System.currentTimeMillis() - started
            val bytes = if (out.exists()) out.length() else 0L

            Log.i(
                TAG,
                "RESULT | $label | ok=${result == 0} | code=$result | " +
                        "${elapsed}ms | ${bytes}B"
            )
            if (result != 0) {
                Log.i(TAG, "  $label -> ${lastLogs.takeLast(4).joinToString(" / ")}")
            }
            out.delete()
        }
        Log.i(TAG, "Probe finished")
    }

    private var lastLogs: List<String> = emptyList()

    private fun runAndWait(cmd: String): Int {
        val latch = CountDownLatch(1)
        var code = Int.MIN_VALUE
        val session = FFmpegKit.executeAsync(
            cmd,
            { completed ->
                code = completed.returnCode?.value ?: Int.MIN_VALUE
                lastLogs = (completed.allLogsAsString ?: "")
                    .lineSequence()
                    .filter { it.contains("mediacodec", true) || it.contains("error", true) }
                    .map { it.trim() }
                    .toList()
                latch.countDown()
            },
            { },
            { }
        )
        if (!latch.await(1200, TimeUnit.SECONDS)) {
            FFmpegKit.cancel(session.sessionId)
        }
        return code
    }

    private companion object {
        const val TAG = "FfmpegProbe"
    }
}
