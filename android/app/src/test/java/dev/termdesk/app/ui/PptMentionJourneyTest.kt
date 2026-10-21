package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 从「打一个 `@`」到「点发送」的完整一趟——PPT 版。
 *
 * 用户把这条路的顺序说得很具体：**先单独问主题，之后问配色、页数，再之后自由选择写要点
 * （不写就不弹）；基础信息齐了以后快速形成大纲，每条能单独改，确认后才开始生成。**
 * 这里就按这句话走一遍，一步不跳。
 *
 * 用的蓝图是**资源包里那一份真的**（`assets/templates/ppt/report.txt`），
 * 所以这里装配出来的，和真机上会发给内核的是同一份东西。
 */
class PptMentionJourneyTest {

    private fun assetsDir(): File {
        val direct = File("src/main/assets")
        if (direct.isDirectory) return direct
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/assets")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到 assets 目录")
    }

    private val realBlueprint: (DeckTemplate) -> String = { deck ->
        File(assetsDir(), deck.assetPath).readText(Charsets.UTF_8)
    }

    private val ppt: ChatMention = ChatMentions.all.first { it.id == "PPT生成" }

    private fun start(): String = ChatMentions.insertion("@", ppt).text

    @Test
    fun `PPT 是 @ 菜单里第二项，并且带着蓝图资源包`() {
        val listed = ChatMentions.match("")
        assertEquals("网页生成", listed.first().id)
        assertEquals("PPT 是用户要的第二个功能", "PPT生成", listed[1].id)
        assertTrue("必须带蓝图资源包，否则挑不了模板", listed[1].decks.isNotEmpty())
        assertEquals(
            "资源包里有几套，注册表里就得有几套",
            DeckTemplates.all.size,
            listed[1].decks.size,
        )
        assertTrue("PPT 要问配色", listed[1].needsColors)
    }

    @Test
    fun `追问顺序就是用户说的那一串：主题 到 配色 到 页数`() {
        var draft = start()
        assertEquals("第一问必须是主题", MentionDraft.LOCAL_THEME, MentionDraft.parse(draft).nextAsk())
        assertFalse("还没答完，不能算备齐", MentionDraft.parse(draft).ready())

        // 答主题
        draft = MentionDraft.render(
            MentionDraft.withTheme(MentionDraft.parse(draft), "数字媒体"),
        )
        assertEquals("第二问是配色", MentionDraft.LOCAL_COLORS, MentionDraft.parse(draft).nextAsk())

        // 答配色
        draft = MentionDraft.render(
            MentionDraft.withColors(MentionDraft.parse(draft), ColorSchemes.byId("科技蓝")!!),
        )
        assertEquals("第三问是页数", MentionDraft.LOCAL_PAGES, MentionDraft.parse(draft).nextAsk())

        // 答页数
        draft = MentionDraft.render(MentionDraft.withPages(MentionDraft.parse(draft), 14))
        assertNull("三问都答完了，本地不再拦", MentionDraft.parse(draft).nextAsk())
    }

    /**
     * 要点**永远不自动弹**。
     *
     * 这一条来自用户的原话：「再之后自由选择写要点 不写的话不自动弹出」。
     * 钉法是看数据本身——只要它不在 localAsks 里，就永远不会被当成欠下的那一问。
     */
    @Test
    fun `要点不在追问清单里，所以永远不会自动弹出来`() {
        assertFalse(
            "要点一旦进了 localAsks，就会变成自动弹出的一问——那正是用户不要的",
            ppt.localAsks.contains("要点"),
        )
        val draft = MentionDraft.render(
            MentionDraft.withPages(
                MentionDraft.withColors(
                    MentionDraft.withTheme(MentionDraft.parse(start()), "数字媒体"),
                    ColorSchemes.byId("科技蓝")!!,
                ),
                12,
            ),
        )
        assertNull("没写要点也不该拦", MentionDraft.parse(draft).nextAsk())
    }

    /**
     * 大纲那道闸门：**确认为止，发不出去**。
     */
    @Test
    fun `三问答完之后，大纲没确认就发不出去；确认后才放行`() {
        var draft = MentionDraft.render(
            MentionDraft.withPages(
                MentionDraft.withColors(
                    MentionDraft.withTheme(MentionDraft.parse(start()), "数字媒体"),
                    ColorSchemes.byId("墨黑金")!!,
                ),
                14,
            ),
        )
        val before = MentionDraft.parse(draft)
        assertTrue("三问都答完了，所以不该再有追问", before.nextAsk() == null)
        assertTrue("但大纲还没确认，必须拦住", before.needsOutlineAsk())
        assertFalse("所以还不算备齐", before.ready())

        // App 本地铺大纲——用户要的「快速形成大纲」，不花模型回合。
        val sections = DeckTemplates.parseSections(realBlueprint(before.deck!!))
        val rows = DeckTemplates.compose(
            sections, before.theme, before.points, before.effectivePages(),
        )
        assertEquals("大纲要是按用户说的页数铺的", 14, rows.sumOf { it.pages })
        draft = MentionDraft.render(MentionDraft.withOutline(before, rows))

        val after = MentionDraft.parse(draft)
        assertFalse("确认之后就不该再拦", after.needsOutlineAsk())
        assertTrue("这时候才真的可以发了", after.ready())
        assertEquals("大纲要能原样读回来", rows.size, after.outline.size)
    }

    /**
     * 「用户有权限单独修改某条」——改一条，发送内容里就得是改过的那一条。
     */
    @Test
    fun `用户单独改了大纲里的某一条，发出去的指令里就是改过的那条`() {
        var md = MentionDraft.parse(
            MentionDraft.render(
                MentionDraft.withPages(
                    MentionDraft.withColors(
                        MentionDraft.withTheme(MentionDraft.parse(start()), "数字媒体"),
                        ColorSchemes.byId("科技蓝")!!,
                    ),
                    14,
                ),
            ),
        )
        val sections = DeckTemplates.parseSections(realBlueprint(md.deck!!))
        val rows = DeckTemplates.compose(sections, md.theme, "专业介绍、实验室开放", 14).toMutableList()
        rows[2] = rows[2].copy(section = "现状与数据", content = "上一季度的三条硬指标")
        md = MentionDraft.withOutline(md, rows)
        val draft = MentionDraft.render(md)

        val sent = ChatMentions.expand(draft, { "" }, realBlueprint)
        assertTrue("模板名要写明", sent.contains("生成 PPT · " + md.deck!!.id))
        assertTrue("页数要写对", sent.contains("一共 14 页"))
        assertTrue(
            "用户改过的那一条要原样进指令",
            sent.contains(rows[2].range + " | 现状与数据 | 上一季度的三条硬指标"),
        )
        assertTrue("配色要带上", sent.contains("主色 #00E5FF"))
        assertTrue("蓝图要整份内联进来", sent.contains("章节:"))
        assertFalse(
            "不能出现没被替换的槽位（规则那句自己写着 `{{`，所以要查槽位形状而不是这两个字）",
            Regex("""\{\{\s*[\p{L}\p{N}_]+\s*}}""").containsMatchIn(sent),
        )
    }

    @Test
    fun `本地问掉三个，剩下两个留给模型，总数不超过五次`() {
        val md = MentionDraft.parse(
            MentionDraft.render(
                MentionDraft.withPages(
                    MentionDraft.withColors(
                        MentionDraft.withTheme(MentionDraft.parse(start()), "数字媒体"),
                        ColorSchemes.fallback,
                    ),
                    12,
                ),
            ),
        )
        assertEquals("本地固定问三个：主题、配色、页数", 3, md.localAsks)
        assertEquals("模型最多再问两个", 2, md.askBudget())
        assertTrue("总数不超过五次", md.localAsks + md.askBudget() <= MentionDraft.ASK_CEILING)
    }

    @Test
    fun `App 自己按主题挑模板：教学类选教学课件，什么都不像就用缺省`() {
        val lesson = MentionDraft.parse("@PPT生成 主题：高校教学课件")
        assertEquals("教学课件", lesson.deck?.id)

        val plain = MentionDraft.parse("@PPT生成 主题：数字媒体")
        assertEquals("没有线索时用缺省那一套", DeckTemplates.fallback.id, plain.deck?.id)

        // 用户手改过模板就听用户的，不再自动改回去。
        val manual = MentionDraft.render(
            MentionDraft.withDeck(
                MentionDraft.parse("@PPT生成 主题：数字媒体"),
                DeckTemplates.byId("方案提案")!!,
            ),
        )
        assertEquals("手选的模板要认得住", "方案提案", MentionDraft.parse(manual).deck?.id)
    }

    @Test
    fun `页数跳过时用模板默认页数，不会变成零页`() {
        val md = MentionDraft.parse(
            MentionDraft.render(
                MentionDraft.withColors(
                    MentionDraft.withTheme(MentionDraft.parse(start()), "数字媒体"),
                    ColorSchemes.fallback,
                ),
            ),
        )
        assertNull("用户没写页数", md.parsedPages())
        assertEquals(
            "要退回模板的默认页数",
            md.deck!!.defaultPages,
            md.effectivePages(),
        )
        assertTrue("默认页数必须是个正数", md.effectivePages() > 0)
    }

    @Test
    fun `普通消息不会被 PPT 逻辑碰到`() {
        val plain = "帮我把这个文件改一下"
        assertEquals(plain, ChatMentions.expand(plain))
        assertNull(MentionDraft.parse(plain).nextAsk())
        assertTrue(MentionDraft.parse(plain).ready())
    }
}
