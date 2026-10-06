package dev.termdesk.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.termdesk.app.MainActivity
import dev.termdesk.app.R

/**
 * Keeps the phone-side sandbox alive while TermDesk is not on screen.
 *
 * Why this exists: the sandbox agent is a child process of the app. The moment the
 * app stops being visible, Android is free to treat that process - and the Node
 * runtime it spawned - as disposable: the low-memory killer may take it, and on
 * Android 12+ the phantom-process killer kills child processes of a cached app
 * outright. A foreground service raises the app's process priority and takes it
 * out of the "cached" bucket, which is the only supported way to say "this is
 * still running, do not reap it".
 *
 * What it deliberately does NOT do:
 *
 *  - It does not restart the agent. `START_NOT_STICKY`, because a service that
 *    comes back after being killed would show "running" over a sandbox that is
 *    gone - a notification that lies is worse than no notification.
 *  - It does not own the agent process. [LocalAgent] still owns it; this service
 *    only keeps the process group alive and gives the user a way to stop it.
 */
class LocalAgentService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The agent owns the process, so ask it to stop; it calls back into
            // [stop] which tears this service down. stopSelf() after is a no-op
            // safety net if the callback was already cleared.
            stopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        val note = intent?.getStringExtra(EXTRA_NOTE)?.takeIf { it.isNotBlank() } ?: DEFAULT_NOTE
        startForeground(NOTIFICATION_ID, buildNotification(note))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "本地内核",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "手机沙盒运行时的常驻通知；关掉它，长任务会在后台被系统回收"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(note: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalAgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_termdesk_notify)
            .setContentTitle("TermDesk 本地内核")
            .setContentText(note)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "termdesk-local-kernel"
        private const val NOTIFICATION_ID = 7431
        private const val EXTRA_NOTE = "note"
        private const val ACTION_STOP = "dev.termdesk.app.LOCAL_AGENT_STOP"
        private const val DEFAULT_NOTE = "本地代理运行中"
        private const val TAG = "TermDeskLocalService"

        /**
         * Set by [LocalAgent] when it has a running agent, so the notification's
         * "停止" action reaches the thing that actually owns the process. A plain
         * `stopService` would leave an orphaned Node process behind.
         */
        @Volatile
        var stopRequested: (() -> Unit)? = null

        fun start(context: Context, note: String) {
            val intent = Intent(context, LocalAgentService::class.java).putExtra(EXTRA_NOTE, note)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "前台服务起不来：${it.message}") }
        }

        fun stop(context: Context) {
            stopRequested = null
            runCatching { context.stopService(Intent(context, LocalAgentService::class.java)) }
        }
    }
}