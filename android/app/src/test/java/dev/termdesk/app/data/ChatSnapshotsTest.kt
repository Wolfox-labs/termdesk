package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatSnapshotsTest {
    private fun frame(vararg sequences: Int) = JSONObject().put("id", "c")
        .put("events", JSONArray(sequences.map { JSONObject().put("seq", it).put("kind", "message").put("text", "new $it") }))

    @Test fun `a full snapshot reconciles removals`() {
        assertEquals(listOf(2), reconcileChatEvents(frame(2)).map { it.seq })
    }
    @Test fun `an empty snapshot clears stale events`() {
        assertTrue(reconcileChatEvents(frame()).isEmpty())
    }
    @Test fun `old agent snapshots without sync metadata remain authoritative`() {
        assertEquals(listOf(1, 2), reconcileChatEvents(frame(2, 1)).map { it.seq })
    }
    @Test fun `revised text is accepted at an unchanged sequence`() {
        assertEquals("new 2", reconcileChatEvents(frame(2)).single().text)
    }
    @Test fun `sync requires an advertised capability`() {
        assertFalse(supportsChatSync(JSONObject()))
        assertTrue(supportsChatSync(JSONObject().put("capabilities", JSONArray().put("chat.sync"))))
    }
}
