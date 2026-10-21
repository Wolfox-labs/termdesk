package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「@文案撰写」这条路：**一张悬浮表单**，不是多轮问答。
 *
 * 用户的要求很具体：点完弹一个窗（类型下拉 / 卖点输入 / 语气单选 / 取消 + 开始生成），
 * 填完点「开始生成」就关窗、拼成一段指令发出去；生成的文案**直接显示在聊天框里**，
 * 同时整理一份 md 放在下方让他下载。这些都在这里钉住。
 */
class CopywritingTest {

    private val doc: ChatMention = ChatMentions.all.first { it.id == "文案撰写" }

    /** 按界面那张表单写进草稿的样子：`类型：`/`卖点：`/`语气：` 三行。 */
    private fun formText(type: String, point: String, tone: String, points: String = ""): String {
        val lines = ArrayList<String>()
        lines.add("@" + doc.id)
        if (type.isNotBlank()) lines.add("类型：" + type)
        if (point.isNotBlank()) lines.add("卖点：" + point)
        if (tone.isNotBlank()) lines.add("语气：" + tone)
        if (points.isNotBlank()) lines.add("要点：" + points)
        return lines.joinToString("\n")
    }

    // ── 1. 表单本身 ────────────────────────────────────────────────────

    @Test
    fun `文案撰写是一条带表单字段的 @ 条目，自己不再追问`() {
        assertNotNull("文案撰写必须带表单字段", doc.copy)
        val fields = doc.copy!!
        assertEquals("用户点名的三种类型", listOf("小红书", "短视频", "新闻稿"), fields.types)
        assertEquals("用户点名的三种语气", listOf("幽默", "专业", "走心"), fields.tones)
        assertTrue("默认类型要在选项里", fields.types.contains(fields.defaultType))
        assertTrue("默认语气要在选项里", fields.tones.contains(fields.defaultTone))
        assertTrue("表单已经把该问的都问掉了，不该再有本地追问", doc.localAsks.isEmpty())
        assertFalse("文案不挑配色", doc.needsColors)
    }

    @Test
    fun `菜单里点它不会插一段写死的指令，而是留空等表单`() {
        assertEquals("带表单的条目 body 必须为空，否则会把装配结果抹掉", "", doc.body)
        assertTrue("它必须在 @ 菜单里", ChatMentions.match("").any { it.id == "文案撰写" })
        assertTrue("搜「小红书」也能筛出来", ChatMentions.match("小红书").any { it.id == "文案撰写" })
    }

    // ── 2. 表单值 → 指令 ───────────────────────────────────────────────

    @Test
    fun `表单三栏原样变成一句人话需求，后面才跟规矩`() {
        val sent = ChatMentions.expand(formText("小红书", "防脱发洗发水", "幽默"))
        assertTrue("要指名是哪种文案", sent.contains("【任务：文案撰写 · 小红书】"))
        assertTrue(
            "开头要是一句可以直接照着念的需求",
            sent.contains("帮我写一个小红书文案，卖点是防脱发洗发水，语气要幽默。"),
        )
        assertTrue("小红书的结构要写出来", sent.contains("话题标签"))
        assertTrue("幽默语气的边界要说清", sent.contains("不要低俗"))
        assertTrue("卖点要立住", sent.contains("这是这一篇要立住的东西"))
        assertFalse("@标记必须被替换掉", sent.contains("@文案撰写"))
    }

    @Test
    fun `三种类型各有各的结构，不会都写成一段散文`() {
        val red = Copywriting.shapeOf("小红书")
        val video = Copywriting.shapeOf("短视频")
        val news = Copywriting.shapeOf("新闻稿")
        assertTrue("小红书要有标题钩子和话题标签", red.contains("标题") && red.contains("话题标签"))
        assertTrue("短视频要按镜头分条", video.contains("镜头") || video.contains("分镜"))
        assertTrue("新闻稿要有导语和引语", news.contains("导语") && news.contains("引语"))
        assertTrue("三种结构不能是同一句话", red != video && video != news && red != news)
    }

    @Test
    fun `没填类型和语气时用默认值兜底，而不是崩掉`() {
        val sent = ChatMentions.expand(formText("", "平价美白", ""))
        assertTrue("默认类型", sent.contains(Copywriting.FIELDS.defaultType))
        assertTrue("默认语气", sent.contains(Copywriting.FIELDS.defaultTone))
        assertTrue("卖点仍然要带上", sent.contains("平价美白"))
    }

    @Test
    fun `要点会当成额外要求带上，不会丢`() {
        val sent = ChatMentions.expand(
            formText("短视频", "防脱发洗发水", "专业", points = "控制在 60 秒内、结尾引导关注"),
        )
        assertTrue("额外要求要原样带上", sent.contains("控制在 60 秒内、结尾引导关注"))
    }

    @Test
    fun `只填了类型没填卖点时，草稿里就没有卖点——界面据此再把表单拉起来`() {
        val pairs = DraftTokens.pairs(
            MentionDraft.parse(formText("小红书", "", "幽默")).free,
        )
        assertEquals("小红书", pairs[Copywriting.MARK_TYPE])
        assertEquals("幽默", pairs[Copywriting.MARK_TONE])
        assertNull("没填卖点就该是 null，发送闸门据此拦一次", pairs[Copywriting.MARK_POINT])
    }

    // ── 3. 产出要求：聊天里出全文 + 下方给一份可下载的 md ───────────────

    @Test
    fun `指令要求两样产出：聊天里贴全文，同时存一份可以下载的 md`() {
        val sent = ChatMentions.expand(formText("新闻稿", "校园融媒体平台上线", "专业"))
        assertTrue("要在聊天里把全文贴出来", sent.contains("把全文贴出来"))
        assertTrue("要写成 Markdown 文件", sent.contains("Markdown 文件"))
        assertTrue("要给文件名规则", sent.contains("copy.md"))
        assertTrue("要保存在工作目录根下，手机才能下载到", sent.contains("工作目录根下"))
        assertTrue("要回报完整路径", sent.contains("文件的完整路径"))
        assertTrue("不要编造事实", sent.contains("不要编造"))
        assertTrue("要有占位词规矩", sent.contains("待补充"))
    }

    @Test
    fun `本地不问，五个问题全留给模型，总数不超过五次`() {
        val md = MentionDraft.parse(formText("小红书", "平价美白", "走心"))
        assertEquals("表单已经问掉了，本地记零次", 0, md.localAsks)
        assertEquals("模型最多问五个", 5, md.askBudget())
        assertTrue("总数不超过五次", md.localAsks + md.askBudget() <= MentionDraft.ASK_CEILING)
        assertNull("本地没有待问的问题", md.nextAsk())
        assertTrue("填完表单随时可以发", md.ready())
    }
}
