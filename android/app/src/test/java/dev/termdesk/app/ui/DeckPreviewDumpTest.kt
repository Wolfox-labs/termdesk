package dev.termdesk.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 把每套 PPT 蓝图装配成的**真实指令**落盘，供人肉眼看一眼。
 *
 * 为什么用测试做这件事：填大纲、拼指令是 [DeckTemplates.instruction] 干的活。
 * 如果为了出预览另写一份近似实现，看到的就不是真东西了——预览好看、真机难看，
 * 正是最坏的一种"验证"。所以这里调的是**生产代码本身**。
 *
 * 产物落在 `android/app/build/deck-preview/`（build 目录，不进版本库）。
 */
class DeckPreviewDumpTest {

    private fun assetsDir(): File = PackFixtures.assetsDir()

    private fun outDir(): File =
        File("build/deck-preview").absoluteFile.also { it.mkdirs() }

    /** 每套蓝图配一份像样的示例内容：主题 / 要点 / 页数。 */
    private val samples: Map<String, Triple<String, String, Int>> = mapOf(
        "工作汇报" to Triple(
            "数字媒体专业建设进展",
            "专业介绍、实验室开放、招生数据、校企合作",
            14,
        ),
        "方案提案" to Triple(
            "校园新媒体矩阵建设方案",
            "现状痛点、平台选择、内容编排、预算",
            16,
        ),
        "教学课件" to Triple(
            "数字媒体导论 · 第一讲",
            "什么是数字媒体、四个典型场景、动手练习",
            12,
        ),
        "大赛答辩" to Triple(
            "数智融媒 · 校园融媒体协同平台",
            "选题背景、技术方案、演示、创新点、应用价值",
            12,
        ),
        "创新方案" to Triple(
            "把校园活动做成一条内容流水线",
            "现象、原因、核心创意、三点不同、落地步骤",
            14,
        ),
        "创业路演" to Triple(
            "低空巡检无人机 · 一句话定位",
            "市场容量、行业痛点、核心技术与原理、盈利模式、团队",
            16,
        ),
        "项目申报" to Triple(
            "校园低空巡检无人机项目",
            "市场容量、国家政策、痛点、创新点、竞品、获奖、团队",
            16,
        ),
        "企业实力" to Triple(
            "数智融媒技术研究院 · 全媒体洞察专家",
            "成立时间、研发历程、专利、数据成绩、推广应用",
            12,
        ),
        "成果发布" to Triple(
            "端侧实时字幕引擎",
            "关键要点、技术壁垒、实测数据、创始人、媒体报道",
            14,
        ),
        "技术方案" to Triple(
            "多模态内容审核引擎",
            "需求、总体架构、关键技术、参数、性能对比、验证",
            14,
        ),
    )

    @Test
    fun `每套蓝图 × 每套配色都装配一份真指令落盘`() {
        val assets = assetsDir()
        val out = outDir()
        var written = 0
        for (deck in DeckTemplates.all) {
            val sample = samples[deck.id]
            assertTrue("示例内容里漏了「" + deck.id + "」", sample != null)
            val (theme, points, pages) = sample!!

            val blueprint = File(assets, deck.assetPath).readText(Charsets.UTF_8)
            val sections = DeckTemplates.parseSections(blueprint)
            assertTrue(deck.id + " 解析不出章节", sections.isNotEmpty())

            val rows = DeckTemplates.compose(sections, theme, points, pages)
            assertTrue(
                deck.id + "：大纲页数加起来不等于要的页数",
                rows.sumOf { it.pages } == pages,
            )

            for (colors in ColorSchemes.all) {
                val sent = DeckTemplates.instruction(
                    deck = deck,
                    blueprint = blueprint,
                    sections = sections,
                    colors = colors,
                    theme = theme,
                    pages = pages,
                    rows = rows,
                    free = "听众是学院评审，讲得务实一点。",
                    askBudget = 2,
                )
                // 用户确认过的大纲必须一条不少地进指令。
                for (r in rows) {
                    assertTrue(
                        deck.id + " × " + colors.id + "：大纲第 " + r.range + " 条丢了",
                        sent.contains(r.range + " | " + r.section),
                    )
                }
                assertTrue(deck.id + "：页数没写进指令", sent.contains("一共 " + pages + " 页"))
                assertFalse(
                    deck.id + "：出现了没被替换的槽位",
                    Regex("""\{\{\s*[\p{L}\p{N}_]+\s*}}""").containsMatchIn(sent),
                )
                File(out, deck.assetPath.substringAfterLast('/').substringBeforeLast('.') +
                    "-" + colors.id + ".txt").writeText(sent, Charsets.UTF_8)
                written += 1
            }
        }
        assertTrue(
            "应该产出 模板 × 配色的全部组合",
            written == DeckTemplates.all.size * ColorSchemes.all.size,
        )
    }

    /**
     * 反面：用户没确认大纲时，指令也不能是半截的。
     *
     * 这条路上应当退回「按章节表铺」，而不是发一段没有结构的空指令。
     */
    @Test
    fun `大纲为空时退回按章节表铺，而不是发空指令`() {
        val assets = assetsDir()
        val out = outDir()
        for (deck in DeckTemplates.all) {
            val blueprint = File(assets, deck.assetPath).readText(Charsets.UTF_8)
            val sections = DeckTemplates.parseSections(blueprint)
            val sent = DeckTemplates.instruction(
                deck = deck,
                blueprint = blueprint,
                sections = sections,
                colors = ColorSchemes.byId("墨黑金")!!,
                theme = "数字媒体",
                pages = 12,
                rows = emptyList(),
                free = "",
                askBudget = 2,
            )
            assertTrue(deck.id + "：应当退回按章节表铺", sent.contains("按这个顺序铺"))
            assertTrue("章节名要出现", sent.contains(sections.first().name))
            File(out, deck.assetPath.substringAfterLast('/').substringBeforeLast('.') +
                "-无大纲.txt").writeText(sent, Charsets.UTF_8)
        }
    }
}
