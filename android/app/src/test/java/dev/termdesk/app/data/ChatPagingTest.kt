package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话分页在手机端的三件纯逻辑：认不认这个能力、这一帧是不是"一页"、一页怎么并进已有历史。
 *
 * 真正的"打开很快"要在模拟器上看；这里钉的是并页时不能丢、不能重、不能把新内容覆盖成旧的。
 */
class ChatPagingTest {

    private fun frame(mode: String, hasMore: Boolean, vararg seqs: Int): JSONObject {
        val events = JSONArray()
        seqs.forEach { seq ->
            events.put(JSONObject().put("seq", seq).put("kind", "message").put("role", "assistant").put("text", "第 $seq 条"))
        }
        return JSONObject()
            .put("id", "chat-1")
            .put("events", events)
            .put("eventSync", JSONObject().put("mode", mode).put("hasMore", hasMore))
    }

    private fun events(vararg seqs: Int): List<ChatEvent> = seqs.map { seq ->
        ChatEvent(
            seq = seq, at = 0L, kind = "message", role = "assistant", text = "第 $seq 条",
            name = null, state = null, exitCode = null, sourceKind = null, streaming = false,
        )
    }

    @Test
    fun `能力在就分页，不在就退回整份`() {
        val pageable = JSONObject().put("capabilities", JSONArray().put("chat.sync").put("chat.page"))
        val old = JSONObject().put("capabilities", JSONArray().put("chat.sync"))
        assertTrue(supportsChatPage(pageable))
        assertFalse("老 agent 只会整份给", supportsChatPage(old))
        assertFalse(supportsChatPage(JSONObject()))
    }

    @Test
    fun `认得出这是一页历史，以及还有没有更早的`() {
        val paged = frame("page", true, 4, 5)
        assertEquals("page", chatPageMode(paged))
        assertTrue(chatPageHasMore(paged))

        val last = frame("page", false, 1, 2)
        assertFalse(chatPageHasMore(last))

        val full = frame("full", false, 1, 2, 3)
        assertEquals("full", chatPageMode(full))
    }

    @Test
    fun `更早的一页并到前面，顺序不乱`() {
        val merged = mergeChatPage(events(3, 4, 5), events(1, 2))
        assertEquals(listOf(1, 2, 3, 4, 5), merged.map { it.seq })
    }

    @Test
    fun `重叠的那几条以新到的那份为准，不会出现两份`() {
        val current = events(3, 4, 5).map { it.copy(text = "旧文本") }
        val incoming = events(2, 3, 4)
        val merged = mergeChatPage(current, incoming)
        assertEquals(listOf(2, 3, 4, 5), merged.map { it.seq })
        assertEquals("新到的修订要覆盖旧的", "第 3 条", merged.first { it.seq == 3 }.text)
    }

    @Test
    fun `空历史时一页就是全部，不会凭空多出东西`() {
        val merged = mergeChatPage(emptyList(), events(7, 8))
        assertEquals(listOf(7, 8), merged.map { it.seq })
    }
}
