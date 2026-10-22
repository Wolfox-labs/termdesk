package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject

internal fun supportsChatSync(auth: JSONObject): Boolean {
    val capabilities = auth.optJSONArray("capabilities") ?: return false
    return (0 until capabilities.length()).any { capabilities.optString(it) == "chat.sync" }
}

/**
 * 这个 agent 会不会按页给历史。
 *
 * 老 agent 只会整份给（chat.sync），那时手机就照旧整份取——分页是可选能力，
 * 不是协议版本的门槛，所以看 capability 而不是看版本号。
 */
internal fun supportsChatPage(auth: JSONObject): Boolean {
    val capabilities = auth.optJSONArray("capabilities") ?: return false
    return (0 until capabilities.length()).any { capabilities.optString(it) == "chat.page" }
}

/** 这个 `chat` 帧是"一页历史"还是"整份快照"。 */
internal fun chatPageMode(frame: JSONObject): String =
    frame.optJSONObject("eventSync")?.optString("mode").orEmpty()

/** 还有没有更早的，以及更早那一页该从哪个 seq 往上要。 */
internal fun chatPageHasMore(frame: JSONObject): Boolean =
    frame.optJSONObject("eventSync")?.optBoolean("hasMore") == true

/** Full snapshots are authoritative, including removals and edits of old events. */
internal fun reconcileChatEvents(frame: JSONObject): List<ChatEvent> =
    parseChatEvents(frame.optJSONArray("events") ?: JSONArray()).sortedBy { it.seq }

/**
 * 把新到的一页并进已有历史。
 *
 * 分页帧只往回拿，所以正常情况下是"前置"；但一页可能和现有内容重叠（重连、修订），
 * 因此按 seq 去重，新的那份优先级更高——它带的是内核刚刚给的最新文本。
 */
internal fun mergeChatPage(current: List<ChatEvent>, incoming: List<ChatEvent>): List<ChatEvent> =
    (incoming + current).distinctBy { it.seq }.sortedBy { it.seq }
