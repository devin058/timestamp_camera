package com.devin.ts_camera.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.devin.ts_camera.MainActivity
import com.devin.ts_camera.R
import com.devin.ts_camera.model.CompressionLevel
import com.devin.ts_camera.model.VideoResolution
import kotlinx.coroutines.*
import java.io.File

/**
 * Foreground service that processes video via FFmpeg in the background
 * while showing a notification with a progress bar.
 */
class VideoProcessingService : Service() {

    companion object {
        private const val TAG = "VideoProcessingSvc"
        private const val CHANNEL_ID = "video_processing"
        private const val NOTIFY_ID = 1001
        const val EXTRA_INPUT_URI = "input_uri"
        const val EXTRA_START_TIME_MS = "start_time_ms"
        const val EXTRA_DURATION_MS = "duration_ms"

        fun start(
            context: Context,
            inputUri: Uri,
            startWallTimeMs: Long,
            durationMs: Long
        ) {
            val intent = Intent(context, VideoProcessingService::class.java).apply {
                putExtra(EXTRA_INPUT_URI, inputUri.toString())
                putExtra(EXTRA_START_TIME_MS, startWallTimeMs)
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "视频处理",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示视频转码进度"
                }
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        val inputUriStr = intent?.getStringExtra(EXTRA_INPUT_URI) ?: run {
            Log.e(TAG, "No input URI in intent")
            return START_NOT_STICKY
        }
        val startWallTimeMs = intent.getLongExtra(EXTRA_START_TIME_MS, 0L)
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        Log.d(TAG, "Processing: uri=$inputUriStr, startMs=$startWallTimeMs, durMs=$durationMs")

        val inputUri = Uri.parse(inputUriStr)
        val outputFile = File.createTempFile("VID_", ".mp4", cacheDir)

        // Read settings
        val resolution = SettingsManager.getResolution(this)
        val compression = SettingsManager.getCompression(this)
        val mute = SettingsManager.isMuted(this)
        val fps = SettingsManager.getFps(this)
        Log.d(TAG, "Settings: res=$resolution, comp=$compression, mute=$mute, fps=$fps")

        startForeground(NOTIFY_ID, buildNotification(0))
        Log.d(TAG, "Foreground started, beginning transcode")

        serviceScope.launch {
            try {
                VideoProcessor.transcode(
                    context = this@VideoProcessingService,
                    params = VideoProcessor.TranscodeParams(
                        inputUri = inputUri,
                        outputFile = outputFile,
                        resolution = resolution,
                        compressionLevel = compression,
                        muteAudio = mute,
                        fps = fps,
                        overlayTimestampMs = startWallTimeMs,
                        videoStartWallTimeMs = startWallTimeMs
                    ),
                    onProgress = { progress ->
                        Log.d(TAG, "Progress: ${(progress * 100).toInt()}%")
                        updateNotification(progress)
                    }
                )
                Log.d(TAG, "Transcode done, saving to MediaStore, size=${outputFile.length()}")

                // Save to MediaStore
                val displayName = "VID_${System.currentTimeMillis()}.mp4"
                val uri = MediaStoreSaver.saveVideo(
                    this@VideoProcessingService,
                    outputFile,
                    displayName
                )
                Log.d(TAG, "Saved to MediaStore: $uri")
                outputFile.delete()

                showDoneNotification(true)
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                try { outputFile.delete() } catch (_: Exception) {}
                showDoneNotification(false)
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(progress: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TS Camera")
            .setContentText(if (progress in 1..99) "处理中 $progress%" else "处理中…")
            .setSmallIcon(R.drawable.app_icon)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private var lastNotifiedPct = -1

    private fun updateNotification(progress: Float) {
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        // Throttle: notify at most once per 5% change to avoid Android rate-limiting
        if (pct == lastNotifiedPct || pct == 100) return
        if (pct % 5 != 0 && pct - lastNotifiedPct < 5) return
        lastNotifiedPct = pct
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFY_ID, buildNotification(pct))
        Log.d(TAG, "Notify: $pct%")
    }

    private fun showDoneNotification(success: Boolean) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = getSystemService(NotificationManager::class.java)
        // Remove progress notification
        nm.cancel(NOTIFY_ID)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (success) "视频已保存" else "处理失败")
            .setContentText(if (success) "点击返回应用" else "请重试")
            .setSmallIcon(R.drawable.app_icon)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(NOTIFY_ID, notification)
    }
}
