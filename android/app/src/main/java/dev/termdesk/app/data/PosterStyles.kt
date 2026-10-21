package dev.termdesk.app.data

import kotlin.math.abs

/**
 * 海报风格：**风格 → 底图**的映射表。
 *
 * 底图是用户给的私密资源，全部放在 `assets/templates/poster/` 里，界面上**没有**任何
 * 选底图的入口——用户只能选风格，落到哪张底图由这里决定。这样既守住了资源，也让用户
 * 面对的是"我要什么感觉"而不是"我要哪张图"。
 *
 * 做成数据表还有一个原因：用户之后会陆续给图让我学。到那时**加一张图 = 往这里的
 * [backgrounds] 里加一个文件名**，界面、渲染、事件都不用动。
 *
 * 色值用 ARGB，`wash` 是压在图上的罩层强度：底图自带的文字越重，罩层就要越强，
 * 否则画上去的标题会和底图原来的标题糊在一起（这是拿真实素材试出来的，不是随手写的）。
 */
internal data class PosterStyle(
    val id: String,
    /** 胶囊按钮上的短标签。 */
    val badge: String,
    val hint: String,
    /** 自动选型用的关键词。 */
    val keywords: List<String>,
    /** 该风格可用的隐藏底图（assets 相对路径）；空表示没有素材，由程序画一张底。 */
    val backgrounds: List<String>,
    val titleColor: Int,
    val subtitleColor: Int,
    val accentColor: Int,
    /** 标题面板的底色与透明度（0~255）。 */
    val panelColor: Int,
    val panelAlpha: Int,
    /** 整图罩层：颜色 + 强度（0~1）。 */
    val washColor: Int,
    val wash: Float,
    /** 底部渐隐强度：把画面下方压暗/提亮，让文字更立得住。 */
    val fade: Float,
) {
    /** 这个风格是不是"程序画底"（还没有素材）。 */
    val drawn: Boolean get() = backgrounds.isEmpty()

    /** 第 variant 张底图；variants 为 0 说明没有素材。取模做成非负的，换底点多少次都安全。 */
    fun backgroundAt(variant: Int): String? {
        if (backgrounds.isEmpty()) return null
        val n = backgrounds.size
        val i = ((variant % n) + n) % n
        return backgrounds[i]
    }
}

internal object PosterStyles {

    /** 赛博暗黑：深蓝底 + 冷色高光。 */
    val CYBER = PosterStyle(
        id = "赛博暗黑",
        badge = "赛博暗黑",
        hint = "深色底、冷光、适合科技与未来主题",
        keywords = listOf(
            "赛博", "暗黑", "科技", "未来", "数字", "智能", "ai", "人工智能", "互联网",
            "数据", "大赛", "电竞", "发布会", "创新", "前沿", "芯片", "算力",
        ),
        backgrounds = listOf(
            // cyber-2.jpg 于 2026-10-18 删除：真机生成出来那张海报用的就是它，
            // 底图自带的元素跟画上去的标题打架，效果不好，用户点名把这套模板下掉。
            // 这张一去掉，赛博暗黑只剩 cyber-1。
            "templates/poster/cyber-1.jpg",
        ),
        titleColor = 0xFFFFFFFF.toInt(),
        subtitleColor = 0xFFBBD3F5.toInt(),
        accentColor = 0xFF35E0FF.toInt(),
        panelColor = 0xFF050B18.toInt(),
        panelAlpha = 128,
        washColor = 0xFF04070F.toInt(),
        wash = 0.42f,
        fade = 0.55f,
    )

    /** 极简科技：亮底 + 藏青字，适合通知、民生、汇报这类要看清字的场合。 */
    val MINIMAL = PosterStyle(
        id = "极简科技",
        badge = "极简科技",
        hint = "亮底、字号大、留白多，适合通知与民生内容",
        keywords = listOf(
            "极简", "简约", "简洁", "清新", "民生", "新闻", "通知", "公告", "医疗",
            "健康", "教育", "校园", "讲座", "培训", "招募", "社区", "服务",
        ),
        backgrounds = listOf(
            "templates/poster/minimal-1.jpg",
            "templates/poster/minimal-2.jpg",
        ),
        titleColor = 0xFF0B2545.toInt(),
        subtitleColor = 0xFF33506E.toInt(),
        accentColor = 0xFF1E6FF2.toInt(),
        panelColor = 0xFFFFFFFF.toInt(),
        panelAlpha = 170,
        washColor = 0xFFF2F7FF.toInt(),
        wash = 0.52f,
        fade = 0.42f,
    )

    /**
     * 复古质感：**目前没有素材**，由程序画一张暖色纸感底。
     *
     * 这不是"缺一张图先凑合"——用户之后给复古素材时，往 [PosterStyle.backgrounds]
     * 里加文件名，这一套就会自动改用真图（见 [PosterStyle.drawn]），代码一行都不用改。
     */
    val RETRO = PosterStyle(
        id = "复古质感",
        badge = "复古质感",
        hint = "暖色纸感、衬线味，适合文艺与怀旧主题",
        keywords = listOf(
            "复古", "怀旧", "国潮", "文艺", "手作", "咖啡", "展览", "市集",
            "唱片", "老", "旧", "民谣", "书店", "手账", "非遗",
        ),
        backgrounds = emptyList(),
        titleColor = 0xFFF7E7C8.toInt(),
        subtitleColor = 0xFFD9BE92.toInt(),
        accentColor = 0xFFE0A458.toInt(),
        panelColor = 0xFF2A1B10.toInt(),
        panelAlpha = 120,
        washColor = 0xFF1E1409.toInt(),
        wash = 0.20f,
        fade = 0.45f,
    )

    val all: List<PosterStyle> = listOf(CYBER, MINIMAL, RETRO)

    /** 什么都不像的时候用这一套：它最通用，也最不容易出错。 */
    val fallback: PosterStyle = CYBER

    fun byId(id: String): PosterStyle? = all.firstOrNull { it.id == id }

    /**
     * 按主题自己挑风格。
     *
     * 做法和表格、PPT 那两处一样：命中关键词就加分，最高分胜出，一个都不中就用缺省。
     * 用户之后给更多图、更多关键词时，只需要改上面的数据。
     */
    fun pick(text: String): PosterStyle {
        val hay = text.lowercase()
        var best: PosterStyle = fallback
        var bestScore = 0
        for (style in all) {
            var score = 0
            for (word in style.keywords) {
                if (word.isNotBlank() && hay.contains(word.lowercase())) score += word.length
            }
            if (score > bestScore) {
                bestScore = score
                best = style
            }
        }
        return best
    }
}
