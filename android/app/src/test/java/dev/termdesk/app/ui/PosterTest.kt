package dev.termdesk.app.ui

import dev.termdesk.app.data.PosterRenderer
import dev.termdesk.app.data.PosterStyles
import dev.termdesk.app.data.PosterText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「@海报生成」这条路。
 *
 * 三件事必须在这里钉死，因为它们都是"错了也不会报错、只会悄悄出丑"的类型：
 *  1. **底图文件名真的存在**——写错一个字母，用户看到的就是一张黑图；
 *  2. **排版算得对**——标题太长要降字号、要折行、折不下要省略号，不能冲出画面；
 *  3. **风格映射与自动选型**——用户只说"我要科技感"，落到哪张图由数据决定。
 *
 * 真正的 Canvas 绘制要在真机上看（JVM 单测没有 Bitmap），所以这里覆盖的是它上游的一切。
 */
class PosterTest {

    private fun assetsDir(): File = PackFixtures.assetsDir()

    // ── 1. 风格与私密底图 ────────────────────────────────────────────────

    @Test
    fun `三种风格齐备，而且每套底图文件名都真的在资源包里`() {
        assertEquals(listOf("赛博暗黑", "极简科技", "复古质感"), PosterStyles.all.map { it.id })
        val assets = assetsDir()
        var checked = 0
        for (style in PosterStyles.all) {
            assertTrue(style.id + " 没写关键词，自动选型挑不中", style.keywords.isNotEmpty())
            assertTrue(style.id + " 的罩层强度越界", style.wash in 0f..1f)
            assertTrue(style.id + " 的底渐隐越界", style.fade in 0f..1f)
            for (path in style.backgrounds) {
                checked += 1
                assertTrue(
                    style.id + " 指的底图不存在：" + path + "（写错文件名只会得到一张黑图）",
                    File(assets, path).isFile,
                )
            }
        }
        assertTrue("至少要内嵌几张真底图", checked >= 3)
    }

    @Test
    fun `底图只在风格内部轮换，换多少次都不会越界`() {
        for (style in PosterStyles.all) {
            if (style.drawn) {
                assertNull("没有素材的风格只能程序画底", style.backgroundAt(0))
                continue
            }
            val first = style.backgroundAt(0)
            assertNotNull(first)
            assertTrue("换底要能换到别的图", style.backgroundAt(1) != first || style.backgrounds.size == 1)
            assertEquals("轮一圈要回到第一张", first, style.backgroundAt(style.backgrounds.size))
            assertNotNull("负数也不能崩", style.backgroundAt(-3))
        }
    }

    @Test
    fun `按主题自己挑风格，挑不中就用缺省`() {
        assertEquals("赛博暗黑", PosterStyles.pick("数字技术变革与未来科技").id)
        assertEquals("极简科技", PosterStyles.pick("社区民生服务通知").id)
        assertEquals("复古质感", PosterStyles.pick("怀旧民谣市集").id)
        assertEquals(PosterStyles.fallback.id, PosterStyles.pick("").id)
        assertEquals(PosterStyles.fallback.id, PosterStyles.pick("随便写点什么").id)
    }

    @Test
    fun `没有素材的风格是靠程序画底，而不是缺一张图就崩`() {
        assertTrue("复古目前没有素材，应当走程序画底", PosterStyles.RETRO.drawn)
        assertFalse("赛博有素材", PosterStyles.CYBER.drawn)
        assertFalse("极简有素材", PosterStyles.MINIMAL.drawn)
    }

    // ── 2. 排版 ──────────────────────────────────────────────────────────

    /** 假的量宽函数：每个字符 10 个单位。这样断行结果是可以手算出来的。 */
    private val measure: (String, Float) -> Float = { text, size -> text.length * 10f * size / 100f }

    @Test
    fun `标题排不下就折行，再排不下才用省略号`() {
        // 每行最多 3 个字（30 / 10），最多两行 → 第 2 行要被省略号收掉。
        val lines = PosterText.wrap("abcdefgh", maxWidth = 30f, maxLines = 2) { it.length * 10f }
        assertEquals(2, lines.size)
        assertEquals("abc", lines[0])
        assertEquals("de…", lines[1])
        assertTrue("带省略号也不能超宽", lines.all { it.length * 10f <= 30f })
    }

    @Test
    fun `排得下就不加省略号，也不多折一行`() {
        val lines = PosterText.wrap("abcd", maxWidth = 30f, maxLines = 2) { it.length * 10f }
        assertEquals(listOf("abc", "d"), lines)
    }

    @Test
    fun `空标题不产生空行，免得画出幽灵文字`() {
        assertTrue(PosterText.wrap("   ", 100f, 2) { it.length * 10f }.isEmpty())
        assertEquals(emptyList<String>(), PosterText.wrap("", 100f, 2) { 0f })
    }

    @Test
    fun `降字号直到放得下，而且不降到下限以下`() {
        val (size, lines) = PosterText.fit(
            text = "数字媒体专业建设成果汇报",
            maxWidth = 50f,
            maxLines = 2,
            start = 100f,
            min = 40f,
            step = 10f,
            measure = measure,
        )
        assertTrue("字号必须在区间内", size in 40f..100f)
        assertTrue("行数不能超", lines.size <= 2)
        assertTrue("每一行都要放得下", lines.all { measure(it, size) <= 50f })
        assertTrue("排不下时必须真的降了字号", size < 100f)
    }

    @Test
    fun `短标题不用降字号`() {
        val (size, _) = PosterText.fit("海报", 400f, 2, 100f, 40f, 10f, measure)
        assertEquals(100f, size, 0.01f)
    }

    // ── 3. 那句台词与快捷调整 ────────────────────────────────────────────

    @Test
    fun `聊天里那句话就是用户要的样子`() {
        assertEquals("「新媒体创意大赛」海报已绘制完成", PosterText.caption("新媒体创意大赛"))
        assertEquals("「新媒体创意大赛」海报已绘制完成", PosterText.caption("  新媒体创意大赛  "))
        assertEquals("这张海报已绘制完成", PosterText.caption(""))
        assertFalse("不能出现空书名号", PosterText.caption(" ").contains("「」"))
    }

    @Test
    fun `快捷调整是累积的，而且都有上限`() {
        var adjust = PosterRenderer.Adjust()
        assertTrue("一开始什么都没调", adjust.labels().isEmpty())

        adjust = adjust.apply(PosterRenderer.Tweak.Contrast).apply(PosterRenderer.Tweak.Contrast)
        assertTrue("连点两次要叠加成 +36", adjust.contrast > 1.30f)
        assertEquals("标签只该有一条，写的是累计量", 1, adjust.labels().count { it.startsWith("对比+") })

        adjust = adjust.apply(PosterRenderer.Tweak.Saturation).apply(PosterRenderer.Tweak.Warm)
        assertTrue(adjust.saturation > 1f)
        assertTrue(adjust.warmth > 0f)

        repeat(20) { adjust = adjust.apply(PosterRenderer.Tweak.Contrast) }
        assertTrue("对比度不能无限涨", adjust.contrast <= 2.2f)

        adjust = adjust.apply(PosterRenderer.Tweak.Reset)
        assertEquals(PosterRenderer.Adjust(), adjust)
        assertTrue(adjust.labels().isEmpty())
    }

    @Test
    fun `换字号是循环的，用户不用记住点了第几次`() {
        var adjust = PosterRenderer.Adjust()
        adjust = adjust.apply(PosterRenderer.Tweak.TitleSize)
        assertTrue("第一次变大", adjust.titleScale > 1f)
        adjust = adjust.apply(PosterRenderer.Tweak.TitleSize)
        assertTrue("第二次变小", adjust.titleScale < 1f)
        adjust = adjust.apply(PosterRenderer.Tweak.TitleSize)
        assertEquals("第三次回到正常", 1f, adjust.titleScale, 0.001f)
    }

    @Test
    fun `换底图会递增，界面上看得出点过几次`() {
        val adjust = PosterRenderer.Adjust().apply(PosterRenderer.Tweak.Variant)
        assertEquals(1, adjust.variant)
        assertTrue(adjust.labels().contains("换底1"))
    }

    // ── 4. 菜单项 ────────────────────────────────────────────────────────

    @Test
    fun `海报生成是纯本地条目：没有指令、不问配色、也不占用本地追问`() {
        val poster = ChatMentions.all.first { it.id == "海报生成" }
        assertTrue("必须是本地操作", poster.poster)
        assertEquals("本地操作不能有写死的指令", "", poster.body)
        assertTrue(poster.templates.isEmpty())
        assertTrue(poster.decks.isEmpty())
        assertFalse(poster.table)
        assertNull(poster.copy)
        assertFalse(poster.image)
        assertTrue("本地操作没有要问的东西", poster.localAsks.isEmpty())
        assertFalse(poster.needsColors)
    }

    @Test
    fun `它在 @ 菜单里，搜海报能筛出来，而且不会被抹成空白`() {
        assertTrue(ChatMentions.match("").any { it.id == "海报生成" })
        assertTrue(ChatMentions.match("海报").any { it.id == "海报生成" })
        assertTrue("本地条目不能被替换成空", ChatMentions.expand("@海报生成").contains("@海报生成"))
    }
}
