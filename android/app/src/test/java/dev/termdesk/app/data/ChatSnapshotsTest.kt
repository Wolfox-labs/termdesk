package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatSnapshotsTest {
    private fun frame(vararg sequences: Int, after: Int = 0, mode: String = "full", id: String = "c") = JSONObject()
        .put("id", id)
        .put("events", JSONArray(sequences.map { JSONObject().put("seq", it).put("kind", "message").put("text", "new $it") }))
        .put("eventSync", JSONObject().put("afterSeq", after).put("mode", mode).put("complete", true))

    @Test fun `an empty delta preserves the visible transcript`() {
        val current = parseChatEvents(frame(1, 2).getJSONArray("events"))
        assertEquals(current, reconcileChatEvents(current, frame(after = 2, mode = "delta"), "c"))
    }
    @Test fun `a delta replaces changed records without duplicate sequences`() {
        val current = parseChatEvents(frame(1, 2).getJSONArray("events"))
        val merged = reconcileChatEvents(current, frame(2, 3, after = 1, mode = "delta"), "c")
        assertEquals(listOf(1, 2, 3), merged.map { it.seq })
    }
    @Test fun `a full snapshot reconciles removals`() {
        val current = parseChatEvents(frame(1, 2).getJSONArray("events"))
        assertEquals(listOf(2), reconcileChatEvents(current, frame(2), "c").map { it.seq })
    }
    @Test fun `an older agent snapshot remains authoritative`() {
        val current = parseChatEvents(frame(1).getJSONArray("events"))
        val snapshot = frame(2).apply { remove("eventSync") }
        assertEquals(listOf(2), reconcileChatEvents(current, snapshot, "c").map { it.seq })
    }
    @Test fun `events from one conversation never merge into another`() {
        val current = parseChatEvents(frame(1).getJSONArray("events"))
        assertEquals(listOf(2), reconcileChatEvents(current, frame(2, after = 1, mode = "delta", id = "other"), "c").map { it.seq })
    }
    @Test fun `sync requires an advertised capability`() {
        assertFalse(supportsChatSync(JSONObject()))
        assertTrue(supportsChatSync(JSONObject().put("capabilities", JSONArray().put("chat.sync"))))
    }
}
