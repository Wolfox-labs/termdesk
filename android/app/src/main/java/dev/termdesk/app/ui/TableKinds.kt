package dev.termdesk.app.ui

// 这个文件只用 Kotlin 标准库：表格这条路不需要读资源包（表头就几列，直接写在数据里），
// 也不需要碰 Android API，所以整套逻辑能在纯 JVM 单测里钉死。

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
 * 加一种体裁 = 在这里加一条数据，界面和别的代码都不用动。
 */
internal object TableKinds {

    private val LEDGER = TableKind(
        id = "台账",
        badge = "台账",
        hint = "一行一件事，按日期排，能一眼看出谁办的、办到哪一步",
        keywords = listOf("台账", "记录", "登记", "流水", "明细", "日志"),
        headers = listOf("序号", "日期", "事项", "经办人", "数量", "金额", "状态", "备注"),
        conventions = listOf(
            "按日期升序排，同一天的排在一起；",
            "「金额」右对齐、千分位两位小数；「状态」只用少量固定取值（如 已完成/进行中/待办）；",
            "「序号」从 1 连续编到底。",
        ),
    )

    private val LIST = TableKind(
        id = "清单",
        badge = "清单",
        hint = "一批东西逐条列清：名称、规格、数量、单价、金额、供应商",
        keywords = listOf("清单", "采购", "物料", "备件", "盘点", "库存", "报价"),
        headers = listOf("序号", "名称", "规格型号", "数量", "单位", "单价", "金额", "供应商", "备注"),
        conventions = listOf(
            "「金额」= 数量 × 单价，写成公式或至少与两列对得上；",
            "最后加一行「合计」，只合计金额与数量；",
            "数量、单价、金额三列都是数字格式（金额两位小数）。",
        ),
    )

    private val SCHEDULE = TableKind(
        id = "排期",
        badge = "排期",
        hint = "分阶段排任务：谁负责、什么时候开始结束、依赖谁",
        keywords = listOf("排期", "进度", "计划", "里程碑", "工期", "甘特", "任务"),
        headers = listOf("序号", "阶段", "任务", "负责人", "开始日期", "结束日期", "工期(天)", "前置任务", "状态"),
        conventions = listOf(
            "按开始日期升序；「工期(天)」用日期相减或直接写数字；",
            "日期统一 yyyy-mm-dd；「前置任务」写序号，便于看出依赖；",
            "同一阶段的任务连着放，阶段之间留一行或加浅色底分组。",
        ),
    )

    private val BUDGET = TableKind(
        id = "预算",
        badge = "预算",
        hint = "钱花在哪：科目、明细、预算、实际、差额、占比",
        keywords = listOf("预算", "费用", "报销", "开支", "成本", "经费", "决算"),
        headers = listOf("序号", "费用科目", "明细项", "预算金额", "实际支出", "差额", "占比", "备注"),
        conventions = listOf(
            "「差额」= 预算 − 实际；「占比」= 该项 / 合计，写成百分比一位小数；",
            "金额全部千分位两位小数，负差额标红或加负号；",
            "末尾加「合计」行，合计预算、实际、差额三列。",
        ),
    )

    private val STATS = TableKind(
        id = "统计汇总",
        badge = "统计",
        hint = "同一批指标横向比：本期、上期、环比、累计、目标、完成率",
        keywords = listOf("统计", "汇总", "报表", "同比", "环比", "分析", "指标", "数据"),
        headers = listOf("项目", "本期", "上期", "环比", "累计", "目标", "完成率", "说明"),
        conventions = listOf(
            "「环比」= (本期 − 上期) / 上期，写成百分比一位小数，上升下降要有正负号；",
            "「完成率」= 本期 / 目标，百分比格式；",
            "指标按重要性排序，最重要的放最上面。",
        ),
    )

    private val ROSTER = TableKind(
        id = "人员名单",
        badge = "名单",
        hint = "谁在里面：姓名、编号、部门班级、职务、联系方式",
        keywords = listOf("名单", "通讯录", "人员", "成员", "学生", "员工", "花名册", "分工"),
        headers = listOf("序号", "姓名", "学号/工号", "部门/班级", "职务", "联系方式", "加入日期", "状态"),
        conventions = listOf(
            "「学号/工号」「联系方式」按文本存——它们不参与计算，存成数字会丢前导零；",
            "同部门的排在一起；",
            "联系方式这一列列宽给足，别显示成 ####。",
        ),
    )

    private val CHECKLIST = TableKind(
        id = "检查表",
        badge = "检查",
        hint = "逐项对照标准：查什么、标准是什么、现状如何、不合格怎么办",
        keywords = listOf("检查", "巡检", "验收", "自查", "整改", "安全", "点检", "核对"),
        headers = listOf("序号", "检查项", "标准要求", "现状/实测", "是否合格", "问题描述", "整改措施", "责任人", "期限"),
        conventions = listOf(
            "「是否合格」只用 合格/不合格/不适用 三种取值；",
            "不合格的行整行加浅红底，方便一眼抓出来；",
            "按检查项的重要性排序，安全相关的排最前面。",
        ),
    )

    private val COMPARE = TableKind(
        id = "对照表",
        badge = "对照",
        hint = "几个方案横向比：一个维度一行，最后一列给结论",
        keywords = listOf("对照", "对比", "选型", "评比", "优缺点", "比较", "差异"),
        headers = listOf("对比维度", "方案一", "方案二", "方案三", "结论/推荐", "说明"),
        conventions = listOf(
            "第一列是维度，后面每列一个方案，最后一列给倾向性结论；",
            "每格只写短句，写不下就换行，不要塞整段；",
            "关键差异（价格、工期、风险）用加粗或底色标出来。",
        ),
    )

    /**
     * 全部体裁，顺序就是自动选型打平时的优先序。
     *
     * 第一条是缺省：主题里什么线索都没有时用它——"台账"是最通用的一种，
     * 日期 + 事项 + 经办人 + 状态，几乎任何主题都接得住。
     */
    val all: List<TableKind> = listOf(LEDGER, LIST, SCHEDULE, BUDGET, STATS, ROSTER, CHECKLIST, COMPARE)

    val fallback: TableKind get() = all.first()

    fun byId(id: String): TableKind? = all.firstOrNull { it.id == id }

    /**
     * 按主题自己挑一种体裁。
     *
     * 和 [DeckTemplates.pick] 同一套规矩：关键词命中算 3 分，最长匹配（也就是同一个词
     * 命中多条）自然胜出；全不命中就用缺省。纯函数，可测。
     */
    fun pick(text: String, from: List<TableKind> = all): TableKind {
        val hay: String = text.lowercase()
        var best: TableKind = from.firstOrNull() ?: fallback
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
}
