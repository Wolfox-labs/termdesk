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
     * 按宽度折行。
     *
     * 中文逐字断行（中文本来就可以在任意字之间断），英文数字也会被逐字量宽，
     * 所以中英混排不会算错。超过 [maxLines] 的部分丢掉，并在最后一行补省略号——
     * 宁可省略，也不许冲出版面。
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
        var i = 0
        var consumed = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\n') {
                lines.add(line.toString())
                line.setLength(0)
                i += 1
                consumed += 1
                if (lines.size == maxLines) break
                continue
            }
            if (line.isNotEmpty() && measure(line.toString() + ch) > maxWidth) {
                lines.add(line.toString())
                line.setLength(0)
                if (lines.size == maxLines) break
                continue
            }
            line.append(ch)
            i += 1
            consumed += 1
        }
        if (lines.size < maxLines && line.isNotEmpty()) lines.add(line.toString())

        val total = s.count { it != '\n' }
        if (consumed < total || (consumed == total && i < s.length)) {
            if (lines.isEmpty()) {
                lines.add("…")
            } else {
                var last = lines[lines.size - 1]
                while (last.isNotEmpty() && measure(last + "…") > maxWidth) last = last.dropLast(1)
                lines[lines.size - 1] = last + "…"
            }
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
        for (ch in s) {
            if (ch == '\n') {
                lines += 1
                line.setLength(0)
                continue
            }
            if (line.isNotEmpty() && measure(line.toString() + ch) > maxWidth) {
                lines += 1
                line.setLength(0)
            }
            line.append(ch)
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
