package dev.termdesk.app.ui

import dev.termdesk.app.data.ImageTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「@图片处理」这条路。
 *
 * 它是目前唯一一条**完全在手机上做完**的路：压缩、转格式、打水印都是确定性的像素操作，
 * 没有"生成"的成分，所以不走内核、没有指令可装配，处理完只往对话里写一行系统提示。
 * 这里钉住的就是这些性质，以及用户点名要的那句话的措辞。
 *
 * 真正的像素处理（[ImageTools.process]）要 Android 的 Bitmap/Canvas，JVM 单测跑不了，
 * 靠真机验收；但"台词"和"数据模型"必须在单测里定死。
 */
class ImageMentionTest {

    private val img: ChatMention = ChatMentions.all.first { it.id == "图片处理" }

    @Test
    fun `图片处理是纯本地条目：没有指令、不问配色、也不占用本地追问`() {
        assertTrue("必须是本地操作", img.image)
        assertEquals("本地操作不能有写死的指令，否则会把装配结果抹掉", "", img.body)
        assertTrue("不该有骨架", img.templates.isEmpty())
        assertTrue("不该有蓝图", img.decks.isEmpty())
        assertTrue("不该有表格体裁", img.tables.isEmpty())
        assertEquals("不该有表单字段", null, img.copy)
        assertTrue("本地操作没有要问的东西", img.localAsks.isEmpty())
        assertFalse("不挑配色", img.needsColors)
    }

    @Test
    fun `它在 @ 菜单里，用图片相关的词都能筛出来`() {
        assertTrue(ChatMentions.match("").any { it.id == "图片处理" })
        for (q in listOf("图片", "照片", "水印", "压缩", "webp")) {
            assertTrue("搜「" + q + "」应当能筛出来", ChatMentions.match(q).any { it.id == "图片处理" })
        }
    }

    @Test
    fun `发出去之前不会被当成消息文本：@标记原样留着，不会被替换成空`() {
        // 本地条目的 body 是空的，装配器必须跳过它，否则 "@图片处理" 会变成空白；
        // 界面上发送时走的是"弹表单"这条分支，不会真的把这句话发给内核。
        val sent = ChatMentions.expand("@图片处理")
        assertTrue("应当原样保留，而不是被抹成空白", sent.contains("@图片处理"))
    }

    @Test
    fun `处理完那句话就是用户要的措辞`() {
        val text = ImageTools.noteText(
            ImageTools.Result(saved = 3, failed = 0, where = "相册 Pictures/TermDesk"),
        )
        assertEquals("✅ 成功处理 3 张图片，已保存至相册。位置：相册 Pictures/TermDesk。", text)
    }

    @Test
    fun `有失败的图会如实说出来，不假装全都成功`() {
        val text = ImageTools.noteText(
            ImageTools.Result(saved = 2, failed = 1, where = "相册 Pictures/TermDesk"),
        )
        assertTrue(text.startsWith("✅ 成功处理 2 张图片，已保存至相册。"))
        assertTrue("失败的张数要讲清楚", text.contains("另有 1 张没能处理"))
    }

    @Test
    fun `保存位置就是相册里的 Pictures 下的 TermDesk`() {
        assertEquals("TermDesk", ImageTools.ALBUM)
        val onNew = ImageTools.noteText(
            ImageTools.Result(saved = 1, failed = 0, where = "相册 Pictures/" + ImageTools.ALBUM),
        )
        val onOld = ImageTools.noteText(
            ImageTools.Result(saved = 1, failed = 0, where = "应用文件 Pictures/" + ImageTools.ALBUM),
        )
        assertTrue("新系统要写进系统相册", onNew.contains("相册 Pictures/TermDesk"))
        assertTrue("旧系统要如实说明写在应用目录，不能让用户以为丢图了", onOld.contains("应用文件"))
    }
}
