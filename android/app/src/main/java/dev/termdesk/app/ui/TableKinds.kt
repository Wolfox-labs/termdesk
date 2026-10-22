package dev.termdesk.app.ui

import dev.termdesk.app.data.FeatureSource
import org.json.JSONArray
import org.json.JSONObject

// 这个文件只用 Kotlin 标准库 + org.json：表格这条路不碰 Android API，
// 所以整套逻辑能在纯 JVM 单测里钉死。

/**
 * 一种常见表格的"经验"：这类表格通常有哪些表头、行怎么排、格式怎么定。
 *
 * 为什么要有它：用户要的是「问完主题，你自己按经验想表头、表框和各项内容」。
 * 把常见体裁的表头先摆出来，就是把这件"经验"从模型的临场发挥变成 App 的既有知识——
 * 和网页/PPT 那两条路同一个思路：**App 先把已知的填好，剩下的才交给模型**。
 *
 * 它只是**起点**不是枷锁：指令里明说了可以按主题增删列。
 */
internal data class TableKind(
    /** 面板与指令里用的名字，例如 `台账`。 */
    val id: String,
    /** 小角标。 */
    val badge: String,
    /** 一行说明：这类表格长什么样。 */
    val hint: String,
    /** 自动选型的搜索词。 */
    val keywords: List<String>,
    /** 这类表格惯用的表头，按列顺序。 */
    val headers: List<String>,
    /** 表框与格式上的惯例（一行一条，直接写进指令）。 */
    val conventions: List<String>,
)

/**
 * 常见表格体裁表 + 指令装配。
 *
 * 体裁数据不再编在 APK 里：它随「表格生成」功能包下发（`data/table-kinds.json`），
 * 所以补一种体裁不用发新版 App。读不到包就是空列表——那条 @ 菜单本来也被门控挡着。
 *
 * 加一种体裁 = 在包里加一条数据，界面和别的代码都不用动。
 */
internal object TableKinds {

    /** 包内相对路径，和 pack 里 `data/` 的布局一致。 */
    const val PACK_PATH = "data/table-kinds.json"

    /** 从功能包读全部体裁；读不到、或 JSON 坏了，都是空列表，不抛异常。 */
    fun load(source: FeatureSource): List<TableKind> {
        val text = runCatching {
            source.open(PACK_PATH)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val kinds = root.optJSONArray("kinds") ?: JSONArray()
        return (0 until kinds.length()).mapNotNull { index ->
            val json = kinds.optJSONObject(index) ?: return@mapNotNull null
            val id = json.optString("id").trim()
            val headers = json.optJSONArray("headers").toStringList()
            if (id.isEmpty() || headers.isEmpty()) return@mapNotNull null
            TableKind(
                id = id,
                badge = json.optString("badge").ifBlank { id },
                hint = json.optString("hint"),
                keywords = json.optJSONArray("keywords").toStringList(),
                headers = headers,
                conventions = json.optJSONArray("conventions").toStringList(),
            )
        }
    }

    /**
     * 按主题自己挑一种体裁。
     *
     * 和 [DeckTemplates.pick] 同一套规矩：关键词命中算 3 分，最长匹配（也就是同一个词
     * 命中多条）自然胜出；全不命中就用第一条。空的体裁表给 null，调用方自己退。
     * 纯函数，可测。
     */
    fun pick(text: String, from: List<TableKind>): TableKind? {
        val first = from.firstOrNull() ?: return null
        val hay: String = text.lowercase()
        var best: TableKind = first
        var bestScore: Int = 0
        for (kind in from) {
            var score: Int = 0
            for (k in kind.keywords) {
                val key: String = k.trim().lowercase()
                if (key.isNotEmpty() && hay.contains(key)) score += 3
            }
            if (score > bestScore) {
                bestScore = score
                best = kind
            }
        }
        return best
    }

    /**
     * 拼出真正发给内核的整段指令。
     *
     * 和网页/PPT 那两条路的差别：表格没有配色、没有页数、也不需要用户确认大纲——
     * 用户要的是"问完主题就直接出东西"。所以这里的重点是**把表头和格式规矩说死**，
     * 让产出立刻能用，而不是出来一个还得自己调列的半成品。
     */
    fun instruction(
        kind: TableKind,
        theme: String,
        free: String,
        askBudget: Int,
    ): String {
        val sb = StringBuilder()
        sb.append("【任务：生成表格 · ").append(kind.id).append("】\n")
        sb.append("请在本对话的工作目录里，做一份**可以直接下载打开**的表格文件。\n")
        sb.append("文件名用小写英文加连字符，后缀 .xlsx，保存在工作目录根下，")
            .append("建议叫 table.xlsx（或按主题取个更贴切的英文名）。\n\n")

        sb.append("【要做什么】\n")
        sb.append("主题：").append(theme.ifBlank { "（我还没写，你按下面的体裁拟一个合理的）" })
            .append('\n')
        sb.append("体裁：").append(kind.id).append(" —— ").append(kind.hint).append('\n')
        if (free.isNotBlank()) {
            sb.append("我另外交代的：").append(free.trim()).append('\n')
        }
        sb.append('\n')

        sb.append("【表头 · 起点，可以按主题增删列】\n")
        sb.append(kind.headers.joinToString(" | ")).append('\n')
        sb.append("先用上面这套表头；如果主题明显需要别的列（比如要「联系人」「地点」「编号」），")
            .append("就补上或替换，**但不要把列数堆到十几列**——手机上横着看不过来，")
            .append("一般 6~9 列最合适。\n\n")

        sb.append("【表框与格式 · 常规要求，照做】\n")
        sb.append("1. 用 openpyxl 之类的库生成真正的 .xlsx；当前环境没有就**自己 ")
            .append("`python -m pip install openpyxl` 装上再继续**（允许装，不用问我），")
            .append("确实装不上（离线等）才退回 .csv 并告诉我一声；\n")
        sb.append("2. **第一行是表头：整行加粗（`Font(bold=True)`）、居中、浅灰底纹，并**冻结首行**；")
            .append("交付前回读一次确认表头的 `bold` 真的是 True——")
            .append("上一版就是明细表头忘了加粗、汇总表却加了；\n")
        sb.append("3. 列宽按内容给足，不要都挤在默认宽度上；全表加细边框；\n")
        sb.append("4. 对齐：文字左对齐，数字右对齐，表头居中；\n")
        sb.append("5. 数字格式：金额用千分位两位小数，百分比用一位小数带 %，日期统一 yyyy-mm-dd；\n")
        sb.append("6. **数字要真的存成数字**，不要存成文本；编号、电话号码这类不参与计算的")
            .append("反而要存成文本，免得丢前导零；\n")
        sb.append("7. 字体统一用「微软雅黑」或「等线」11pt，正文与表头同一套字体；\n")
        for ((i, c) in kind.conventions.withIndex()) {
            sb.append(8 + i).append(". ").append(c).append('\n')
        }
        val next: Int = 8 + kind.conventions.size
        sb.append(next).append(". 需要分类汇总时**单独开一个工作表**，不要和明细混在一张表里；\n")
        sb.append(next + 1).append(". 行数按主题给到「够用」：清单/台账类至少 12~20 行，")
            .append("让人觉得是份真表，而不是三行示例；\n")
        sb.append(next + 2).append(". 用户没给的真实信息（公司名、金额、联系方式）**按主题自己拟**")
            .append("一组前后一致的合理值，让这份表看起来是能直接用的成品，")
            .append("**不要写“待补充”“占位”“示例”这类字眼**；\n")
        sb.append(next + 3).append(". 做完后告诉我 .xlsx 的完整路径，以及行数与列数。\n")
        sb.append(next + 4).append(". **每一张工作表的表头都要加粗**，空单元格不要留 ")
            .append("`inlineStr` 之类的类型痕迹，工作表不要有多余的空列。\n\n")

        sb.append("【关于追问】\n")
        if (askBudget > 0) {
            sb.append("如果确实还缺关键信息（比如要统计哪一段时间、面向谁），")
                .append("你最多可以再问我 ").append(askBudget).append(" 个问题。\n")
            sb.append("一次只问一个，等我说完再问下一个；我可能会分好几次回答，")
                .append("把每次回答累积起来，问满或问清之后立刻开工，不要重复问同一件事。\n")
        } else {
            sb.append("不要再来问我了，直接按上面的信息把东西做出来，缺的细节你按常理补。\n")
        }
        sb.append("做完后告诉我文件的完整路径，并用一句话说明我可以改哪些地方。\n")
        return sb.toString()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optString(index).trim().takeIf { it.isNotEmpty() }
        }
    }
}
