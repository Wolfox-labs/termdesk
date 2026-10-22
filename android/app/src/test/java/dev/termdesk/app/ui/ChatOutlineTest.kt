package dev.termdesk.app.ui

import dev.termdesk.app.data.ChatEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话目录只挑用户自己的话。
 *
 * 模型的旁白不是章节，工具调用更不是；每一轮用户的提问才是"一章"。
 * 序号必须和聊天列表的 item 下标对齐，否则点了会跳错一行。
 */
class ChatOutlineTest {

    private fun event(seq: Int, role: String, text: String, at: Long = seq * 1000L) = ChatEvent(
        seq = seq, at = at, kind = "message", role = role, text = text,
        name = null, state = null, exitCode = null, sourceKind = null, streaming = false,
    )

    private fun one(event: ChatEvent) = TranscriptRow.One(event)

    @Test
    fun `只收用户消息，顺序和下标都对得上`() {
        val rows = listOf(
            one(event(1, "user", "第一问")),
            one(event(2, "assistant", "回答")),
            one(event(3, "user", "第二问")),
        )
        val outline = turnOutline(rows)
        assertEquals(2, outline.size)
        assertEquals(listOf(0, 2), outline.map { it.index })
        assertEquals(listOf("第一问", "第二问"), outline.map { it.title })
    }

    @Test
    fun `头部多一行时序号整体往后挪`() {
        val rows = listOf(
            one(event(1, "user", "问")),
            one(event(2, "assistant", "答")),
        )
        assertEquals(listOf(1), turnOutline(rows, offset = 1).map { it.index })
    }

    @Test
    fun `多行消息取第一行非空文字当标题`() {
        val rows = listOf(one(event(1, "user", "\n\n  标题在这里\n正文")))
        assertEquals("标题在这里", turnOutline(rows).single().title)
    }

    @Test
    fun `空消息给一个能点的占位标题，不是空字符串`() {
        val rows = listOf(one(event(1, "user", "   ")))
        assertEquals("（空消息）", turnOutline(rows).single().title)
    }

    @Test
    fun `没有任何用户消息就没有目录`() {
        val rows = listOf(one(event(1, "assistant", "只有模型在说话")))
        assertEquals(0, turnOutline(rows).size)
    }
}
