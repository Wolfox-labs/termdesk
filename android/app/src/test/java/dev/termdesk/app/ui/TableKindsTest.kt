package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「@表格生成」这条路的装配链。
 *
 * 用户对这条路的描述很短：「问生成表格主题，然后根据经验思索需要构成的表头、表框、
 * 各项目内容，之后直接生成，要一个能下载的 excel」。所以这里钉的就是这几件事：
 *  1. 只问主题一件事，问完就没有别的闸门（不像 PPT 那样还要确认大纲）；
 *  2. 「经验」= 一种常见体裁的表头，按主题自己挑；
 *  3. 指令里必须把表框和格式说死——那正是「常规格式要求」的落点；
 *  4. 指令里必须要求回报完整路径，否则手机端那个下载端口就没有东西可下。
 */
class TableKindsTest {

    private val xls: ChatMention = ChatMentions.all.first { it.id == "表格生成" }

    // ── 1. 体裁表本身 ──────────────────────────────────────────────────

    @Test
    fun `每种体裁的表头都够用，而且没有重复列`() {
        assertTrue("体裁不能只有一种", TableKinds.all.size >= 6)
        val ids = TableKinds.all.map { it.id }
        assertEquals("体裁名不能重复", ids.size, ids.toSet().size)
        for (kind in TableKinds.all) {
            assertTrue(kind.id + " 没写说明", kind.hint.isNotBlank())
            assertTrue(kind.id + " 没关键词，自动选型会挑不中", kind.keywords.isNotEmpty())
            assertTrue(
                kind.id + " 的表头太少（" + kind.headers.size + " 列），不像一份能用的表",
                kind.headers.size in 5..10,
            )
            assertEquals(
                kind.id + " 的表头有重复列",
                kind.headers.size,
                kind.headers.toSet().size,
            )
            assertTrue(kind.id + " 没写表框/格式惯例", kind.conventions.isNotEmpty())
        }
    }

    // ── 2. 按主题自己挑体裁 ────────────────────────────────────────────

    @Test
    fun `按主题挑得出对得上的体裁`() {
        assertEquals("台账", TableKinds.pick("日常工作台账").id)
        assertEquals("清单", TableKinds.pick("设备采购清单").id)
        assertEquals("排期", TableKinds.pick("项目排期").id)
        assertEquals("预算", TableKinds.pick("部门费用预算").id)
        assertEquals("统计汇总", TableKinds.pick("数据统计汇总").id)
        assertEquals("人员名单", TableKinds.pick("参会人员名单").id)
        assertEquals("检查表", TableKinds.pick("安全巡检检查表").id)
        assertEquals("对照表", TableKinds.pick("三个方案对比").id)
    }

    @Test
    fun `主题里没有线索时用缺省那一套，而不是空手`() {
        assertEquals(TableKinds.fallback.id, TableKinds.pick("").id)
        assertEquals(TableKinds.fallback.id, TableKinds.pick("随便写点什么").id)
        assertEquals("缺省应当是最通用的台账", "台账", TableKinds.fallback.id)
    }

    // ── 3. 指令：表头、格式、回报路径 ──────────────────────────────────

    @Test
    fun `指令里要有表头 格式规矩 和回报完整路径的要求`() {
        val kind = TableKinds.byId("预算")!!
        val sent = TableKinds.instruction(
            kind = kind,
            theme = "数字媒体实验室年度经费",
            free = "按季度拆一下",
            askBudget = 4,
        )
        assertTrue("要说清是哪一种体裁", sent.contains("生成表格 · 预算"))
        assertTrue("主题要原样带上", sent.contains("数字媒体实验室年度经费"))
        assertTrue("自由文字也要带上", sent.contains("按季度拆一下"))
        for (h in kind.headers) {
            assertTrue("表头「" + h + "」没进指令", sent.contains(h))
        }
        assertTrue("要生成真正的 xlsx", sent.contains("openpyxl"))
        // 政策在 2026-10-18 改了：上一版写「不要自己乱装东西」，于是本机没装 openpyxl 时
        // 内核退回 CSV/Markdown，用户拿不到 .xlsx。现在允许它自己装上再继续。
        assertTrue("没库时要允许它自己装上", sent.contains("pip install openpyxl"))
        assertTrue("表头必须整行加粗——上一版明细表头漏了加粗", sent.contains("整行加粗"))
        assertTrue("要冻结首行", sent.contains("冻结首行"))
        assertTrue("金额格式", sent.contains("千分位"))
        assertTrue("字体要求", sent.contains("微软雅黑"))
        assertTrue("数字别存成文本", sent.contains("真的存成数字"))
        assertTrue("要有一定的行数", sent.contains("12~20 行"))
        assertTrue("不确定的事实要留占位符", sent.contains("待补充"))
        assertTrue(
            "必须要求回报完整路径——手机端就靠它提供下载",
            sent.contains(".xlsx 的完整路径"),
        )
        assertTrue("要交代问额度", sent.contains("最多可以再问我 4 个问题"))
        assertFalse("不能出现没填的模板占位符", sent.contains("{{"))
    }

    // ── 4. 走一趟：只问主题，答完就能发 ────────────────────────────────

    @Test
    fun `表格是 @ 菜单里的一项，并且带着体裁资源`() {
        assertTrue("表格生成必须在菜单里", ChatMentions.match("").any { it.id == "表格生成" })
        assertTrue("必须带体裁资源", xls.tables.isNotEmpty())
        assertEquals(TableKinds.all.size, xls.tables.size)
        assertFalse("表格不用挑配色", xls.needsColors)
        assertEquals("只问一件事：主题", listOf(MentionDraft.LOCAL_THEME), xls.localAsks)
    }

    @Test
    fun `点表格生成只问主题，答完就能发出去`() {
        var draft = ChatMentions.insertion("@", xls).text
        assertEquals("第一问是主题", MentionDraft.LOCAL_THEME, MentionDraft.parse(draft).nextAsk())
        assertTrue("主题是软问，不挡发送", MentionDraft.parse(draft).ready())

        draft = MentionDraft.render(
            MentionDraft.withTheme(MentionDraft.parse(draft), "设备采购清单"),
        )
        assertNull("答完主题就没有别的闸门了", MentionDraft.parse(draft).nextAsk())
        assertTrue("可以发了", MentionDraft.parse(draft).ready())
        assertFalse("表格不需要确认大纲", MentionDraft.parse(draft).needsOutlineAsk())
        assertFalse("表格不需要配色", MentionDraft.parse(draft).needsColorAsk())
    }

    @Test
    fun `发出去的是整段表格指令，@标记被替换掉了`() {
        val draft = MentionDraft.render(
            MentionDraft.withTheme(
                MentionDraft.parse(ChatMentions.insertion("@", xls).text),
                "设备采购清单",
            ),
        )
        val sent = ChatMentions.expand(draft)
        assertTrue("要装配成表格指令", sent.contains("【任务：生成表格 · 清单】"))
        assertTrue("主题要带上", sent.contains("设备采购清单"))
        assertFalse("@标记必须被替换掉", sent.contains("@表格生成"))
        assertTrue("表头要带上", sent.contains("规格型号"))
    }

    @Test
    fun `本地问掉一个，剩下四个留给模型，总数不超过五次`() {
        val md = MentionDraft.parse(
            MentionDraft.render(
                MentionDraft.withTheme(
                    MentionDraft.parse(ChatMentions.insertion("@", xls).text),
                    "采购清单",
                ),
            ),
        )
        assertEquals("本地只问主题一个", 1, md.localAsks)
        assertEquals("模型最多再问四个", 4, md.askBudget())
        assertTrue("总数不超过五次", md.localAsks + md.askBudget() <= MentionDraft.ASK_CEILING)
    }
}
