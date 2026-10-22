package dev.termdesk.app.ui

// 这个文件不需要任何 import：它只用 Kotlin 标准库。
// 位置要求：和 ChatSection.kt 放在同一个文件夹（app/src/main/java/dev/termdesk/app/ui/），
// 包名 dev.termdesk.app.ui 就是对的。

/**
 * `@` 提及菜单里的一项产出任务。
 *
 * 它不是一张图、也不是一个点了没反应的占位按钮：
 *  - 选中后，输入框里留下的是 `id` 这个短标记（例如 `@网页生成`）；
 *  - 发送时，`body` 这段完整指令会替换掉标记，真正随消息发给内核（本地沙盒内核与远程内核同一套接口）；
 *  - 内核按指令把文件写进当前对话的工作目录，然后回报完整路径；
 *  - 那个目录在详情面板里有现成入口（「在文件里打开」），产物可以直接点开。
 *
 * 也就是说：模板决定内核去做什么，文件是内核真做出来的。
 */
internal data class ChatMention(
    /** 输入框里的短标记，也是发送时被替换掉的文本。 */
    val id: String,
    /** 菜单里的名字。 */
    val title: String,
    /** 菜单里的一行说明，告诉用户点下去最终会得到什么。 */
    val hint: String,
    /** 小角标，纯文本，不依赖任何图标库。 */
    val badge: String,
    /** 额外的搜索词。 */
    val keywords: List<String>,
    /**
     * 这一条要靠哪个功能包才能用；null 表示不需要包（纯提示词条目）。
     *
     * 五个带模板/体裁/风格的功能都在这条线上：包没装时菜单里不出现，装了才出现。
     * 这样"没装"不会伪装成"点了没反应"。
     */
    val pack: String? = null,
    /** 真正发给内核的完整指令。带 [templates] 的条目不读它——那种由 App 现场装配。 */
    val body: String,
    /**
     * 这一类产出物可选的骨架资源包。
     *
     * 不空就表示「选中之后还要再挑一次模板」，挑完由 App 把骨架整份装配进指令里。
     * 空表示这一类是一段固定指令，直接展开即可。
     */
    val templates: List<PageTemplate> = emptyList(),
    /**
     * 这一类产出物可选的 PPT 蓝图资源包。
     *
     * 和 [templates] 是并列的两包，不是一套：网页的模板是一份 HTML 骨架，
     * PPT 的模板是一份章节蓝图，资源形态不同、装配方式也不同。
     * 两者都遵循同一条规矩——**加模板只要丢一个资源文件 + 加一条数据**。
     */
    val decks: List<DeckTemplate> = emptyList(),
    /**
     * 这一类产出物是**表格**：按主题自己挑一种体裁，把表头摆好再交给模型。
     *
     * 只是一个开关而不是一份体裁列表：体裁本身在「表格生成」功能包里，
     * 由 [TableKinds.load] 在装配时读，所以补一种体裁不用改这里。
     */
    val table: Boolean = false,
    /**
     * 这一类产出物要用**悬浮表单**收需求时的字段定义；null 表示不用表单。
     *
     * 目前只有「文案撰写」用它：类型 / 卖点 / 语气三栏一次填完，填完直接拼指令发出去。
     */
    val copy: CopyFieldSet? = null,
    /**
     * 这一类产出物**在手机本地做完**，不走内核（目前只有「图片处理」）。
     *
     * 这种条目点一下弹出的是自己的操作表单，处理完往对话里写一行系统提示；
     * 它没有指令可装配，所以 [body] 必须是空的。
     */
    val image: Boolean = false,
    /**
     * 这一类产出物也是在手机本地画出来的，但走的是自己的表单与卡片（目前只有「海报生成」）。
     *
     * 和 [image] 分开而不是共用一个开关：两者的表单、进度、产出物呈现都不一样
     * （图片处理出一堆相册图 + 一行提示，海报出一张卡在聊天里的可调图）。
     */
    val poster: Boolean = false,
    /** 这一类产出物需要用户先定配色。 */
    val needsColors: Boolean = false,
    /**
     * App 本地固定要问的几个问题。
     *
     * 用户给的上限是「最多问五次」，本地一次点击就能问掉的那几个（配色、主题）
     * 放在这里，剩下的额度交给模型多轮追问。
     */
    val localAsks: List<String> = emptyList(),
)

/**
 * 一次插入的结果：输入框里应有的新文本，以及**光标该落在哪**。
 *
 * 光标位置之所以要算出来、而不是交给输入框，见 [ChatMentions.insertion] 的说明。
 */
internal data class MentionInsertion(
    /** 插入之后的完整文本。 */
    val text: String,
    /** 光标字符偏移量，落在插入内容的末尾。 */
    val caret: Int,
)

/**
 * 模板表 + 纯文本工具。
 *
 * 这里刻意不做任何持久化状态：菜单显示什么、套用了哪个模板，全部从输入框的文本本身推导。
 * 好处是删掉 `@标记`，提示行会自己消失，永远不会出现「按钮说套用了、其实没套用」。
 */
internal object ChatMentions {

    // ------------------------------------------------------------------
    // 写模板的三条规矩：
    //  1. 明确要求「在本对话的工作目录里」产出文件 —— 产物才落在文件页能打开的目录；
    //  2. 明确要求回报完整路径 —— 用户才知道东西在哪；
    //  3. 主题取自本条消息里的其它文字，模板里绝不写死内容。
    // 注意：body 里不要再出现「@ + 模板名」这种文本，否则 expand 会二次替换。
    // ------------------------------------------------------------------

    /**
     * `@` 菜单里的第一项，也是唯一一项「App 现场装配」的产出物。
     *
     * 它和别的条目的区别只有两点，都是为了用户提的那件事：
     *  1. **它不是一个写死的 prompt。**用户选完之后还要再挑一套骨架
     *     （`assets/templates/web/` 里的资源包），App 把他已经打出来的字
     *     **在本地**填进骨架的槽位里——标题、副标题、关键要点。
     *     模型拿到手时版式已经定了、文字已经填了一半，它只需要补空，
     *     所以「用户打出具体事项时反应更快」是真的，不是话术。
     *  2. **配色不再写死。**原来这里钉着 #0B0E14 / #00E5FF，用户想换一套
     *     只能改代码。现在配色是发送前问一次、写进文本的一小段指令，
     *     7 套预设任意换，也能手打。
     */
    private val WEB = ChatMention(
        id = "网页生成",
        pack = "web",
        title = "网页生成",
        hint = "挑一套骨架 + 一套配色，生成能直接双击打开的 HTML 页面",
        badge = "WEB",
        keywords = listOf("网页", "页面", "html", "web", "官网", "落地页", "宣传页", "海报页"),
        body = "",
        templates = WebTemplates.all,
        needsColors = true,
        localAsks = listOf(MentionDraft.LOCAL_COLORS, MentionDraft.LOCAL_THEME),
    )

    /**
     * PPT：和「网页生成」同构的第二条路。
     *
     * 差别只在问什么、按什么顺序问，而这些**写在数据里**：
     *  - [localAsks] 的顺序就是追问的顺序——主题 → 配色 → 页数，一次只问一个；
     *  - 「要点」刻意不在这个列表里，所以它**永远不会自动弹出来**，
     *    用户想写就点芯片去写，不想写就一路走到生成；
     *  - 基础信息齐了以后由 App 本地铺大纲（每部分几页、写什么），
     *    用户逐条改完、点了确认才真的发出去。
     *
     * 页数、大纲这些事的规则都在 [DeckTemplates] 里，这里一份数据就够了。
     */
    private val PPT = ChatMention(
        id = "PPT生成",
        pack = "ppt",
        title = "PPT生成",
        hint = "挑一套版式 + 一套配色，按页数生成真正能打开的 .pptx",
        badge = "PPT",
        keywords = listOf("ppt", "幻灯片", "演示", "slide", "汇报", "pptx", "课件", "路演"),
        body = "",
        decks = DeckTemplates.all,
        needsColors = true,
        localAsks = listOf(
            MentionDraft.LOCAL_THEME,
            MentionDraft.LOCAL_COLORS,
            MentionDraft.LOCAL_PAGES,
        ),
    )

    /**
     * 图片处理：**完全在手机上做完**，不走内核。
     *
     * 压缩、转格式、打水印都是确定性的像素操作，没有"生成"的成分——交给模型只会绕一大圈
     * （图传到电脑、处理完再传回来），而手机自己几百毫秒就能出结果，还省一次联网。
     * 所以它的 [body] 是空的：点它弹出的是那张悬浮表单（见 [ImageTools] 与 ChatSection）。
     */
    private val IMG = ChatMention(
        id = "图片处理",
        pack = "image",
        title = "图片处理",
        hint = "选图 + 压缩质量 + 格式 + 水印，在手机上直接处理并存进相册",
        badge = "IMG",
        keywords = listOf("图片", "照片", "压缩", "水印", "转格式", "尺寸", "jpg", "png", "webp", "批处理"),
        body = "",
        image = true,
    )

    private val SUB = ChatMention(
        id = "字幕生成",
        title = "字幕生成",
        hint = "给视频生成 .srt 字幕文件",
        badge = "SUB",
        keywords = listOf("字幕", "srt", "视频", "语音", "转写", "whisper", "听写"),
        body = """
【任务：生成字幕】
请在本对话的工作目录里，给一个视频生成字幕文件。
要求：
1. 要处理的视频就是我在这条消息里写的那个；没写就是工作目录里最新、或唯一的一个视频；
2. 先检查当前环境里有没有可用的语音识别工具（whisper、faster-whisper 等）；有就用，没有就告诉我，不要自己乱装东西；
3. 输出标准 .srt：UTF-8、带序号和时间轴，文件名与视频同名；
4. 如果需要下载模型，先把体积和大概耗时告诉我，等我同意再下；
5. 做完后告诉我 .srt 的完整路径、总条数，以及用的是哪种语言模型。
如果我只发了这条模板、没指定视频，先把工作目录里的视频列出来让我挑。
""".trimIndent()
    )

    /**
     * 表格：第三条「App 现场装配」的路。
     *
     * 用户点它之后**只问一件事：主题**（`localAsks` 里就写了一个）。问完直接出东西——
     * 表格不需要挑配色、不需要报页数，也不需要像 PPT 那样先确认大纲，那是给"要排版的
     * 长东西"准备的。表头、表框、格式这些"经验"由 [TableKinds] 按主题自动挑一种体裁
     * 先摆好，模型负责按主题把它填成一份真表。
     */
    private val XLS = ChatMention(
        id = "表格生成",
        pack = "table",
        title = "表格生成",
        hint = "问一句主题，直接生成一份能下载的 .xlsx（表头、格式都排好）",
        badge = "XLS",
        keywords = listOf("表格", "excel", "xlsx", "csv", "数据", "统计", "清单", "台账", "报表"),
        body = "",
        table = true,
        needsColors = false,
        localAsks = listOf(MentionDraft.LOCAL_THEME),
    )

    /**
     * 文案撰写：第四条「App 现场装配」的路，也是唯一一条**用悬浮表单**收需求的。
     *
     * 用户要的是"点一下就弹一个表单，填完直接出东西"：类型、卖点、语气这三件事本来就是
     * 并列的一次性输入，用多轮问答问要等三个来回。所以这里 `localAsks` 是空的——
     * 该问的都由那张表单一次问掉了。
     */
    private val DOC = ChatMention(
        id = "文案撰写",
        title = "文案撰写",
        hint = "填一个小表单（类型/卖点/语气），直接出文案，并存一份 md 供下载",
        badge = "MD",
        keywords = listOf("文案", "脚本", "分镜", "小红书", "抖音", "公众号", "标题", "口播", "新闻稿"),
        body = "",
        copy = Copywriting.FIELDS,
        needsColors = false,
        localAsks = emptyList(),
    )

    /**
     * 海报生成：**全程在手机上画出来**，底图是内嵌的私密资源，不走内核。
     *
     * 为什么不上内核：底图是用户点名要保密的资源，发给模型就等于出了这台手机；而合成本身
     * 是确定性的绘制（读图 → 调色 → 排字 → 导出），手机自己几十毫秒就做完。
     * 所以它的 [body] 是空的：点它弹出的是自己那张悬浮表单（见 PosterRenderer 与 ChatSection）。
     */
    private val ART = ChatMention(
        id = "海报生成",
        pack = "poster",
        title = "海报生成",
        hint = "挑一个风格、写标题，直接画出海报并存进相册",
        badge = "ART",
        keywords = listOf("海报", "宣传海报", "宣传图", "封面图", "主视觉", "banner", "kv"),
        body = "",
        poster = true,
    )

    private val GAME = ChatMention(
        id = "游戏演示",
        title = "游戏演示",
        hint = "做一个手机浏览器里能玩的单页小游戏",
        badge = "GAME",
        keywords = listOf("游戏", "demo", "小游戏", "玩法", "示意", "h5"),
        body = """
【任务：游戏示意 demo】
请在本对话的工作目录里，做一个可以直接在手机浏览器里玩的单页小游戏。
优先套模板：先看工作目录下有没有 `_templates/game.html` —— 有就把它整份复制成新文件，只替换 {{...}} 占位符里的文字，不要重写 CSS、不要重排版式；骨架不够用时才补新的块，并沿用同一套配色变量。没有那个文件才自己写。
要求：
1. 单个 HTML 文件，样式和脚本都写在同一个文件里，不引用外部 CDN，断网也能玩；
2. 直接用触摸操作，可点的元素不小于 44px；横竖屏都不要溢出屏幕；
3. 底色 #0B0E14，主色 #00E5FF，画面用简单几何图形画出来即可，不要依赖图片素材；
4. 只做一个 30 秒内能明白怎么玩的核心玩法，不要做关卡、菜单、存档这些；
5. 玩法与主题就是我在这条消息里写的其它文字；
6. 文件名用小写英文加连字符，后缀 .html，保存在工作目录根下；
7. 做完后告诉我文件的完整路径，并用一句话说明怎么玩。
如果我只发了这条模板、没说做什么游戏，先给我 2-3 个简单玩法建议让我挑，不要直接开工。
""".trimIndent()
    )

    /**
     * 菜单里的全部条目，顺序就是显示顺序。
     *
     * 以后要加「字幕生成」「图片批处理」，在这里加一个 ChatMention 就行，
     * 菜单、筛选、展开三处会自动带上，不需要改任何界面代码。
     */
    val all: List<ChatMention> = listOf(WEB, PPT, IMG, SUB, XLS, DOC, ART, GAME)

    // ------------------------------------------------------------------
    // 纯文本工具
    // ------------------------------------------------------------------

    /**
     * 光标前正在输入的那个 @ 词；不在 @ 里就返回 null（菜单关闭）。
     *
     * 只认「最后一个空格或换行之后」的那一段，所以：
     *  - 只打一个 @        -> ""（列出全部）
     *  - 「帮我做 @pp」     -> "pp"（筛出 PPT）
     *  - 「a@b.com」        -> null（第二个 @ 说明用户不是在挑模板）
     *  - 「@网页生成 」     -> null（尾部空格表示已经选完，菜单收起）
     */
    fun query(draft: String): String? {
        val token: String = draft.substringAfterLast(' ').substringAfterLast('\n')
        if (!token.startsWith("@")) return null
        if (token.indexOf('@', 1) >= 0) return null
        return token.drop(1)
    }

    /** 按关键词筛选，空查询给全部。 */
    fun match(q: String, installed: Set<String>? = null): List<ChatMention> {
        // installed 为 null 表示"不按包过滤"（单测和文案用）；有值时只留下装得上的条目。
        val pool = if (installed == null) all else all.filter { it.pack == null || it.pack in installed }
        if (q.isBlank()) return pool
        return pool.filter { m: ChatMention ->
            m.id.contains(q, ignoreCase = true) ||
                m.title.contains(q, ignoreCase = true) ||
                m.keywords.any { k: String -> k.contains(q, ignoreCase = true) }
        }
    }

    /** 因为没装包而暂时看不到的条目，菜单底部据此提示一句。 */
    fun hiddenByPacks(installed: Set<String>): List<ChatMention> =
        all.filter { it.pack != null && it.pack !in installed }

    /**
     * 把正在输入的那个 @ 词换成「@标记 + 一个空格」，并把**光标位置**一并算好。
     *
     * 为什么返回的不只是文本：光标不能留给输入框自己记着。Compose 的文本字段在外面
     * 把文本换掉时只换文本、**原样保留原来的选区**。用户敲 `@` 的时候光标在第 1 位，
     * 于是插入「@网页生成 」之后光标还停在第 1 位——接下来打的主题就插到 `@` 和
     * 标记之间，成了「@主题网页生成」（真机上就是这个症状）。位置算在这个纯文本函数里
     * （而不是算在界面上），是为了让它能被单测钉住，而不用靠真机上一眼一眼地看。
     *
     * 尾空格是关键：菜单随即收起，用户的下一句话就是模板要的主题，不用再点一下输入框。
     */
    fun insertion(draft: String, mention: ChatMention): MentionInsertion {
        val cut: Int = draft.length - trailingTokenLength(draft)
        val text: String = draft.take(cut) + "@" + mention.id + " "
        return MentionInsertion(text, text.length)
    }

    /**
     * 草稿里已经套用了哪些模板（按 all 的顺序，天然去重）。
     *
     * 从文本推导，不存额外状态：用户手动打字写 `@PPT生成` 同样算数，删掉它就当没套用。
     */
    fun applied(draft: String): List<ChatMention> {
        return all.filter { m: ChatMention -> draft.contains("@" + m.id) }
    }

    /**
     * 发送前把「@标记」展开成完整指令。
     *
     * 展开发生在本地、在发送之前，所以内核读到的就是这份完整指令；
     * 而 transcript 显示的是实际发出的内容，用户回看时能看到自己到底发了什么。
     *
     * @param skeletonOf 取骨架正文。资源包在 APK 里，读它要 Context，所以从外面传进来；
     *                   单测里传一个假骨架就能把整条装配链钉死。返回空串表示没读到，
     *                   那时指令会退回「让模型自己写」，而不是发出一段空骨架。
     * @param blueprintOf 取 PPT 蓝图正文，同一套道理。
     * @param tablesOf 取表格体裁表；它同样在功能包里，所以也从外面传。空表表示没装包，
     *                 那时表格这条不装配，用户的话原样发出去。
     */
    fun expand(
        text: String,
        skeletonOf: (PageTemplate) -> String = { "" },
        blueprintOf: (DeckTemplate) -> String = { "" },
        tablesOf: () -> List<TableKind> = { emptyList() },
    ): String {
        val draft: MentionDraft = MentionDraft.parse(text)
        val mention: ChatMention = draft.mention ?: return text

        // 带资源包的条目（现在是「网页生成」和「PPT生成」两条）由 App 现场装配：
        // 挑好的模板 + 用户已经打出来的字 + 已选配色，一次拼成完整指令。
        var out: String = text
        if (mention.templates.isNotEmpty()) {
            val template: PageTemplate = draft.template ?: mention.templates.first()
            // 用户写在大白话里的 `地点：图书馆报告厅` 这类行，在本地就填进骨架同名槽位；
            // 摘走这些行之后剩下的，才是要原样交给模型的自由文字。
            val extras: Map<String, String> = DraftTokens.pairs(draft.free)
            val free: String = DraftTokens.freeText(draft.free, extras)
            // 用户没单独写「主题：」时，把他写的第一句短话当主题——这样标题这类槽位
            // 在本地就填好了，不用等模型去猜「这句话里哪个词是标题」。
            val theme: String = draft.theme.ifBlank { shortTheme(free) }
            out = WebTemplates.instruction(
                template = template,
                theme = theme,
                points = draft.points,
                extras = extras,
                free = free,
                skeleton = skeletonOf(template),
                colors = draft.colors ?: ColorSchemes.fallback,
                askBudget = draft.askBudget(),
            )
        } else if (mention.decks.isNotEmpty()) {
            // PPT 这条路的装配：模板（章节蓝图）+ 配色 + 页数 + **用户已确认的大纲**。
            // 大纲是这里最要紧的一份输入——用户逐条改过的东西必须原样落到指令里，
            // 不能变成"再让模型自己排一遍"。
            val deck: DeckTemplate = draft.deck ?: mention.decks.first()
            val blueprint: String = blueprintOf(deck)
            out = DeckTemplates.instruction(
                deck = deck,
                blueprint = blueprint,
                sections = DeckTemplates.parseSections(blueprint),
                colors = draft.colors ?: ColorSchemes.fallback,
                theme = draft.theme.ifBlank { shortTheme(draft.free) },
                pages = draft.effectivePages(),
                rows = draft.outline,
                free = draft.free,
                askBudget = draft.askBudget(),
            )
        } else if (mention.table) {
            // 表格这条路的装配：按主题自己挑一种体裁，把**表头和格式规矩**先摆好。
            // 这里没有任何"等用户确认"的步骤——表格是一次点击就该出东西的东西，
            // 所以它只问主题，问完直接装配出完整指令。
            //
            // 体裁数据在「表格生成」功能包里；没装包时 pick 给 null，这里就不装配，
            // 原样把用户的话发出去（菜单那条本来也被门控挡住了，走到这里是兜底）。
            val picked = TableKinds.pick(text, tablesOf())
            if (picked != null) {
                out = TableKinds.instruction(
                    kind = picked,
                    theme = draft.theme.ifBlank { shortTheme(draft.free) },
                    free = draft.free,
                    askBudget = draft.askBudget(),
                )
            }
        } else if (mention.copy != null) {
            // 文案这条路：表单填的三栏以 `类型：/卖点：/语气：` 的形式躺在正文里，
            // 这里用和其它指令段同一套机制把它们取回来——所以用户手改那一行同样生效。
            val pairs: Map<String, String> = DraftTokens.pairs(draft.free)
            // 「要点」也算额外要求：表单只收三栏，剩下的想补充的走要点那一栏。
            val extra: String = listOf(draft.points.trim(), DraftTokens.freeText(draft.free, pairs))
                .filter { it.isNotBlank() }
                .joinToString("\n")
            out = Copywriting.instruction(
                type = pairs[Copywriting.MARK_TYPE].orEmpty(),
                sellingPoint = pairs[Copywriting.MARK_POINT].orEmpty(),
                tone = pairs[Copywriting.MARK_TONE].orEmpty(),
                extra = extra,
                askBudget = draft.askBudget(),
            )
        }

        // 其余条目仍是「一段固定指令替换掉标记」，用户写的其它文字原样保留。
        // 带资源包的四类在上面已经装配过了，这里必须跳过——否则会拿空的 body
        // 把刚拼好的整段指令抹掉。
        for (m in all) {
            if (m.templates.isNotEmpty() || m.decks.isNotEmpty() ||
                m.table || m.copy != null || m.image || m.poster
            ) {
                continue
            }
            out = out.replace("@" + m.id, m.body)
        }
        return out
    }

    /**
     * 用户没写「主题：」时，拿他写的第一句当主题。
     *
     * 两条限制，都是为了防止把要求当成标题：
     *  - 只在够短的时候才认（一行、不超过 24 字）——长句子是需求描述；
     *  - 带冒号的行跳过，那多半是 `地点：…` 这种要被填进槽位的行，不是标题。
     * 不合用时宁可留着 `{{标题}}` 让模型按上下文补，也不硬塞一个长句进大标题。
     */
    private fun shortTheme(free: String): String {
        val first: String = free.lineSequence()
            .map { it.trim() }
            .firstOrNull { line -> line.isNotEmpty() && !line.contains('：') && !line.contains(':') }
            .orEmpty()
        return if (first.isNotEmpty() && first.length <= 24) first else ""
    }

    private fun trailingTokenLength(draft: String): Int {
        val last: Int = draft.indexOfLast { c: Char -> c == ' ' || c == '\n' }
        return draft.length - (last + 1)
    }
}
