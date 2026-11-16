package com.aiqulabs.abcd.dj.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.metrolist.android.R
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_library_notification_channel
import com.metrolist.shared.generated.resources.dj_library_notification_stop
import com.metrolist.shared.generated.resources.dj_library_notification_text
import com.metrolist.shared.generated.resources.dj_library_notification_title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import timber.log.Timber

/**
 * Foreground service (type dataSync) that keeps "Analyse my library" running while the app is in the
 * background, with a progress notification and a Stop button. It only hosts [DjLibraryAnalyzer.run];
 * all state lives in [DjLibraryAnalyzer] so the screens can show it.
 */
class DjAnalysisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var texts: Texts? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            DjLibraryAnalyzer.stop()
            if (!DjLibraryAnalyzer.isRunning) stopSelf()
            return START_NOT_STICKY
        }
        val loaded = texts ?: runBlocking { loadTexts() }.also { texts = it }
        createChannel(loaded)
        startInForeground(notification(loaded, done = 0, total = 0))
        if (DjLibraryAnalyzer.isRunning) return START_NOT_STICKY // a second tap must not start a second batch

        val job =
            scope.launch {
                try {
                    DjLibraryAnalyzer.run(applicationContext) { done, total, title ->
                        val progress = getString(Res.string.dj_library_notification_text, (done + 1).coerceAtMost(total), total)
                        notificationManager().notify(NOTIFICATION_ID, notification(loaded, done, total, progress, title))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Library analysis failed")
                } finally {
                    ServiceCompat.stopForeground(this@DjAnalysisService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        DjLibraryAnalyzer.attach(job)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notification(
        texts: Texts,
        done: Int,
        total: Int,
        progressText: String? = null,
        title: String? = null,
    ): Notification {
        val openApp =
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
        val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(texts.title)
            .setContentText(progressText)
            .setSubText(title)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(total, done, total == 0)
            .addAction(R.drawable.ic_notification_close, texts.stop, stop)
            .build()
    }

    private fun createChannel(texts: Texts) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, texts.channel, NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    /** Strings are read once, because the service must show its notification within seconds of starting. */
    private suspend fun loadTexts() =
        Texts(
            title = getString(Res.string.dj_library_notification_title),
            channel = getString(Res.string.dj_library_notification_channel),
            stop = getString(Res.string.dj_library_notification_stop),
        )

    private class Texts(
        val title: String,
        val channel: String,
        val stop: String,
    )

    companion object {
        private const val TAG = "DjAnalysisService"
        private const val CHANNEL_ID = "dj_library_analysis"
        private const val NOTIFICATION_ID = 7421
        private const val ACTION_STOP = "com.aiqulabs.abcd.dj.STOP_LIBRARY_ANALYSIS"

        internal fun startIntent(context: Context) = Intent(context, DjAnalysisService::class.java)

        internal fun stopIntent(context: Context) = Intent(context, DjAnalysisService::class.java).setAction(ACTION_STOP)
    }
}
