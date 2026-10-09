package dev.termdesk.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.termdesk.app.MainActivity
import dev.termdesk.app.R

/**
 * Something the PC decided this phone should be told about.
 *
 * The decision is made over there (`pc-agent/src/notify.js`), because that is the side that
 * knows when a turn really ended and when a kernel is blocked waiting for an answer. This
 * side decides only whether to interrupt somebody who is already reading that conversation —
 * the one thing the PC cannot know.
 */
data class AgentNotification(
    val id: String,
    /** turn_done | turn_failed | approval */
    val kind: String,
    val chatId: String?,
    val sessionId: String?,
    val engine: String?,
    val title: String,
    val text: String,
    val at: Long,
    /** True when it happened while no client was attached, so it arrives late. */
    val whileAway: Boolean,
    /**
     * The question this is about, for `kind == "approval"`.
     *
     * It is what lets the notification be taken back once somebody answers: without it the
     * phone only knows "a kernel was waiting at some point", and a notification saying that
     * outlives the wait.
     */
    val requestId: String? = null,
) {
    val isApproval: Boolean get() = kind == "approval"
    val isFailure: Boolean get() = kind == "turn_failed"
}

/** A conversation a notification tap asked for. */
data class OpenRequest(val chatId: String)

/** The tap target carried by a notification, or null for an ordinary launch. */
fun parseOpenRequest(intent: Intent?): OpenRequest? =
    intent?.getStringExtra(AgentNotifications.EXTRA_CHAT_ID)
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?.let { OpenRequest(it) }

/**
 * The notification surface: one channel, one place that builds a notification.
 *
 * `IMPORTANCE_HIGH` on purpose. This channel carries only "somebody is waiting for you" — a
 * finished turn, a failed turn, a question the kernel is blocked on. The persistent sandbox
 * notice is a different channel at IMPORTANCE_LOW, so muting that one cannot mute these.
 */
object AgentNotifications {
    const val CHANNEL_ID = "termdesk.turns"
    const val EXTRA_CHAT_ID = "termdesk.openChatId"
    private const val GROUP = "termdesk.conversations"

    /**
     * The channel, created once.
     *
     * `POST_NOTIFICATIONS` is declared in the manifest, and this app targets API 28, so on a
     * newer Android the permission is granted without a prompt. That is worth knowing
     * rather than assuming: a build that raised targetSdk would start being silently ignored
     * here until somebody asked for the permission at runtime.
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "会话动态", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "一轮回答结束、失败，或内核在等你确认时提醒你"
                enableVibration(true)
            },
        )
    }

    /** Tapping opens the conversation it is about. */
    fun intentFor(context: Context, note: AgentNotification): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            note.chatId?.let { putExtra(EXTRA_CHAT_ID, it) }
        }
        // The request code is part of the identity: two conversations must not overwrite
        // each other's pending intent, extras included.
        val code = (note.chatId ?: note.id).hashCode()
        return PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun build(context: Context, note: AgentNotification): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_termdesk_notify)
            .setContentTitle(note.title.ifBlank { "TermDesk" })
            .setContentText(
                note.text.ifBlank { if (note.isApproval) "内核在等你确认" else "这一轮已经结束" },
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
            .setWhen(whenFor(note.at))
            .setAutoCancel(true)
            .setContentIntent(intentFor(context, note))
            .setPriority(
                if (note.isApproval) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH,
            )
            .setGroup(GROUP)
            .apply {
                // Said out loud rather than implied: a notification that waited for the phone
                // to come back should not read as if it happened this second.
                if (note.whileAway) setSubText("你不在的时候")
                if (note.isApproval) setCategory(NotificationCompat.CATEGORY_REMINDER)
            }
            .build()

    /**
     * How far apart the two clocks may be before the PC's stamp is not trusted for display.
     *
     * The timestamp is the PC's (`notify.js` stamps it there), and a machine whose clock is
     * a few minutes ahead would date the notification in the future — which Android draws as
     * "in 3 minutes", for something that has already happened. The two sides agreeing on
     * "now" is the moment this phone received it, and that is the honest fallback.
     */
    private const val CLOCK_TRUST_MS = 120_000L

    /** The stamp to draw: the PC's when it is close to this phone's clock, otherwise now. */
    fun whenFor(at: Long, now: Long = System.currentTimeMillis()): Long =
        if (at > 0 && kotlin.math.abs(now - at) <= CLOCK_TRUST_MS) at else now

    /**
     * Stable per conversation and kind, so a second turn replaces the first instead of
     * stacking, while an unanswered question keeps its own slot beside a finished turn.
     */
    fun idFor(note: AgentNotification): Int =
        (note.chatId ?: note.id).hashCode() * 31 + if (note.isApproval) 1 else 0
}
