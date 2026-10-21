package dev.termdesk.app.ui

// 这个文件不需要任何 import：它只用 Kotlin 标准库。
// 位置要求：和 ChatSection.kt 放在同一个文件夹（app/src/main/java/dev/termdesk/app/ui/），
// 包名 dev.termdesk.app.ui 就是对的。

/**
 * 一套配色，就是网页模板里 `:root` 那几个变量的值。
 *
 * 为什么把它做成 App 里的数据，而不是让内核自己挑颜色：
 *  - 用户要的是「**在发出去之前**就定下来」，选色是一次点击的事，没必要花一个模型回合；
 *  - 同一套配色要在骨架里填进 5 个变量，让模型自己算容易出现底色和卡片色打架；
 *  - 定死之后，用户改一个词（`配色：墨黑金`）就能整体换一套，不用重写 CSS。
 */
internal data class ColorScheme(
    /** 写进文本里的那个词，也是发送时被解析的键，例如 `科技蓝`。 */
    val id: String,
    /** 面板里的一行说明。 */
    val hint: String,
    /** 页面底色。 */
    val bg: String,
    /** 卡片/区块底色。 */
    val card: String,
    /** 正文颜色。 */
    val text: String,
    /** 次要文字颜色。 */
    val muted: String,
    /** 主色：按钮、强调、标题点缀。 */
    val primary: String,
) {
    /** 画色板圆点用的顺序：底、卡片、主色、正文。 */
    val swatch: List<String> get() = listOf(bg, card, primary, text)
}

/**
 * 配色表的唯一事实来源。
 *
 * 面板、解析、展开三处都读这一份，所以不会出现「面板里选了、指令里没带上」。
 * 加一套配色 = 在这里加一条数据，界面不用改。
 */
internal object ColorSchemes {

    /** 文本里表示「我选了哪一套」的那个前缀。改这里，解析和写回一起变。 */
    const val MARK: String = "配色："

    private val TECH = ColorScheme(
        id = "科技蓝",
        hint = "深色 · 青蓝主色，最通用的科技感",
        bg = "#0B0E14", card = "#12161F", text = "#E6EDF7", muted = "#9BA7BD", primary = "#00E5FF",
    )

    private val INK = ColorScheme(
        id = "墨黑金",
        hint = "深色 · 金色主色，稳重、有质感",
        bg = "#0F0D0A", card = "#1A1712", text = "#F3EDE1", muted = "#A99C84", primary = "#D8B45A",
    )

    private val PAPER = ColorScheme(
        id = "纸白蓝",
        hint = "浅色 · 藏青主色，像正式公文与官网",
        bg = "#F7F8FA", card = "#FFFFFF", text = "#1B2430", muted = "#66748A", primary = "#1F5FBF",
    )

    private val WARM = ColorScheme(
        id = "暖橙",
        hint = "浅色 · 橙色主色，活动、招新、促销",
        bg = "#FFF8F2", card = "#FFFFFF", text = "#2A1F17", muted = "#8A7466", primary = "#F4703A",
    )

    /**
     * 暖金：从用户给的参考 PPT「智能创新」里取的色。
     *
     * 那套片子是白底 + #FFC000 / #FBD35A 的金色，珠光感、偏"创新发布"的调子。
     * 现有的 7 套里没有能对上的：墨黑金是**深色**底，暖橙的橙红又明显更暖更跳，
     * 拿它们去顶会让生成出来的片子一眼就不像原版——所以才补了这一套。
     * 主色取 #C08A00 而不是原图的 #FFC000：金色直接当正文色/按钮底色时对比度不够，
     * 在白底上会糊成一片。
     */
    private val GOLD = ColorScheme(
        id = "暖金",
        hint = "浅色 · 金色主色，创新、发布、路演",
        bg = "#FFFFFF", card = "#FFF7E3", text = "#1C1508", muted = "#8A7550", primary = "#C08A00",
    )

    private val JADE = ColorScheme(
        id = "青绿",
        hint = "深色 · 薄荷绿主色，清爽、校园感",
        bg = "#08120F", card = "#0F1D18", text = "#E3F5EE", muted = "#8FB3A7", primary = "#2FD8A0",
    )

    private val VIOLET = ColorScheme(
        id = "紫罗兰",
        hint = "深色 · 紫色主色，发布会、创意类",
        bg = "#0C0A18", card = "#16132A", text = "#EAE7F8", muted = "#9E97C4", primary = "#9B7BFF",
    )

    private val CHINA = ColorScheme(
        id = "中国红",
        hint = "浅色 · 正红主色，节庆、党建、通知",
        bg = "#FFF7F6", card = "#FFFFFF", text = "#2A1614", muted = "#8A6A66", primary = "#C8102E",
    )

    /**
     * 面板里的全部配色，顺序就是显示顺序。
     *
     * 第一套是缺省：用户完全不选的时候，展开出来的还是原来那套深色科技风，
     * 所以这次改动对「懒得选」的人是零变化。
     */
    val all: List<ColorScheme> = listOf(TECH, INK, PAPER, WARM, GOLD, JADE, VIOLET, CHINA)

    /** 缺省配色。 */
    val fallback: ColorScheme get() = TECH

    /** 按 id 找；找不到就当没选。 */
    fun byId(id: String): ColorScheme? = all.firstOrNull { it.id == id }

    /**
     * 从草稿文本里认出用户选了哪一套。
     *
     * 认三种写法，都是人在手机上真会打出来的：
     *  - `配色：科技蓝`
     *  - `配色:科技蓝`（半角冒号）
     *  - `配色 科技蓝`（空格）
     * 颜色名后面的文字（`配色：科技蓝，别太亮`）不影响识别。
     */
    fun parse(text: String): ColorScheme? {
        val tail: String = afterMark(text) ?: return null
        // 最长匹配优先，免得以后加了「蓝」「科技蓝」两个词时先撞上短的。
        return all.filter { tail.startsWith(it.id) }.maxByOrNull { it.id.length }
    }

    /**
     * 把 `配色：X（底 #… · 主色 #…）` 这一整段从文本里摘掉，返回剩下的正文。
     *
     * 摘掉而不是留着：这段是指令词，不是用户想说的话。展开时它会变成完整的色值块，
     * 留在正文里会让内核看到「配色：科技蓝」这种半截话。
     *
     * 括号那一截也要一起吃掉——[directive] 写回草稿时带着色值，只删到颜色名的话，
     * 改一次配色就会在输入框里留下一串没人认领的 `（底 #0B0E14 · 主色 #00E5FF）`。
     */
    fun strip(text: String): String {
        val start: Int = indexOfMark(text) ?: return text
        val head: Int = markStart(text, start)
        val tail: String = text.substring(head + markLength(text, start))
        val scheme: ColorScheme = all.filter { tail.startsWith(it.id) }.maxByOrNull { it.id.length }
            ?: return text
        var end: Int = head + markLength(text, start) + scheme.id.length
        end = consumeParen(text, end)
        return text.removeRange(start, end)
    }

    /**
     * 写回草稿时用的那一段。
     *
     * **只写名字，不写色号**（用户点名要的）：`配色：科技蓝` 就够了。
     * 上一版会把 `（底 #0B0E14 · 卡片 #12161F · 正文 #E6EDF7 · 主色 #00E5FF）`
     * 一并写进输入框，用户要改自己写的话时，先得跨过那一长串十六进制色号——那是
     * 给内核看的，不是给人看的。色值仍然会进**发给内核的指令**（见 [summary]/[cssVars]），
     * 只是不再污染输入框。
     */
    fun directive(scheme: ColorScheme): String = MARK + scheme.id

    /**
     * 展开进指令里的完整定义，形如：
     * ```
     * --bg:#0B0E14; --card:#12161F; ...
     * ```
     */
    fun cssVars(scheme: ColorScheme): String =
        "--bg:" + scheme.bg + "; --card:" + scheme.card + "; --text:" + scheme.text +
            "; --muted:" + scheme.muted + "; --primary:" + scheme.primary + ";"

    /** 给内核看的一行中文说明。 */
    fun summary(scheme: ColorScheme): String =
        "底色 " + scheme.bg + "，卡片 " + scheme.card + "，正文 " + scheme.text +
            "，次要文字 " + scheme.muted + "，主色 " + scheme.primary

    private fun indexOfMark(text: String): Int? {
        val full: Int = text.indexOf(MARK)
        val half: Int = text.indexOf("配色:")
        return when {
            full >= 0 && half >= 0 -> minOf(full, half)
            full >= 0 -> full
            half >= 0 -> half
            else -> indexOfSpaceForm(text)
        }
    }

    /** `配色 科技蓝` 这种不带冒号的写法。 */
    private fun indexOfSpaceForm(text: String): Int? {
        var from: Int = 0
        while (true) {
            val at: Int = text.indexOf("配色", from)
            if (at < 0) return null
            val tail: String = text.substring(at + 2)
            if (tail.startsWith(" ") || tail.startsWith("　")) return at
            from = at + 2
        }
    }

    /** 「配色」这两个字本身从哪里开始；三种写法都一样。 */
    private fun markStart(text: String, at: Int): Int = at

    /** 标记本身占几个字：带冒号是 3，`配色 科技蓝` 这种带空格的是 2。 */
    private fun markLength(text: String, at: Int): Int = when {
        text.startsWith(MARK, at) -> MARK.length
        text.startsWith("配色:", at) -> 3
        else -> 2
    }

    /** 紧跟在标记后面的中英文括号整段吃掉（[directive] 写的色值就在里面）。 */
    private fun consumeParen(text: String, from: Int): Int {
        var i: Int = from
        while (i < text.length && (text[i] == ' ' || text[i] == '　')) i += 1
        val close: Char = when {
            i < text.length && text[i] == '（' -> '）'
            i < text.length && text[i] == '(' -> ')'
            else -> return from
        }
        val end: Int = text.indexOf(close, i + 1)
        return if (end < 0) from else end + 1
    }

    private fun afterMark(text: String): String? {
        val at: Int = indexOfMark(text) ?: return null
        val from: Int = markStart(text, at) + markLength(text, at)
        return text.substring(from).trimStart(' ', '　')
    }
}
