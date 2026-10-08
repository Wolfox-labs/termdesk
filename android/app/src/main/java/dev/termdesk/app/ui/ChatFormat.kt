package dev.termdesk.app.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import dev.termdesk.app.data.SessionInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The small decisions a screen should not be making inline: what a session is
 * called, whether the transcript is scrolled to the bottom, how a time is
 * written, and how a working directory is shortened.
 *
 * Split out of ChatSection.kt. `workspaceShortName` is here on purpose: the
 * session list is about to be grouped by workspace, and this is the name a group
 * header will show.
 *
 * `javax.xml.bind` is not on Android, so the ISO parser tries the portable
 * patterns in order of how much of the timestamp they need.
 */

internal fun sessionTitle(session: SessionInfo): String =
    session.title?.takeIf { it.isNotBlank() } ?: session.id.take(18)

/**
 * Scroll so the last item's bottom edge sits at the viewport bottom.
 *
 * `animateScrollToItem` only pins the item's top, which leaves a tall final
 * message cut off — the opposite of what opening a conversation should do.
 */
internal suspend fun LazyListState.animateScrollToBottom() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    animateScrollToItem(lastIndex)
    alignLastItemBottom(animated = true)
}

/** Same as [animateScrollToBottom], but without the animation — used when a chat opens. */
internal suspend fun LazyListState.scrollToBottomNow() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    scrollToItem(lastIndex)
    alignLastItemBottom(animated = false)
}

internal suspend fun LazyListState.alignLastItemBottom(animated: Boolean) {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 }
        ?: info.visibleItemsInfo.lastOrNull()
        ?: return
    val remaining = (last.offset + last.size) - info.viewportEndOffset
    if (remaining <= 0f) return
    if (animated) animateScrollBy(remaining.toFloat()) else scrollBy(remaining.toFloat())
}

/** True when the list end is within [thresholdPx] of the viewport bottom. */
internal fun LazyListState.isNearBottom(thresholdPx: Int = 160): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    val remaining = (last.offset + last.size) - info.viewportEndOffset
    return remaining <= thresholdPx
}

/** Short display name for a working directory: its last meaningful path segment. */
internal fun workspaceShortName(cwd: String): String {
    val normalized = cwd.replace('\\', '/').trimEnd('/')
    if (normalized.isBlank()) return cwd.ifBlank { "未命名" }
    return normalized.substringAfterLast('/').ifBlank { normalized }
}

/**
 * Format an ISO-8601 timestamp for the drawer.
 *
 * `javax.xml.bind` is not on Android, so the portable parsers are tried in
 * order of how much of the timestamp they need.
 */
internal fun parseIsoDate(iso: String?): Date? {
    if (iso.isNullOrBlank()) return null
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    )
    for (pattern in patterns) {
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }.parse(iso)
        }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}

internal fun formatChatTime(iso: String?): String {
    val date = parseIsoDate(iso)
    if (date != null) return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(date)
    // Last resort: show the date part rather than nothing.
    if (iso.isNullOrBlank()) return "—"
    return iso.take(16).replace('T', ' ')
}

/** "刚刚 / 12 分钟前 / 3 小时前 / 2 天前 / MM-dd" — the drawer's session timestamps. */
internal fun formatRelativeTime(iso: String?): String {
    val date = parseIsoDate(iso) ?: return formatChatTime(iso)
    val diff = System.currentTimeMillis() - date.time
    if (diff < 0) return formatChatTime(iso)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 7 * day -> "${diff / day} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.US).format(date)
    }
}

internal fun formatChatTime(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(epochMs))
}
