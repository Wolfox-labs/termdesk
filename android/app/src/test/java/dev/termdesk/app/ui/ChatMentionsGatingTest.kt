package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@` 菜单按"装没装"门控。
 *
 * 五个带数据的功能（网页 / PPT / 海报 / 表格 / 图片）都跟着包走：没装就不出现，
 * 装了才出现。字幕 / 文案 / 游戏是纯提示词条目，永远在。
 */
class ChatMentionsGatingTest {

    private val allIds = ChatMentions.all.map { it.id }
    private val fivePacks = setOf("web", "ppt", "poster", "table", "image")

    @Test
    fun `一个包都没装时，只剩三个纯提示词条目`() {
        assertEquals(
            listOf("字幕生成", "文案撰写", "游戏演示"),
            ChatMentions.match("", emptySet()).map { it.id },
        )
        assertEquals(
            listOf("网页生成", "PPT生成", "图片处理", "表格生成", "海报生成"),
            ChatMentions.hiddenByPacks(emptySet()).map { it.id },
        )
    }

    @Test
    fun `五个包都装了时，八条全回来，顺序不变`() {
        assertEquals(allIds, ChatMentions.match("", fivePacks).map { it.id })
        assertTrue(ChatMentions.hiddenByPacks(fivePacks).isEmpty())
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
    fun `每个功能都声明了自己靠哪个包，纯提示词条目不挂包`() {
        val byId = ChatMentions.all.associateBy { it.id }
        assertEquals("web", byId.getValue("网页生成").pack)
        assertEquals("ppt", byId.getValue("PPT生成").pack)
        assertEquals("poster", byId.getValue("海报生成").pack)
        assertEquals("table", byId.getValue("表格生成").pack)
        assertEquals("image", byId.getValue("图片处理").pack)
        assertEquals(null, byId.getValue("文案撰写").pack)
        assertEquals(null, byId.getValue("字幕生成").pack)
        assertEquals(null, byId.getValue("游戏演示").pack)
    }
}
