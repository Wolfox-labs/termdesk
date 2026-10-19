package dev.termdesk.app.ui

import android.content.res.AssetManager

// 除了读资源包要用的 AssetManager，这个文件只用 Kotlin 标准库。
// 位置要求：和 ChatSection.kt 放在同一个文件夹（app/src/main/java/dev/termdesk/app/ui/），
// 包名 dev.termdesk.app.ui 就是对的。

/**
 * 一份网页骨架模板。
 *
 * 骨架正文不在这里，在 `assets/templates/web/` 里——那些 .html 就是「资源包」：
 * 要加一套新模板，往资源目录里放一个 .html，再在这里加一条数据即可，界面不动。
 *
 * 为什么把「填哪几个槽位」也写成数据：用户打了字以后，App 要在**本地、零延迟**地
 * 把已知信息填进骨架，剩下的 `{{...}}` 才留给模型补。哪个槽位叫什么名字，只有模板
 * 自己知道，所以它必须跟着模板走。
 */
internal data class PageTemplate(
    /** 写进文本里的那个词，也是发送时被解析的键，例如 `官网首页`。 */
    val id: String,
    /** 资源包里的相对路径。 */
    val assetPath: String,
    /** 面板里的一行说明：点下去会得到什么。 */
    val hint: String,
    /** 小角标，纯文本，不依赖任何图标库。 */
    val badge: String,
    /** 额外的搜索词。 */
    val keywords: List<String>,
    /** 主题/标题要填进哪个槽位。 */
    val primarySlot: String,
    /**
     * 文档标题（浏览器标签页上那行字）的槽位。
     *
     * 也用主题填：留着不填的话，页面标题会变成字面的 `{{页面标题}}`，
     * 虽然页面上看不见，但分享链接、加入书签、多标签页切换时一眼就知道没做完。
     */
    val titleSlot: String = "页面标题",
    /** 副标题要填进哪个槽位；没有就是空串。 */
    val subSlot: String,
    /** 逐条要点按顺序填进这些槽位。 */
    val bulletSlots: List<String>,
)

/**
 * 一份装配好的网页任务：给内核的完整指令，以及给人在预览条上看的一句话摘要。
 */
internal data class WebAssembly(
    /** 真正发给内核的完整指令。 */
    val instruction: String,
    /** 预览条上的一行字：模板名 · 配色 · 主题。 */
    val summary: String,
)

/**
 * 网页模板资源包的清单 + 本地装填逻辑。
 *
 * 全是纯函数：不读文件、不碰 Android API。骨架正文由调用方通过 `skeletonOf` 传进来，
 * 所以这套逻辑能在单测里被钉死，而不用靠真机上一眼一眼地看。
 */
internal object WebTemplates {

    /** 文本里表示「我选了哪套骨架」的那个前缀。 */
    const val MARK: String = "模板："

    /** 主题/标题的写法，App 追问一次后写回文本用的就是它。 */
    const val THEME: String = "主题"

    /** 逐条要点的写法。 */
    const val POINTS: String = "要点"

    private val OFFICIAL = PageTemplate(
        id = "官网首页",
        assetPath = "templates/web/official.html",
        hint = "导航 + 首屏 + 核心服务卡片 + 数据 + 页脚",
        badge = "官网",
        keywords = listOf("官网", "首页", "企业", "机构", "公司", "主页", "官方"),
        primarySlot = "标题",
        subSlot = "副标题",
        bulletSlots = listOf("要点1标题", "要点2标题", "要点3标题", "要点4标题"),
    )

    private val LANDING = PageTemplate(
        id = "宣传落地页",
        assetPath = "templates/web/landing.html",
        hint = "一屏讲清一件事：痛点 → 方案 → 行动号召",
        badge = "落地",
        keywords = listOf("宣传", "落地", "推广", "招生", "介绍", "单页", "活动宣传"),
        primarySlot = "主标题",
        subSlot = "副标题",
        bulletSlots = listOf("痛点1", "痛点2", "痛点3"),
    )

    private val EVENT = PageTemplate(
        id = "活动页",
        assetPath = "templates/web/event.html",
        hint = "活动名 + 时间地点 + 流程时间线 + 报名须知",
        badge = "活动",
        keywords = listOf("活动", "开放日", "讲座", "比赛", "报名", "会议", "论坛", "招新"),
        primarySlot = "活动名称",
        subSlot = "一句话介绍",
        bulletSlots = listOf("环节1标题", "环节2标题", "环节3标题", "环节4标题"),
    )

    private val PRODUCT = PageTemplate(
        id = "产品介绍",
        assetPath = "templates/web/product.html",
        hint = "产品名 + 卖点 + 功能清单 + 规格表 + 咨询",
        badge = "产品",
        keywords = listOf("产品", "功能", "卖点", "规格", "服务", "方案", "报价"),
        primarySlot = "产品名",
        subSlot = "一句话卖点",
        bulletSlots = listOf("功能1标题", "功能2标题", "功能3标题", "功能4标题"),
    )

    private val PROFILE = PageTemplate(
        id = "个人主页",
        assetPath = "templates/web/profile.html",
        hint = "姓名 + 身份 + 简介 + 经历 + 联系方式",
        badge = "名片",
        keywords = listOf("个人", "主页", "简历", "名片", "介绍自己", "作品集", "关于我"),
        primarySlot = "姓名",
        subSlot = "一句话简介",
        bulletSlots = listOf("标签1", "标签2", "标签3", "标签4", "标签5"),
    )

    /**
     * 数据洞察页（参照「数说指数 · 社媒分析洞察平台」）。
     *
     * 和「官网首页」的区别在首屏：那一套靠一句话卖点说服人，这一套靠**一个查询框**
     * 把人直接带进数据里，所以主题之外还多一块指数速览和一个榜单。
     */
    private val INSIGHT = PageTemplate(
        id = "数据洞察页",
        assetPath = "templates/web/insight.html",
        hint = "查询框 + 指数速览 + 榜单 + 能力卡片 + 行动号召",
        badge = "数据",
        keywords = listOf("数据", "指数", "洞察", "榜单", "排行", "统计", "看板", "分析", "报表"),
        primarySlot = "标题",
        subSlot = "副标题",
        bulletSlots = listOf("要点1标题", "要点2标题", "要点3标题"),
    )

    /**
     * 内容门户页（参照「数英网 · 广告市场营销服务平台」）。
     *
     * 这一套的主体是**列表**，不是卡片卖点：一条头条 + 一个热门榜开屏，下面按栏目
     * 铺收录条目。要点在这里进的是条目标题，所以用户写「要点：A、B」就等于点名要
     * 哪几条内容当门面。
     */
    private val PORTAL = PageTemplate(
        id = "内容门户页",
        assetPath = "templates/web/portal.html",
        hint = "头条 + 热门榜 + 栏目标签 + 收录列表 + 订阅",
        badge = "门户",
        keywords = listOf("门户", "媒体", "资讯", "文章", "列表", "博客", "收录", "专栏", "内容"),
        primarySlot = "头条标题",
        subSlot = "头条摘要",
        bulletSlots = listOf("文章1标题", "文章2标题", "文章3标题", "文章4标题"),
    )

    /**
     * 监测预警页（参照「舆情洞察平台 · 全媒体洞察专家」）。
     *
     * 版式的重心是「流程」：能力模块讲能做什么，时间线讲事情怎么一步步被处置。
     * 顶栏常驻一个电话、底部两个按钮，都是这一行的常规做法。
     */
    private val MONITOR = PageTemplate(
        id = "监测预警页",
        assetPath = "templates/web/monitor.html",
        hint = "能力模块 + 处置流程时间线 + 优势数字 + 试用号召",
        badge = "监测",
        keywords = listOf("监测", "预警", "舆情", "风控", "大屏", "实时", "告警", "指标", "平台"),
        primarySlot = "标题",
        subSlot = "副标题",
        bulletSlots = listOf("要点1标题", "要点2标题", "要点3标题", "要点4标题"),
    )

    /**
     * 资源包里的全部模板，顺序就是面板里的显示顺序。
     *
     * 第一条是缺省：用户不挑的时候用它。后加的三套**排在原有五套后面**，
     * 这样「不挑就用官网首页」这个默认、以及面板里前五项的位置都没变——
     * 已经用顺手的人不该因为多了几套模板就找不到原来那几个。
     */
    val all: List<PageTemplate> =
        listOf(OFFICIAL, LANDING, EVENT, PRODUCT, PROFILE, INSIGHT, PORTAL, MONITOR)

    val fallback: PageTemplate get() = OFFICIAL

    /**
     * 从 APK 的资源包里读骨架正文。
     *
     * 读不到就返回空串——那时 [instruction] 会退回「让模型自己写」，而不是
     * 发出一段半截骨架。宁可退化成"没模板"，也不要产出"坏模板"。
     */
    fun readSkeleton(assets: AssetManager, template: PageTemplate): String =
        runCatching {
            assets.open(template.assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")

    fun byId(id: String): PageTemplate? = all.firstOrNull { it.id == id }

    /**
     * 从草稿里认出选了哪套骨架。
     *
     * 和配色一样认三种写法（全角/半角冒号、空格），并且**最长匹配优先**，
     * 免得「活动页」和以后可能加的「页」互相抢。
     */
    fun parse(text: String): PageTemplate? {
        val at: Int = indexOfMark(text) ?: return null
        val tail: String = text.substring(at + MARK.length)
        return all.filter { tail.startsWith(it.id) }.maxByOrNull { it.id.length }
    }

    /** 把 `模板：X` 这一小段从文本里摘掉（它是指令词，不是用户想说的话）。 */
    fun strip(text: String): String {
        val at: Int = indexOfMark(text) ?: return text
        val tail: String = text.substring(at + MARK.length)
        val tpl: PageTemplate = all.filter { tail.startsWith(it.id) }.maxByOrNull { it.id.length }
            ?: return text
        return text.removeRange(at, at + MARK.length + tpl.id.length)
    }

    /** 写回草稿时用的那一段。 */
    fun directive(template: PageTemplate): String = MARK + template.id

    /**
     * 把草稿里的内容装进骨架。
     *
     * 分工是刻意的：
     *  - **App 填**用户已经说清楚的那些（主题 → 标题、要点 → 卡片），零延迟、不改写；
     *  - **剩下的 `{{...}}`** 原样留着，由指令要求模型按主题补全。
     *
     * 这样「用户打出具体事项时反应更快」是真的：模型拿到手时骨架已经填了一半，
     * 它要做的是补空，而不是从零设计版式。
     */
    fun fill(
        template: PageTemplate,
        skeleton: String,
        theme: String,
        points: String,
        extras: Map<String, String>,
        colors: ColorScheme,
    ): String {
        var out: String = skeleton.replace("{{配色变量}}", "  " + ColorSchemes.cssVars(colors))

        // 主题填进这套骨架的主标题槽位（官网是「标题」、活动页是「活动名称」……），
        // 顺手把浏览器标签页上的文档标题也填掉。
        if (theme.isNotBlank()) {
            out = out.replace("{{" + template.primarySlot + "}}", escape(theme))
            out = out.replace("{{" + template.titleSlot + "}}", escape(theme))
        }
        if (template.subSlot.isNotBlank()) {
            val sub: String = extras["副标题"].orEmpty()
            if (sub.isNotBlank()) out = out.replace("{{" + template.subSlot + "}}", escape(sub))
        }
        // 副标题同时当 meta description：这句话本来就是给搜索引擎看的一句话简介。
        val lead: String = extras["副标题"].orEmpty().ifBlank { extras["一句话简介"].orEmpty() }
        if (lead.isNotBlank()) {
            out = out.replace("{{一句话简介}}", escape(lead))
            out = out.replace("{{一句话卖点}}", escape(lead))
        }

        // 要点：一条一个槽位，多于槽位数量的部分丢掉（骨架只有那么多张卡片）。
        val list: List<String> = bulletPoints(points)
        template.bulletSlots.forEachIndexed { index: Int, slot: String ->
            val one: String = list.getOrNull(index).orEmpty()
            if (one.isNotBlank()) out = out.replace("{{" + slot + "}}", escape(one))
        }

        // 其它「键：值」原样对应到同名槽位（时间、地点、联系电话、邮箱……）。
        for ((key, value) in extras) {
            if (key == "副标题" || value.isBlank()) continue
            out = out.replace("{{" + key + "}}", escape(value))
        }

        // 可选区块与兜底清理，顺序不能反。
        out = dropEmptyOptionalBlocks(out)
        return emptyLeftovers(out)
    }

    /** 可选区块的标记。骨架用它圈出「没人给内容就整块别出现」的部分。 */
    private const val OPT_START = "<!--可选-->"
    private const val OPT_END = "<!--/可选-->"

    /**
     * 把「填不满的可选区块」整块删掉。
     *
     * 为什么需要这个：骨架里有些槽位与用户这次的主题无关，App 也无从得知——统计数字、
     * 联系电话、邮箱、地址就是典型。真机上模型的反应是**把 `{{数据1}}` 原样留在页面上**，
     * 于是成品页面上明晃晃挂着三个花括号，一眼就是半成品。
     *
     * 这里给的规矩是：区块里只要还有一个没填上的槽位，整块连标记一起消失。页面因此
     * 只留下真正有内容的段落，不会出现「数据1、数据2、数据3」这种和主题无关的空壳。
     * 用户想留这些内容也有路——在正文里写 `数据1：120+`，槽位被填上，区块就保住了。
     */
    private fun dropEmptyOptionalBlocks(html: String): String {
        var out: String = html
        var from: Int = 0
        while (true) {
            val start: Int = out.indexOf(OPT_START, from)
            if (start < 0) return out
            val end: Int = out.indexOf(OPT_END, start)
            if (end < 0) return out
            val body: String = out.substring(start + OPT_START.length, end)
            val kept: String = if (body.contains("{{")) "" else body
            out = out.substring(0, start) + kept + out.substring(end + OPT_END.length)
            from = start + kept.length
        }
    }

    /**
     * 事实型槽位：App 无从得知、又**不该让模型去编**的那几个。
     *
     * 电话、邮箱、地址、统计数字都是事实。让模型编一个电话号比留空更糟，所以走到这里
     * 还没被填上的一律清空（连同 `tel:` / `mailto:` 的残骸）。
     *
     * 每一套骨架对「数字」的叫法不一样——官网叫数据、洞察页叫指标、监测页叫优势——
     * 所以这里按名字逐个列出来。名字变了就得跟着改，这正是它该显眼的原因：
     * 漏掉一个的后果是用户在自己页面上看到两个花括号。
     *
     * **只清这些**：`{{要点2说明}}` 这类描述性槽位必须原样留着——那是给模型的写作提示，
     * 清掉它等于让模型对着一片空白猜要写什么，出来的页面反而会更空。
     */
    private val FACT_SLOTS = listOf(
        "数据1", "数据1说明", "数据2", "数据2说明", "数据3", "数据3说明",
        "指标1数值", "指标1名称", "指标2数值", "指标2名称",
        "指标3数值", "指标3名称", "指标4数值", "指标4名称",
        "优势1数值", "优势1名称", "优势2数值", "优势2名称",
        "优势3数值", "优势3名称", "优势4数值", "优势4名称",
        "联系电话", "邮箱", "地址",
    )

    /**
     * 兜底：清掉还没被填上的事实型槽位。
     *
     * 这是最后一道闸。可选区块已经处理过一遍，但像活动页那种把电话写在按钮属性里的
     * 骨架，槽位不在任何区块内，只能在这里收尾。
     */
    private fun emptyLeftovers(html: String): String {
        var out: String = html
        for (slot in FACT_SLOTS) out = out.replace("{{" + slot + "}}", "")
        return out.replace("href=\"tel:\"", "href=\"#\"")
            .replace("href=\"mailto:\"", "href=\"#\"")
    }

    /**
     * 拼出真正发给内核的整段指令。
     *
     * @param theme     用户这次要做的主题
     * @param points    逐条要点（还没切）
     * @param extras    其它已经填好的槽位（`副标题`、`时间`、`地点`……）
     * @param free      用户的自由文字，原样附在末尾
     * @param skeleton  骨架正文；为空表示资源没读到，退回「让模型自己写」
     * @param colors    已选配色
     * @param askBudget 还能向用户追问几次（上限 5 次减去本地已经问掉的）
     */
    fun instruction(
        template: PageTemplate,
        theme: String,
        points: String,
        extras: Map<String, String>,
        free: String,
        skeleton: String,
        colors: ColorScheme,
        askBudget: Int,
    ): String {
        val filled: String =
            if (skeleton.isBlank()) "" else fill(template, skeleton, theme, points, extras, colors)

        val sb = StringBuilder()
        sb.append("【任务：生成网页 · ").append(template.id).append("】\n")
        sb.append("请在本对话的工作目录里，做出一个可以直接双击打开的网页。\n")
        sb.append("文件名用小写英文加连字符，后缀 .html，保存在工作目录根下，建议叫 ")
            .append(fileNameStem(template)).append(".html。\n\n")

        sb.append("【配色 · 已由我在手机上选定，直接照用，不要另配】\n")
        sb.append(ColorSchemes.summary(colors)).append('\n')
        sb.append("骨架的 :root 里已经写好了这几个变量，页面里所有颜色都从它们取，")
            .append("不要在别处写死颜色：\n")
        sb.append("  ").append(ColorSchemes.cssVars(colors)).append("\n\n")

        if (filled.isNotBlank()) {
            sb.append("【骨架 · 整份照抄，只替换剩下的花括号占位符】\n")
            sb.append("下面是本次要用的骨架，我已经把能确定的文字填进去了。\n")
            sb.append("请把整份 HTML 作为新文件写出，并遵守：\n")
            sb.append("1. 不要改结构、不要重写 CSS、不要换库，也不要引入任何外部 CDN；\n")
            sb.append("2. 还带着双花括号的地方，按下面的主题补齐，取值要短、要像人写的；\n")
            sb.append("3. 骨架里没有的东西不要自己加，尤其不要再加一套配色方案；\n")
            sb.append("4. **成品文件里不许出现任何 `{{` 或 `}}`，一处都不行。**每一处占位符")
                .append("都必须变成真实文字或数字。实在不确定的（比如统计数字、联系电话、地址），")
                .append("就填一个一眼能看出要改的占位词（例如「待补充」），并在回复里把它们列出来让我替换。")
                .append("**不要**把花括号原样留在页面上，也**不要**留成空白——")
                .append("页面上的一个洞比一句坦诚的「待补充」糟得多，前者看起来是没做完，后者本来就是要我填的。\n")
            sb.append("```html\n").append(filled.trimEnd()).append("\n```\n\n")
        } else {
            sb.append("【骨架 · 资源包没读到，这次请你按下面的要求自己写】\n")
            sb.append("要求：单个 HTML 文件，样式写在同一个文件里，不引用外部 CDN、")
                .append("断网也能正常打开；手机上打开不能横向滚动；电脑上内容区居中，")
                .append("最大宽度 960px；颜色只用上面给出的那几个变量。\n\n")
        }

        sb.append("【我这次要做的】\n")
        if (theme.isNotBlank()) sb.append("主题：").append(theme).append('\n')
        if (points.isNotBlank()) sb.append("要点：").append(points).append('\n')
        for ((key, value) in extras) {
            if (key == "副标题") continue
            if (value.isNotBlank()) sb.append(key).append('：').append(value).append('\n')
        }
        sb.append(
            if (free.isBlank()) "（我只写了上面那些，剩下的按主题合理补全，不要编造与我无关的信息。）\n"
            else free.trim() + "\n"
        )
        sb.append('\n')

        sb.append("【关于追问】\n")
        if (askBudget > 0) {
            sb.append("如果确实还有关键信息缺失（比如面向谁、要放哪些数字、联系方式），")
                .append("你最多可以再问我 ").append(askBudget).append(" 个问题。\n")
            sb.append("一次只问一个，等我说完再问下一个；我可能会分好几次回答，")
                .append("把每次回答累积起来，问满或问清之后立刻开工，不要重复问同一件事。\n")
        } else {
            sb.append("不要再来问我了，直接按上面的信息把东西做出来，缺的细节你按常理补。\n")
        }
        sb.append("做完后告诉我文件的完整路径，并用一句话说明我可以改哪些地方。\n")
        return sb.toString()
    }

    /** 建议的文件名主干，取自资源文件名（official.html → official）。 */
    fun fileNameStem(template: PageTemplate): String {
        val slash: Int = template.assetPath.lastIndexOf('/')
        val dot: Int = template.assetPath.lastIndexOf('.')
        val from: Int = slash + 1
        return if (dot > from) template.assetPath.substring(from, dot)
        else template.assetPath.substring(from)
    }

    /**
     * 预览条上的一行字：把用户真的会关心的三件事说清楚。
     *
     * [colors] 为 null 表示**还没问过配色**，这时必须写「配色未选」，不能悄悄拿缺省那套顶上：
     * 真机上出现过芯片写「配色 未选」、摘要却写「科技蓝」的自相矛盾——用户会以为已经选好了。
     */
    fun summaryLine(template: PageTemplate, colors: ColorScheme?, theme: String, points: String): String {
        val sb = StringBuilder()
        sb.append(template.id).append(" · ").append(colors?.id ?: "配色未选")
        if (theme.isNotBlank()) sb.append(" · 主题 ").append(theme)
        val list: List<String> = bulletPoints(points)
        if (list.isNotEmpty()) sb.append(" · ").append(list.size).append(" 个要点")
        return sb.toString()
    }

    /** 「要点：a、b、c」切成一条一条。中英文顿号、逗号、分号、竖线都认。 */
    fun bulletPoints(raw: String): List<String> =
        raw.split('、', '，', ',', '；', ';', '|', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * 骨架里还剩下的、要交给模型补的占位符个数。
     *
     * HTML 注释整段跳过：骨架头部那句「只替换 {{...}} 里的文字」是写给模型的说明，
     * 到不了页面，但它确实长得像一个占位符——不跳过的话，预览条上的
     * 「还有 N 处交给模型补」会永远多算一处，用户看到的数字就是错的。
     */
    fun remainingSlots(html: String): Int {
        var count = 0
        var from = 0
        while (from < html.length) {
            val comment = html.indexOf("<!--", from)
            val open = html.indexOf("{{", from)
            if (open < 0) break
            if (comment in 0 until open) {
                val close = html.indexOf("-->", comment)
                if (close < 0) break
                from = close + 3
                continue
            }
            val end = html.indexOf("}}", open)
            if (end < 0) break
            count += 1
            from = end + 2
        }
        return count
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

    /** 值里出现 `{{` 会破坏后面的替换，所以先拆掉。 */
    private fun escape(value: String): String = value.replace("{{", "{ {").replace("}}", "} }")
}

/**
 * 把正文里的 `键：值` 行抠出来，填进骨架里同名的槽位。
 *
 * 这是「用户打出具体事项时反应更快」的另一半：他只要在正文里写
 * ```
 * 地点：图书馆报告厅
 * 时间：10 月 20 日 上午 9:00
 * ```
 * 骨架里那两个 `{{地点}}`、`{{时间}}` 就在本地被填掉了，不用等模型去猜。
 *
 * 只认「一行、冒号前不超过 8 个字、里面没有空格」的写法，所以
 * 「我今天去了：学校」不会被误当成槽位。值**只取本行**——多行取值会把用户的
 * 大白话吸进槽位里，那比不填更糟。认不出来的行原样留在 [freeText] 里，一个字都不丢。
 */
internal object DraftTokens {

    fun pairs(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.split('\n')) {
            val (k, v) = splitKey(line)
            if (k != null && v.isNotBlank()) out[k] = v
        }
        return out
    }

    /** 去掉所有已配对的 `键：值` 行，剩下的就是用户的自由文字。 */
    fun freeText(text: String, pairs: Map<String, String>): String {
        if (pairs.isEmpty()) return text.trim()
        val kept = ArrayList<String>()
        for (line in text.split('\n')) {
            val (k, v) = splitKey(line)
            if (k != null && pairs.containsKey(k) && pairs[k] == v) continue
            kept.add(line)
        }
        return kept.joinToString("\n").trim()
    }

    /** 一行是不是 `键：值`；是就把键和值拆开，不是就返回 (null, 行本身)。 */
    private fun splitKey(line: String): Pair<String?, String> {
        val at: Int = firstColon(line)
        if (at <= 0) return null to line
        val key: String = line.substring(0, at).trim()
        if (key.isEmpty() || key.length > 8) return null to line
        if (key.any { it == ' ' || it == '\t' || it == '　' }) return null to line
        return key to line.substring(at + 1).trim()
    }

    private fun firstColon(line: String): Int {
        val full: Int = line.indexOf('：')
        val half: Int = line.indexOf(':')
        return when {
            full < 0 -> half
            half < 0 -> full
            else -> minOf(full, half)
        }
    }
}
