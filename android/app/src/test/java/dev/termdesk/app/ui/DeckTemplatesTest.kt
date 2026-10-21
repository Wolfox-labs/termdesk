package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「@PPT生成」这条路的装配链——和 [WebTemplatesTest] 一个体位，钉的是 PPT 那几件特有的事：
 *
 *  1. 追问顺序是**主题 → 配色 → 页数**（写在数据里，不是写死在函数里）；
 *  2. 大纲由 App 本地铺：每部分几页、这部分写什么，**加起来正好等于用户要的页数**；
 *  3. 大纲要能逐条改——所以它写成文本块，写出去再读回来必须一模一样；
 *  4. 模板自动选型：按主题/内容挑更贴合的那一套。
 */
class DeckTemplatesTest {

    private fun assetsDir(): File {
        val direct = File("src/main/assets")
        if (direct.isDirectory) return direct
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/assets")
            if (candidate.isDirectory) return candidate
            val here = File(dir, "src/main/assets")
            if (here.isDirectory) return here
            dir = dir.parentFile
        }
        throw AssertionError("找不到 assets 目录，当前工作目录：" + File("").absolutePath)
    }

    private fun blueprintOf(deck: DeckTemplate): String =
        File(assetsDir(), deck.assetPath).readText(Charsets.UTF_8)

    private val report: DeckTemplate = DeckTemplates.byId("工作汇报")!!
    private val reportSections: List<DeckSection> =
        DeckTemplates.parseSections(blueprintOf(report))

    // ── 0. 资源包本身 ──────────────────────────────────────────────────

    @Test
    fun `每套蓝图都真的在资源包里，而且能解析出章节`() {
        for (deck in DeckTemplates.all) {
            val file = File(assetsDir(), deck.assetPath)
            assertTrue(deck.id + " 的蓝图不在资源包里：" + deck.assetPath, file.isFile)
            val sections = DeckTemplates.parseSections(file.readText(Charsets.UTF_8))
            assertTrue(deck.id + " 解析不出任何章节", sections.isNotEmpty())
            for (s in sections) {
                assertTrue(deck.id + " 有章节没名字", s.name.isNotBlank())
                assertTrue(deck.id + " 的「" + s.name + "」没写这段讲什么", s.hint.isNotBlank())
                assertTrue(deck.id + " 的「" + s.name + "」权重必须为正", s.weight > 0)
            }
            assertTrue(deck.id + " 至少要有封面和结尾", sections.size >= 3)
            assertEquals(
                deck.id + " 的第一章应当是封面",
                "封面",
                sections.first().layout,
            )
        }
    }

    @Test
    fun `蓝图里的冒号不影响解析——正文里带全角冒号也要认得出键`() {
        val blueprint = """
            名称: 试一下
            章节: 方案细节 | 每一块怎么做：谁来做、做到什么程度 | 3 | 要点
            章节: 排期 | 分几个阶段：每阶段多久 | 1 | 时间线
        """.trimIndent()
        val sections = DeckTemplates.parseSections(blueprint)
        assertEquals("两行都要认出来", 2, sections.size)
        assertEquals("方案细节", sections[0].name)
        assertEquals("每一块怎么做：谁来做、做到什么程度", sections[0].hint)
        assertEquals(3, sections[0].weight)
        assertEquals("时间线", sections[1].layout)
    }

    @Test
    fun `网页和 PPT 两包模板的 id 不许重名`() {
        val web = WebTemplates.all.map { it.id }.toSet()
        val deck = DeckTemplates.all.map { it.id }.toSet()
        val clash = web.intersect(deck)
        assertTrue(
            "两边共用一个 `模板：` 标记，id 撞了就会解析串味：$clash",
            clash.isEmpty(),
        )
    }

    @Test
    fun `同一个 模板 标记，两边各认各的`() {
        assertEquals("工作汇报", DeckTemplates.parse("@PPT生成 模板：工作汇报")!!.id)
        assertEquals("官网首页", WebTemplates.parse("@网页生成 模板：官网首页")!!.id)
        assertTrue(
            "拿网页的 id 问 PPT，PPT 那边不该认",
            DeckTemplates.parse("@PPT生成 模板：官网首页") == null,
        )
    }

    // ── 1. 自动选型 ────────────────────────────────────────────────────

    @Test
    fun `主题里有教学就挑教学课件`() {
        assertEquals("教学课件", DeckTemplates.pick("高校教学课件").id)
        assertEquals("教学课件", DeckTemplates.pick("这门课的课件要做成什么样").id)
    }

    /**
     * 从用户上传的参考 PPT 学来的三套，也要能被主题挑中。
     *
     * 这三条同时是**关键词不串味**的护栏：大赛/答辩归「大赛答辩」，
     * 创意/点子归「创新方案」，融资/路演归「创业路演」——
     * 它们和通用三套在字面上有重叠（比如「方案」既是方案提案也是创新方案），
     * 所以这里挑的是各自最独特的那个词。
     */
    @Test
    fun `从参考 PPT 学来的三套也能被主题挑中`() {
        assertEquals("大赛答辩", DeckTemplates.pick("数智教育创新大赛答辩").id)
        assertEquals("创新方案", DeckTemplates.pick("一个全新的创意点子").id)
        assertEquals("创业路演", DeckTemplates.pick("项目融资路演").id)
    }

    /**
     * 后学的四套同样要挑得中，而且**不能和前面几套抢**。
     *
     * 这几条的输入都是刻意挑的"边界主题"：「项目申报」和「方案提案」都有"项目/方案"，
     * 「成果发布」和「创新方案」都有"新品/发布"，「技术方案」和「方案提案」都有"方案"。
     * 关键词集如果没拉开，这里就会挑错——那正是自动选型最容易坏的地方。
     */
    @Test
    fun `后学的四套也挑得中，而且不和前面几套抢`() {
        assertEquals("项目申报", DeckTemplates.pick("校园低空巡检无人机项目申报").id)
        assertEquals("企业实力", DeckTemplates.pick("公司简介宣传片").id)
        assertEquals("成果发布", DeckTemplates.pick("新品发布会成果").id)
        assertEquals("技术方案", DeckTemplates.pick("技术方案与架构设计").id)
        // 反面：老的那几套还认得住，没被新词抢走。
        assertEquals("方案提案", DeckTemplates.pick("这个项目的立项提案").id)
        assertEquals("工作汇报", DeckTemplates.pick("第三季度工作总结汇报").id)
    }

    @Test
    fun `主题里有汇报就挑工作汇报，有方案就挑方案提案`() {
        assertEquals("工作汇报", DeckTemplates.pick("第三季度工作总结汇报").id)
        assertEquals("方案提案", DeckTemplates.pick("这个项目的立项提案").id)
    }

    @Test
    fun `什么都不像的时候用缺省那一套，而不是空手`() {
        assertEquals(DeckTemplates.fallback.id, DeckTemplates.pick("").id)
        assertEquals(DeckTemplates.fallback.id, DeckTemplates.pick("随便写点什么").id)
    }

    // ── 2. 大纲：页数分下去，加起来必须正好 ──────────────────────────────

    @Test
    fun `各章页数加起来，正好等于用户要的页数`() {
        // 从下限 3 一直试到 40：不管用户报什么数字，总页数都必须对得上。
        for (pages in 3..40) {
            val rows = DeckTemplates.compose(reportSections, "数字媒体", "", pages)
            assertEquals(
                "要 $pages 页，排出来却是 " + rows.sumOf { it.pages } + " 页",
                pages,
                rows.sumOf { it.pages },
            )
            assertEquals("页码必须从 1 开始连着排", 1, rows.first().from)
            assertEquals("最后一页必须正好是总数", pages, rows.last().to)
            for (i in 1 until rows.size) {
                assertEquals(
                    "第 " + (i + 1) + " 条要和上一条首尾相接",
                    rows[i - 1].to + 1,
                    rows[i].from,
                )
            }
        }
    }

    /**
     * 用户报的页数和"我们学的那套"不一样时，也得排得对。
     *
     * 这是用户点名的要求：「即使用户没有写到你学习过的 PPT 的页数，写了其他数字，
     * 你也要可以正确分配内容」。两个方向都钉：比章节少就**先裁章节**
     * （不能把 6 页硬夹成 14 页），比章节多就**按权重**把页数分下去。
     */
    @Test
    fun `任何页数都排得出来，总页数永远等于用户要的那个数`() {
        val deck = DeckTemplates.byId("项目申报")!!
        val sections = DeckTemplates.parseSections(
            File(assetsDir(), deck.assetPath).readText(Charsets.UTF_8),
        )
        assertTrue("这套模板章节多，正好拿来试小页数", sections.size >= 12)

        // 小页数：先裁章节，留封面和结尾。
        val small = DeckTemplates.compose(sections, "校园低空巡检无人机", "", 6)
        assertEquals("要 6 页就得是 6 页", 6, small.sumOf { it.pages })
        assertEquals("6 页就该是 6 条", 6, small.size)
        assertEquals("封面必须在", "封面", small.first().section)
        assertEquals("结尾必须在", sections.last().name, small.last().section)
        assertFalse("页数紧的时候先裁权重低的", small.any { it.section == "政策依据" })

        // 大页数：章节一个不少，厚的更厚。
        val big = DeckTemplates.compose(sections, "校园低空巡检无人机", "", 40)
        assertEquals("要 40 页就得是 40 页", 40, big.sumOf { it.pages })
        assertEquals("大页数时章节一个都不能少", sections.size, big.size)
        val byName = big.associateBy { it.section }
        assertTrue(
            "权重高的章要比权重低的厚",
            byName.getValue("产品与功能").pages > byName.getValue("政策依据").pages,
        )

        // 两头越界都夹住，不许崩，也不许排出空表。
        assertEquals(
            "太少就夹到下限",
            DeckTemplates.MIN_PAGES,
            DeckTemplates.compose(sections, "x", "", 1).sumOf { it.pages },
        )
        assertEquals(
            "太多就夹到上限",
            DeckTemplates.MAX_PAGES,
            DeckTemplates.compose(sections, "x", "", 999).sumOf { it.pages },
        )
    }

    @Test
    fun `要点按顺序落进内容页，封面不吃要点`() {
        val rows = DeckTemplates.compose(reportSections, "数字媒体", "专业介绍、实验室开放", 16)
        assertEquals("封面写的是主题", "数字媒体", rows.first().content)
        val body = rows.filter { it.layout != "封面" && it.layout != "目录" && it.layout != "结尾" }
        assertTrue("第一条要点要进第一个内容页", body[0].content.contains("专业介绍"))
        assertTrue("第二条要点要进第二个内容页", body[1].content.contains("实验室开放"))
        assertFalse("第二条要点不该串到第一个内容页里", body[0].content.contains("实验室开放"))
        // 模板给的那半句也要留着：只留用户那句，大纲就看不出这一章是干什么的了。
        assertTrue(
            "模板的结构提示不该被要点挤掉",
            body[0].content.contains(reportSections.first { it.name == body[0].section }.hint),
        )
    }

    /**
     * 要点要**按名字**落到对应那一章，而不是从最上面顺次往下发。
     *
     * 这条来自抽查装配结果时看出来的偏移：「推广应用」本该落在「应用与推广」，
     * 顺次发却落到了「资质与荣誉」——五条要点配八章时这种漂移一眼就是错的。
     */
    @Test
    fun `要点按名字配对到对应章节，不会顺次漂移`() {
        val deck = DeckTemplates.byId("企业实力")!!
        val sections = DeckTemplates.parseSections(
            File(assetsDir(), deck.assetPath).readText(Charsets.UTF_8),
        )
        val rows = DeckTemplates.compose(
            sections, "数智融媒", "成立时间、研发历程、专利、数据成绩、推广应用", 12,
        )
        fun rowOf(name: String) = rows.first { it.section == name }

        assertTrue("「推广应用」要落到「推广与合作」", rowOf("推广与合作").content.contains("推广应用"))
        assertFalse("不能漂到「资质与扶持」上", rowOf("资质与扶持").content.contains("推广应用"))
        assertTrue("「研发历程」要落到「发展历程」", rowOf("发展历程").content.contains("研发历程"))
        assertTrue("「专利」要落到「技术与专利」", rowOf("技术与专利").content.contains("专利"))
        assertTrue("「数据成绩」要落到「数据与成绩」", rowOf("数据与成绩").content.contains("数据成绩"))
        assertTrue("「成立时间」配不上任何章节，按顺序落到第一个内容页", rowOf("我们是谁").content.contains("成立时间"))
    }

    @Test
    fun `配对不上的要点仍然按顺序填进还空着的内容页，一条都不丢`() {
        val rows = DeckTemplates.compose(reportSections, "数字媒体", "专业介绍、实验室开放", 16)
        val body = rows.filter { it.layout != "封面" && it.layout != "目录" && it.layout != "结尾" }
        assertTrue(body[0].content.contains("专业介绍"))
        assertTrue(body[1].content.contains("实验室开放"))
    }

    /**
     * 页数被夹上去以后，**摘要、大纲、指令三处的数字必须一致**。
     *
     * 真问题：模板有 14 章而用户要 12 页时，实际会排 14 页。如果摘要和指令还写"12 页"，
     * 用户看到的是"12 页"、大纲却有 14 条，模型收到的又是"一共 12 页，按大纲走"——
     * 三处自相矛盾，模型只能猜。这条把三处钉在一起。
     */
    /**
     * 摘要、大纲、指令三处的页数必须是同一个数。
     *
     * 这一条盯着的是"三处各算各的"：摘要写用户敲的数、大纲按章节数排、指令又抄一遍用户
     * 敲的数——用户看到 12 页、大纲 14 条、模型收到"一共 12 页，按大纲走"，只能猜。
     */
    @Test
    fun `摘要 大纲 指令三处的页数必须一致`() {
        val deck = DeckTemplates.byId("项目申报")!!
        val sections = DeckTemplates.parseSections(
            File(assetsDir(), deck.assetPath).readText(Charsets.UTF_8),
        )
        for (asked in listOf(6, 12, 20, 33)) {
            val rows = DeckTemplates.compose(sections, "校园低空巡检无人机", "", asked)
            val real = rows.sumOf { it.pages }
            assertEquals("大纲总页数要等于用户要的页数（$asked）", asked, real)
            assertEquals("指令取的是大纲末页", real, rows.last().to)

            val sent = DeckTemplates.instruction(
                deck = deck,
                blueprint = "",
                sections = emptyList(),
                colors = ColorSchemes.fallback,
                theme = "校园低空巡检无人机",
                pages = asked,
                rows = rows,
                free = "",
                askBudget = 2,
            )
            assertTrue("指令里要写 $real 页", sent.contains("一共 " + real + " 页"))
            assertTrue(
                "摘要也要写 $real 页",
                DeckTemplates.summaryLine(deck, null, "", asked.toString(), rows)
                    .contains(real.toString() + " 页"),
            )
        }
    }

    /**
     * 字体与"不许出界"两条规矩必须在指令里。
     *
     * 来源是一次端到端实验：照着指令生成真 .pptx，封面 12 个字的标题量出来每字 70pt
     * （XML 里写着 40pt），冲出了画面右缘——根因是那台机器没装指令里点名的字体，
     * 渲染时被静默替换。这两条不写死在指令里，模型每次都可能再踩一遍。
     */
    @Test
    fun `指令里要求先确认字体装没装，并且不许让文字出界`() {
        val rows = DeckTemplates.compose(reportSections, "数字媒体", "", 12)
        val sent = DeckTemplates.instruction(
            deck = report,
            blueprint = "",
            sections = emptyList(),
            colors = ColorSchemes.fallback,
            theme = "数字媒体",
            pages = 12,
            rows = rows,
            free = "",
            askBudget = 2,
        )
        assertTrue("要先确认字体装没装", sent.contains("确认这些字体装没装"))
        assertTrue("要允许按顺序往下挑一个装了的", sent.contains("挑一个装了的"))
        assertTrue("中文字体要两处都设", sent.contains("eastAsia"))
        assertTrue("不许超出一页", sent.contains("不许超出页面"))
        assertTrue("做完要逐页检查", sent.contains("逐页过一遍"))

        // 光说"不许出界"没用：模型得有能照着做、也能自己核的量。这些数字是拿一次
        // 端到端验收的真实结果换来的（编造数字、整行留白 2.4in、多列说明糊成一段、
        // 深色底表格看不出行），少一条就会退回那个稿子。
        assertTrue("画布要写死真 16:9 的尺寸", sent.contains("宽 13.333in（12192000 EMU）× 高 7.5in"))
        assertTrue("要回读 sldSz 核对", sent.contains("p:sldSz"))
        assertTrue("要给安全边距", sent.contains("左右安全边距各 0.80in"))
        assertTrue("要给字号档位", sent.contains("字号只用这七档"))
        assertTrue("要禁止整行大留白", sent.contains("不许留下超过 1.0in 的整行空白"))
        assertTrue("配色只许派生不许新色相", sent.contains("不要引入新色相"))
        // 2026-10-22：数字的处理也反过来了。上一版让模型"禁止填看起来真实的数字"，
        // 结果成品里全是「待补充」，用户拿到手根本不能用；现在要求写满、写自洽，
        // 只是不许再用占位词。
        assertTrue("数字必须写满", sent.contains("必须写满"))
        assertTrue("数字不许写占位词", sent.contains("不许写“待补充”"))
        assertTrue("图表表格必须带单位", sent.contains("都必须带单位"))
        assertTrue("深色底表格要有分隔线", sent.contains("每行加一条 0.75pt 分隔线"))
        assertTrue("封面主标题有字数上限", sent.contains("封面主标题 ≤12 汉字"))
        assertTrue("要点有字数上限", sent.contains("每条要点 ≤20 汉字"))
        assertTrue("不许出现孤字行", sent.contains("折行后第二行不许只剩 1~2 个字"))
        assertTrue("并排列宽给了公式", sent.contains("列宽 =（内容区宽 11.733in"))
        assertTrue("页标题必须是结论句", sent.contains("页标题必须能独立成句"))
        assertTrue("页脚页码有规定", sent.contains("右下角 10pt 次要色写两位页码"))
        assertTrue("大纲优先于模板权重", sent.contains("一律以【大纲】为准"))
        assertTrue("自查要转 PDF 再栅格化", sent.contains("转成 PDF，**再从 PDF 栅格化成 PNG**"))
        assertTrue("要点明 1.5 倍栅格化的坑", sent.contains("1.5 倍栅格化"))
        assertTrue("要有墨迹包围盒的判据", sent.contains("墨迹包围盒"))
        assertTrue("要能用估算自查（中文 1.0×字号）", sent.contains("中文按 1.0×字号"))
        assertTrue("要明确什么叫没通过", sent.contains("估算宽度大于框宽就是没通过"))
    }

    @Test
    fun `封面 目录 结尾永远只占一页，多出来的页数都给内容页`() {
        val rows = DeckTemplates.compose(reportSections, "数字媒体", "", 20)
        for (r in rows) {
            if (r.layout == "封面" || r.layout == "目录" || r.layout == "结尾") {
                assertEquals(r.section + " 不该多占页——一份片子两页封面是荒唐的", 1, r.pages)
            }
        }
        assertEquals("总页数仍然要正好", 20, rows.sumOf { it.pages })
        assertTrue(
            "多出来的页数必须落到内容页上",
            rows.filter { it.layout != "封面" && it.layout != "目录" && it.layout != "结尾" }
                .any { it.pages > 1 },
        )
    }

    @Test
    fun `要点比内容页还多时，多出来的一个字都不丢`() {
        val points = (1..30).joinToString("、") { "要点" + it }
        val rows = DeckTemplates.compose(reportSections, "数字媒体", points, 12)
        val all = rows.joinToString("\n") { it.content }
        for (i in 1..30) {
            assertTrue("第 " + i + " 条要点被丢了", all.contains("要点" + i))
        }
    }

    // ── 3. 大纲写成文本块，写出去再读回来必须一样 ────────────────────────

    @Test
    fun `大纲块往返稳定，逐条改一条之后也不串味`() {
        val rows = DeckTemplates.compose(reportSections, "数字媒体", "专业介绍、实验室开放", 14)
        // 用户单独改了中间那一条：这是「有权限单独修改某条」的核心动作。
        val edited = rows.toMutableList()
        edited[3] = edited[3].copy(section = "现状与数据（改过）", content = "改成我自己写的这段")
        val text = DeckTemplates.renderOutline(edited)

        val back = DeckTemplates.parseOutline(text)
        assertEquals("条数不能变", edited.size, back.size)
        assertEquals("改过的那一条要原样回来", edited[3].section, back[3].section)
        assertEquals("改过的内容要原样回来", edited[3].content, back[3].content)
        for (i in edited.indices) {
            assertEquals("第 " + (i + 1) + " 条的页数区间变了", edited[i].range, back[i].range)
            assertEquals("第 " + (i + 1) + " 条的章节变了", edited[i].section, back[i].section)
        }
        assertEquals("再渲染一次要一模一样", text, DeckTemplates.renderOutline(back))
    }

    @Test
    fun `大纲块后面的自由文字不会被吞掉`() {
        val rows = DeckTemplates.compose(reportSections, "数字媒体", "", 10)
        val text = DeckTemplates.renderOutline(rows) + "\n记得做得活泼一点"
        assertEquals(rows.size, DeckTemplates.parseOutline(text).size)
        val left = DeckTemplates.stripOutline(text)
        assertTrue("用户自己写的话要留下", left.contains("记得做得活泼一点"))
        assertFalse("大纲本身要摘干净", left.contains("大纲："))
    }

    @Test
    fun `只写了大纲两个字、后面没跟条目，不算已确认`() {
        assertTrue(DeckTemplates.parseOutline("大纲：").isEmpty())
        assertTrue(DeckTemplates.parseOutline("大纲：\n随便写一行").isEmpty())
    }

    // ── 4. 指令装配 ────────────────────────────────────────────────────

    @Test
    fun `指令里要有模板名 页数 配色色值和每一条大纲`() {
        val rows = DeckTemplates.compose(reportSections, "数字媒体", "专业介绍、实验室开放", 14)
        val sent = DeckTemplates.instruction(
            deck = report,
            blueprint = blueprintOf(report),
            sections = reportSections,
            colors = ColorSchemes.byId("墨黑金")!!,
            theme = "数字媒体",
            pages = 14,
            rows = rows,
            free = "记得做得活泼一点",
            askBudget = 2,
        )
        assertTrue("要说清是哪一套模板", sent.contains("生成 PPT · 工作汇报"))
        assertTrue("页数要写明", sent.contains("一共 14 页"))
        assertTrue("配色要照用", sent.contains("底色 #0F0D0A"))
        assertTrue("主色要写进去", sent.contains("主色 #D8B45A"))
        assertTrue("大纲头要说清是已确认的", sent.contains("我已经确认过"))
        for (r in rows) {
            assertTrue(
                "大纲第 " + r.range + " 条没进指令",
                sent.contains(r.range + " | " + r.section),
            )
        }
        assertTrue("主题要原样带上", sent.contains("主题：数字媒体"))
        assertTrue("自由文字要原样带上", sent.contains("记得做得活泼一点"))
        assertTrue("不许出现花括号这条规矩要在", sent.contains("不许出现任何"))
        assertTrue("要交代问额度", sent.contains("最多可以再问我 2 个问题"))
        // 注意不能笼统地查「有没有 `{{`」：规则那一句自己就写着 `{{`，
        // 那样查会永远为真。要查的是「有没有某一个还带着花括号的槽位」。
        assertFalse(
            "不能留下没被替换掉的槽位",
            Regex("""\{\{\s*[\p{L}\p{N}_]+\s*}}""").containsMatchIn(sent),
        )
    }

    @Test
    fun `蓝图读不到时，退回按大纲自己设计，而不是发一段半截模板`() {
        val rows = DeckTemplates.compose(reportSections, "数字媒体", "", 12)
        val sent = DeckTemplates.instruction(
            deck = report,
            blueprint = "",
            sections = emptyList(),
            colors = ColorSchemes.fallback,
            theme = "数字媒体",
            pages = 12,
            rows = rows,
            free = "",
            askBudget = 2,
        )
        assertTrue("要明说资源包没读到", sent.contains("资源包没读到"))
        assertTrue("大纲仍然要带上——它是用户确认过的", sent.contains("我已经确认过"))
        assertFalse("不能出现空的代码块", sent.contains("```\n```"))
    }
}
