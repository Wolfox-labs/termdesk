package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@` 菜单按"装没装"门控。
 *
 * 规则只有一条：带包的功能（网页 / PPT / 海报 / 表格 / 图片）没装就不出现，纯提示词的
 * 条目（字幕 / 文案 / 游戏）永远在。这样"没装"不会表现成"点了没反应"。
 */
class ChatMentionsGatingTest {

    private val allIds = ChatMentions.all.map { it.id }

    @Test
    fun `一个包都没装时，只剩不需要包的三条`() {
        val visible = ChatMentions.match("", emptySet()).map { it.id }
        assertEquals(listOf("字幕生成", "文案撰写", "游戏演示"), visible)
        // 五条带包的都在，只是被挡住。
        assertEquals(5, ChatMentions.hiddenByPacks(emptySet()).size)
    }

    @Test
    fun `五个包都装了时，八条全回来，顺序不变`() {
        val installed = setOf("web", "ppt", "poster", "table", "image")
        val visible = ChatMentions.match("", installed).map { it.id }
        assertEquals(allIds, visible)
        assertTrue(ChatMentions.hiddenByPacks(installed).isEmpty())
    }

    @Test
    fun `装了网页包之后，搜网页能搜到，搜表格仍然搜不到`() {
        val installed = setOf("web")
        assertEquals(listOf("网页生成"), ChatMentions.match("网页", installed).map { it.id })
        assertTrue(ChatMentions.match("表格", installed).isEmpty())
        // 不按包过滤时（文案提示、单测）两者都认得出来。
        assertEquals(listOf("表格生成"), ChatMentions.match("表格").map { it.id })
    }

    @Test
    fun `每条需要包的功能都声明了自己的包名，而且拼写和打包脚本一致`() {
        val byId = ChatMentions.all.associateBy { it.id }
        assertEquals("web", byId.getValue("网页生成").pack)
        assertEquals("ppt", byId.getValue("PPT生成").pack)
        assertEquals("poster", byId.getValue("海报生成").pack)
        assertEquals("table", byId.getValue("表格生成").pack)
        assertEquals("image", byId.getValue("图片处理").pack)
        // 纯提示词条目不该挂包，否则它们会被门控挡掉。
        assertEquals(null, byId.getValue("文案撰写").pack)
        assertEquals(null, byId.getValue("字幕生成").pack)
        assertEquals(null, byId.getValue("游戏演示").pack)
    }
}
