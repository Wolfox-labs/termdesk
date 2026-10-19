package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject

internal fun supportsChatSync(auth: JSONObject): Boolean {
    val capabilities = auth.optJSONArray("capabilities") ?: return false
    return (0 until capabilities.length()).any { capabilities.optString(it) == "chat.sync" }
}

/** Full snapshots are authoritative, including removals and edits of old events. */
internal fun reconcileChatEvents(frame: JSONObject): List<ChatEvent> =
    parseChatEvents(frame.optJSONArray("events") ?: JSONArray()).sortedBy { it.seq }
