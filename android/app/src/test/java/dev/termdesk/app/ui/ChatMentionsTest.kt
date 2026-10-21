package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选中「@模板」之后，输入框里应该是什么、光标应该在哪。
 *
 * 这个类存在的原因是一次真机上的症状：在手机上打 `@` 再从菜单里点「网页生成」，
 * 输入框里出现的是 `@网页生成 `，但**光标停在 @ 后面**（第 1 位）而不是文字后面。
 * 接着打主题就插到了 @ 和标记之间，成了「@校园开放日网页生成」。
 *
 * 根因不在模板文本上：Compose 的文本字段在外面换掉文本时只换文本、**保留原来的选区**，
 * 而用户敲 @ 时光标正好在第 1 位。所以插入必须自己把光标一起给出去——下面这条断言
 * 就是钉住那一件事。
 */
class ChatMentionsTest {

    private val web: ChatMention = ChatMentions.all.first { it.id == "网页生成" }

    @Test
    fun `光标落在插入内容之后，而不是停在刚敲下的 @ 后面`() {
        val out = ChatMentions.insertion("@", web)
        assertEquals("插入结果是「@标记 + 一个空格」", "@网页生成 ", out.text)
        assertEquals("光标必须在插入内容末尾", out.text.length, out.caret)
        // 第 1 位就是真机上看到的那个位置：@ 刚打完时光标所在处。
        assertTrue("光标不能在 @ 后面（真机症状）", out.caret > 1)
    }

    @Test
    fun `插入替换掉光标前那一个 @ 词，前面的文字原样保留`() {
        val out = ChatMentions.insertion("主题：校园开放日 @网", web)
        assertEquals("主题：校园开放日 @网页生成 ", out.text)
        assertEquals(out.text.length, out.caret)
    }

    @Test
    fun `尾空格让菜单收起，光标正好在那个空格之后`() {
        val out = ChatMentions.insertion("@", web)
        assertTrue("插入结果以空格结尾", out.text.endsWith(" "))
        assertEquals("光标在空格之后，可以直接接着打主题", out.text.length, out.caret)
        assertNull("菜单随即收起", ChatMentions.query(out.text))
    }

    @Test
    fun `插入后接着打的是主题，不会挤在 @ 和标记之间`() {
        val out = ChatMentions.insertion("@", web)
        val typed = out.text.substring(0, out.caret) + "校园开放日"
        assertEquals("@网页生成 校园开放日", typed)
        // 展开发生在发送之前，所以主题确实会随完整指令一起到内核手里。
        assertTrue(
            "展开后的指令里带着主题",
            ChatMentions.expand(typed).contains("校园开放日"),
        )
    }

    @Test
    fun `手动打出来的 @标记同样算套用，删掉就算没套用`() {
        assertEquals(listOf("网页生成"), ChatMentions.applied("@网页生成 校园开放日").map { it.id })
        assertEquals(emptyList<String>(), ChatMentions.applied("校园开放日").map { it.id })
    }
}
