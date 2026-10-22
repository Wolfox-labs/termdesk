package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 骨架资源包与模板注册表之间的契约。
 *
 * [WebTemplatesTest] 用的是内存里的假骨架，钉的是装配逻辑；这一份读**真的**
 * `app/src/main/assets/templates/web/` 下那几份 .html，钉的是「注册表说的槽位，骨架里真的有」。
 *
 * 为什么值得单独一个文件：这两边是分开维护的——加一套骨架只要往资源目录丢一个 .html，
 * 改槽位名只要改一行数据。两边一旦对不上，症状是**真机上生成出来的页面留着
 * `{{标题}}` 这种占位符**，而那要等到跑完一次模型、打开文件才看得见。
 * 这里在编译期就把它拦住。
 */
class PageSkeletonContractTest {

    /** 骨架已从 assets 搬到 packs/，这里交给 PackFixtures 摊平（包内路径不变）。 */
    private fun assetsDir(): File = PackFixtures.assetsDir()

    /** 骨架里所有 `{{...}}` 槽位的名字，按出现顺序去重。 */
    private fun slotsIn(html: String): List<String> {
        val names = LinkedHashSet<String>()
        var from = 0
        while (true) {
            val open = html.indexOf("{{", from)
            if (open < 0) break
            val close = html.indexOf("}}", open)
            if (close < 0) break
            names.add(html.substring(open + 2, close).trim())
            from = close + 2
        }
        return names.toList()
    }

    private fun read(template: PageTemplate): String {
        val file = File(assetsDir(), template.assetPath)
        assertTrue("资源包里缺少 " + template.assetPath + "（" + template.id + "）", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `每套骨架的资源文件都真的在资源包里，而且不是空文件`() {
        for (template in WebTemplates.all) {
            val html = read(template)
            assertTrue(template.id + " 的骨架太短了，不像一份能用的页面", html.length > 1200)
            assertTrue(template.id + " 的骨架必须是完整 HTML 文档", html.contains("<!DOCTYPE html>"))
            assertTrue(template.id + " 的骨架必须自包含样式", html.contains("<style>"))
            assertFalse(
                template.id + " 的骨架不能引用外部资源，否则断网打不开",
                html.contains("http://") || html.contains("https://"),
            )
        }
    }

    @Test
    fun `注册表声明的槽位，骨架上必须真的有`() {
        for (template in WebTemplates.all) {
            val slots = slotsIn(read(template))
            assertTrue(
                template.id + "：注册表说主标题填进「" + template.primarySlot + "」，但骨架里没有这个槽位",
                slots.contains(template.primarySlot),
            )
            if (template.subSlot.isNotBlank()) {
                assertTrue(
                    template.id + "：注册表说副标题填进「" + template.subSlot + "」，但骨架里没有",
                    slots.contains(template.subSlot),
                )
            }
            for (slot in template.bulletSlots) {
                assertTrue(
                    template.id + "：注册表说要点填进「" + slot + "」，但骨架里没有",
                    slots.contains(slot),
                )
            }
            assertTrue(
                template.id + "：注册表说文档标题填进「" + template.titleSlot + "」，但骨架里没有",
                slots.contains(template.titleSlot),
            )
        }
    }

    @Test
    fun `文档标题会被主题填掉，不会留下字面的占位符`() {
        for (template in WebTemplates.all) {
            val filled = WebTemplates.fill(
                template,
                read(template),
                "校园开放日",
                "社团展示、报名方式",
                mapOf("副标题" to "走进校园看看"),
                ColorSchemes.fallback,
            )
            assertTrue(
                template.id + "：浏览器标签页上的标题没被填掉，会露出字面的占位符",
                filled.contains("<title>校园开放日</title>"),
            )
        }
    }

    @Test
    fun `统计待补槽位时要跳过 HTML 注释里的说明文字`() {
        val fake = """
            <!-- 说明：只替换 {{...}} 里的文字 -->
            <h1>{{标题}}</h1><p>{{副标题}}</p>
        """.trimIndent()
        assertEquals("注释里那处不能算进去", 2, WebTemplates.remainingSlots(fake))
    }

    @Test
    fun `每套骨架都留了配色变量的位置`() {
        for (template in WebTemplates.all) {
            val html = read(template)
            assertTrue(template.id + " 的骨架必须留出 {{配色变量}}", html.contains("{{配色变量}}"))
            // 变量要写在 :root 里，否则页面里 var(--bg) 全是空的。
            val rootAt = html.indexOf(":root")
            val varAt = html.indexOf("{{配色变量}}")
            assertTrue(template.id + " 的配色变量要落在 :root 块里", rootAt in 0 until varAt)
            assertTrue(template.id + " 的正文要用 var(--primary) 取主色", html.contains("var(--primary)"))
        }
    }

    @Test
    fun `真骨架 + 真装配：主题进大标题，要点进卡片，配色变量被换成具体色值`() {
        val official = WebTemplates.byId("官网首页")!!
        val html = read(official)
        val colors = ColorSchemes.byId("墨黑金")!!

        val filled = WebTemplates.fill(
            official,
            html,
            "校园开放日",
            "社团展示、报名方式、时间地点",
            emptyMap(),
            colors,
        )

        assertFalse("配色变量必须被替换掉", filled.contains("{{配色变量}}"))
        assertTrue("底色要换成墨黑金那套", filled.contains("--bg:#0F0D0A"))
        assertTrue("主色要换成墨黑金那套", filled.contains("--primary:#D8B45A"))
        assertTrue("主题要真的进大标题", filled.contains(">校园开放日<"))
        assertTrue("第一条要点要进卡片", filled.contains(">社团展示<"))
        assertTrue("第二条要点要进卡片", filled.contains(">报名方式<"))
        assertTrue("第三条要点要进卡片", filled.contains(">时间地点<"))

        // 剩下没填的那些，是留给模型按主题补的——它们必须还在，不能凭空消失。
        val left = slotsIn(filled)
        assertTrue("还应该有留给模型的槽位", left.isNotEmpty())
        assertFalse("不能留下空槽位", left.any { it.isBlank() })
    }

    @Test
    fun `五套骨架各不相同，不是同一份复制粘贴`() {
        val bodies = WebTemplates.all.map { read(it) }
        assertEquals("每套骨架应当是独立的文件", bodies.size, bodies.toSet().size)
    }

    /**
     * 这条来自真机：生成出来的页面在统计条里挂着 `{{数据1}}`、页脚挂着 `{{联系电话}}`。
     * 那些槽位与用户这次的主题无关，App 也无从得知，而模型选择把它们原样留着。
     *
     * 规矩定成：区块里只要还有没填上的槽位，整块连标记一起消失。页面因此只留下真正
     * 有内容的段落，不会出现「数据1、数据2、数据3」这种和主题无关的空壳。
     */
    @Test
    fun `没人给内容的可选区块整块消失，绝不把花括号留在页面上`() {
        val official = WebTemplates.byId("官网首页")!!
        val skeleton = read(official)

        val bare = WebTemplates.fill(
            official, skeleton, "数字媒体", "专业介绍、实验室开放", emptyMap(), ColorSchemes.fallback,
        )
        assertFalse("统计区块必须整块消失", bare.contains("id=\"data\""))
        assertFalse("联系方式列必须整块消失", bare.contains("联系我们"))
        assertFalse("标记本身不能留在成品里", bare.contains("可选"))
        assertFalse("事实型槽位不能留在页面上", bare.contains("{{数据1}}"))
        assertFalse("联系电话也不能留", bare.contains("{{联系电话}}"))
        assertTrue("页面主体还得在", bare.contains("<h1>数字媒体</h1>"))

        val kept = WebTemplates.fill(
            official, skeleton, "数字媒体", "", mapOf(
                "数据1" to "120+", "数据1说明" to "服务单位",
                "数据2" to "3.2 亿", "数据2说明" to "累计触达",
                "数据3" to "99.9%", "数据3说明" to "服务可用性",
                "联系电话" to "400-601-9800", "邮箱" to "hi@example.com", "地址" to "贵阳市",
            ), ColorSchemes.fallback,
        )
        assertTrue("给了数据，统计条就要保留", kept.contains("120+"))
        assertTrue("给了联系方式，那一列就要保留", kept.contains("400-601-9800"))
        assertFalse("保留下来时也不能带标记", kept.contains("<!--可选-->"))
    }

    /**
     * 反面：描述性槽位**必须继续留着**。
     *
     * 那是给模型的写作提示。如果为了「不留花括号」把它们一起清空，模型就会对着
     * 一片空白猜要写什么，出来的页面反而更空——修一个缺陷换来一个更差的缺陷。
     */
    @Test
    fun `描述性槽位要继续留着当写作提示，不能被一起清掉`() {
        val official = WebTemplates.byId("官网首页")!!
        val filled = WebTemplates.fill(
            official, read(official), "数字媒体", "专业介绍", emptyMap(), ColorSchemes.fallback,
        )
        assertTrue("卡片说明要给模型留着", filled.contains("{{要点1说明}}"))
        assertTrue("后面几张卡片的标题也要留着", filled.contains("{{要点2标题}}"))
    }
}
