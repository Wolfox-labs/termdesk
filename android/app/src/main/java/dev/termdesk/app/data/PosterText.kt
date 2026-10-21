package dev.termdesk.app.data

/**
 * 海报上的文字排版——**纯计算，不碰任何 Android 类型**。
 *
 * 单独拎出来的原因：排版是最容易悄悄出错的地方（标题太长就冲出画面、英文被从单词中间
 * 断开、副标题挤成三行），而它一旦和 Bitmap/Canvas 混在一起就只能靠在真机上看图来发现。
 * 这里只做"给一个量宽度的函数，我来决定怎么断行、用多大字号"，于是能在 JVM 单测里
 * 用假的量宽函数把它钉死。
 */
internal object PosterText {

    /**
     * 切成可断行的**词**。
     *
     * 中文逐字（中文本来就能在任意字之间断），拉丁字母 / 数字连成一个词，
     * 空格跟在前一个词后面当断点。这样 `Art` 不会被劈成 `A` + `rts`——
     * 用户上一版看到的就是这个：一行末尾一个 `A`，下一行开头 `rts`。
     */
    private fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        fun flush() { if (word.isNotEmpty()) { out.add(word.toString()); word.setLength(0) } }
        for (ch in s) {
            when {
                ch == '\n' -> { flush(); out.add("\n") }
                ch == ' ' -> { word.append(ch); flush() }
                ch.code < 128 && !ch.isWhitespace() -> word.append(ch)
                else -> { flush(); out.add(ch.toString()) }
            }
        }
        flush()
        return out
    }

    /** 一个词长到一行都放不下时，只能按字符硬拆。 */
    private fun splitLong(token: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in token) {
            if (cur.isNotEmpty() && measure(cur.toString() + ch) > maxWidth) {
                parts.add(cur.toString()); cur.setLength(0)
            }
            cur.append(ch)
        }
        if (cur.isNotEmpty()) parts.add(cur.toString())
        return parts
    }

    /**
     * 按宽度折行。
     *
     * 先按词排，排不下再换行；单个词比整行还宽时才逐字硬拆。
     * 超过 [maxLines] 的部分丢掉，并在最后一行补省略号——宁可省略，也不许冲出版面。
     */
    fun wrap(
        text: String,
        maxWidth: Float,
        maxLines: Int,
        measure: (String) -> Float,
    ): List<String> {
        val s = text.trim().replace("\r", "")
        if (s.isEmpty() || maxLines <= 0) return emptyList()

        val lines = ArrayList<String>()
        val line = StringBuilder()

        fun pushLine() { lines.add(line.toString().trimEnd()); line.setLength(0) }

        for (token in tokens(s)) {
            if (lines.size == maxLines) break
            if (token == "\n") { pushLine(); continue }
            val candidate = line.toString() + token
            if (line.isEmpty() || measure(candidate.trimEnd()) <= maxWidth) {
                line.append(token)
            } else {
                pushLine()
                if (lines.size == maxLines) break
                // 新开一行之后仍然放不下，说明这个词本身超宽，只能拆。
                if (measure(token.trimEnd()) > maxWidth) {
                    val parts = splitLong(token, maxWidth, measure)
                    for ((idx, part) in parts.withIndex()) {
                        if (idx == parts.size - 1) { line.append(part) }
                        else { lines.add(part); if (lines.size == maxLines) break }
                    }
                } else {
                    line.append(token)
                }
            }
        }
        if (lines.size < maxLines && line.isNotEmpty()) pushLine()

        if (lines.size > maxLines) {
            while (lines.size > maxLines) lines.removeAt(lines.size - 1)
        }
        return lines
    }

    /**
     * 整段文字按这个宽度**需要几行**（不做省略）。
     *
     * 单独有这个函数，是因为 [wrap] 超行时会补省略号——那意味着"wrap 出来的行数永远不超过
     * 上限"，如果拿它来判断要不要降字号，就永远不会降，长标题会被截成「…」而不是缩小字号。
     * 这是单测逼出来的：第一版就是这么错的。
     */
    fun lineCount(text: String, maxWidth: Float, measure: (String) -> Float): Int {
        val s = text.trim().replace("\r", "")
        if (s.isEmpty()) return 0
        var lines = 1
        val line = StringBuilder()
        for (token in tokens(s)) {
            if (token == "\n") { lines += 1; line.setLength(0); continue }
            val candidate = line.toString() + token
            if (line.isEmpty() || measure(candidate.trimEnd()) <= maxWidth) {
                line.append(token)
            } else {
                lines += 1
                line.setLength(0)
                if (measure(token.trimEnd()) > maxWidth) {
                    val parts = splitLong(token, maxWidth, measure)
                    lines += (parts.size - 1)
                    line.append(parts.lastOrNull().orEmpty())
                } else {
                    line.append(token)
                }
            }
        }
        return lines
    }

    /**
     * 逐级降字号，直到折行之后每一行都放得下、且**不超行数**。
     *
     * 返回最终字号与它的行。下限 [min] 一定会停——宁可字小，也不许出界或截成省略号。
     */
    fun fit(
        text: String,
        maxWidth: Float,
        maxLines: Int,
        start: Float,
        min: Float,
        step: Float,
        measure: (String, Float) -> Float,
    ): Pair<Float, List<String>> {
        var size = start
        while (true) {
            val need = lineCount(text, maxWidth) { measure(it, size) }
            val lines = wrap(text, maxWidth, maxLines) { measure(it, size) }
            val widest = lines.maxOfOrNull { measure(it, size) } ?: 0f
            if ((need <= maxLines && widest <= maxWidth) || size <= min) {
                return size to lines
            }
            size -= step
        }
    }

    /**
     * 聊天里那句话：`「主标题」海报已绘制完成`。
     *
     * 主标题空着时退回"这张"，免得出现"「」海报已绘制完成"这种空书名号。
     */
    fun caption(title: String): String {
        val t = title.trim().replace('\n', ' ')
        return if (t.isEmpty()) "这张海报已绘制完成" else "「" + t + "」海报已绘制完成"
    }
}
