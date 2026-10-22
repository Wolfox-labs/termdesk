package dev.termdesk.app.ui

import dev.termdesk.app.data.FeatureSource

// 除了读资源包要用的 AssetManager，这个文件只用 Kotlin 标准库。
// 位置要求：和 ChatSection.kt 放在同一个文件夹（app/src/main/java/dev/termdesk/app/ui/），
// 包名 dev.termdesk.app.ui 就是对的。

/**
 * 一份 PPT 蓝图里的一个章节。
 *
 * 「这部分几页、这部分写什么」的**来源**。用户把页数一说，App 就按这里的权重把页数
 * 分下去，再把用户自己写的要点塞进正文各章——大纲因此在本地就成型了，
 * 不用花一个模型回合去问。
 */
internal data class DeckSection(
    /** 章节名，例如「现状与数据」。 */
    val name: String,
    /** 这一章默认写什么，直接进大纲那一列。 */
    val hint: String,
    /** 页数权重：余量按它分，越大分到的页越多。 */
    val weight: Int = 1,
    /** 版式：封面 / 目录 / 数据 / 要点 / 时间线 / 结尾…… */
    val layout: String = "要点",
    /**
     * 这一章**建议摆什么元素**：图表 / 表格 / 数字条 / 图文 / 时间轴 / 示意图……
     *
     * 留空就按 [DeckTemplates.visualOf] 从版式推一个默认值。这一列是拿来治
     * 「做出来的片子全是纯文字」的：实测用户给的那 7 份参考 PPT，
     * 图片/图表/表格的用量都不低（例如芯片科技 17 页里有 6 个图表、
     * 无人机那份有 3 个表格 4 个图表），而模型自己写的时候最容易退化成通篇文字。
     */
    val visual: String = "",
) {
    /** 封面、目录、结尾不吃用户写的要点——它们是结构页，不是内容页。 */
    val isContent: Boolean
        get() = layout != "封面" && layout != "目录" && layout != "结尾"
}

/**
 * 一份 PPT 模板（蓝图）的**元数据**。
 *
 * 和网页那套的分工完全一致：元数据在 Kotlin 里（面板要显示、自动选型要打分），
 * 正文在 `assets/templates/ppt/` 里（章节结构是内容，改它不该动代码）。
 * 要加一套 PPT 模板 = 往资源目录丢一个 .txt + 在这里加一条数据，界面不动。
 */
internal data class DeckTemplate(
    /** 写进文本里的那个词，也是发送时被解析的键，例如 `芯片科技`。 */
    val id: String,
    /** 资源包里的相对路径。 */
    val assetPath: String,
    /** 面板里的一行说明。 */
    val hint: String,
    /** 小角标，纯文本。 */
    val badge: String,
    /** 自动选型的关键词：命中越多越贴合。 */
    val keywords: List<String>,
    /** 这套模板默认配哪一套配色。 */
    val colors: ColorScheme?,
    /** 建议字体（写进指令，让模型照用，别自己挑）。 */
    val fonts: String,
    /** 页数面板上的几个预设。 */
    val pagePresets: List<Int>,
    /** 用户啥也没选时的页数。 */
    val defaultPages: Int,
    /** 这套模板的调性，既写进指令，也参与自动选型打分。 */
    val style: String,
)

/**
 * 大纲里的一条：第几页到第几页、哪一章、这几页讲什么、什么版式。
 *
 * 用户能**单独改任意一条**，这就是「每部分几页 这部分写什么内容，每条分列清楚」。
 */
internal data class OutlineRow(
    val from: Int,
    val to: Int,
    val section: String,
    val content: String,
    val layout: String = "要点",
    /**
     * 这一条建议摆什么元素（图表/表格/图文……）。
     *
     * 从文本里读回来的大纲没有这一列——那时按 [DeckTemplates.visualOf] 从版式推，
     * 所以用户手改过的那一行也照样有视觉建议，不会因为"没存"就丢。
     */
    val visual: String = "",
) {
    /** 这几页一共几页。 */
    val pages: Int get() = to - from + 1

    /** 页数区间：一页就写「3」，多页写「3-5」。 */
    val range: String get() = if (from == to) from.toString() else from.toString() + "-" + to
}

/**
 * PPT 模板资源包的清单 + 本地装配逻辑。
 *
 * 全是纯函数：不读文件、不碰 Android API。蓝图正文由调用方通过参数传进来
 * （和 [WebTemplates.fill] 收 `skeleton` 是同一个路子），所以整套逻辑能在单测里钉死。
 */
internal object DeckTemplates {

    /** 文本里表示「我选了哪套模板」的那个前缀。**与 [WebTemplates] 共用同一个词**，
     *  靠两边的 id 不重名区分；下面有一条测试专门钉这件事。 */
    const val MARK: String = "模板："

    /** 大纲区块的头一行。后面紧跟若干「页数区间|章节|内容」。 */
    const val OUTLINE: String = "大纲："

    /** 页数下限：封面 + 至少一章 + 结尾，再少就不成片了。 */
    const val MIN_PAGES: Int = 3

    /** 页数上限：再多手机上就没人看了，而且多半是把"页数"当别的填了。 */
    const val MAX_PAGES: Int = 80

    /**
     * 名字配对的门槛：一条要点和章节名**至少共用两个字**，才算"这条是在说那一章"。
     *
     * 一个字太松：「要点1」和「需要的支持」都含「要」，会被错配到一起；
     * 而真配得上的（「推广应用」对「应用与推广」）会共用四个字，隔得很开。
     */
    private const val MATCH_FLOOR: Int = 2

    /** 配对时不算数的字：连接词和标点不携带意思，算进去只会制造假配对。 */
    private val NAME_STOP: Set<Char> = setOf('与', '和', '的', '及', '、', '，', '：', ' ', '　')

    // ── 第一阶段的三套通用蓝图 ────────────────────────────────────────
    // 这三套是「先把功能跑通」用的，章节结构取自最常见的三种汇报场景。
    // 第二阶段会按用户上传的 7 个参考 PPT 逐个补充更具体的模板。

    private val REPORT = DeckTemplate(
        id = "工作汇报",
        assetPath = "templates/ppt/report.txt",
        hint = "封面 + 目录 + 现状 + 问题 + 方案 + 计划 + 结尾",
        badge = "汇报",
        keywords = listOf("汇报", "总结", "述职", "周报", "月报", "复盘", "进展", "年度", "季度"),
        colors = ColorSchemes.byId("科技蓝"),
        fonts = "思源黑体, HarmonyOS Sans SC, OPPOSans, 微软雅黑",
        pagePresets = listOf(8, 12, 16, 20),
        defaultPages = 12,
        style = "深色底、数据优先、每页一个结论；标题短，正文只留短句，不放整段文字",
    )

    private val PROPOSAL = DeckTemplate(
        id = "方案提案",
        assetPath = "templates/ppt/proposal.txt",
        hint = "封面 + 背景 + 目标 + 方案 + 取舍 + 排期 + 结尾",
        badge = "方案",
        keywords = listOf("方案", "提案", "立项", "计划", "规划", "投标", "策划", "可行性"),
        colors = ColorSchemes.byId("墨黑金"),
        fonts = "思源黑体, HarmonyOS Sans SC, OPPOSans, 微软雅黑",
        pagePresets = listOf(10, 14, 18, 24),
        defaultPages = 14,
        style = "稳重、有质感；先讲问题再讲方案，关键取舍要单独占一页",
    )

    private val LESSON = DeckTemplate(
        id = "教学课件",
        assetPath = "templates/ppt/lesson.txt",
        hint = "封面 + 学习目标 + 知识点 + 案例 + 练习 + 小结",
        badge = "课件",
        keywords = listOf("教学", "课件", "培训", "课程", "讲课", "知识", "学习", "教育"),
        colors = ColorSchemes.byId("纸白蓝"),
        fonts = "思源黑体, HarmonyOS Sans SC, OPPOSans, 微软雅黑",
        pagePresets = listOf(8, 12, 16, 20),
        defaultPages = 12,
        style = "浅色底、字号偏大、留白充足；一页只讲一个知识点，末尾给练习",
    )

    // ── 从用户上传的参考 PPT 里学来的三套 ──────────────────────────────
    // 这三套的章节顺序、配色、字体都是从真片子量出来的（不是凭空写的），
    // 量法见 _dev/analyze_pptx.py：从每页的底色和形状填充取样，
    // 因为其中几个文件的 theme 就是 PowerPoint 默认色板，照 theme 写会写歪。

    /**
     * 大赛答辩（学的《智教未来》数智教育创新大赛（动画版），8 页）。
     *
     * 真实取到的：浅色底、蓝 #024FCC + 淡蓝 #ECF5FE，组合与图片用得极多，
     * 也就是"一页一张图 + 一句结论"。所以它的配色直接用「纸白蓝」——
     * 那套的白底藏青主色和它几乎是一回事，没必要为此再加一套。
     */
    private val CONTEST = DeckTemplate(
        id = "大赛答辩",
        assetPath = "templates/ppt/contest.txt",
        hint = "现状问题 + 设计思路 + 分阶段实现 + 作品演示 + 效果验证 + 反思",
        badge = "答辩",
        keywords = listOf("大赛", "竞赛", "答辩", "参赛", "评审", "作品", "数智", "数媒", "挑战杯"),
        colors = ColorSchemes.byId("纸白蓝"),
        fonts = "思源黑体, OPPOSans, HarmonyOS Sans SC, 微软雅黑",
        pagePresets = listOf(9, 12, 16),
        defaultPages = 12,
        style = "浅色底、蓝主色、字大留白足；一页一个论点配一张示意图；" +
            "答辩节奏快，标题就直接写结论",
    )

    /**
     * 创新方案（学的「智能创新」，20 页）。
     *
     * 真实取到的：白底 + 金色 #FFC000 / #FBD35A，标题用粗宋体（思源宋体 CN Heavy）。
     * 现有配色里没有能对上的——墨黑金是深色底、暖橙偏橙红——所以补了一套「暖金」。
     */
    private val IDEA = DeckTemplate(
        id = "创新方案",
        assetPath = "templates/ppt/idea.txt",
        hint = "问题现象 + 原因 + 核心创意 + 它新在哪 + 落地步骤",
        badge = "创新",
        keywords = listOf("创新", "创意", "点子", "灵感", "构想", "新品", "发布", "设计"),
        colors = ColorSchemes.byId("暖金"),
        fonts = "思源宋体 CN Heavy, 思源黑体, 微软雅黑",
        pagePresets = listOf(10, 14, 18),
        defaultPages = 14,
        style = "浅色底、暖金主色、标题用粗宋体；图多字少、节奏明快；一页只讲一个想法",
    )

    /**
     * 创业路演（学的「星空未来」，23 页）。
     *
     * 章节顺序是照着那份片子的真实页序提炼的：市场分析 → 行业痛点 → 产品介绍 →
     * 核心技术 → 功能原理 → 科技成果 → 财务/盈利 → 社会价值 → 未来规划 → 专家顾问。
     * 底色近黑 + 紫 #9558FD，标题几乎全用「优设标题黑」，所以配色直接用「紫罗兰」。
     */
    private val PITCH = DeckTemplate(
        id = "创业路演",
        assetPath = "templates/ppt/pitch.txt",
        hint = "市场分析 + 行业痛点 + 产品 + 核心技术 + 商业模式 + 未来规划 + 团队",
        badge = "路演",
        keywords = listOf(
            "路演", "创业", "融资", "商业计划", "投资", "盈利", "市场", "团队",
            "bp", "pitch",
        ),
        colors = ColorSchemes.byId("紫罗兰"),
        fonts = "优设标题黑, 思源黑体, HarmonyOS Sans SC, 微软雅黑",
        pagePresets = listOf(12, 16, 20, 24),
        defaultPages = 16,
        style = "深色底、紫色主色、字大对比强；先讲市场再讲产品，数据与图表优先，一页一个论点",
    )

    /**
     * 资源包里的全部模板，顺序就是面板里的显示顺序，也是自动选型打平时的优先序。
     *
     * 通用三套排在前面：**没有任何关键词命中时用第一套**，所以那个缺省必须是一套
     * 什么主题都接得住的（工作汇报），不能变成"某个专项模板"。
     */
    /**
     * 项目申报（学的「深蓝科技风无人机」，22 页，青 #00FFFF / #00FFF6，思源黑体 CN Bold）。
     *
     * 它和「方案提案」的区别是**外部要钱**而不是内部立项：开头就先摆政策依据，
     * 中间必须有竞品分析、获奖与已交付项目，结尾是申报诉求而不是"要谁拍板"。
     * 所以关键词也刻意避开「方案」，用「申报/政策/课题/招标」这一组。
     */
    private val PROJECT = DeckTemplate(
        id = "项目申报",
        assetPath = "templates/ppt/project.txt",
        hint = "项目背景 + 政策依据 + 痛点 + 产品 + 竞品 + 成果 + 财务 + 团队",
        badge = "申报",
        keywords = listOf(
            "项目申报", "申报", "申请书", "专项资金", "课题", "招标", "政策", "可研",
        ),
        colors = ColorSchemes.byId("科技蓝"),
        fonts = "思源黑体 CN Bold, 思源黑体, 微软雅黑",
        // 它学的那份片子是**六章十四节**，所以页数下限本来就高（14 章）；
        // 预设从 14 起，避免给用户一个点了就会被夹上去的数字。
        pagePresets = listOf(14, 18, 24),
        defaultPages = 18,
        style = "深色底、青色主色；事实优先，政策与数据都要能查到出处；一页一个论点",
    )

    /**
     * 企业实力（学的「深蓝深邃基因安全」，20 页）。
     *
     * 看清页序才知道它不是技术答辩，而是**机构介绍**：扶持项目 → 研发历程 →
     * 专利 → 数据 → 推广到 99 家 → 合作共赢。视觉是近黑底 + 米金，故配色用「墨黑金」。
     */
    private val COMPANY = DeckTemplate(
        id = "企业实力",
        assetPath = "templates/ppt/company.txt",
        hint = "机构简介 + 发展历程 + 技术与专利 + 数据成绩 + 资质扶持 + 推广合作",
        badge = "实力",
        keywords = listOf(
            "企业", "公司", "机构", "简介", "实力", "资质", "荣誉", "历程", "招商", "研究报告",
        ),
        colors = ColorSchemes.byId("墨黑金"),
        fonts = "阿里妈妈数黑体, 阿里巴巴普惠体, 思源黑体, 微软雅黑",
        pagePresets = listOf(8, 12, 16),
        defaultPages = 12,
        style = "近黑底、米金主色、字很大；用数字和资质说话，不要形容词堆砌",
    )

    /**
     * 成果发布（学的「创新科技」，20 页，深色底 + 青 #68F0FE / #0BF9FE）。
     *
     * 那套片子的重心是"我们做出来了"：关键要点 → 专利与论文 → 技术壁垒 →
     * 创始人 → 各大媒体争相报道。所以它比「技术方案」更对外、更宣传。
     */
    private val LAUNCH = DeckTemplate(
        id = "成果发布",
        assetPath = "templates/ppt/launch.txt",
        hint = "关键要点 + 技术壁垒 + 数据实测 + 团队创始人 + 媒体报道",
        badge = "发布",
        keywords = listOf("发布", "发布会", "成果", "报道", "论文", "专利", "亮相", "新品发布"),
        colors = ColorSchemes.byId("科技蓝"),
        fonts = "HarmonyOS Sans SC, 优设标题黑, 思源黑体, 微软雅黑",
        pagePresets = listOf(10, 14, 18),
        defaultPages = 14,
        style = "深色底、青色主色、对比强；先给结论再给证据，关键指标做成图表",
    )

    /**
     * 技术方案（学的「芯片科技」，17 页，浅色底 + 青 #68F0FE / #15F7F9）。
     *
     * 原片是"目录分两大章 + 观点页 + 文本页 + 图表页"的技术评审写法，
     * 提炼成：需求 → 架构 → 关键技术 → 参数表 → 性能对比 → 验证 → 风险。
     * 它是这几份参考里少见的**浅色底科技片**，正好补上"技术评审"这个场景。
     */
    private val TECH = DeckTemplate(
        id = "技术方案",
        assetPath = "templates/ppt/tech.txt",
        hint = "需求目标 + 总体架构 + 关键技术 + 参数规格 + 性能对比 + 验证",
        badge = "技术",
        keywords = listOf(
            "技术方案", "技术", "架构", "原理", "参数", "规格", "性能", "研发", "评测", "指标",
        ),
        colors = ColorSchemes.byId("纸白蓝"),
        fonts = "微软雅黑, 优设标题黑, 思源黑体",
        pagePresets = listOf(10, 14, 18, 24),
        defaultPages = 14,
        style = "浅色底、藏青主色；架构图与参数表为主，术语要准确，一页只讲一个模块",
    )

    /**
     * 资源包里的全部模板，顺序就是面板里的显示顺序，也是自动选型打平时的优先序。
     *
     * 通用三套排在前面：**没有任何关键词命中时用第一套**，所以那个缺省必须是一套
     * 什么主题都接得住的（工作汇报），不能变成"某个专项模板"。
     * 后面七套是从用户上传的参考 PPT 里学来的，越靠后的越专。
     */
    val all: List<DeckTemplate> =
        listOf(REPORT, PROPOSAL, LESSON, CONTEST, IDEA, PITCH, PROJECT, COMPANY, LAUNCH, TECH)

    /** 一套都没有时的兜底。 */
    val fallback: DeckTemplate get() = all.first()

    // ── 资源包读取 ────────────────────────────────────────────────────

    /**
     * 从 APK 的资源包里读蓝图正文。
     *
     * 读不到返回空串——那时 [instruction] 会退回「按大纲自己设计版式」，
     * 而不是发一段半截结构。宁可退化成"没模板"，也不要产出"坏模板"。
     */
    fun readBlueprint(source: FeatureSource, deck: DeckTemplate): String =
        runCatching {
            // 蓝图随「PPT 生成」功能包下发，不在 APK 里；读不到就是没装包。
            source.open(deck.assetPath)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull().orEmpty()

    /**
     * 把蓝图正文解析成章节表。
     *
     * 格式刻意做得极简（每行 `章节: 名称|内容提示|权重|版式`），因为这份解析必须
     * 在**纯 JVM 单测**里跑：用 JSON 就得引 Android SDK 里的 org.json，
     * 那会让整个测试环境跟着变重。逐行文本和仓库里其它解析器（[DraftTokens]）一致。
     *
     * - 以 `#` 开头的行是注释；
     * - 权重不是正整数就按 1 算，不能因为一行手滑就让整套模板失效；
     * - 版式留空按「要点」算。
     */
    fun parseSections(blueprint: String): List<DeckSection> {
        val out = ArrayList<DeckSection>()
        for (raw in blueprint.split('\n')) {
            val line: String = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // 取**最靠前**的那个冒号当分隔符，而不是优先找全角冒号：
            // `章节: 方案细节 | 每一块：谁来做 | 3` 这种行，正文里带一个全角冒号，
            // 优先找全角就会把键读成「章节: 方案细节 | 每一块」，整行被丢掉。
            val at: Int = listOf(line.indexOf('：'), line.indexOf(':'))
                .filter { it >= 0 }
                .minOrNull() ?: continue
            if (line.substring(0, at).trim() != "章节") continue
            val parts: List<String> = line.substring(at + 1).split('|').map { it.trim() }
            val name: String = parts.getOrNull(0).orEmpty()
            if (name.isEmpty()) continue
            out.add(
                DeckSection(
                    name = name,
                    hint = parts.getOrNull(1).orEmpty(),
                    weight = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: 1,
                    layout = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "要点",
                    visual = parts.getOrNull(4).orEmpty(),
                ),
            )
        }
        return out
    }

    // ── 认得出来 / 摘得掉 ─────────────────────────────────────────────

    fun byId(id: String): DeckTemplate? = all.firstOrNull { it.id == id }

    /**
     * 从草稿里认出选了哪套模板。
     *
     * 与 [WebTemplates.parse] 同一套规则（最长匹配优先），因为两边的标记是同一个词。
     */
    fun parse(text: String): DeckTemplate? {
        val at: Int = indexOfMark(text) ?: return null
        val tail: String = text.substring(at + MARK.length)
        return all.filter { tail.startsWith(it.id) }.maxByOrNull { it.id.length }
    }

    /** 把 `模板：X` 这一小段摘掉。 */
    fun strip(text: String): String {
        val at: Int = indexOfMark(text) ?: return text
        val tail: String = text.substring(at + MARK.length)
        val deck: DeckTemplate = all.filter { tail.startsWith(it.id) }.maxByOrNull { it.id.length }
            ?: return text
        return text.removeRange(at, at + MARK.length + deck.id.length)
    }

    /** 写回草稿时用的那一段。 */
    fun directive(deck: DeckTemplate): String = MARK + deck.id

    /** 建议的文件名主干，取自资源文件名（report.txt → report）。 */
    fun fileNameStem(deck: DeckTemplate): String {
        val slash: Int = deck.assetPath.lastIndexOf('/')
        val dot: Int = deck.assetPath.lastIndexOf('.')
        val from: Int = slash + 1
        return if (dot > from) deck.assetPath.substring(from, dot) else deck.assetPath.substring(from)
    }

    // ── 自动选型 ──────────────────────────────────────────────────────

    /**
     * 按主题/要点/正文自己挑一套更贴合的模板。
     *
     * 用户的要求是「让 App 根据主题、内容自己决定选择更加贴合的那一套」，所以这件事
     * 必须发生在**本地、零延迟**：一次字符串扫描就够了，不值得花一个模型回合。
     *
     * 记分规则刻意简单、可预测：模板关键词命中一次算 3 分，风格词命中算 1 分；
     * 全都不命中就用第一套（缺省）。同分时排在前面的赢——顺序就是 [all] 的顺序，
     * 所以「哪套更常用」可以靠调整顺序表达，而不用搞一套权重表。
     */
    fun pick(text: String, from: List<DeckTemplate> = all): DeckTemplate {
        val hay: String = text.lowercase()
        var best: DeckTemplate = from.firstOrNull() ?: fallback
        var bestScore: Int = 0
        for (deck in from) {
            var score: Int = 0
            for (k in deck.keywords) {
                val key: String = k.trim().lowercase()
                if (key.isNotEmpty() && hay.contains(key)) score += 3
            }
            for (w in styleWords(deck.style)) {
                if (w.length >= 2 && hay.contains(w)) score += 1
            }
            if (score > bestScore) {
                bestScore = score
                best = deck
            }
        }
        return best
    }

    /** 把风格那句话切成词，只用来打分，不参与任何产出。 */
    private fun styleWords(style: String): List<String> =
        style.split('、', '，', ',', '；', ';', ' ', '　', '。', '·')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ── 大纲：本地合成 ────────────────────────────────────────────────

    /**
     * 把「主题 + 要点 + 页数」铺成一份大纲。
     *
     * 两条硬规矩：
     *  1. **每章至少一页**，且所有章节的页数加起来**正好等于**用户要的页数——
     *     等价于"页数说了算"，用户不会看到 8 页的请求变成 9 页；
     *  2. 余量按权重用**最大余数法**分，而不是简单取整：后者会让余数被丢掉，
     *     总和就不等于用户要的页数了。这条单独有测试钉着。
     *
     * 页数比章节数还少时**夹到章节数**（封面和结尾不能没有），并在界面上说清楚，
     * 不静默改数。
     */
    fun compose(
        sections: List<DeckSection>,
        theme: String,
        points: String,
        pages: Int,
    ): List<OutlineRow> {
        if (sections.isEmpty()) return emptyList()
        val total: Int = clampPages(pages)

        // 页数比章节还少时**先裁章节**，而不是把页数夹上去：
        // 用户说"做一个 6 页的"就该拿到 6 页，把 6 夹成 14 是另一种不听话。
        val kept: List<DeckSection> = pruneSections(sections, total)

        // 结构页（封面/目录/结尾）永远各占一页，多出来的页数按**权重**分给内容页。
        // 权重就是那套参考 PPT 里这一章真实占的页数——见各蓝图头部记的实测页序——
        // 所以比例是学来的，不是拍的。
        val contentIdx: List<Int> = kept.indices.filter { kept[it].isContent }
        val forContent: Int = (total - (kept.size - contentIdx.size)).coerceAtLeast(contentIdx.size)
        val weights: List<Int> = contentIdx.map { kept[it].weight.coerceAtLeast(1) }
        val sumW: Int = weights.sum().coerceAtLeast(1)
        val quota: List<Double> = weights.map { forContent.toDouble() * it / sumW }
        // 每章保底一页：一份片子不能因为权重小就整章消失。
        val each: MutableList<Int> = quota.map { it.toInt().coerceAtLeast(1) }.toMutableList()
        var left: Int = forContent - each.sum()
        val byRemainder: List<Int> = contentIdx.indices.sortedWith(
            compareByDescending<Int> { quota[it] - each[it] }.thenBy { it },
        )
        var k = 0
        while (left > 0 && byRemainder.isNotEmpty()) {
            each[byRemainder[k % byRemainder.size]] += 1
            left -= 1
            k += 1
        }
        // 保底一页可能把总数顶超（章节多、页数少时），从最厚的那章往下扣回来，
        // 保证"用户要几页就是几页"这条永远成立。
        while (left < 0) {
            val most: Int = contentIdx.indices
                .filter { each[it] > 1 }
                .maxByOrNull { each[it] } ?: break
            each[most] -= 1
            left += 1
        }
        val add: List<Int> = kept.indices.map { i ->
            val at: Int = contentIdx.indexOf(i)
            if (at < 0) 0 else each[at] - 1
        }

        // 用户写的要点落进内容页。两趟：
        //   第一趟**按名字配对**——「推广应用」应当落到「应用与推广」上，而不是
        //   从最上面那章顺次往下发。这件事真机上看得出来：五条要点配八章时，
        //   顺次发会让"推广应用"落在"资质与荣誉"那一章上，一眼就是错的。
        //   第二趟把没配上对的按顺序填进还空着的内容页，保证一条都不丢。
        val bullets: List<String> = WebTemplates.bulletPoints(points)
        val assigned: MutableMap<Int, String> = LinkedHashMap()
        val used: MutableSet<Int> = HashSet()
        for ((bi, bullet) in bullets.withIndex()) {
            var best: Int = -1
            var bestScore: Int = MATCH_FLOOR - 1
            for (si in contentIdx) {
                if (assigned.containsKey(si)) continue
                val score: Int = nameScore(bullet, kept[si].name)
                if (score > bestScore) {
                    bestScore = score
                    best = si
                }
            }
            if (best >= 0) {
                assigned[best] = bullet
                used.add(bi)
            }
        }
        val leftovers: MutableList<Int> = ArrayList()
        for ((bi, bullet) in bullets.withIndex()) if (bi !in used) leftovers.add(bi)
        var li = 0
        for (si in contentIdx) {
            if (assigned.containsKey(si)) continue
            if (li >= leftovers.size) break
            assigned[si] = bullets[leftovers[li]]
            li += 1
        }

        val rows = ArrayList<OutlineRow>()
        var page = 1
        for ((i, s) in kept.withIndex()) {
            val count: Int = 1 + add[i]
            var content: String = s.hint
            if (s.layout == "封面" && theme.isNotBlank()) content = theme
            val matched: String? = assigned[i]
            if (s.isContent && matched != null) {
                // 两样都留着：前半句说这一章是干什么的（模板 know-how），
                // 后面接用户自己写的那条。只留用户那句，大纲就看不出结构了；
                // 只留模板那句，用户会觉得自己写的字没被接住。
                content = if (s.hint.isBlank()) matched else s.hint + "：" + matched
            }
            rows.add(OutlineRow(page, page + count - 1, s.name, content, s.layout, s.visual))
            page += count
        }

        // 要点比内容页还多时，多出来的塞进最后一个内容页，**一个字都不丢**。
        if (li < leftovers.size) {
            val idx: Int = rows.indices.lastOrNull { kept[it].isContent } ?: -1
            if (idx >= 0) {
                val rest: List<String> = leftovers.drop(li).map { bullets[it] }
                rows[idx] = rows[idx].copy(
                    content = rows[idx].content + "；" + rest.joinToString("、"),
                )
            }
        }
        return rows
    }

    /**
     * 页数不够时先裁章节。
     *
     * 规矩：**封面和结尾一定留**（没有这两样的演示文稿不成片），中间的按权重从重到轻留，
     * 同权重时靠前的先留，最后按原顺序摆回去。低权重的结构页（目录）因此会最先被裁掉——
     * 这也符合直觉：页数紧的时候，先砍目录，不砍内容。
     *
     * 这条正是"用户写的页数和我们学的那套不一样"时的出路：学来的是 22 页的片子，
     * 用户要 8 页，那就得靠裁章节把 8 页排满，而不是硬塞成 22 页。
     */
    private fun pruneSections(sections: List<DeckSection>, pages: Int): List<DeckSection> {
        if (sections.size <= pages) return sections
        if (pages <= 2 || sections.size <= 2) return sections
        val head: DeckSection = sections.first()
        val tail: DeckSection = sections.last()
        val middle: List<DeckSection> = sections.subList(1, sections.size - 1)
        val slots: Int = pages - 2
        if (slots >= middle.size) return sections
        val chosen: List<DeckSection> = middle.withIndex()
            .sortedWith(compareByDescending<IndexedValue<DeckSection>> { it.value.weight }.thenBy { it.index })
            .take(slots)
            .sortedBy { it.index }
            .map { it.value }
        return listOf(head) + chosen + listOf(tail)
    }

    /**
     * 一条要点和一个章节名有多像：共同字的个数。
     *
     * 用**字集合**而不是子串或编辑距离，是因为中文短语的语序经常变
     * （用户写「推广应用」、章节叫「应用与推广」），子串匹配会完全落空，
     * 而字集合一眼就看出是同一件事。连接词（与/和/的/及）先剔掉，它们不携带意思。
     */
    private fun nameScore(bullet: String, section: String): Int {
        val b: Set<Char> = bullet.filter { it !in NAME_STOP }.toSet()
        val s: Set<Char> = section.filter { it !in NAME_STOP }.toSet()
        return b.intersect(s).size
    }

    /** 把大纲渲染成能存进草稿的文本块（含头一行）。 */
    fun renderOutline(rows: List<OutlineRow>): String {
        if (rows.isEmpty()) return ""
        val sb = StringBuilder(OUTLINE)
        for (r in rows) {
            sb.append('\n').append(r.range).append('|').append(r.section).append('|').append(r.content)
        }
        return sb.toString()
    }

    /**
     * 从草稿里把大纲块抽出来。
     *
     * 块是**自终止**的：头一行正好是 `大纲：`，后面连续若干行必须长成 `页数|章节|内容`，
     * 第一行不合格式就结束。这样用户在大纲后面接着写的大白话不会被吞掉。
     *
     * 解析顺序上必须**先抽它、再抽主题/要点**：内容里万一出现「要点：」这样的字眼，
     * 先抽主题要点就会把这一行从中间切断。
     */
    fun parseOutline(text: String): List<OutlineRow> {
        val lines: List<String> = text.split('\n')
        var head = -1
        for ((i, raw) in lines.withIndex()) {
            val t: String = raw.trim()
            if (t == OUTLINE || t == "大纲:") {
                head = i
                break
            }
        }
        if (head < 0) return emptyList()
        val rows = ArrayList<OutlineRow>()
        for (i in head + 1 until lines.size) {
            val r: OutlineRow = parseRow(lines[i].trim()) ?: break
            rows.add(r)
        }
        return rows
    }

    /** 一行大纲；不合格式返回 null。 */
    private fun parseRow(line: String): OutlineRow? {
        val parts: List<String> = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val rangeRaw: String = parts[0].trim()
        val dash: Int = rangeRaw.indexOf('-')
        val from: Int = if (dash > 0) rangeRaw.substring(0, dash).trim().toIntOrNull() ?: return null
        else rangeRaw.toIntOrNull() ?: return null
        val to: Int = if (dash > 0) rangeRaw.substring(dash + 1).trim().toIntOrNull() ?: from else from
        val section: String = parts[1].trim()
        if (section.isEmpty() || from <= 0 || to < from) return null
        return OutlineRow(from, to, section, parts[2].trim(), layoutOf(section))
    }

    /**
     * 行里没存版式（用户手改过的行就是纯文本），按章节名猜一个。
     *
     * 猜错的代价很小：版式只用于指令里的一句提示，不影响大纲本身的页数分配。
     */
    private fun layoutOf(section: String): String = when {
        section.contains("封面") -> "封面"
        section.contains("目录") -> "目录"
        section.contains("结尾") || section.contains("总结") || section.contains("结论") -> "结尾"
        else -> "要点"
    }

    /** 把大纲块从正文里摘掉，剩下的才是用户自己的话。 */
    fun stripOutline(text: String): String {
        val lines: List<String> = text.split('\n')
        var head = -1
        for ((i, raw) in lines.withIndex()) {
            val t: String = raw.trim()
            if (t == OUTLINE || t == "大纲:") {
                head = i
                break
            }
        }
        if (head < 0) return text
        var end: Int = head + 1
        while (end < lines.size && parseRow(lines[end].trim()) != null) end += 1
        val kept = ArrayList<String>()
        kept.addAll(lines.subList(0, head))
        kept.addAll(lines.subList(end, lines.size))
        return kept.joinToString("\n")
    }

    /** 大纲一共几条。面板上显示「N 条」。 */
    fun outlineRowCount(text: String): Int = parseOutline(text).size

    /** 预览条上的一行字：模板 · 配色 · 页数 · 章节数 · 主题。 */
    fun summaryLine(
        deck: DeckTemplate,
        colors: ColorScheme?,
        theme: String,
        pages: String,
        outline: List<OutlineRow>,
    ): String {
        val sb = StringBuilder()
        sb.append(deck.id).append(" · ").append(colors?.id ?: "配色未选")
        // 页数要写**实际页数**，不是用户敲的那个数：章节比页数多时会被夹上去，
        // 那时候写"12 页"而大纲排了 14 页，用户一眼就看出自相矛盾。
        // 大纲已经铺好时以大纲为准——那才是真正会生成出来的页数。
        val asked: Int = pages.trim().toIntOrNull() ?: deck.defaultPages
        val n: Int = outline.lastOrNull()?.to ?: asked
        sb.append(" · ").append(n).append(" 页")
        if (outline.isNotEmpty()) sb.append(" · ").append(outline.size).append(" 条大纲")
        if (theme.isNotBlank()) sb.append(" · 主题 ").append(theme)
        return sb.toString()
    }

    /**
     * 实际能排出来的页数。
     *
     * 下限是 3（封面 + 至少一章 + 结尾），上限 80——再多手机上就没人看了，
     * 而且多半是把"页数"当别的填了。**页数不再以章节数为下限**：
     * 章节比页数多时由 [pruneSections] 裁章节，用户要几页就是几页。
     */
    fun clampPages(pages: Int): Int = pages.coerceIn(MIN_PAGES, MAX_PAGES)

    /**
     * 从版式推一个"这一页该摆什么"。
     *
     * 这份对照表是拿用户给的 7 份参考 PPT 量出来的：它们没有一份是通篇文字的，
     * 图片、图表、表格的用量都不低（芯片科技 17 页里 6 个图表、无人机那份 3 个表格
     * 4 个图表、基因安全有 1 表 1 图）。而模型自己写的时候最容易退化成纯文字，
     * 所以大纲里每一行都明写建议元素。
     */
    fun visualOf(layout: String): String = when (layout) {
        "封面" -> "大标题 + 一行说明"
        "目录" -> "编号列表"
        "数据" -> "数字条或图表"
        "对比" -> "左右对照或表格"
        "时间线" -> "时间轴"
        "结尾" -> "一句话结论"
        else -> "图标卡 + 短句"
    }

    // ── 指令装配 ──────────────────────────────────────────────────────

    /**
     * 拼出真正发给内核的整段指令。
     *
     * @param blueprint 蓝图正文；为空表示资源没读到，退回「按大纲自己设计版式」
     * @param sections  蓝图解析出来的章节表（只用于给出结构说明）
     * @param rows      用户**已确认**的大纲
     */
    fun instruction(
        deck: DeckTemplate,
        blueprint: String,
        sections: List<DeckSection>,
        colors: ColorScheme,
        theme: String,
        pages: Int,
        rows: List<OutlineRow>,
        free: String,
        askBudget: Int,
    ): String {
        val sb = StringBuilder()
        sb.append("【任务：生成 PPT · ").append(deck.id).append("】\n")
        sb.append("请在本对话的工作目录里，做出一个可以直接交给别人的演示文稿。\n")
        sb.append("文件名用小写英文加连字符，后缀 .pptx，保存在工作目录根下，建议叫 ")
            .append(fileNameStem(deck)).append(".pptx。\n\n")

        sb.append("【配色 · 已由我在手机上选定，直接照用，不要另配】\n")
        sb.append(ColorSchemes.summary(colors)).append('\n')
        sb.append("页面里的底色、卡片、正文、次要文字、强调色都从这几个值取，")
            .append("不要自己再发明一套颜色：\n")
        sb.append("  底色 ").append(colors.bg).append("；卡片 ").append(colors.card)
            .append("；正文 ").append(colors.text).append("；次要 ").append(colors.muted)
            .append("；主色 ").append(colors.primary).append("\n\n")

        sb.append("【规格】\n")
        // 页数以**大纲**为准：大纲是用户逐条确认过的，那才是真正会做出来的页数。
        // 传进来的 `pages` 只是用户敲的意向值，章节多的时候它已经不成立了
        // （要 12 页但模板有 14 章 → 实际 14 页）。两处数字不一致会把模型带偏。
        val total: Int = rows.lastOrNull()?.to ?: pages
        sb.append("16:9；一共 ").append(total).append(" 页，页数按下面的大纲走，不要多也不要少。\n")
        sb.append("先检查当前环境里有没有 python-pptx：有就用它生成真正的 .pptx；")
            .append("没有就**自己用 `python -m pip install python-pptx` 装上再继续**——")
            .append("这件事允许你做，不用等我同意，也不要因为缺库就退回一份 Markdown 大纲交差；")
            .append("确实装不上（离线等）才退回来告诉我。\n")
        sb.append("**生成脚本写在本对话工作目录里**（例如 `build_deck.py`，用 shell 写文件即可），")
            .append("不要在工作目录之外的地方创建或编辑文件——那边的写文件权限需要我逐次批准，")
            .append("我这边批不了，整件事就会卡死在这一步。\n")
        sb.append("字体优先用：").append(deck.fonts).append("。\n")
        // 这两条是拿真实生成结果量出来的，不是想当然：
        // 一次端到端实验里，指令按"优先用思源黑体"照抄了字体名，而那台机器没装这个字体，
        // 渲染时被静默替换，封面上 12 个字的标题量出来每字 70pt（XML 里明明写着 40pt），
        // 直接冲出了画面右缘。所以字体和"不许出界"这两件事必须写死在规矩里。
        sb.append("**先在环境里确认这些字体装没装**：没装就按上面的顺序往下挑一个装了的，")
            .append("不要硬写一个装不上的字体名——渲染时会被静默替换，字号与行距跟着变，")
            .append("标题会冲出画面。\n")
        sb.append("中文字体要**同时**设 latin 与 eastAsia 两处字体名：")
            .append("python-pptx 的 `run.font.name` 只写得到 latin，中文会退回默认字体。\n\n")

        // 下面这一整块是拿一次**端到端验收**的真实结果换来的，不是想当然。
        // 上一版只有"不许出界"这类原则性的话，实测产出的是：编造得很像真的数字、
        // 整行留白 2.4in、时间轴四列说明糊成一段、深色底上的表格看不出行在哪。
        // 原因不是模型不会画，而是指令没给它**能照着做、也能自己核**的量。
        // 所以下面全部写成可执行的数字。
        sb.append("【画布 · 写死，不要沿用模板默认】\n")
        sb.append("宽 13.333in（12192000 EMU）× 高 7.5in（6858000 EMU）。")
            .append("python-pptx 的默认模板是 10×7.5in（4:3），必须显式改；")
            .append("保存后回读 ppt/presentation.xml 的 p:sldSz，确认 cx=12192000、cy=6858000。\n")
        sb.append("左右安全边距各 0.80in，内容区从 y=1.95in 到 y=6.85in。\n")
        sb.append("字号只用这七档：封面主标题 54、页标题 28、卡片标题 17、大数字 44、")
            .append("正文 15、次级说明 13、页脚 10。不要自创字号。\n")
        sb.append("每页内容纵向要占到 6.30in 以下；内容不够就放大字号或加高卡片把版面填满，")
            .append("**任何地方都不许留下超过 1.0in 的整行空白**——上一版就数在这里最难看。\n\n")

        sb.append("【配色 · 只用给定色值及其派生，不要引入新色相】\n")
        sb.append("描边 = 卡片色提亮 8%；表格表头底 = 卡片色提亮 12%；")
            .append("图表网格线 = 卡片色提亮 10%；坐标轴文字 = 次要色；")
            .append("数据标签与强调 = 主色。\n\n")

        sb.append("【数字与图表 · 必须写满，不许留空、不许写“待补充”】\n")
        sb.append("用户没给真实数字时，**按主题自己编一组内部自洽的合理数据**填进去：")
            .append("例如规模类写 320 人 / 1,280 万元，增长类写 +12.4%、较上期 +3.1 个百分点，")
            .append("比例类加起来要等于 100%。同一套数字在全文、图表、表格里必须**前后一致**，")
            .append("图表系列值不能是 0，数字条不能空着。")
            .append("任何图表和表格都必须带单位（人 / % / 万元）。")
            .append("**禁止出现“待补充”“占位”“示例数据”“待替换”这类字眼**——")
            .append("读者看到的应该是一份可以直接交付的稿子，而不是一张待填的表。\n\n")

        sb.append("【图表与表格 · 深色底必须显式设样式】\n")
        sb.append("① 图表区与绘图区设成无填充，让底色透出来；② 坐标轴文字用次要色、不小于 11pt；")
            .append("③ 网格线用卡片色提亮值，不要用默认灰；④ 数据系列和数据标签都用主色；")
            .append("⑤ 表格：表头底用卡片色提亮值、表头文字用主色加粗，")
            .append("单元格文字用正文色或次要色，**每行加一条 0.75pt 分隔线**——")
            .append("深色底上没有分隔线，读的人是会串行的。\n\n")

        sb.append("【文字 · 字数上限：超了先精简措辞，再降一档字号，最后才折行】\n")
        sb.append("封面主标题 ≤12 汉字；页标题 ≤14 汉字；卡片标题 ≤8 汉字；")
            .append("每条要点 ≤20 汉字；时间轴每列说明 ≤14 汉字且最多 2 行。")
            .append("**折行后第二行不许只剩 1~2 个字**，出现这种孤字必须改措辞。\n\n")

        sb.append("【并排与时间线 · 列宽和间距都给死】\n")
        sb.append("一行最多 3 列；列宽 =（内容区宽 11.733in − 0.30in ×（列数 − 1））÷ 列数；")
            .append("相邻两列的文字渲染后不得靠得比 0.20in 更近。")
            .append("时间线最多 4 个节点，节点间距 ≥2.80in。\n\n")

        sb.append("【标题 · 每一页的标题都要是一句结论】\n")
        sb.append("除封面、目录、结尾之外，页标题必须能独立成句，不能是名词短语：")
            .append("不写「招生数据总览」，要写「招生规模三年小幅下滑，结构需优化」；")
            .append("标题里要有数字或明确的判断词。\n\n")

        sb.append("【页脚与封面】\n")
        sb.append("每页左上角 11pt 主色写「章节名 · 章节序号」，右下角 10pt 次要色写两位页码，")
            .append("封面不写页码。封面左对齐、垂直居中于 1.95–6.85in：")
            .append("主标题 54pt 加粗（最多 2 行、行距 1.15），其上方 0.30in 一条 ")
            .append("1.10in × 0.07in 主色短线，下方 0.45in 一行 18pt 次要色的")
            .append("「听众 · 汇报人：X · 日期：Y」，X、Y 用户没给就自己按主题拟一个合理的人名与日期，")
            .append("**不要写“待补充”**。")
            .append("封面内容不要高过 2.60in，也不要低过 6.60in。\n\n")

        sb.append("【大纲与模板冲突时，一律以【大纲】为准】\n")
        sb.append("权重只用来把总页数分下去；我写明了起止页的，直接照抄，不要按权重重算。\n\n")

        sb.append("【交付前必须自查 · 方法给死，别凭眼睛看】\n")
        sb.append("① 先把 .pptx 转成 PDF，**再从 PDF 栅格化成 PNG**——")
            .append("直接渲染 .pptx 的工具常会按 1.5 倍栅格化却按原尺寸出画布，")
            .append("右边和下边各被切掉约三分之一，**照那个假象去改会把好页面改坏**；\n")
        sb.append("② 逐页把每个文本框的墨迹包围盒和它自己的框比一比，")
            .append("任何一边超出 0.02in 就算出界；墨迹不许落到页面 0.60in 安全边距之外；")
            .append("没有渲染工具时就估算：中文按 1.0×字号、英文数字按 0.55×字号累加，")
            .append("估算宽度大于框宽就是没通过，回去改字号或折行；\n")
        sb.append("③ 顺便数一遍：整行空白有没有超过 1.0in、有没有折行后只剩一两个字的孤行。\n\n")

        if (rows.isNotEmpty()) {
            sb.append("【大纲 · 我已经确认过，严格按这个来】\n")
            sb.append("每一行是「页数 | 章节 | 这几页讲什么（版式 · 建议元素）」：\n")
            for (r in rows) {
                val visual: String = r.visual.ifBlank { visualOf(r.layout) }
                sb.append("  ").append(r.range).append(" | ").append(r.section)
                    .append(" | ").append(r.content)
                    .append("（").append(r.layout)
                if (visual.isNotBlank()) sb.append(" · ").append(visual)
                sb.append("）\n")
            }
            sb.append('\n')
        } else if (sections.isNotEmpty()) {
            sb.append("【结构 · 按这个顺序铺】\n")
            for (s in sections) {
                val visual: String = s.visual.ifBlank { visualOf(s.layout) }
                sb.append("  ").append(s.name).append("：").append(s.hint)
                    .append("（").append(s.layout)
                if (visual.isNotBlank()) sb.append(" · ").append(visual)
                sb.append("）\n")
            }
            sb.append('\n')
        }

        if (blueprint.isNotBlank()) {
            sb.append("【模板 · 整份照用，不要改结构、不要另配视觉】\n")
            sb.append("这是一套已经定型的演示文稿结构，请沿用它的章节顺序、版式分配与气质：\n")
            sb.append("```\n").append(blueprint.trimEnd()).append("\n```\n\n")
        } else {
            sb.append("【模板 · 资源包没读到，这次请你按大纲自己设计版式】\n")
            sb.append("要求：深色底或浅色底只用上面给的那一套；一页一个观点；")
                .append("标题短、正文只留短句；不要贴网络图片。\n\n")
        }

        sb.append("【风格】").append(deck.style).append('\n')
        sb.append("【规则】\n")
        sb.append("1. 每一页只讲一个观点，正文三到五条短句，不要把整段文字塞进一页；\n")
        sb.append("2. 第一页是封面，最后一页是结论或下一步；\n")
        sb.append("3. 大纲里已经写明的章节和页数不要改，要改就只改页内的措辞；\n")
        sb.append("4. 图表用可编辑的原生图表或表格，不要截图；数字要和大纲对得上；\n")
        sb.append("5. **成品文件里不许出现任何 `{{` 或 `}}`**；用户没给的信息")
            .append("（真实统计数字、公司名、日期、人名）**按主题自己拟一个合理的**，")
            .append("保证全文自洽；不要写“待补充”“占位”“示例”这类字眼；\n")
        sb.append("6. **任何一行文字都不许超出页面**：标题太长就缩小字号或折行，")
            .append("正文最多三到五条；做完以后逐页过一遍，确认没有出界、没有截断；\n")
        sb.append("7. 做完后告诉我 .pptx 的完整路径，以及实际做了几页。\n\n")

        sb.append("【我这次要做的】\n")
        if (theme.isNotBlank()) sb.append("主题：").append(theme).append('\n')
        sb.append(
            if (free.isBlank()) "（我只写了上面那些，剩下的按主题合理补全，不要编造与我无关的信息。）\n"
            else free.trim() + "\n",
        )
        sb.append('\n')

        sb.append("【关于追问】\n")
        // 上一版把"缺数字"写成必问项，真机上内核停在那里问、那一轮就白等；
        // 后来又改成"写待补充"，用户看到成品里全是待补充更不满意。
        // 现在的取舍：**不问、也不留空**——按主题自己拟一组自洽的数据，交付一份完整的稿。
        sb.append("**如果【大纲】里有数据页却没有给我数字：不要停下来问我。**")
            .append("按主题自己拟一组内部自洽的合理数据直接生成，")
            .append("**不要写“待补充”“占位”或任何提醒替换的字样**。\n")
        if (askBudget > 0) {
            sb.append("如果确实还有关键信息缺失（比如面向谁、要放哪些数字、汇报人是谁），")
                .append("你最多可以再问我 ").append(askBudget).append(" 个问题。\n")
            sb.append("一次只问一个，等我说完再问下一个；我可能会分好几次回答，")
                .append("把每次回答累积起来，问满或问清之后立刻开工，不要重复问同一件事。\n")
        } else {
            sb.append("不要再来问我了，直接按上面的信息把东西做出来，缺的细节你按常理补。\n")
        }
        sb.append("做完后告诉我文件的完整路径，并用一句话说明我可以改哪些地方。\n")
        return sb.toString()
    }

    private fun indexOfMark(text: String): Int? {
        val full: Int = text.indexOf(MARK)
        val half: Int = text.indexOf("模板:")
        return when {
            full >= 0 && half >= 0 -> minOf(full, half)
            full >= 0 -> full
            half >= 0 -> half
            else -> null
        }
    }
}
