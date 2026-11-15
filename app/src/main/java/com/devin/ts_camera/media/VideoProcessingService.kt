package com.devin.ts_camera.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.devin.ts_camera.MainActivity
import com.devin.ts_camera.R
import com.devin.ts_camera.location.ReverseGeocoder
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Foreground service that transcodes recorded videos one at a time.
 *
 * Jobs are persisted to disk before processing starts and removed only after
 * the output reaches MediaStore, so a crash, a reboot, or a failed transcode
 * can't silently lose a recording.
 */
class VideoProcessingService : Service() {

    companion object {
        private const val TAG = "VideoProcessingSvc"
        private const val CHANNEL_ID = "video_processing"
        private const val NOTIFY_ID = 1001
        private const val MAX_ATTEMPTS = 2

        /** Minimum gap between notification updates while a job runs. */
        private const val NOTIFY_MIN_INTERVAL_MS = 1_000L

        /** Wakes the service when a job is enqueued or the app resumes. */
        private val kick = Channel<Unit>(Channel.CONFLATED)

        private lateinit var appContext: Context

        @Volatile
        private var started = false

        fun start(
            context: Context,
            inputUri: Uri,
            startWallTimeMs: Long,
            durationMs: Long,
            alreadyStamped: Boolean = false
        ) {
            appContext = context.applicationContext
            val job = JobStore.enqueue(
                appContext,
                inputPath = inputUri.path ?: error("视频路径无效"),
                startWallTimeMs = startWallTimeMs,
                durationMs = durationMs,
                stampedDuringRecording = alreadyStamped
            )
            Log.d(TAG, "Enqueued job #${job.id} (${job.inputPath})")

            kick.trySend(Unit)

            // Starting the service only matters the first time; while it is
            // already alive the queued job is picked up from JobStore. Claim
            // the slot before starting so a concurrent call can't double-start.
            if (!claimStartSlot()) return

            val intent = Intent(context, VideoProcessingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Nothing can process the queue in the background now; let the
                // next call try again rather than wedging the guard at true.
                Log.e(TAG, "Could not start processing service", e)
                started = false
                throw e
            }
        }

        @Synchronized
        private fun claimStartSlot(): Boolean {
            if (started) return false
            started = true
            return true
        }


        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "视频处理",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示视频转码进度"
                    setShowBadge(false)
                }
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedPct = -1

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        acquireWakeLock()
        // Must happen within a few seconds of startForegroundService().
        startForeground(NOTIFY_ID, buildNotification(0, 0))
        serviceScope.launch { processLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A sticky restart (or a second start call) still needs the loop woken;
        // onCreate already started it, but it is parked on the channel.
        kick.trySend(Unit)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.coroutineContext.cancelChildren()
        releaseWakeLock()
        started = false
        super.onDestroy()
    }

    // ---- Queue loop -------------------------------------------------------

    private suspend fun processLoop() {
        // Re-adopt anything left behind by a previous process death before
        // serving new work.
        recoverInterruptedJobs()

        while (true) {
            if (!kick.receiveCatching().isSuccess) return

            while (true) {
                val pending = JobStore.nextPending(this)
                if (pending == null) {
                    Log.d(TAG, "Queue empty, stopping service")
                    // Clear the guard before stopping: `start()` may have
                    // enqueued a job while the loop was draining, and it has
                    // to be able to start us again.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    started = false
                    stopSelf()
                    return
                }
                processJob(pending)
            }
        }
    }

    private fun recoverInterruptedJobs() {
        JobStore.all(this)
            .filter { it.state == JobState.RUNNING }
            .forEach {
                Log.w(TAG, "Recovering interrupted job #${it.id}")
                JobStore.update(this, it.copy(state = JobState.PENDING, attempts = 0))
            }
    }

    private suspend fun processJob(job: VideoJob) {
        val source = File(job.inputPath)
        if (!source.exists()) {
            Log.e(TAG, "Source missing for job #${job.id}: ${job.inputPath}")
            JobStore.remove(this, job.id)
            return
        }

        JobStore.update(this, job.copy(state = JobState.RUNNING))
        lastNotifiedPct = -1
        currentJobDurationMs = job.durationMs
        currentJobStartedAt = System.currentTimeMillis()
        notifyProgress(0f)

        val resolution = SettingsManager.getResolution(this)
        val compression = SettingsManager.getCompression(this)
        val mute = SettingsManager.isMuted(this)
        val fps = SettingsManager.getFps(this)

        val outputFile = File.createTempFile("VID_", ".mp4", cacheDir)
        val startedAt = System.currentTimeMillis()
        Log.d(TAG, "Processing #${job.id}: ${job.durationMs}ms, res=$resolution, mute=$mute, fps=$fps")

        val params = VideoProcessor.TranscodeParams(
            inputUri = Uri.fromFile(source),
            outputFile = outputFile,
            resolution = resolution,
            compressionLevel = compression,
            muteAudio = mute,
            fps = fps,
            // Skip the overlay when the recorder already burned it in.
            videoStartWallTimeMs =
                if (job.stampedDuringRecording) 0L else job.startWallTimeMs
        )

        try {
            try {
                VideoProcessor.transcode(
                    context = this,
                    params = params,
                    onProgress = { notifyProgress(it) }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The stamp overlay is the most fragile part of the filter
                // graph. Losing it is much better than losing the recording,
                // so retry once with a plain pass before giving up.
                Log.e(TAG, "Stamped transcode failed for #${job.id}; retrying without timestamp", e)
                outputFile.delete()
                if (!saveFallback(job, params)) throw e
                return
            }

            val elapsed = System.currentTimeMillis() - startedAt
            Log.d(TAG, "Transcoded in ${elapsed}ms (${outputFile.length()} bytes)")

            // Names come in without an extension; adding one here used to
            // produce "VID_123.mp4.mp4".
            val displayName = "VID_${job.startWallTimeMs}"
            val savedUri = MediaStoreSaver.saveVideo(this, outputFile, displayName)
            Log.d(TAG, "Saved $savedUri")

            RecordingHistory.add(
                context = this,
                uri = savedUri,
                displayName = displayName,
                durationMs = job.durationMs,
                sizeBytes = outputFile.length(),
                address = reverseGeocode(job)
            )

            JobStore.remove(this, job.id)
            source.delete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Job #${job.id} failed (attempt ${job.attempts + 1})", e)
            val attempts = job.attempts + 1
            if (attempts >= MAX_ATTEMPTS) {
                // Keep the recording: park it next to the app's files so the
                // user can recover it from the file manager.
                val kept = quarantine(source, job.id)
                Log.e(TAG, "Giving up on #${job.id}; source kept at $kept")
                JobStore.update(this, job.copy(state = JobState.FAILED, attempts = attempts))
            } else {
                JobStore.update(this, job.copy(state = JobState.PENDING, attempts = attempts))
            }
        } finally {
            outputFile.delete()
        }

        notifyProgress(1f)
    }

    /**
     * Attempt the no-timestamp export and, if it produces a file, publish it.
     *
     * @return true when the recording reached MediaStore, in which case the
     *         caller should stop handling this job as a failure.
     */
    private suspend fun saveFallback(
        job: VideoJob,
        params: VideoProcessor.TranscodeParams
    ): Boolean {
        val fallbackUri = VideoProcessor.transcodeWithoutStamp(
            context = this,
            params = params,
            onProgress = { notifyProgress(it) }
        ) ?: return false

        val savedUri = MediaStoreSaver.saveVideo(this, params.outputFile, "VID_${job.startWallTimeMs}")
        Log.w(TAG, "Saved #${job.id} without timestamp: $savedUri")

        JobStore.remove(this, job.id)
        File(job.inputPath).delete()
        return true
    }

    /**
     * Best-effort address for a finished recording.
     *
     * The service has no stored fix, so it reads the device's last known
     * location and resolves it. Failure just means the history entry shows
     * the time without an address.
     */
    private suspend fun reverseGeocode(job: VideoJob): String? {
        val location = try {
            LocationServices.getFusedLocationProviderClient(this)
                .lastLocation
                .awaitOrNull()
        } catch (e: SecurityException) {
            Log.d(TAG, "No location permission for reverse geocoding")
            return null
        } catch (e: Exception) {
            Log.d(TAG, "Could not read last location: ${e.message}")
            return null
        }

        return if (location == null) {
            null
        } else {
            ReverseGeocoder.lookup(location.latitude, location.longitude)
        }
    }

    /** Bridges a Play Services Task into a suspend call without extra deps. */
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitOrNull(): T? =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { cont.resume(it) }
            addOnFailureListener { cont.resume(null) }
            addOnCanceledListener { cont.cancel() }
        }

    private fun quarantine(source: File, jobId: Long): File? = try {
        val dir = File(getExternalFilesDir(null), "recovered")
        dir.mkdirs()
        val dest = File(dir, "VID_${System.currentTimeMillis()}_$jobId.mp4")
        if (source.renameTo(dest)) {
            dest
        } else {
            source.copyTo(dest, overwrite = true)
            source.delete()
            dest
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not quarantine $source", e)
        null
    }

    // ---- Wake lock --------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "TS_camera:processing"
        ).apply {
            setReferenceCounted(false)
            // Not time-boxed: a long recording legitimately needs well over an
            // hour, and a lapsed lock would let the CPU sleep mid-encode. The
            // service releases this explicitly when the queue drains.
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---- Notifications ----------------------------------------------------

    /** Non-null while a job is encoding, so the notification can show ETA. */
    private var currentJobDurationMs = 0L
    private var currentJobStartedAt = 0L

    private fun buildNotification(progress: Int, queueSize: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val queueText = if (queueSize > 0) " (+${queueSize}个排队)" else ""
        val etaText = remainingText(progress)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TS Camera")
            .setContentText(
                if (progress in 1..99) "处理中 $progress%$etaText$queueText"
                else "处理中…$queueText"
            )
            .setSmallIcon(R.drawable.app_icon)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * Rough remaining time, from the source duration and the progress so far.
     *
     * Without this a 50-minute 1080p recording shows a bare percentage for
     * over an hour and looks stuck.
     */
    private fun remainingText(progress: Int): String {
        if (progress !in 1..99 || currentJobDurationMs <= 0L) return ""
        val elapsedMs = System.currentTimeMillis() - currentJobStartedAt
        if (elapsedMs <= 0L) return ""
        val totalMs = elapsedMs * 100L / progress
        val remainingMin = ((totalMs - elapsedMs) / 60_000L).coerceAtLeast(0L)
        return if (remainingMin > 0) " · 约剩 ${remainingMin} 分钟" else " · 即将完成"
    }

    private var lastNotifiedAt = 0L

    private fun notifyProgress(progress: Float) {
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        if (pct == lastNotifiedPct) return

        // Throttle by time, not by percentage. On a 50-minute recording each
        // percent takes over half a minute, and the old "only every 5%" rule
        // left the notification frozen for minutes at a time — which reads as
        // a hang. One update a second is cheap and keeps the ETA fresh.
        val now = System.currentTimeMillis()
        val done = pct >= 100
        if (!done && now - lastNotifiedAt < NOTIFY_MIN_INTERVAL_MS) return

        lastNotifiedPct = pct
        lastNotifiedAt = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFY_ID, buildNotification(pct, JobStore.pendingCount(this)))
    }
}
