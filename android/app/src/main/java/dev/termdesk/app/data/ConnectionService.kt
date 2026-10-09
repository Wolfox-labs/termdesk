package dev.termdesk.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.termdesk.app.MainActivity
import dev.termdesk.app.R

/**
 * Keeps the app scheduled while it is off screen, so the phone can still be told things.
 *
 * Measured on the real device before this existed: backgrounded for 35 seconds, the PC wrote
 * a notification frame to a socket that was still open, and the phone did nothing with it —
 * no log line, nothing drawn. Coming back to the window processed it **32 milliseconds**
 * later. Android had frozen the process; the bytes were sitting in the socket buffer the
 * whole time. A notification about work that finished while the phone is in a pocket is
 * exactly the case this feature exists for, and it cannot work if the reader is frozen.
 *
 * A foreground service is the supported way to say "this process is still doing something the
 * user asked for". What it deliberately does NOT do:
 *
 *  - it does not own the connection. [AgentClient] does; this only raises the process
 *    priority, which is why stopping it never drops the link by itself;
 *  - it does not restart itself (`START_NOT_STICKY`): a service that came back after being
 *    killed would show "connected" over a socket that is gone, and a notification that lies
 *    is worse than none;
 *  - it does not stay for its own sake. The caller stops it the moment the window is back or
 *    the link is down, because a permanent "connected" notice over a dead link is a lie the
 *    user cannot switch off without switching off the feature.
 *
 * Forward-looking note: starting a foreground service from the background is refused from
 * targetSdk 31 up — this app targets 28 (see `app/build.gradle.kts`), which is why the
 * "start it as the window goes away" wiring in `AppRoot` is allowed to work. Raising
 * targetSdk means revisiting that moment (start it earlier, while the window is still
 * visible), not deleting this class.
 */
class ConnectionService : Service() {

    /**
     * Keeps the CPU awake so the socket keeps being read while the screen is off.
     *
     * The foreground service raises the process's priority; this is about the *screen going
     * off*, which is when a phone in a pocket spends its time. Without it the reader stops
     * being scheduled with the rest of the device, which is the same symptom as the freeze
     * measured above. Held only while this service lives, and released in [onDestroy].
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        wakeLock = runCatching {
            val power = getSystemService(PowerManager::class.java)
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName.connection")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "唤醒锁拿不到：${it.message}") }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // "Stop telling me" has to mean the notice goes away now; keeping the link is
            // the app's business, and it will ask again next time it goes to the background.
            stopSelf()
            return START_NOT_STICKY
        }
        val where = intent?.getStringExtra(EXTRA_WHERE)?.takeIf { it.isNotBlank() }
        startForeground(NOTIFICATION_ID, buildNotification(where))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "保持连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "离开 App 后仍与电脑保持连接，这样“答完了”“在等你确认”才能及时提醒你"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(where: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (where != null) {
            "与 $where 保持连接 · 离开 App 也能收到“答完了”"
        } else {
            "离开 App 也能收到“答完了”"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_termdesk_notify)
            .setContentTitle("TermDesk 正在守着这台电脑")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "不用了", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Only offered while there is something to allow. The measured situation — the system
        // suspending this app even with the service and the wake lock held — is not something
        // the app can fix, and the honest response is to say where the switch is instead of
        // pretending the notice is enough on its own.
        if (!BatteryGuidance.isExempt(this)) {
            val allow = PendingIntent.getActivity(
                this,
                2,
                BatteryGuidance.intentFor(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "允许后台运行", allow)
            builder.setSubText("如果通知总是晚到，多半是系统不允许它后台运行")
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "termdesk.connection"
        private const val NOTIFICATION_ID = 7432
        private const val EXTRA_WHERE = "where"
        private const val ACTION_STOP = "dev.termdesk.app.CONNECTION_STOP"
        private const val TAG = "TermDeskConnection"

        fun start(context: Context, where: String?) {
            val intent = Intent(context, ConnectionService::class.java)
                .putExtra(EXTRA_WHERE, where)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "前台服务起不来：${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ConnectionService::class.java)) }
        }
    }
}
