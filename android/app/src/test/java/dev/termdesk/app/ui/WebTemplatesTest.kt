package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「@网页生成」这条路的装配链。
 *
 * 这个类钉的是用户提的四件事，一件一条：
 *  1. 打完要求之后，**自动生成的那段字里必须体现出来**——主题进大标题、要点进卡片；
 *  2. 配色要能**在这段话里**设置，改了不留残渣；
 *  3. 发送之前欠的那一次询问（配色、主题）必须先问掉；
 *  4. 模板不止一套，选哪套就用哪套。
 */
class WebTemplatesTest {

    /** 一份最小的假骨架，形状和 `assets/templates/web/official.html` 一致。 */
    private val skeleton = """
        <style>
        :root{
        {{配色变量}}
        }
        </style>
        <h1>{{标题}}</h1>
        <p>{{副标题}}</p>
        <div>{{要点1标题}}</div>
        <div>{{要点2标题}}</div>
        """.trimIndent()

    private val web: ChatMention = ChatMentions.all.first { it.id == "网页生成" }

    private val official: PageTemplate = WebTemplates.all.first { it.id == "官网首页" }

    private val fakeSkeleton: (PageTemplate) -> String = { skeleton }

    // ── 1. 自动生成的字里必须体现用户的要求 ──────────────────────────────

    @Test
    fun `用户打的主题进大标题、要点进卡片`() {
        val draft = MentionDraft.render(
            MentionDraft.parse("@网页生成 模板：官网首页 配色：科技蓝")
                .let { MentionDraft.withTheme(it, "校园开放日") }
                .let { MentionDraft.withPoints(it, "时间地点、报名方式") },
        )
        val out = ChatMentions.expand(draft, fakeSkeleton)

        assertTrue("主题要出现在展开后的指令里", out.contains("主题：校园开放日"))
        assertTrue("要点要出现在展开后的指令里", out.contains("要点：时间地点、报名方式"))
        assertTrue("主题要真的填进骨架大标题", out.contains("<h1>校园开放日</h1>"))
        assertTrue("第一条要点要真的填进第一张卡片", out.contains("<div>时间地点</div>"))
        assertTrue("第二条要点要真的填进第二张卡片", out.contains("<div>报名方式</div>"))
    }

    @Test
    fun `用户没写主题时，第一句短话当主题；长句子不当`() {
        val short = ChatMentions.expand("@网页生成 模板：官网首页 配色：科技蓝\n校园开放日宣传页", fakeSkeleton)
        assertTrue("短句应当被当作主题填进大标题", short.contains("<h1>校园开放日宣传页</h1>"))

        val long = "这是一个很长的需求描述，长到不应该被塞进大标题里，因为它会把整个版式撑坏掉"
        val out = ChatMentions.expand("@网页生成 模板：官网首页 配色：科技蓝\n$long", fakeSkeleton)
        assertTrue("长句要原样留在正文里", out.contains(long))
        assertFalse("长句不能被当成标题", out.contains("<h1>$long</h1>"))
    }

    @Test
    fun `正文里的 键：值 行会填进骨架同名槽位`() {
        val out = ChatMentions.expand(
            "@网页生成 模板：官网首页 配色：科技蓝\n主题：开放日\n地点：图书馆报告厅",
            fakeSkeleton,
        )
        assertTrue("主题照常进标题", out.contains("<h1>开放日</h1>"))
        assertTrue("正文里的「地点」也要原样带上", out.contains("地点：图书馆报告厅"))
    }

    // ── 2. 配色就在这段话里，改了不留残渣 ────────────────────────────────

    @Test
    fun `配色写进文本、解析得回来，并且真的替换了骨架变量`() {
        val ink = ColorSchemes.byId("墨黑金")!!
        val draft = MentionDraft.render(
            MentionDraft.withColors(MentionDraft.parse("@网页生成 模板：官网首页"), ink),
        )
        assertTrue("写回草稿时要带上颜色名", draft.contains("配色：墨黑金"))
        assertEquals("解析要认得出这一套", "墨黑金", MentionDraft.parse(draft).colors?.id)

        val out = ChatMentions.expand(draft, fakeSkeleton)
        assertTrue("骨架的配色变量要换成这套的值", out.contains("--bg:#0F0D0A"))
        assertTrue("主色也要跟着换", out.contains("--primary:#D8B45A"))
    }

    @Test
    fun `换一次配色不会在文本里留下上一套的色值`() {
        val once = MentionDraft.render(
            MentionDraft.withColors(MentionDraft.parse("@网页生成 模板：官网首页"), ColorSchemes.byId("科技蓝")!!),
        )
        val twice = MentionDraft.render(
            MentionDraft.withColors(MentionDraft.parse(once), ColorSchemes.byId("纸白蓝")!!),
        )
        assertFalse("旧颜色名不能还在", twice.contains("科技蓝"))
        assertFalse("旧色值不能还在", twice.contains("#00E5FF"))
        assertTrue("新的一套要在", twice.contains("配色：纸白蓝"))
        assertEquals("只应该剩下一个配色段", 1, Regex("配色").findAll(twice).count())
    }

    @Test
    fun `手打配色也认，半角冒号和空格都认`() {
        assertEquals("紫罗兰", MentionDraft.parse("@网页生成 配色:紫罗兰").colors?.id)
        assertEquals("青绿", MentionDraft.parse("@网页生成 配色 青绿").colors?.id)
    }

    // ── 3. 发送之前必须先把欠的那次询问问掉 ──────────────────────────────

    @Test
    fun `刚选完骨架时，先欠配色，再欠主题`() {
        val bare = MentionDraft.parse("@网页生成 模板：官网首页 ")
        assertTrue("配色没定就该欠着", bare.needsColorAsk())
        assertEquals("第一个问的必须是配色", MentionDraft.LOCAL_COLORS, bare.nextAsk())
        assertFalse("配色没定就不算备齐，点发送也发不出去", bare.ready())
        assertTrue("主题这一问同样欠着——用户什么都还没写", bare.needsThemeAsk())

        val colored = MentionDraft.parse(
            MentionDraft.render(MentionDraft.withColors(bare, ColorSchemes.fallback)),
        )
        assertFalse("配色定了就不再欠配色", colored.needsColorAsk())
        assertEquals("接着问主题", MentionDraft.LOCAL_THEME, colored.nextAsk())
        assertTrue("主题不挡发送——跳过也照发", colored.ready())
    }

    @Test
    fun `用户已经在正文里写了内容时，主题那一问不再拦路`() {
        val written = MentionDraft.parse("@网页生成 模板：官网首页 配色：科技蓝\n校园开放日宣传页")
        assertTrue("已经写了要做什么，就算备齐", written.ready())
        assertFalse("不能因为没写「主题：」就再拦一次", written.needsThemeAsk())
    }

    @Test
    fun `本地固定问两个，剩下三个留给模型，总数不超过五次`() {
        val colored = MentionDraft.parse(
            MentionDraft.render(
                MentionDraft.withColors(MentionDraft.parse("@网页生成 模板：官网首页"), ColorSchemes.fallback),
            ),
        )
        assertEquals("本地固定问两个", 2, colored.localAsks)
        assertEquals("模型最多再问三个", 3, colored.askBudget())
        assertTrue("总数不超过五次", colored.localAsks + colored.askBudget() <= MentionDraft.ASK_CEILING)
    }

    @Test
    fun `指令里写明了追问额度，并且要求把多次回答累积起来`() {
        val draft = MentionDraft.render(
            MentionDraft.withColors(MentionDraft.parse("@网页生成 模板：官网首页"), ColorSchemes.fallback),
        )
        val out = ChatMentions.expand(draft, fakeSkeleton)
        assertTrue("要给出剩余额度", out.contains("最多可以再问我 3 个问题"))
        assertTrue("要让模型一次只问一个", out.contains("一次只问一个"))
        assertTrue("要说明用户会分多次回答", out.contains("分好几次回答"))
    }

    @Test
    fun `指令里明确禁止把花括号留在成品页面上`() {
        val draft = MentionDraft.render(
            MentionDraft.withColors(MentionDraft.parse("@网页生成 模板：官网首页"), ColorSchemes.fallback),
        )
        val out = ChatMentions.expand(draft, fakeSkeleton)
        assertTrue(
            "必须明说不许出现花括号——否则模型漏填一处，用户就在页面上看到 {{要点1说明}}",
            out.contains("不许出现任何") && out.contains("`{{`"),
        )
        assertTrue(
            "要给出替代做法：填一个一眼能看出要改的词，并且列出来让我替换",
            out.contains("待补充") && out.contains("列出来"),
        )
        assertFalse(
            "不能再教它「留空」——真机上它就是这么照做的，页面上真的多出几个洞",
            out.contains("把那段文字留空"),
        )
    }

    // ── 4. 模板不止一套 ────────────────────────────────────────────────

    @Test
    fun `资源包里有八套骨架，每套都有自己的资源文件`() {
        assertTrue("要有得选，不能只有一套", WebTemplates.all.size >= 8)
        val paths = WebTemplates.all.map { it.assetPath }
        assertEquals("资源路径不能重复", paths.size, paths.toSet().size)
        assertTrue("都要落在资源包目录里", paths.all { it.startsWith("templates/web/") && it.endsWith(".html") })
        val ids = WebTemplates.all.map { it.id }
        assertEquals("模板名也不能重复——重复的话解析会挑错那一套", ids.size, ids.toSet().size)
    }

    /**
     * 这一轮内嵌的三套：数据洞察页、内容门户页、监测预警页。
     *
     * 每一套都要能走完「面板里点一下 → 写进文本 → 发送时再认回来」这条闭环，
     * 并且**认的是它自己**——三套的 id 首字相近（数据/内容/监测），
     * 一旦解析写成前缀匹配就会串味，所以这里逐个钉一遍。
     */
    @Test
    fun `新增的三套骨架都在册，并且认得出是自己那一套`() {
        val expected = mapOf(
            "数据洞察页" to "标题",
            "内容门户页" to "头条标题",
            "监测预警页" to "标题",
        )
        for ((id, slot) in expected) {
            val template = WebTemplates.byId(id)
            assertNotNull(id + " 没在册", template)
            assertEquals(id + " 的主标题槽位应当是「" + slot + "」", slot, template!!.primarySlot)

            val draft = MentionDraft.render(
                MentionDraft.withTemplate(
                    MentionDraft.parse("@网页生成 配色：科技蓝 主题：数字媒体"),
                    template,
                ),
            )
            assertEquals(id + "：选了就必须认得出这一套", id, MentionDraft.parse(draft).template?.id)
            assertTrue(
                id + "：指令里要写明是哪一套",
                ChatMentions.expand(draft, fakeSkeleton).contains("生成网页 · " + id),
            )
            assertTrue(
                id + "：建议的文件名要跟着骨架走",
                WebTemplates.fileNameStem(template).isNotBlank() &&
                    !WebTemplates.fileNameStem(template).contains("/"),
            )
        }
    }

    /**
     * 花括号必须「服务于主题」。
     *
     * 这是用户上一轮亲眼看到的问题：页面上挂着 `数据1`、`数据2` 这种与主题无关的占位。
     * 规矩有两条，这里一起钉：
     *  1. **事实型槽位**（数字、电话、邮箱、地址）没有内容时一个都不许留到成品里；
     *  2. **要点**必须真的进每套骨架自己的卡片槽位，不是只写在指令里。
     */
    @Test
    fun `每套骨架的事实槽位都不会漏到页面上，要点都进自己的卡片槽位`() {
        val facts = listOf("数据1", "数据1说明", "指标1数值", "指标1名称", "优势1数值", "联系电话", "邮箱", "地址")
        for (template in WebTemplates.all) {
            val skeleton = buildString {
                append("{{配色变量}}\n")
                append("<h1>{{").append(template.primarySlot).append("}}</h1>\n")
                for (slot in template.bulletSlots) append("<i>{{").append(slot).append("}}</i>\n")
                for (fact in facts) append("<u>{{").append(fact).append("}}</u>\n")
            }
            val filled = WebTemplates.fill(
                template, skeleton, "数字媒体", "一、二、三、四、五、六", emptyMap(), ColorSchemes.fallback,
            )
            assertTrue(template.id + "：主题要进主标题", filled.contains(">数字媒体<"))
            for (slot in template.bulletSlots) {
                assertFalse(template.id + "：要点没进「" + slot + "」", filled.contains("{{" + slot + "}}"))
            }
            for (fact in facts) {
                assertFalse(
                    template.id + "：没人给内容的「" + fact + "」漏到页面上了",
                    filled.contains("{{" + fact + "}}"),
                )
            }
        }
    }

    @Test
    fun `选哪套骨架就用哪套，文件名也跟着换`() {
        val event = WebTemplates.all.first { it.id == "活动页" }
        val draft = MentionDraft.render(
            MentionDraft.parse("@网页生成 配色：暖橙 主题：校园开放日")
                .let { MentionDraft.withTemplate(it, event) },
        )
        assertEquals("解析要认得出这套骨架", "活动页", MentionDraft.parse(draft).template?.id)
        assertEquals("建议的文件名要跟着骨架走", "event", WebTemplates.fileNameStem(event))
        assertTrue("指令里要写明是哪一套", ChatMentions.expand(draft, fakeSkeleton).contains("生成网页 · 活动页"))
    }

    @Test
    fun `主题会填进每套骨架自己的主标题槽位`() {
        val landing = WebTemplates.all.first { it.id == "宣传落地页" }
        assertEquals("落地页的大标题槽位叫主标题", "主标题", landing.primarySlot)
        val filled = WebTemplates.fill(
            landing,
            "<h1>{{主标题}}</h1><p>{{副标题}}</p>",
            "校园开放日",
            "",
            mapOf("副标题" to "欢迎参观"),
            ColorSchemes.fallback,
        )
        assertTrue(filled.contains("<h1>校园开放日</h1>"))
        assertTrue(filled.contains("<p>欢迎参观</p>"))
    }

    // ── 兜底：资源没读到也不能发出半截东西 ──────────────────────────────

    @Test
    fun `骨架读不到时退回让模型自己写，而不是发一段空骨架`() {
        val draft = MentionDraft.render(
            MentionDraft.parse("@网页生成 模板：官网首页 配色：科技蓝 主题：开放日"),
        )
        val out = ChatMentions.expand(draft) { "" }
        assertTrue("要明说资源包没读到", out.contains("资源包没读到"))
        assertFalse("不能出现空的代码块", out.contains("```html\n\n```"))
        assertTrue("配色仍然要给", out.contains("--primary:#00E5FF"))
    }

    @Test
    fun `render 与 parse 往返稳定，用户的自由文字一个字都不丢`() {
        val original = "@网页生成 配色：暖橙\n主题：校运会\n要点：时间、地点\n记得做得活泼一点"
        val once = MentionDraft.render(MentionDraft.parse(original))
        val twice = MentionDraft.render(MentionDraft.parse(once))
        assertEquals("往返两次结果必须一样", once, twice)
        val parsed = MentionDraft.parse(once)
        assertEquals("主题不能丢", "校运会", parsed.theme)
        assertEquals("要点不能丢", "时间、地点", parsed.points)
        assertEquals("自由文字不能丢", "记得做得活泼一点", parsed.free)
        assertNotNull("配色要认得出", parsed.colors)
    }

    @Test
    fun `没写 @标记 时一切照旧，不打乱普通消息`() {
        val plain = "帮我把这个文件改一下"
        assertEquals(plain, ChatMentions.expand(plain, fakeSkeleton))
        assertNull("没有 @ 就没有待问的问题", MentionDraft.parse(plain).nextAsk())
        assertTrue("普通消息永远备齐", MentionDraft.parse(plain).ready())
    }
}
