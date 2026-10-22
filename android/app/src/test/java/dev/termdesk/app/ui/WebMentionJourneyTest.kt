package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 从「打一个 `@`」到「点发送」的完整一趟，一步不跳。
 *
 * 这个文件存在的理由：其余几个测试各钉一段（语法层、装配层、资源契约），
 * 但没有一个走完用户真正走的那条路。真机上那条路是
 * `@` → 菜单 → 网页生成 → 挑骨架 → 挑配色 → 填主题 → 发送，
 * 中间任何一步的顺序错了（比如配色问在骨架之前、或发送时忘了拦），
 * 分段测试都看不出来。
 *
 * 用的骨架是**资源包里那一份真的**（`assets/templates/web/official.html`），
 * 不是内存里的假货——所以这里跑的装配结果，和真机上会发给内核的是同一份。
 */
class WebMentionJourneyTest {

    private fun assetsDir(): File = PackFixtures.assetsDir()

    /** 和 ChatSection 里那条 `skeletonOf` 取的是同一个文件。 */
    private val realSkeleton: (PageTemplate) -> String = { template ->
        File(assetsDir(), template.assetPath).readText(Charsets.UTF_8)
    }

    @Test
    fun `打一个 @ 之后，网页生成就是菜单第一项`() {
        assertEquals("刚敲下 @ 时返回空查询，表示列出全部", "", ChatMentions.query("@"))
        val listed = ChatMentions.match("")
        assertTrue("菜单不能是空的", listed.isNotEmpty())
        assertEquals(
            "用户说这是「@ 之后的第一个功能」，那它就必须是第一项",
            "网页生成",
            listed.first().id,
        )
        assertNotNull("网页生成必须带骨架资源包，否则挑不了模板", listed.first().templates.firstOrNull())
        assertEquals(
            "资源包里八套骨架都要能选（原来五套 + 这一轮内嵌的三套参考站点）",
            8,
            listed.first().templates.size,
        )
        assertEquals(
            "资源包里有几套，注册表里就得有几套——数字对不上说明有一边漏改了",
            WebTemplates.all.size,
            listed.first().templates.size,
        )
    }

    @Test
    fun `@ 网页生成 走完一趟：挑骨架 挑配色 填主题 然后才发得出去`() {
        // ── 1. 在输入框里敲 @，菜单里点「网页生成」 ────────────────────
        val inserted = ChatMentions.insertion("@", ChatMentions.all.first { it.id == "网页生成" })
        assertEquals("插入结果是「@标记 + 空格」", "@网页生成 ", inserted.text)
        assertEquals("光标要落在插入内容之后", inserted.text.length, inserted.caret)

        var draft = inserted.text
        assertTrue("套用了模板，预览条就该出现了", ChatMentions.applied(draft).isNotEmpty())

        // ── 2. 挑骨架 ──────────────────────────────────────────────────
        val official = WebTemplates.byId("官网首页")!!
        draft = MentionDraft.render(MentionDraft.withTemplate(MentionDraft.parse(draft), official))
        assertTrue("骨架的选择要写进文本本身，可手改", draft.contains("模板：官网首页"))

        // ── 3. 发送之前：第一问必须是配色，而且发不出去 ────────────────
        assertEquals(
            "还没定配色时，发送要先问配色",
            MentionDraft.LOCAL_COLORS,
            MentionDraft.parse(draft).nextAsk(),
        )
        assertFalse("这一问没答完，不能算备齐", MentionDraft.parse(draft).ready())

        // ── 4. 挑配色，色值要写进文本 ─────────────────────────────────
        val ink = ColorSchemes.byId("墨黑金")!!
        draft = MentionDraft.render(MentionDraft.withColors(MentionDraft.parse(draft), ink))
        assertTrue("配色要看得见颜色名", draft.contains("配色：墨黑金"))
        // 政策改了（用户点名）：输入框里**只留颜色名，不再写十六进制色号**。
        // 色值仍然会进发给内核的指令（下面第 7 步两条断言还在），只是不再污染输入框。
        assertFalse("输入框里不该再出现 #RRGGBB 这种色号", draft.contains("#"))

        // ── 5. 第二问：主题（本地固定问的最后一个） ────────────────────
        assertEquals(
            "配色定了就轮到主题",
            MentionDraft.LOCAL_THEME,
            MentionDraft.parse(draft).nextAsk(),
        )
        draft = MentionDraft.render(MentionDraft.withTheme(MentionDraft.parse(draft), "校园开放日"))
        draft = MentionDraft.render(MentionDraft.withPoints(MentionDraft.parse(draft), "社团展示、报名方式"))

        // ── 6. 该问的都问完了，这时候才发得出去 ────────────────────────
        val pending = MentionDraft.parse(draft)
        assertNull("两问都答完就不该再拦", pending.nextAsk())
        assertTrue("可以发了", pending.ready())

        // ── 7. 展开成内核真正收到的那段，用真骨架 ──────────────────────
        val sent = ChatMentions.expand(draft, realSkeleton)

        assertTrue("要说清是哪一套骨架", sent.contains("生成网页 · 官网首页"))
        assertTrue("配色要照用，不许模型另配", sent.contains("直接照用"))
        assertTrue("墨黑金的底色要真的进指令", sent.contains("--bg:#0F0D0A"))
        assertTrue("墨黑金的主色要真的进指令", sent.contains("--primary:#D8B45A"))
        assertTrue("不能留下没被替换的配色变量", !sent.contains("{{配色变量}}"))
        assertFalse("骨架已经内联，不该再去读工作目录里的模板文件", sent.contains("_templates/page.html"))

        assertTrue("主题要原样带给内核", sent.contains("主题：校园开放日"))
        assertTrue("要点要原样带给内核", sent.contains("要点：社团展示、报名方式"))
        assertTrue("主题要在**本地**就填进真骨架的大标题", sent.contains("<h1>校园开放日</h1>"))
        assertTrue("第一条要点要在本地填进卡片", sent.contains("<h3>社团展示</h3>"))
        assertTrue("第二条要点要在本地填进卡片", sent.contains("<h3>报名方式</h3>"))
        assertTrue("文档标题也要填掉，不能露出占位符", sent.contains("<title>校园开放日</title>"))

        assertTrue("要说清还能问几次：本地已经问了两次，剩三次", sent.contains("最多可以再问我 3 个问题"))
        assertTrue("要允许用户分多次回答", sent.contains("分好几次回答"))
        assertTrue("要明说成品里不许出现花括号", sent.contains("不许出现任何"))
    }

    @Test
    fun `用户把配色那一段删掉再点发送，必须重新拦一次而不是直接发出去`() {
        var draft = MentionDraft.render(
            MentionDraft.parse("@网页生成 模板：官网首页 配色：科技蓝 主题：开放日"),
        )
        assertNull("先确认这时候是备齐的", MentionDraft.parse(draft).nextAsk())

        // 用户手动把「配色：…（底 #… · 主色 #…）」整段删掉。
        draft = draft.replace(Regex("配色：[^\\n]*"), "")
        assertEquals(
            "删掉配色之后，下一次点发送应当重新问配色",
            MentionDraft.LOCAL_COLORS,
            MentionDraft.parse(draft).nextAsk(),
        )
        assertFalse("没配色就不算备齐", MentionDraft.parse(draft).ready())
    }

    @Test
    fun `后续几轮追问的回答走原路，不再被 @ 逻辑拦一次`() {
        // 内核反问「面向谁」，用户在同一个对话里分几次回答。这些都是普通消息，
        // 不该被模板逻辑改写、也不该再弹一次配色/主题。
        val answers = listOf(
            "高三学生和家长",
            "再放一组去年的到场人数：1200 人",
            "联系方式写 0851-8888-6666",
        )
        for (answer in answers) {
            assertEquals("普通消息必须原样发出", answer, ChatMentions.expand(answer, realSkeleton))
            assertTrue("没有 @ 就不该套模板", ChatMentions.applied(answer).isEmpty())
            assertNull("更不该再拦一次配色/主题", MentionDraft.parse(answer).nextAsk())
            assertTrue("普通消息永远备齐", MentionDraft.parse(answer).ready())
        }
    }

    @Test
    fun `每套骨架都能从 @ 一路装配到可发送`() {
        for (template in WebTemplates.all) {
            var draft = MentionsFixture.start(template)
            assertEquals(
                template.id + "：挑完骨架就该问配色",
                MentionDraft.LOCAL_COLORS,
                MentionDraft.parse(draft).nextAsk(),
            )
            draft = MentionDraft.render(
                MentionDraft.withColors(MentionDraft.parse(draft), ColorSchemes.byId("青绿")!!),
            )
            draft = MentionDraft.render(MentionDraft.withTheme(MentionDraft.parse(draft), "开放日"))
            assertNull(template.id + "：答完两问就该放行", MentionDraft.parse(draft).nextAsk())

            val sent = ChatMentions.expand(draft, realSkeleton)
            assertTrue(template.id + "：要说清是哪一套", sent.contains("生成网页 · " + template.id))
            assertTrue(template.id + "：主题要进它自己的主标题槽位", sent.contains(">开放日<"))
            assertTrue(template.id + "：不能残留配色变量", !sent.contains("{{配色变量}}"))
            assertTrue(template.id + "：骨架要真的内联进来", sent.contains("<!DOCTYPE html>"))
        }
    }

    /**
     * 半角冒号。
     *
     * 这条来自真机：使用者在手机上打的是 `要点:专业介绍、实验室开放`（半角冒号），
     * 而解析器当时只认全角 `要点：`，于是那一整行被当成主题，`<h1>` 直接变成
     * 「数字媒体 要点:专业介绍、实验室开放」。手机上打标点用什么都是随手的，
     * 让人为了对齐解析器去挑冒号，是解析器的错。
     */
    @Test
    fun `半角冒号也认，主题不会被整行吞掉`() {
        val d = MentionDraft.parse(
            "@网页生成 配色:紫罗兰\n主题:数字媒体 要点:专业介绍、实验室开放",
        )
        assertEquals("主题只到要点之前为止", "数字媒体", d.theme)
        assertEquals("要点要认得出来", "专业介绍、实验室开放", d.points)
        assertEquals("配色的半角写法也要认", "紫罗兰", d.colors?.id)

        val sent = ChatMentions.expand(MentionDraft.render(d), realSkeleton)
        assertTrue("大标题只能是主题本身", sent.contains("<h1>数字媒体</h1>"))
        assertFalse("要点不能被塞进大标题", sent.contains("<h1>数字媒体 要点"))
        assertTrue("要点要进卡片", sent.contains("<h3>专业介绍</h3>"))
    }

    /**
     * 光标回归防护。
     *
     * 真机症状：改一次配色，光标跳回输入框最前面，接着打的字全插到 `@网页生成` 之前。
     * 根因是 `TextFieldValue(text)` 的默认选区是 `TextRange(0)`。这条断言直接读源码，
     * 因为 Compose 的光标行为没法在单元测试里跑——但「有没有把位置给出来」可以读得出来。
     */
    @Test
    fun `凡是由程序换掉草稿的地方，都必须自己给出光标位置`() {
        val conversation = File("src/main/java/dev/termdesk/app/ui/ChatConversation.kt").readText(Charsets.UTF_8)
        val composer = File("src/main/java/dev/termdesk/app/ui/ChatComposer.kt").readText(Charsets.UTF_8)
        assertFalse(
            "ChatConversation 里出现了没有 TextRange 的 TextFieldValue(MentionDraft.render(...))；" +
                "那样光标会跳到最前面，请改用 caretAtEnd(...)",
            Regex("""TextFieldValue\(\s*MentionDraft\.render""").containsMatchIn(conversation),
        )
        assertTrue("caretAtEnd 必须存在", composer.contains("fun caretAtEnd("))
        assertTrue("Conversation 必须使用 caretAtEnd", conversation.contains("caretAtEnd(MentionDraft.render"))
        assertTrue(
            "普通程序化草稿更新也必须把光标放在末尾（例如选择 slash 命令后继续输入参数）",
            conversation.contains("updateDraftValue(caretAtEnd(value))"),
        )
    }

    /**
     * 要点回归防护。
     *
     * 真机症状：点上面那个「要点」芯片，弹出的是一整段指令全文——一大段没用的字，
     * 既没法打字、也不是用户要的东西，用户的原话是「这个功能完全坏掉」。
     * 它本来就该和主题并列：一个自己的小输入框，填了就进卡片，不填也能照发。
     *
     * 面板开合是 Compose 的事，单测里跑不了，所以和光标那条一样读源码：
     * 「要点」的点击块里只许开 pointsPanel，不许碰 previewOpen——整段全文只留给
     * 旁边那个「看全文」的开关。
     */
    @Test
    fun `点要点只开自己的小输入框，不再铺一整段指令全文`() {
        val src = File("src/main/java/dev/termdesk/app/ui/ChatConversation.kt").readText(Charsets.UTF_8)
        val panel = File("src/main/java/dev/termdesk/app/ui/ChatDemandPanels.kt").readText(Charsets.UTF_8)

        val chipAt = src.indexOf("\"要点\",")
        assertTrue("找不到「要点」那个芯片了", chipAt > 0)
        // 窗口切在芯片和「整段全文」那块之间，正好是这一次点击干了什么。
        // 注释要剔掉：旧代码的说明里就写着 `previewOpen = true`，把注释算进来的话
        // 这条断言会永远失败——而它要盯的是**真的还在跑的那行代码**。
        val previewAt = src.indexOf("if (previewOpen)", chipAt)
        assertTrue("要点芯片后面应当紧跟「看全文」那一块", previewAt > chipAt)
        val click = src.substring(chipAt, previewAt)
            .lineSequence()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")

        assertTrue(
            "点「要点」必须把现有要点装进它自己的输入框",
            click.contains("pointsInput = mentionDraft.points"),
        )
        assertTrue("点了就要把那个面板打开", click.contains("pointsPanel = true"))
        assertFalse(
            "点「要点」不能再打开 previewOpen —— 那正是用户说的「出来一大段字」",
            click.contains("previewOpen"),
        )

        assertTrue("要点面板要真的存在", src.contains("if (pointsPanel)"))
        assertTrue("面板里要能真的打字", panel.contains("value = value"))
        assertTrue(
            "不填也得能发出去：面板必须留一个跳过",
            panel.contains("跳过，交给模型按主题拟"),
        )
        assertTrue(
            "整段指令全文只能由「看全文」那个开关打开",
            src.contains("previewOpen = !previewOpen"),
        )
    }

    /** 从「敲 @」开始，替某一套骨架把话接上。 */
    private object MentionsFixture {
        fun start(template: PageTemplate): String {
            val web = ChatMentions.all.first { it.id == "网页生成" }
            val inserted = ChatMentions.insertion("@", web)
            return MentionDraft.render(
                MentionDraft.withTemplate(MentionDraft.parse(inserted.text), template),
            )
        }
    }
}
