package dev.termdesk.app.ui

// 只用 Kotlin 标准库：文案这条路不读资源包，也不碰 Android API，整套逻辑能在纯 JVM 单测里钉死。

/**
 * 「文案撰写」那张悬浮表单要填的几栏。
 *
 * 做成数据而不是写死在界面里：加一种文案类型、加一种语气，改这里一行就行。
 */
internal data class CopyFieldSet(
    /** 文案类型，界面上是下拉框。 */
    val types: List<String>,
    /** 语气风格，界面上是单选。 */
    val tones: List<String>,
    val defaultType: String,
    val defaultTone: String,
)

/**
 * 文案撰写：表单收需求 + 本地拼指令。
 *
 * 为什么是表单而不是多轮问答：用户明确要"直接一个悬浮窗"，填完点一下就走。
 * 多轮问答每问一次都要等一个来回，而这三件事（类型、卖点、语气）本来就是**并列的
 * 一次性输入**——用表单问完，用户少等两个回合，也少打两次字。
 *
 * 表单值以 `键：值` 的形式写进草稿正文（`类型：`/`卖点：`/`语气：`），
 * 所以它和仓库里其它指令段一样：**看得见、可手改、删掉就等于没填**。
 * 装配时用 [DraftTokens.pairs] 取回来，和网页那条路取 `地点：` 是同一套机制。
 */
internal object Copywriting {

    /** 表单里那三栏在正文里的写法。 */
    const val MARK_TYPE: String = "类型"
    const val MARK_POINT: String = "卖点"
    const val MARK_TONE: String = "语气"

    val FIELDS = CopyFieldSet(
        types = listOf("小红书", "短视频", "新闻稿"),
        tones = listOf("幽默", "专业", "走心"),
        defaultType = "小红书",
        defaultTone = "专业",
    )

    /**
     * 一种文案类型的写法要求。
     *
     * 这几条是"经验"的落点：小红书要标题带钩子、正文分点、结尾带话题标签；
     * 短视频要给分镜或口播；新闻稿要有导语和引语。不给这些，模型很容易一律写成
     * 一段散文——那三种体裁就白分了。
     */
    fun shapeOf(type: String): String = when {
        type.contains("小红书") ->
            "标题（12~20 字，带一个钩子或数字）→ 正文（3~4 个短段，每段一个点，" +
                "适当用 emoji 和换行）→ 结尾（一句行动号召）→ 5~8 个话题标签"
        type.contains("短视频") ->
            "前 3 秒的钩子 → 口播正文（按镜头分条，每条标出画面与台词）→ 结尾引导关注或评论"
        type.contains("新闻稿") ->
            "标题 → 导语（一句话说清谁在什么时候做了什么）→ 正文（背景、细节、数据）" +
                "→ 一到两句引语 → 结尾（可对外发布的联系方式或落款）"
        else -> "标题 → 正文（2~4 段）→ 结尾行动号召"
    }

    /**
     * 拼出真正发给内核的整段指令。
     *
     * 开头那一句刻意写成用户可以照着抄的整句话（"帮我写一个 X 文案，卖点是 Y，语气要 Z"），
     * 后面才是规矩——模型先看到的是"人话需求"，再看到格式要求。
     */
    fun instruction(
        type: String,
        sellingPoint: String,
        tone: String,
        extra: String,
        askBudget: Int,
    ): String {
        val t: String = type.ifBlank { FIELDS.defaultType }
        val p: String = sellingPoint.trim()
        val n: String = tone.ifBlank { FIELDS.defaultTone }

        val sb = StringBuilder()
        sb.append("【任务：文案撰写 · ").append(t).append("】\n")
        sb.append("帮我写一个").append(t).append("文案")
        if (p.isNotBlank()) sb.append("，卖点是").append(p)
        sb.append("，语气要").append(n).append("。\n\n")

        sb.append("【体裁与结构】\n")
        sb.append(t).append("：").append(shapeOf(t)).append('\n')
        sb.append("语气：").append(n).append(
            when (n) {
                "幽默" -> "——可以玩梗、可以用夸张的对比，但不要低俗，不要为了搞笑牺牲信息量"
                "专业" -> "——用词准确、结论先行，不堆形容词，把依据讲清楚"
                "走心" -> "——写具体的场景和细节，少用大词，宁可平实也不要煽"
                else -> ""
            },
        ).append('\n')
        if (p.isNotBlank()) {
            sb.append("卖点：").append(p).append("——这是这一篇要立住的东西，")
                .append("每一条内容都要能回到它身上，不要写成泛泛而谈。\n")
        }
        if (extra.isNotBlank()) {
            sb.append("我另外交代的：").append(extra.trim()).append('\n')
        }
        sb.append('\n')

        sb.append("【要求】\n")
        sb.append("1. 直接给我可以复制走就发的成品，**不要在回复里讲你的思路或给我多个版本**，")
            .append("除非我另外要求；\n")
        sb.append("2. 不要编造具体的价格、成分、销量、认证这类**事实性信息**；")
        sb.append("确实需要的地方写一个一眼能看出要改的占位词（例如「待补充」），")
            .append("并在末尾列出来让我替换；\n")
        sb.append("3. 同时把这份文案写进本对话工作目录里的一个 Markdown 文件，")
            .append("文件名用小写英文加连字符（例如 copy.md），保存在工作目录根下，")
            .append("方便我之后在文件里打开和下载；")
            .append("**这一步必须真的调用写文件工具把它写出来**——")
            .append("只在回复里说「正在写入 Markdown 文件…」而没真写，我这边是什么都拿不到的；\n")
        sb.append("4. 做完后告诉我文件的完整路径，并在聊天里把全文贴出来。")
            .append("顺序是：先写文件 → 再贴全文 → 最后报路径。\n\n")

        sb.append("【关于追问】\n")
        if (askBudget > 0) {
            sb.append("如果确实还缺关键信息（比如面向谁、投放在哪个平台、有没有字数限制），")
                .append("你最多可以再问我 ").append(askBudget).append(" 个问题。\n")
            sb.append("一次只问一个，等我说完再问下一个；我可能会分好几次回答，")
                .append("把每次回答累积起来，问满或问清之后立刻开工，不要重复问同一件事。\n")
        } else {
            sb.append("不要再来问我了，直接按上面的信息把东西写出来，缺的细节你按常理补。\n")
        }
        return sb.toString()
    }
}
