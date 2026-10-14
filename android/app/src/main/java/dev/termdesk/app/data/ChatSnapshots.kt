package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject

internal fun supportsChatSync(auth: JSONObject): Boolean {
    val capabilities = auth.optJSONArray("capabilities") ?: return false
    return (0 until capabilities.length()).any { capabilities.optString(it) == "chat.sync" }
}

/** Older agents send full snapshots; an explicit complete delta merges by seq. */
internal fun reconcileChatEvents(current: List<ChatEvent>, frame: JSONObject, currentChatId: String?): List<ChatEvent> {
    val incoming = parseChatEvents(frame.optJSONArray("events") ?: JSONArray())
    val sync = frame.optJSONObject("eventSync")
    val delta = currentChatId == frame.optString("id") && sync != null &&
        sync.optString("mode") != "full" && sync.optInt("afterSeq") > 0 && sync.optBoolean("complete", false)
    if (!delta) return incoming.sortedBy { it.seq }
    val index = current.associateByTo(linkedMapOf()) { it.seq }
    incoming.forEach { index[it.seq] = it }
    return index.values.sortedBy { it.seq }
}
