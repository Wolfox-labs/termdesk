package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@` 菜单按"装没装"门控。
 *
 * 规则：带包且包真的会下发的那几项（网页 / PPT / 海报 / 表格），没装就不出现；纯提示词的
 * 条目（字幕 / 文案 / 游戏）永远在；图片暂时不门控——它的预设还没随包下发，
 * 门控一个装不上的包等于把功能下线。等 P3-e 把预设搬进包，再把 [ChatMention.pack] 补上。
 */
class ChatMentionsGatingTest {

    private val allIds = ChatMentions.all.map { it.id }

    @Test
    fun `一个包都没装时，被挡住的是网页 PPT 海报 表格四条`() {
        val visible = ChatMentions.match("", emptySet()).map { it.id }
        assertEquals(
            listOf("图片处理", "字幕生成", "文案撰写", "游戏演示"),
            visible,
        )
        assertEquals(
            listOf("网页生成", "PPT生成", "表格生成", "海报生成"),
            ChatMentions.hiddenByPacks(emptySet()).map { it.id },
        )
    }

    @Test
    fun `四个包都装了时，八条全回来，顺序不变`() {
        val installed = setOf("web", "ppt", "poster", "table")
        val visible = ChatMentions.match("", installed).map { it.id }
        assertEquals(allIds, visible)
        assertTrue(ChatMentions.hiddenByPacks(installed).isEmpty())
    }

    @Test
    fun `装了网页包之后，搜网页能搜到；没装表格包时搜表格搜不到`() {
        val installed = setOf("web")
        assertEquals(listOf("网页生成"), ChatMentions.match("网页", installed).map { it.id })
        assertTrue("表格包没装", ChatMentions.match("表格", installed).isEmpty())
        // 不按包过滤时（文案提示、单测）两者都认得出来。
        assertEquals(listOf("表格生成"), ChatMentions.match("表格").map { it.id })
    }

    @Test
    fun `门控只挂在会下发的包上，纯提示词条目没有被误挡`() {
        val byId = ChatMentions.all.associateBy { it.id }
        assertEquals("web", byId.getValue("网页生成").pack)
        assertEquals("ppt", byId.getValue("PPT生成").pack)
        assertEquals("poster", byId.getValue("海报生成").pack)
        assertEquals("table", byId.getValue("表格生成").pack)
        assertEquals(null, byId.getValue("文案撰写").pack)
        assertEquals(null, byId.getValue("字幕生成").pack)
        assertEquals(null, byId.getValue("游戏演示").pack)
        assertEquals("图片预设还没随包下发，先不门控", null, byId.getValue("图片处理").pack)
    }
}
