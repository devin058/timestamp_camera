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
import java.util.Collections
import java.util.LinkedList

/**
 * Foreground service that processes videos sequentially via FFmpeg.
 * New recordings are queued if a job is already running.
 */
class VideoProcessingService : Service() {

    companion object {
        private const val TAG = "VideoProcessingSvc"
        private const val CHANNEL_ID = "video_processing"
        private const val NOTIFY_ID = 1001
        const val EXTRA_INPUT_URI = "input_uri"
        const val EXTRA_START_TIME_MS = "start_time_ms"
        const val EXTRA_DURATION_MS = "duration_ms"

        // ---- Job queue ----
        data class VideoJob(
            val inputUri: Uri,
            val startWallTimeMs: Long,
            val durationMs: Long
        )

        private val pendingJobs = Collections.synchronizedList(LinkedList<VideoJob>())
        @Volatile private var isProcessing = false
        private var activeService: VideoProcessingService? = null

        fun start(
            context: Context,
            inputUri: Uri,
            startWallTimeMs: Long,
            durationMs: Long
        ) {
            val job = VideoJob(inputUri, startWallTimeMs, durationMs)
            val alreadyRunning: Boolean = synchronized(pendingJobs) {
                pendingJobs.add(job)
                isProcessing
            }

            val intent = Intent(context, VideoProcessingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            Log.d(TAG, "Job queued. alreadyRunning=$alreadyRunning, queueSize=${pendingJobs.size}")
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
        activeService = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isProcessing) {
            processNext()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        activeService = null
        super.onDestroy()
    }

    // ---- Queue processor ----

    private fun processNext() {
        val job: VideoJob? = synchronized(pendingJobs) {
            if (pendingJobs.isEmpty()) {
                isProcessing = false
                null
            } else {
                isProcessing = true
                pendingJobs.removeAt(0)
            }
        }

        if (job == null) {
            Log.d(TAG, "Queue empty, stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        startForeground(NOTIFY_ID, buildNotification(0, pendingJobs.size))
        lastNotifiedPct = -1

        val outputFile = File.createTempFile("VID_", ".mp4", cacheDir)

        // Read settings
        val resolution = SettingsManager.getResolution(this)
        val compression = SettingsManager.getCompression(this)
        val mute = SettingsManager.isMuted(this)
        val fps = SettingsManager.getFps(this)
        Log.d(TAG, "Processing job: uri=${job.inputUri}, dur=${job.durationMs}ms, res=$resolution, mute=$mute, fps=$fps, queueRemaining=${pendingJobs.size}")

        serviceScope.launch {
            try {
                VideoProcessor.transcode(
                    context = this@VideoProcessingService,
                    params = VideoProcessor.TranscodeParams(
                        inputUri = job.inputUri,
                        outputFile = outputFile,
                        resolution = resolution,
                        compressionLevel = compression,
                        muteAudio = mute,
                        fps = fps,
                        overlayTimestampMs = job.startWallTimeMs,
                        videoStartWallTimeMs = job.startWallTimeMs
                    ),
                    onProgress = { progress ->
                        updateNotification(progress, pendingJobs.size)
                    }
                )
                Log.d(TAG, "Transcode done, size=${outputFile.length()}")

                // Save to MediaStore
                val displayName = "VID_${System.currentTimeMillis()}.mp4"
                val uri = MediaStoreSaver.saveVideo(this@VideoProcessingService, outputFile, displayName)
                Log.d(TAG, "Saved: $uri")
                outputFile.delete()

                // Delete original
                try {
                    File(job.inputUri.path!!).let { if (it.exists()) it.delete() }
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                try { outputFile.delete() } catch (_: Exception) {}
            } finally {
                // Process next job in queue
                processNext()
            }
        }
    }

    // ---- Notifications ----

    private var lastNotifiedPct = -1

    private fun buildNotification(progress: Int, queueSize: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val queueText = if (queueSize > 0) " (+${queueSize}个排队)" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TS Camera")
            .setContentText(if (progress in 1..99) "处理中 $progress%$queueText" else "处理中…$queueText")
            .setSmallIcon(R.drawable.app_icon)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(progress: Float, queueSize: Int) {
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        if (pct == lastNotifiedPct) return
        if (pct != 100 && pct % 5 != 0 && pct - lastNotifiedPct < 5) return
        lastNotifiedPct = pct
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFY_ID, buildNotification(pct, queueSize))
    }
}
