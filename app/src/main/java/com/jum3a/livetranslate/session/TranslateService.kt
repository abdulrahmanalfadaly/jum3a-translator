package com.jum3a.livetranslate.session

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jum3a.livetranslate.App
import com.jum3a.livetranslate.Graph
import com.jum3a.livetranslate.MainActivity
import com.jum3a.livetranslate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the microphone running when the screen turns off or you switch apps
 * (Android only allows background recording from a visible "foreground service").
 */
class TranslateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Graph.engine.stop()
            shutdown()
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Listening for speech…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            shutdown()
            return START_NOT_STICKY
        }

        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jum3a:live")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        }

        if (!Graph.engine.start()) {
            shutdown()
            return START_NOT_STICKY
        }

        if (observer == null) {
            observer = scope.launch {
                launch {
                    Graph.engine.state.map { it.running }.distinctUntilChanged().collect { running ->
                        if (!running) shutdown()
                    }
                }
                Graph.engine.state
                    .map { st -> st.entries.lastOrNull { it.english != null }?.english }
                    .distinctUntilChanged()
                    .collect { latest ->
                        if (latest != null) {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, buildNotification(latest))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        observer?.cancel()
        observer = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Graph.engine.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Translating Malay → English")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "com.jum3a.livetranslate.START"
        private const val ACTION_STOP = "com.jum3a.livetranslate.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TranslateService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            Graph.engine.stop()
            context.startService(Intent(context, TranslateService::class.java).setAction(ACTION_STOP))
        }
    }
}
