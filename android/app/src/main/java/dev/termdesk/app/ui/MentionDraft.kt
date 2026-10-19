package dev.termdesk.app.ui

// 这个文件不需要任何 import：它只用 Kotlin 标准库。
// 位置要求：和 ChatSection.kt 放在同一个文件夹（app/src/main/java/dev/termdesk/app/ui/），
// 包名 dev.termdesk.app.ui 就是对的。

/**
 * 输入框里那段话的结构。
 *
 * 为什么要有这个文件：用户要的是「**直接在这段话里**设置配色」，而不是在一个并行的小面板里
 * 配置一堆隐藏状态。所以骨架、配色、主题、要点全部写成文本本身的一部分，能被看见、能被手改、
 * 删掉就等于没选。既然它在文本里，就必须有一套唯一的解析与回写规则——就是这里。
 *
 * 语法（App 自己写回时一行一段，但**解析时写在哪一行都认**）：
 * ```
 * @网页生成 模板：官网首页 配色：科技蓝
 * 主题：校园开放日
 * 要点：时间地点、报名方式、社团展示
 * 帮我做得清爽一点，别太花。
 * ```
 *
 * 四条设计约束：
 *  1. **不丢字**：认不出来的文字一个字都不扔，原样留在 `free` 里；
 *  2. **可手改**：把 `配色：科技蓝` 改成 `配色：墨黑金` 同样生效——解析的是文本本身；
 *  3. **不吞正文**：每个指令段的值只取到本行末尾，所以后面的大白话不会被吸进槽位；
 *  4. **纯函数**：不依赖任何界面状态，所以「点了模板之后文本应该变成什么」能被单测钉死。
 */
internal data class MentionDraft(
    /** 草稿里套用了哪个 `@` 模板。 */
    val mention: ChatMention?,
    /** 选了哪套骨架；网页类按缺省补一个，不会是 null。 */
    val template: PageTemplate?,
    /** 选了哪套配色；null 表示还没问过/还没选。 */
    val colors: ColorScheme?,
    /** `主题：` 后面的值。 */
    val theme: String,
    /** `要点：` 后面的值，原样（还没切成一条一条）。 */
    val points: String,
    /** 指令段之外、用户自己写的全部文字（含 `键：值` 行）。 */
    val free: String,
    /**
     * 选了哪套 PPT 蓝图；非 PPT 类是 null。
     *
     * 和 [template]（网页骨架）并列，而不是合并成一个字段：两者的资源形态不一样
     * （网页是一份 HTML，PPT 是一份章节蓝图），合成一个会到处写 when 分支。
     * 但两者的**解析规则**是同一套（同一个 `模板：` 标记 + 最长匹配）。
     */
    val deck: DeckTemplate? = null,
    /** `页数：` 后面的值（PPT 专用）。空串表示还没问过，或用户跳过了。 */
    val pages: String = "",
    /**
     * 已确认的大纲（PPT 专用）。
     *
     * 它就是「确认后才开始生成」那道闸门：空 = 还没确认，非空 = 可以发了。
     * 存在这里、同时又写进文本，是刻意的——用户因此能直接改其中某一条。
     */
    val outline: List<OutlineRow> = emptyList(),
) {
    /** 这套骨架要不要先定配色。 */
    val needsColors: Boolean get() = mention?.needsColors == true

    /**
     * App 本地已经固定问掉的问题数。
     *
     * 用户给的上限是「最多问五次」，本地点掉几个就少几个——这里算的是那笔账，
     * 而不是把追问全交给模型，因为配色和主题这两件事本地一次点击就够了。
     */
    val localAsks: Int
        get() {
            val m: ChatMention = mention ?: return 0
            var n = 0
            if (needsColors) n += 1
            if (m.localAsks.contains(LOCAL_THEME)) n += 1
            if (m.localAsks.contains(LOCAL_PAGES)) n += 1
            return n
        }

    /** 还能让模型追问几次。 */
    fun askBudget(ceiling: Int = ASK_CEILING): Int = (ceiling - localAsks).coerceAtLeast(0)

    /**
     * 发送之前，App 还欠用户哪一个问题。
     *
     * 顺序**由数据决定**：照 [ChatMention.localAsks] 里写的先后走。这一点是刻意的——
     * 网页那条路的顺序是「配色 → 主题」，PPT 那条路是「主题 → 配色 → 页数」，
     * 两者不一样，但都不该写死在这个函数里：模板自己最清楚该先问什么。
     *
     * 返回 null 表示该问的都问完了（大纲那道闸门另有 [needsOutlineAsk]）。
     */
    fun nextAsk(): String? {
        val m: ChatMention = mention ?: return null
        for (ask in m.localAsks) {
            when (ask) {
                LOCAL_COLORS -> if (needsColorAsk()) return LOCAL_COLORS
                LOCAL_THEME -> if (needsThemeAsk()) return LOCAL_THEME
                LOCAL_PAGES -> if (needsPagesAsk()) return LOCAL_PAGES
            }
        }
        return null
    }

    /** 配色还没定。定了就不算欠。 */
    fun needsColorAsk(): Boolean = needsColors && colors == null

    /** 这一条是 PPT 类：它需要先形成大纲并让用户确认。 */
    val needsDecks: Boolean get() = mention?.decks?.isNotEmpty() == true

    /** 页数还没填，而且这一条确实要问页数。 */
    fun needsPagesAsk(): Boolean =
        mention?.localAsks?.contains(LOCAL_PAGES) == true && parsedPages() == null

    /** 用户写的页数；写不出正整数就当没写。 */
    fun parsedPages(): Int? = pages.trim().toIntOrNull()?.takeIf { it > 0 }

    /** 真正拿去铺大纲的页数：用户没写就用这套模板的默认页数。 */
    fun effectivePages(): Int = parsedPages() ?: deck?.defaultPages ?: DEFAULT_PAGES

    /**
     * 大纲还没确认。
     *
     * 这就是用户要的那道闸门——「确认后 开始生成」。它和配色一样是硬拦，
     * 因为大纲决定每一页讲什么；跳过它等于让模型自己重排一遍，用户刚才改的那几条就白改了。
     */
    fun needsOutlineAsk(): Boolean = needsDecks && outline.isEmpty()

    /**
     * 主题还没定。
     *
     * 只在用户**什么都没写**的时候才算欠：如果他已经在正文里写清楚了要做什么，
     * 再拦一次就只是添乱。App 仍然会把这一问问出来（固定问一次），
     * 但那一次是给人确认的，不挡路。
     */
    fun needsThemeAsk(): Boolean =
        mention?.localAsks?.contains(LOCAL_THEME) == true && theme.isBlank() && free.isBlank()

    /** 备齐了没有：配色和大纲这两项会挡住发送（页数是软问，跳过就用默认值）。 */
    fun ready(): Boolean = !needsColorAsk() && !needsOutlineAsk()

    companion object {
        /** 本地固定问的第一个问题。 */
        const val LOCAL_COLORS: String = "配色"

        /** 本地固定问的第二个问题。 */
        const val LOCAL_THEME: String = "主题"

        /** PPT 类固定问的第三个问题：这次做几页。 */
        const val LOCAL_PAGES: String = "页数"

        /** 连默认页数都取不到时的兜底（理论上到不了，[DeckTemplate.defaultPages] 总有值）。 */
        const val DEFAULT_PAGES: Int = 12

        /** 追问总上限：本地固定问 + 交给模型的额度 ≤ 5。 */
        const val ASK_CEILING: Int = 5

        /** 各指令段的写法，解析和回写共用一份。 */
        private const val MARK_THEME = "主题："
        private const val MARK_POINTS = "要点："
        private const val MARK_PAGES = "页数："

        /** 同一行里遇到这些标记就说明这一段到头了。 */
        /**
         * 同一行里遇到这些标记就说明这一段到头了。
         *
         * 全角和半角都列进来：只列全角的话，`主题：数字媒体 要点:专业介绍` 里的
         * `要点:` 就切不断主题，整行会被当成主题——真机上 `<h1>` 就是这么被污染的。
         */
        private val ALL_MARKS = listOf(
            MARK_THEME, "主题:",
            MARK_POINTS, "要点:",
            MARK_PAGES, "页数:",
            WebTemplates.MARK, "模板:",
            ColorSchemes.MARK, "配色:",
        )

        /**
         * 解析整段草稿。
         *
         * 指令段写在哪一行都行——用户是手打进来的，不是按模板填表。认不出来的文字
         * 原样进 `free`，所以「帮我把标题写短一点」这种话不会被当成指令丢掉。
         */
        fun parse(text: String): MentionDraft {
            val mention: ChatMention = ChatMentions.applied(text).firstOrNull()
                ?: return MentionDraft(null, null, null, "", "", text.trim())

            // 标记本身先摘掉：留着的话它会被当成正文的一部分。
            var rest: String = text.replace("@" + mention.id, " ")

            // 大纲块**必须最先抽**。它是一整片多行区块，而下面的 take() 是按行取值的：
            // 万一某一条大纲的内容里写着「要点：」，先抽主题要点就会把那一行从中间切断，
            // 于是这一条大纲既进不了大纲、又变成半截正文。顺序在这里是正确性的一部分。
            val outline: List<OutlineRow> = if (mention.decks.isEmpty()) emptyList()
            else DeckTemplates.parseOutline(rest)
            if (outline.isNotEmpty()) rest = DeckTemplates.stripOutline(rest)

            val theme: String
            val points: String
            val pages: String
            run {
                val (value, left) = take(rest, MARK_THEME)
                theme = value
                rest = left
            }
            run {
                val (value, left) = take(rest, MARK_POINTS)
                points = value
                rest = left
            }
            run {
                val (value, left) = take(rest, MARK_PAGES)
                pages = value
                rest = left
            }
            rest = WebTemplates.strip(rest)
            rest = DeckTemplates.strip(rest)
            rest = ColorSchemes.strip(rest)

            // 骨架/蓝图/配色从**原文**里认，不从剪过的 rest 里认：认完之后由各自的 strip 负责剪。
            val template: PageTemplate? = if (mention.templates.isEmpty()) null
            else WebTemplates.parse(text) ?: mention.templates.first()
            val deck: DeckTemplate? = if (mention.decks.isEmpty()) null
            else DeckTemplates.parse(text) ?: DeckTemplates.pick(text, mention.decks)
            val colors: ColorScheme? = if (!mention.needsColors) null else ColorSchemes.parse(text)

            return MentionDraft(
                mention = mention,
                template = template,
                colors = colors,
                theme = theme,
                points = points,
                free = tidy(rest),
                deck = deck,
                pages = pages,
                outline = outline,
            )
        }

        /**
         * 按当前选择重新渲染整段草稿。
         *
         * 每改一次配色或主题都走这里，而不是在字符串上做局部替换：局部替换迟早会
         * 留下「两个配色段」或「半截括号」这种脏东西，全量重渲染则永远只有一个答案。
         *
         * 一行一段是刻意的：手机上横着看一行太长的指令很痛苦，而「主题」「要点」
         * 本来就是两件不同的事，分行之后用户也能一眼看出该改哪一行。
         *
         * 大纲是个例外——它本来就是多行的一块，而且**写成文本**正是用户能直接改
         * 其中某一条的原因；放在正文末尾，用户在大纲后面接着写的话也不会被吞
         * （块按「页数|章节|内容」的格式自终止）。
         */
        fun render(draft: MentionDraft): String {
            val mention: ChatMention = draft.mention ?: return draft.free.trim()
            val lines = ArrayList<String>()
            val head = StringBuilder("@" + mention.id)
            draft.template?.let { head.append(' ').append(WebTemplates.directive(it)) }
            draft.deck?.let { head.append(' ').append(DeckTemplates.directive(it)) }
            if (mention.needsColors) {
                draft.colors?.let { head.append(' ').append(ColorSchemes.directive(it)) }
            }
            lines.add(head.toString())
            if (draft.theme.isNotBlank()) lines.add(MARK_THEME + draft.theme.trim())
            if (draft.pages.isNotBlank()) lines.add(MARK_PAGES + draft.pages.trim())
            if (draft.points.isNotBlank()) lines.add(MARK_POINTS + draft.points.trim())
            if (draft.outline.isNotEmpty()) lines.add(DeckTemplates.renderOutline(draft.outline))
            if (draft.free.isNotBlank()) lines.add(draft.free.trim())
            return lines.joinToString("\n")
        }

        /** 换配色。 */
        fun withColors(draft: MentionDraft, scheme: ColorScheme): MentionDraft =
            draft.copy(colors = scheme)

        /** 换骨架。 */
        fun withTemplate(draft: MentionDraft, template: PageTemplate): MentionDraft =
            draft.copy(template = template)

        /** 换 PPT 蓝图。 */
        fun withDeck(draft: MentionDraft, deck: DeckTemplate): MentionDraft =
            draft.copy(deck = deck)

        /** 写入页数。 */
        fun withPages(draft: MentionDraft, pages: Int): MentionDraft =
            draft.copy(pages = pages.toString())

        /**
         * 写入已确认的大纲。
         *
         * 传空表等于「取消确认」——用户把大纲那几行删掉就能回到未确认状态，
         * 这与「删掉指令段就等于没选」的既有规矩一致。
         */
        fun withOutline(draft: MentionDraft, rows: List<OutlineRow>): MentionDraft =
            draft.copy(outline = rows)

        /** 写入主题。 */
        fun withTheme(draft: MentionDraft, theme: String): MentionDraft =
            draft.copy(theme = theme.trim())

        /** 写入要点。 */
        fun withPoints(draft: MentionDraft, points: String): MentionDraft =
            draft.copy(points = points.trim())

        /**
         * 取出 `mark` 后面的值，连同「删掉这一段之后剩下的文本」一起返回。
         *
         * 值的右边界是**本行末尾**，不是整段文本的末尾：用户完全可能在写了
         * `主题：校运会` 之后另起一行写 `记得做得活泼一点`，后者是要求，不是主题的一部分。
         *
         * 全角冒号和半角冒号都认。真机上就是这么栽的：使用者用半角打了 `要点:专业介绍`，
         * 而这里只认全角，于是这一整行被当成了主题——`<h1>` 直接变成
         * 「数字媒体 要点:专业介绍、实验室开放」。手机上打标点用什么都是随手的，
         * 让人为了对齐解析器去挑冒号，是解析器的错，不是人的错。
         */
        private fun take(text: String, mark: String): Pair<String, String> {
            val half: String = mark.dropLast(1) + ":"
            val fullAt: Int = text.indexOf(mark)
            val halfAt: Int = if (half == mark) -1 else text.indexOf(half)
            val at: Int = when {
                fullAt < 0 -> halfAt
                halfAt < 0 -> fullAt
                else -> minOf(fullAt, halfAt)
            }
            if (at < 0) return "" to text
            val width: Int = if (text.startsWith(mark, at)) mark.length else half.length
            var to: Int = text.length
            val nl: Int = text.indexOf('\n', at)
            if (nl >= 0) to = nl
            val from: Int = at + width
            for (other in ALL_MARKS) {
                if (other == mark) continue
                val p: Int = text.indexOf(other, from)
                if (p in from until to) to = p
            }
            val value: String = text.substring(from, to).trim()
            return value to text.removeRange(at, to)
        }

        /** 去掉摘走指令段之后留下的空行和行尾空白，让正文读起来还是一段话。 */
        private fun tidy(text: String): String =
            text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
