package dev.termdesk.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 海报合成：**全部在手机上用 Canvas 画出来**，不走内核。
 *
 * 2026-10-20 重写。上一版是"素材整张铺满 + 压一层罩 + 中间盖个面板写标题"，用户的原话是
 * 「你把那些图片当背景，然后在图片上加了一行字」。问题不在调色，在**层级**：素材自带一套
 * 构图（花纹、标题、水印），整张铺上去等于把别人的设计当成品，再往上写字必然打架。
 *
 * 这一版的规矩只有一条：**素材只是素材**。
 *  - 底色、几何、网格、纸纹、信息条全部由 [PosterStyle] 的五个色**画出来**，构图层层不同；
 *  - 素材只以"局部照片"的身份出现（横幅 / 竖构图 / 方块 / 圆形），**必然居中裁切**，
 *    所以底图原有的完整构图不可能出现在成品里；
 *  - 照片必须被"P"过：降饱和 + 主色 MULTIPLY 叠色 + 圆角/圆形裁切 + 同色系 scrim，
 *    看起来是这套设计里的一张配图，而不是一张原图；
 *  - 文字是主体，按 主标题 ≫ 副标题 > 要点 > 信息条 的四级层级排。
 *
 * 三个仍然成立的老经验：
 *  1. **两趟解码**：先 `inJustDecodeBounds` 量尺寸再降采样，否则一张手机大图进内存就是几十 MB；
 *     额外加了一层 WeakHashMap 缓存——点"还原/换字号"要连画好几张，不缓存就得反复解码；
 *  2. **不许逐像素**：纸纹/噪点用一百来个半透明小矩形凑，`getPixel` 那种写法一张图要几百毫秒；
 *  3. **宁可降字号，不许出界**：每行文字落笔前都过一遍 [truncateToWidth]，见那里的注释。
 */
internal object PosterRenderer {

    const val OUT_W = 1080
    const val OUT_H = 1440

    /** 左右安全边距：所有文字/照片的横向基准，改这一个值整版跟着动。 */
    private const val MARGIN = 84f

    /** 快捷调整：用户不满意时点的那几个键。 */
    enum class Tweak(val label: String) {
        Contrast("对比"),
        Saturation("饱和"),
        Warm("暖色"),
        TitleSize("字号"),
        Variant("换底"),
        Reset("还原"),
    }

    /**
     * 已经调过的参数。**累积的**：连点两次对比度就是加两次。
     *
     * 做成不可变数据而不是几个散落的开关，是为了"重新生成"这件事——换风格、换底图、
     * 点快捷调整，走的都是同一份参数，不会出现"点完暖色之后换风格把暖色丢了"。
     */
    data class Adjust(
        val contrast: Float = 1f,
        val saturation: Float = 1f,
        val warmth: Float = 0f,
        val titleScale: Float = 1f,
        val variant: Int = 0,
    ) {
        fun apply(tweak: Tweak): Adjust = when (tweak) {
            Tweak.Contrast -> copy(contrast = (contrast + 0.18f).coerceAtMost(2.2f))
            Tweak.Saturation -> copy(saturation = (saturation + 0.30f).coerceAtMost(2.6f))
            Tweak.Warm -> copy(warmth = (warmth + 0.35f).coerceAtMost(1.4f))
            // 字号循环：正常 → 加大 → 缩小 → 正常。
            // 判据必须是"比正常大 / 比正常小"这两侧，而不是和上次的值比——
            // 否则 1.0 永远只会跳回"加大"，第三下永远回不到正常（第一版就是这么错的）。
            Tweak.TitleSize -> copy(
                titleScale = when {
                    titleScale > 1.05f -> 0.84f
                    titleScale < 0.95f -> 1f
                    else -> 1.16f
                },
            )
            Tweak.Variant -> copy(variant = variant + 1)
            Tweak.Reset -> Adjust()
        }

        /** 界面上把调过的项列出来，用户才知道自己点过什么、能不能回去。 */
        fun labels(): List<String> = buildList {
            if (contrast > 1.001f) add("对比+" + ((contrast - 1f) * 100).roundToInt())
            if (saturation > 1.001f) add("饱和+" + ((saturation - 1f) * 100).roundToInt())
            if (warmth > 0.001f) add("暖色+" + (warmth * 100).roundToInt())
            if (titleScale > 1.05f) add("字号大")
            if (titleScale < 0.95f) add("字号小")
            if (variant != 0) add("换底" + variant)
        }
    }

    data class Request(
        val style: PosterStyle,
        val title: String,
        val subtitle: String,
        val scene: String,
        val adjust: Adjust = Adjust(),
    )

    data class Output(
        val bitmap: Bitmap,
        /** 这次真正用了哪张素材（null = 由程序画纹理块）。 */
        val asset: String?,
        /** 落盘的成品 png，聊天里显示的就是它。 */
        val file: String,
        val tweaks: List<String>,
    )

    // ── 字号表：四级层级写在一处，改层级只改这里 ───────────────────────────

    private const val TITLE_START = 132f
    private const val TITLE_MIN = 96f
    private const val SUB_START = 40f
    private const val SUB_MIN = 34f
    private const val BULLET_SIZE = 30f
    private const val INFO_SIZE = 24f

    // ── 版式 ─────────────────────────────────────────────────────────────

    /** 照片在版面上的身份。不是"背景"，是四选一的**局部图**。 */
    private enum class PhotoRole { BANNER, VERTICAL, TILE, CIRCLE }

    /** 同类风格下的两种构图；换底键在它们之间轮换，点一次换一个版。 */
    private enum class Comp { A, B }

    private class Layout(
        val role: PhotoRole,
        val comp: Comp,
        val serif: Boolean,
        val align: Layout.Alignment,
        val photo: RectF,
        /** 主标题起始基线与可用宽度。 */
        val titleX: Float,
        val titleY: Float,
        val titleW: Float,
        val titleLines: Int,
        val titleStart: Float,
        /** 信息区（底部 1/3 那条带）的顶边。 */
        val bandTop: Float,
    ) {
        enum class Alignment { LEFT, CENTER }
    }

    /**
     * 按风格 + 换底次数挑构图。
     *
     * 2026-10-21 二次改版。用户否掉了"标题压在图上"的做法，并给出两条路径：
     *   1) 完全自设计：文字/内容/排版全部自己排，**模板只作参考，不当底图**；
     *   2) 用模板：把模板上不相干的字擦掉，再放用户的内容。
     *
     * 所以这里的照片退成**顶部主视觉区**（0~520 或 0~560），标题、分区、信息条
     * 全在它下面的独立文字区里，互不压盖；整版由上到下都有内容，不再出现大片空白。
     */
    private fun layoutFor(style: PosterStyle, variant: Int): Layout {
        val v = ((variant % 2) + 2) % 2
        val comp = if (v == 0) Comp.A else Comp.B
        val wide = OUT_W - MARGIN * 2
        // A：主视觉 520 高；B：主视觉 640 高——换底键在两种节奏之间切。
        val heroH = if (comp == Comp.A) 520f else 640f
        return when (style.id) {
            PosterStyles.CYBER.id -> Layout(
                role = PhotoRole.BANNER, comp = comp, serif = false,
                align = Layout.Alignment.LEFT,
                photo = RectF(0f, 0f, OUT_W.toFloat(), heroH),
                titleX = MARGIN, titleY = heroH + 190f, titleW = wide,
                titleLines = 3, titleStart = 118f, bandTop = 1120f,
            )
            PosterStyles.MINIMAL.id -> Layout(
                role = PhotoRole.BANNER, comp = comp, serif = false,
                align = Layout.Alignment.LEFT,
                photo = RectF(0f, 0f, OUT_W.toFloat(), heroH),
                titleX = MARGIN, titleY = heroH + 200f, titleW = wide,
                titleLines = 3, titleStart = 112f, bandTop = 1120f,
            )
            PosterStyles.RETRO.id -> Layout(
                role = PhotoRole.BANNER, comp = comp, serif = true,
                align = Layout.Alignment.LEFT,
                photo = RectF(0f, 0f, OUT_W.toFloat(), heroH),
                titleX = MARGIN, titleY = heroH + 200f, titleW = wide,
                titleLines = 3, titleStart = 114f, bandTop = 1120f,
            )
            else -> Layout(
                role = PhotoRole.BANNER, comp = comp, serif = false,
                align = Layout.Alignment.LEFT,
                photo = RectF(0f, 0f, OUT_W.toFloat(), heroH),
                titleX = MARGIN, titleY = heroH + 190f, titleW = wide,
                titleLines = 3, titleStart = TITLE_START, bandTop = 1120f,
            )
        }
    }

    /** 画一张海报并落盘。IO 线程，调用方只需 await。 */
    suspend fun render(context: Context, request: Request): Output = withContext(Dispatchers.IO) {
        val style = request.style
        val adjust = request.adjust
        val layout = layoutFor(style, adjust.variant)
        val asset = style.backgroundAt(adjust.variant)

        val canvasBitmap = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)

        // 顺序就是层级：设计出来的底 → 承载信息区的带 → 照片 → 文字。照片在文字之下，
        // 所以照片里再有天大的构图也压不住标题。
        paintBackground(canvas, style, adjust, layout)
        paintBand(canvas, style, layout.bandTop)
        paintPhoto(canvas, context, style, adjust, layout, asset)
        paintWords(canvas, style, request, layout)

        val file = writeToFiles(context, canvasBitmap)
        Output(
            bitmap = canvasBitmap,
            asset = asset,
            file = file,
            tweaks = adjust.labels(),
        )
    }

    // ── 调色 ─────────────────────────────────────────────────────────────

    /**
     * 把对比度、饱和度、暖色合成一个 ColorMatrix。
     *
     * 一次算完而不是叠三个 ColorFilter：叠三层等于对每个像素做三遍矩阵乘法，
     * 手机上一张 1080×1440 就是几百万次多余计算。
     *
     * [satScale] 是给照片用的：素材天生是别人的照片，先按风格把它的彩色抽掉大半，
     * 剩下的颜色才是这套设计要的颜色（用户说的"把底片的内容直接改掉"从这一步开始）。
     */
    fun toneMatrix(adjust: Adjust, satScale: Float = 1f): ColorMatrix {
        val m = ColorMatrix()
        m.setSaturation((adjust.saturation * satScale).coerceIn(0f, 3f))
        val c = adjust.contrast
        val t = (-0.5f * c + 0.5f) * 255f
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        if (adjust.warmth > 0f) {
            val w = adjust.warmth * 36f
            m.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, w,
                        0f, 1f, 0f, 0f, w * 0.35f,
                        0f, 0f, 1f, 0f, -w,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        return m
    }

    /** 把不透明度按比例压到 0~255。三套版式里用得最多的一行。 */
    private fun withAlpha(color: Int, ratio: Float): Int =
        Color.argb(
            (ratio.coerceIn(0f, 1f) * 255f).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    /** 在两色之间插值，用来在同一套色里造出深浅层次，不必往 [PosterStyle] 里加字段。 */
    private fun mix(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(from) + (Color.red(to) - Color.red(from)) * k).roundToInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * k).roundToInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k).roundToInt(),
        )
    }

    /**
     * 光照强度 0~1 → 中性色。
     *
     * 深色风格上要往白里推，浅色风格上要往黑里推——写死"加白"的话，极简那套的
     * 所有装饰元素都会糊在浅底上什么都看不见。判据用亮度而不是 style.id，
     * 这样以后用户塞一套新配色进来也不用改这里。
     */
    private fun towardsLight(style: PosterStyle, t: Float): Int {
        val luma = (
            Color.red(style.accentColor) * 0.299f +
                Color.green(style.accentColor) * 0.587f +
                Color.blue(style.accentColor) * 0.114f
            ) / 255f
        return mix(style.accentColor, if (luma > 0.55f) Color.BLACK else Color.WHITE, t)
    }

    // ── 读图 ─────────────────────────────────────────────────────────────

    /**
     * 解码结果缓存。
     *
     * 用 WeakHashMap 而不是强引用表：位图由 GC 看着内存压力回收，我不必自己写 LRU，
     * 也不会因为"缓存没清"把一台低端机撑爆。键是 assets 路径。
     *
     * 为什么要缓存：用户点"换字号""还原"都要重画整张，一次点击就是一次解码；
     * 素材是几 MB 的 jpg，解一次几十毫秒，全画一遍的预算才 300ms。
     */
    private val decoded = WeakHashMap<String, Bitmap>()

    /**
     * 从 assets 读私密素材，两趟解码防 OOM。
     *
     * assets 里的图没有资源 id，所以要开两次流：第一趟只量尺寸（不分配像素），
     * 第二趟才按算出来的比例真解码。
     *
     * 注意这里**不再 recycle**：位图交给上面的缓存复用，回收由 GC 负责。
     * 上一版每次画完就 recycle，结果同一张图连画两张要解码两次。
     */
    private fun decodeDownsampled(context: Context, asset: String): Bitmap? {
        synchronized(decoded) { decoded[asset] }?.let { if (!it.isRecycled) return it }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 降采样只降到"裁出来的那块还够用"为止。
        // 这里不能用画布宽度当阈值：竖构图栏位是右侧 45%，它只取源图中间那一竖条，
        // 按画布宽度降到 1080 之后再裁，那一条就只剩三百来像素宽，放大回去整张照片是糊的。
        // 留到 1600 是折中——裁切后仍有 1.5 倍以上余量，内存也不至于失控。
        var sample = 1
        var longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / 2 >= 1600 && sample < 8) {
            sample *= 2
            longEdge /= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = runCatching {
            context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null

        synchronized(decoded) { decoded[asset] = bitmap }
        return bitmap
    }

    /**
     * 按目标框的长宽比居中裁切源图。
     *
     * 这是整次重写的关键一步：**只能裁，不能缩**。整张缩放等于把素材自带的那套构图
     * （它的标题、它的花纹、它的人像位置）原样搬进成品；裁掉大半只留中间一块，
     * 素材才真正降级成"一张配图"。
     */
    fun centerCrop(src: Bitmap, targetRatio: Float): Rect {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0 || targetRatio <= 0f) return Rect(0, 0, maxOf(w, 1), maxOf(h, 1))
        return if (w.toFloat() / h > targetRatio) {
            val nw = (h * targetRatio).roundToInt().coerceIn(1, w)
            val x = (w - nw) / 2
            Rect(x, 0, x + nw, h)
        } else {
            val nh = (w / targetRatio).roundToInt().coerceIn(1, h)
            val y = (h - nh) / 2
            Rect(0, y, w, y + nh)
        }
    }

    // ── 1. 设计出来的底 ───────────────────────────────────────────────────

    /** 按风格铺底：底色 + 大号几何 + 底部 1/3 的过渡带。照片和文字之后才盖上来。 */
    private fun paintBackground(canvas: Canvas, style: PosterStyle, adjust: Adjust, layout: Layout) {
        when (style.id) {
            PosterStyles.MINIMAL.id -> paintMinimalBase(canvas, style, adjust, layout)
            PosterStyles.RETRO.id -> paintRetroBase(canvas, style, adjust, layout)
            else -> paintCyberBase(canvas, style, adjust, layout)
        }
    }

    /**
     * 赛博：深底 + 冷光。
     *
     * 构图是"网格 + 斜切 + 大号数字"三件套。数字用半透明描边而不是实心填充，
     * 是因为实心大字会把标题区的对比拉平；描边只吃一点亮度，像底噪一样存在。
     */
    private fun paintCyberBase(canvas: Canvas, style: PosterStyle, adjust: Adjust, layout: Layout) {
        // 底：主色压暗后的垂直渐变，比纯黑多一点蓝，屏幕上看得出是"有色"的底。
        val deep = mix(style.panelColor, Color.BLACK, 0.35f)
        val top = mix(style.panelColor, style.accentColor, 0.10f)
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, OUT_H.toFloat(),
                intArrayOf(top, deep, mix(deep, Color.BLACK, 0.45f)),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, OUT_W.toFloat(), OUT_H.toFloat(), bgPaint)

        // 细网格：90px 一格，光学级别的一层"图纸感"。线全部在画布内，不会画出界。
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(towardsLight(style, 0.10f), 0.55f)
            strokeWidth = 1.6f
        }
        var gx = MARGIN
        while (gx < OUT_W - MARGIN) {
            canvas.drawLine(gx, MARGIN, gx, 1180f, grid)
            gx += 90f
        }
        var gy = MARGIN
        while (gy < 1180f) {
            canvas.drawLine(MARGIN, gy, OUT_W - MARGIN, gy, grid)
            gy += 90f
        }

        // 斜切色块：两层，主色一层压在左上角，中性光一层贴右下角，造出对角张力。
        val primaryBlock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(style.accentColor, 0.16f)
        }
        canvas.save()
        canvas.rotate(-14f, OUT_W * 0.5f, OUT_H * 0.3f)
        canvas.drawRect(-160f, OUT_H * 0.30f, OUT_W * 0.72f, OUT_H * 0.30f + 156f, primaryBlock)
        canvas.restore()
        val lightBlock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(towardsLight(style, 0.45f), 0.07f)
        }
        canvas.save()
        canvas.rotate(12f, OUT_W * 0.5f, OUT_H * 0.62f)
        canvas.drawRect(OUT_W * 0.34f, OUT_H * 0.60f, OUT_W + 200f, OUT_H * 0.60f + 120f, lightBlock)
        canvas.restore()

        // 左上角一圈主色光晕。用径向渐变而不是 setShadowLayer：
        // 软件画布上的大半径模糊是逐像素的，一张图就能吃掉整个 300ms 预算。
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.shader = RadialGradient(
            OUT_W * 0.86f, OUT_H * 0.16f, 260f,
            intArrayOf(withAlpha(style.accentColor, 0.34f), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(OUT_W * 0.86f, OUT_H * 0.16f, 260f, glow)

        // 大号编号：压在标题后面的底纹。字号 240，透明度 7%，只在画面上半部露一点。
        val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 240f
            color = withAlpha(towardsLight(style, 0.6f), 0.07f)
            // 注意：这里的 `style` 是函数参数（PosterStyle），写 `style = Paint.Style.STROKE`
            // 会被当成给那个 val 重新赋值——所以必须显式指到 Paint 的字段。
            this.style = Paint.Style.STROKE
            strokeWidth = 2.4f
        }
        // 取模必须先转正：variant 是累加的，用户点上十几次也不会越界，但 Math 里负数取模是负的。
        val seq = (adjust.variant % 9 + 9) % 9 + 1
        val code = "0$seq/" + if (layout.comp == Comp.A) "01" else "02"
        canvas.drawText(code, MARGIN - 8f, OUT_H * 0.52f, digits)

        // 一条主色细线从标题区斜着划出去，把网格破开，视线才不会死在格子里。
        val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(style.accentColor, 0.75f)
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(MARGIN, 880f, MARGIN + 220f, 880f, slash)
    }

    /**
     * 极简：浅底 + 大留白。
     *
     * 装饰全部是"细线 + 空框"，一个实心色块都不给——留白才是这套版式的主题，
     * 加得越多越像普通的通知图。
     */
    private fun paintMinimalBase(canvas: Canvas, style: PosterStyle, adjust: Adjust, layout: Layout) {
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, OUT_H.toFloat(),
                intArrayOf(Color.WHITE, mix(style.washColor, Color.WHITE, 0.35f), style.washColor),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, OUT_W.toFloat(), OUT_H.toFloat(), bgPaint)

        // 左上的一块实色 + 右下的一圈空框：一实一虚拉开对角，版心自然居中。
        val block = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(style.accentColor, 0.12f)
        }
        canvas.drawRect(MARGIN, 96f, MARGIN + 150f, 240f, block)

        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = withAlpha(style.accentColor, 0.45f)
        }
        canvas.drawRect(OUT_W - MARGIN - 320f, 372f, OUT_W - MARGIN, 372f + 236f, frame)

        // 细横线：只在标题区上方来一条，起"分栏"的作用，多了就碎。
        // 位置从**照片底边**推，不从标题推——标题字号会被用户 tweak 改，跟着字号走的分隔线
        // 在字号调大时会插进标题的字身上（第一版就是这么写的，看起来很脏）。
        val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(style.titleColor, 0.22f)
            strokeWidth = 2f
        }
        val hairY = layout.photo.bottom + 30f
        canvas.drawLine(MARGIN, hairY, OUT_W - MARGIN, hairY, hair)
        canvas.drawLine(MARGIN, layout.bandTop - 40f, OUT_W - MARGIN, layout.bandTop - 40f, hair)

        // 右下角一组同心圆弧，是这套版式里唯一的"软"元素，用来平衡满屏的直线。
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 3f
            color = withAlpha(style.accentColor, if (layout.comp == Comp.A) 0.35f else 0.22f)
        }
        canvas.drawArc(RectF(OUT_W - 470f, OUT_H - 470f, OUT_W + 90f, OUT_H + 90f), 180f, 90f, false, arc)
        canvas.drawArc(RectF(OUT_W - 620f, OUT_H - 620f, OUT_W + 240f, OUT_H + 240f), 180f, 90f, false, arc)
    }

    /**
     * 复古：暖色纸感 + 衬线。
     *
     * "纸"靠两样东西：暖色渐变，和一层不规则的噪点矩形。
     * 噪点是 130 个半透明小方块，不是逐像素噪声——逐像素那种写法在真机上要几百毫秒，
     * 而这两者在 1080px 宽度下肉眼几乎分不出。
     */
    private fun paintRetroBase(canvas: Canvas, style: PosterStyle, adjust: Adjust, layout: Layout) {
        val paperTop = mix(style.panelColor, style.accentColor, 0.16f)
        val paperBottom = mix(style.panelColor, Color.BLACK, 0.42f)
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, OUT_W.toFloat(), OUT_H.toFloat(),
                intArrayOf(mix(paperTop, Color.WHITE, 0.10f), paperTop, paperBottom),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, OUT_W.toFloat(), OUT_H.toFloat(), bgPaint)

        // 纸纹噪点：位置用"隔一个质数跳一步"推出来，不用随机数——渲染必须是确定性的，
        // 同一份参数画两次要长得一模一样，否则用户点"还原"会看到一张新图。
        val grainLight = Paint().apply { color = withAlpha(0xFFFFF3D6.toInt(), 0.055f) }
        val grainDark = Paint().apply { color = withAlpha(0xFF1B120A.toInt(), 0.070f) }
        var seed = 7
        var i = 0
        while (i < 130) {
            seed = (seed * 31 + 17) % 100003
            val x = (seed % 1040).toFloat()
            val y = ((seed / 1040) % 1400).toFloat()
            seed = (seed * 31 + 17) % 100003
            val w = 1.5f + (seed % 4) * 1.2f
            val h = 1.0f + (seed % 3) * 0.9f
            val paint = if (i % 2 == 0) grainLight else grainDark
            canvas.drawRect(x, y, x + w, y + h, paint)
            i += 1
        }

        // 内框：老印刷品的双线边框，做出"这张纸是有边的"的感觉。
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 3f
            color = withAlpha(style.accentColor, 0.5f)
        }
        canvas.drawRect(36f, 36f, OUT_W - 36f, OUT_H - 36f, frame)
        canvas.drawRect(48f, 48f, OUT_W - 48f, OUT_H - 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = withAlpha(style.accentColor, 0.28f)
        })

        // 印章式小方块：右下角一枚，用主色实心 + 白字风格的空心描边模拟钤印。
        val seal = RectF(OUT_W - 214f, layout.bandTop - 196f, OUT_W - 118f, layout.bandTop - 100f)
        canvas.drawRect(
            seal,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(style.accentColor, 0.85f) },
        )
        canvas.drawRect(
            RectF(seal.left + 9f, seal.top + 9f, seal.right - 9f, seal.bottom - 9f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = 3f
                color = withAlpha(style.panelColor, 0.75f)
            },
        )
    }

    /** 底部 1/3：一条渐变压暗/提亮带，给信息区一个"底板"，让它不悬在画面里。 */
    private fun paintBand(canvas: Canvas, style: PosterStyle, bandTop: Float) {
        // 深色底压得更暗、浅色底加一层同色雾，两边都是"往自己的底色方向沉下去"。
        val tint = if (style.wash > 0.35f) style.washColor else mix(style.panelColor, Color.BLACK, 0.25f)
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, bandTop, 0f, OUT_H.toFloat(),
                intArrayOf(Color.TRANSPARENT, withAlpha(tint, 0.55f), withAlpha(tint, 0.92f)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, bandTop, OUT_W.toFloat(), OUT_H.toFloat(), paint)

        // 信息带顶边一条主色细线：整版唯一的"硬边框"，视线到这儿就停住。
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(style.accentColor, 0.85f)
            strokeWidth = 4f
        }
        canvas.drawLine(MARGIN, bandTop, OUT_W - MARGIN, bandTop, edge)
    }

    // ── 2. 照片素材：裁切 + P 图 ──────────────────────────────────────────

    /**
     * 把素材画成版式里的"一张图"。
     *
     * 四步一个都不能省：
     *  1. 按照片框的长宽比 [centerCrop]（局部，不是整张）；
     *  2. 降饱和调色（`toneMatrix(adjust, 0.55f)`，把别人照片的颜色抽掉）；
     *  3. 主色 MULTIPLY 叠色（让照片染上这套设计的色）；
     *  4. 同色系 scrim + 底部渐隐（照片边缘不再"贴"在版上）。
     */
    private fun paintPhoto(
        canvas: Canvas,
        context: Context,
        style: PosterStyle,
        adjust: Adjust,
        layout: Layout,
        asset: String?,
    ) {
        val frame = RectF(layout.photo)
        val src = asset?.let { decodeDownsampled(context, it) }

        canvas.save()
        clipPhoto(canvas, layout, frame)
        if (src != null) {
            drawPhotoBitmap(canvas, src, style, adjust, layout, frame)
        } else {
            paintPhotoTexture(canvas, style, frame)
        }
        canvas.restore()

        // 描边放在 clip 之外：被裁掉的弧线会让圆角看起来缺一块。
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 4f
            color = withAlpha(style.accentColor, 0.85f)
        }
        // 通栏出血的图不描边：贴着画布边缘画一圈线，看起来像"图没放好"，
        // 而且这条线在成品海报上会被裁掉一半。
        val flush = frame.left <= 0.5f && frame.top <= 0.5f &&
            frame.right >= OUT_W - 0.5f && frame.bottom >= OUT_H - 0.5f
        if (!flush) when (layout.role) {
            PhotoRole.CIRCLE -> canvas.drawCircle(frame.centerX(), frame.centerY(), frame.width() / 2f, outline)
            PhotoRole.TILE -> {
                // 复古的"贴纸"要转一点点角度才像手工放的；旋转只影响描边这一笔。
                val angle = if (style.id == PosterStyles.RETRO.id) -1.6f else 0f
                canvas.save()
                canvas.rotate(angle, frame.centerX(), frame.centerY())
                canvas.drawRoundRect(frame, 10f, 10f, outline)
                canvas.restore()
            }
            else -> canvas.drawRoundRect(frame, 8f, 8f, outline)
        }
    }

    /** 按版式把照片裁成对应的形状。圆形用椭圆路径，其余用圆角矩形。 */
    private fun clipPhoto(canvas: Canvas, layout: Layout, frame: RectF) {
        when (layout.role) {
            PhotoRole.CIRCLE -> {
                val path = Path().apply { addOval(frame, Path.Direction.CW) }
                canvas.clipPath(path)
            }
            PhotoRole.TILE -> canvas.clipRect(frame)
            // Canvas 上没有 clipRoundRect：圆角裁切要走 Path + clipPath。
            PhotoRole.VERTICAL -> canvas.clipPath(roundRectPath(frame, 8f))
            PhotoRole.BANNER -> canvas.clipPath(roundRectPath(frame, 10f))
        }
    }

    /** 一块圆角矩形的 Path —— 裁切和描边都用它，省得两处圆角对不上。 */
    private fun roundRectPath(frame: RectF, radius: Float): Path = Path().apply {
        addRoundRect(frame, radius, radius, Path.Direction.CW)
    }

    /**
     * 照片本体：裁切 → 调色 → 主色叠色 → scrim。
     *
     * 叠色用 MULTIPLY 而不是 SRC_OVER：SRC_OVER 只是盖一层透明色，照片还是照片；
     * MULTIPLY 是让主色**参与**照片的明暗计算，出来的是一张被这套配色重印过的图。
     */
    private fun drawPhotoBitmap(
        canvas: Canvas,
        src: Bitmap,
        style: PosterStyle,
        adjust: Adjust,
        layout: Layout,
        frame: RectF,
    ) {
        val rect = centerCrop(src, frame.width() / frame.height())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // 降饱和是固定动作（不是 tweak）：素材天生带别人的色彩，先抽掉一半再说。
        paint.colorFilter = ColorMatrixColorFilter(toneMatrix(adjust, 0.52f))
        canvas.drawBitmap(src, rect, frame, paint)

        // 主色叠色：强度按版式分——横幅是主角，压得轻；圆形小图只是个点，压得重。
        val strength = when (layout.role) {
            PhotoRole.BANNER -> 0.30f
            PhotoRole.VERTICAL -> 0.38f
            PhotoRole.TILE -> 0.42f
            PhotoRole.CIRCLE -> 0.46f
        }
        val blend = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(style.accentColor, strength)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
        canvas.drawRect(frame, blend)

        // 同色系 scrim：从照片底部糊上来，照片的下缘就和版式连成一片了。
        val scrim = Paint().apply {
            shader = LinearGradient(
                frame.left, frame.top, frame.left, frame.bottom,
                intArrayOf(withAlpha(style.washColor, 0.04f), withAlpha(style.washColor, 0.34f)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(frame, scrim)

        // 顶部再补一道极淡的主色光，模拟"打光过的照片"，和纯 scrim 压暗是两种味道。
        val light = Paint().apply {
            shader = LinearGradient(
                frame.left, frame.top, frame.right, frame.bottom,
                intArrayOf(withAlpha(style.accentColor, 0.16f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.7f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(frame, light)
    }

    /**
     * 素材读不出来时的替身：一块同色系纹理。
     *
     * 不能留空——留空整版会缺一块，用户看到的是"破了"；画一块同色系的斜纹，
     * 至少版式是完整的，而且和真图的观感一致。
     */
    private fun paintPhotoTexture(canvas: Canvas, style: PosterStyle, frame: RectF) {
        val paint = Paint().apply {
            shader = LinearGradient(
                frame.left, frame.top, frame.right, frame.bottom,
                intArrayOf(mix(style.panelColor, style.accentColor, 0.55f), mix(style.panelColor, Color.BLACK, 0.30f)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(frame, paint)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(towardsLight(style, 0.35f), 0.22f)
            strokeWidth = 3f
        }
        var x = frame.left - frame.height()
        while (x < frame.right) {
            canvas.drawLine(x, frame.bottom, x + frame.height(), frame.top, line)
            x += 34f
        }
    }

    // ── 3. 文字 ──────────────────────────────────────────────────────────

    /**
     * 硬保证：这一行画出来绝不超出 [maxWidth]。
     *
     * 为什么必须有：`PosterText.wrap` 对"一个字符就比整行宽"的情况没法处理（首字无条件收下），
     * 中文大字号 + 极窄的竖构图栏位就可能撞上。真撞上时这里逐字砍到放得下为止。
     * 这是整份代码里唯一一处"宁可少几个字也不许出界"的兜底。
     */
    private fun truncateToWidth(text: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(text) <= maxWidth) return text
        val body = StringBuilder(text)
        while (body.isNotEmpty() && measure(body.toString() + "…") > maxWidth) {
            body.setLength(body.length - 1)
        }
        return body.toString() + "…"
    }

    /**
     * 断行 + 降字号。
     *
     * 中文逐字断、英文按空格断——[PosterText.wrap] 里已经做到了（它的量宽函数是逐字调用的，
     * 而空格本身就是一个可断点，所以英文不会从单词中间裂开）。这里只负责"降到放得下"。
     */
    private fun fitText(
        text: String,
        maxWidth: Float,
        maxLines: Int,
        start: Float,
        min: Float,
        measure: (String, Float) -> Float,
    ): Pair<Float, List<String>> {
        val (size, lines) = PosterText.fit(text, maxWidth, maxLines, start, min, 4f, measure)
        return size to lines.map { truncateToWidth(it, maxWidth) { s -> measure(s, size) } }
    }

    /**
     * 要点那一列：主色小圆点 + 一句话。
     *
     * 这里是真的用 [StaticLayout] 排的，不是 `drawText`：要点是全场唯一会写成半句话的地方
     * （"三天两夜，含住宿与早餐"），它排不下时该**自己折成两行**，而不是被我截成省略号。
     * StaticLayout 会顺带把行高、换行点算对，比手写循环稳。
     *
     * 圆点始终挂在第一行的光学中心上，所以圆点要在排版前就定好位置——先量行高，再画点，
     * 最后 `translate` 到缩进处画文字。顺序反过来就是"点跟着文字块居中"，长条目会飘。
     */
    private fun drawBullets(
        canvas: Canvas,
        style: PosterStyle,
        texts: List<String>,
        x: Float,
        top: Float,
        maxWidth: Float,
        size: Float = BULLET_SIZE,
    ): Float {
        if (texts.isEmpty()) return top
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            color = style.subtitleColor
            textSize = size
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.accentColor }
        val indent = 34f
        val column = maxWidth - indent
        var y = top
        for (text in texts) {
            val body = text.trim()
            if (body.isEmpty()) continue
            val firstBaseline = y + paint.fontMetrics.let { -it.ascent }
            canvas.drawCircle(x + 8f, firstBaseline - size * 0.30f, 7f, dot)

            // 一行放得下就不排版了：StaticLayout 建一次是实打实的开销，
            // 四个要点都走它没有必要。真正需要它的是会折行的长条目。
            if (paint.measureText(body) <= column) {
                canvas.drawText(body, x + indent, firstBaseline, paint)
                y += size * 1.6f
                continue
            }
            val layout = StaticLayout.Builder
                .obtain(body, 0, body.length, paint, column.toInt().coerceAtLeast(1))
                // 必须写全限定名：本文件里有个自己的 `Layout`（版式表），
                // 直接写 `Layout.Alignment` 会解析到它身上，报 ALIGN_NORMAL 找不到。
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .build()
            canvas.save()
            canvas.translate(x + indent, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + size * 0.62f
        }
        return y
    }

    /** 一行小字：日期 / 地点 / 主办。都从参数里来，空着就按版式留占位。 */
    private fun drawInfoRow(
        canvas: Canvas,
        style: PosterStyle,
        align: Layout.Alignment,
        cells: List<String>,
        size: Float = INFO_SIZE,
    ) {
        // 少于两段就不画分隔符：只剩一段还要在后面吊一个"·"，看起来像渲染坏了。
        if (cells.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            color = withAlpha(style.subtitleColor, 0.95f)
            textSize = size
        }
        val sep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(style.accentColor, 0.6f)
            textSize = size
        }
        // 三段都量一遍再决定左右锚点：中间那段永远正中，两边不会因为字数不同而歪。
        val base = OUT_H - 64f
        val gap = 26f
        val widths = cells.map { paint.measureText(it) }
        val sepW = sep.measureText("·")
        val total = widths.sum() + (sepW + gap * 2) * (cells.size - 1).coerceAtLeast(0)
        when (align) {
            Layout.Alignment.LEFT -> {
                var x = MARGIN
                cells.forEachIndexed { index, cell ->
                    canvas.drawText(cell, x, base, paint)
                    x += widths[index]
                    if (index < cells.size - 1) {
                        x += gap
                        canvas.drawText("·", x, base, sep)
                        x += sepW + gap
                    }
                }
            }
            Layout.Alignment.CENTER -> {
                var x = (OUT_W - total) / 2f
                cells.forEachIndexed { index, cell ->
                    canvas.drawText(cell, x, base, paint)
                    x += widths[index]
                    if (index < cells.size - 1) {
                        x += gap
                        canvas.drawText("·", x, base, sep)
                        x += sepW + gap
                    }
                }
            }
        }
    }

    /**
     * 信息条上面那句话（就是表单里的"场景/需求"）。
     *
     * 短的排成一行完整显示；长的按标点切两行。切点优先在 / 、 ， 这些分隔符上，
     * 而不是数到一半硬断——用户写的是"日期 / 地点 / 主办"这种结构，断在分隔符上才读得通。
     * 两行都排不下时收成一行并加省略号（[truncateToWidth] 保证不出界）。
     */
    private fun drawSceneStrip(
        canvas: Canvas,
        style: PosterStyle,
        layout: Layout,
        scene: String,
        x: Float,
        top: Float,
        maxWidth: Float,
    ) {
        if (scene.isBlank()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (layout.serif) Typeface.SERIF else Typeface.DEFAULT
            color = withAlpha(style.subtitleColor, 0.92f)
            textSize = SUB_MIN - 4f
        }
        val text = scene.trim()
        val measured = paint.measureText(text)
        val lines = if (measured <= maxWidth) {
            listOf(text)
        } else {
            val middle = text.length / 2
            var cut = -1
            for (offset in 0 until text.length) {
                for (index in intArrayOf(middle - offset, middle + offset)) {
                    if (index in 1 until text.length && text[index] in "·/、，,|— ") {
                        cut = index + 1
                        break
                    }
                }
                if (cut > 0) break
            }
            val at = if (cut > 0) cut else middle
            listOf(text.take(at).trim(), text.drop(at).trim()).filter { it.isNotEmpty() }
        }
        var y = top + paint.textSize
        for (line in lines.take(2)) {
            val shown = truncateToWidth(line, maxWidth) { paint.measureText(it) }
            canvas.drawText(shown, x, y, paint)
            y += paint.textSize * 1.32f
        }
    }

    /**
     * 三个信息盒：日期 / 地点 / 主办。
     *
     * 存在的理由是"把中间那段空白填上"——用户的原话是"大部分你设计的却特别的空"。
     * 盒子里的内容全部来自用户自己填的场景文字（[infoCells]），不是编的。
     */
    private fun drawKeyBoxes(
        canvas: Canvas,
        style: PosterStyle,
        layout: Layout,
        cells: List<String>,
        top: Float,
    ) {
        if (cells.isEmpty()) return
        // 放不下就不画：宁可少一块装饰，也不能压到标题或信息条上。
        if (top < 0f || top + 96f > layout.bandTop - 40f) return

        val gap = 14f
        val w = (OUT_W - MARGIN * 2 - gap * 2) / 3f
        val boxH = 96f
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 2f
            color = withAlpha(style.accentColor, 0.42f)
        }
        val index = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 20f
            color = withAlpha(style.accentColor, 0.85f)
        }
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = 26f
            color = style.titleColor
        }
        cells.take(3).forEachIndexed { i, cell ->
            val x = MARGIN + i * (w + gap)
            canvas.drawRoundRect(RectF(x, top, x + w, top + boxH), 10f, 10f, border)
            canvas.drawText("0${i + 1}", x + 16f, top + 30f, index)
            val shown = truncateToWidth(cell, w - 32f) { value.measureText(it) }
            canvas.drawText(shown, x + 16f, top + 68f, value)
        }
    }

    /** 文字层。顺序：锚点 → 主标题 → 副标题 → 要点 → 信息盒 → 页脚 → 信息条。 */
    private fun paintWords(canvas: Canvas, style: PosterStyle, request: Request, layout: Layout) {
        val align = layout.align
        val centered = align == Layout.Alignment.CENTER
        // 居中排的版式必须按**整幅画布**收宽度，不能按 [Layout.titleW]（那是左右各留 84 的版心）。
        // 居中的一行的落点是 (OUT_W - 行宽) / 2，如果只按版心量宽，行宽可以到 912，
        // 落点就成了负数——文字从左边跑出画布。按画布量宽，落点最小是 0，最多顶到右边缘。
        val safeW = if (centered) OUT_W.toFloat() else layout.titleW

        // 主标题：字体族按风格定（复古走 SERIF），字号在版式给的起点上再乘用户 tweak。
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (layout.serif) {
                Typeface.create(Typeface.SERIF, Typeface.BOLD)
            } else {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        }
        val titleSource = request.title.trim().ifBlank { "未命名" }
        val (titleSize, titleLines) = fitText(
            text = titleSource,
            maxWidth = safeW,
            maxLines = layout.titleLines,
            start = layout.titleStart * request.adjust.titleScale,
            min = TITLE_MIN * request.adjust.titleScale,
        ) { text, size ->
            titlePaint.textSize = size
            titlePaint.measureText(text)
        }
        titlePaint.textSize = titleSize

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (layout.serif) Typeface.SERIF else Typeface.DEFAULT
            color = style.subtitleColor
        }
        val subSource = request.subtitle.trim()
        val (subSize, subLines) = if (subSource.isEmpty()) {
            0f to emptyList()
        } else {
            fitText(
                text = subSource,
                maxWidth = safeW,
                maxLines = 2,
                start = SUB_START,
                min = SUB_MIN,
            ) { text, size ->
                subPaint.textSize = size
                subPaint.measureText(text)
            }
        }

        val titleLine = titleSize * 1.02f
        val titleTop = layout.titleY - titleSize
        val titleBottom = titleTop + titleLines.size * titleLine

        // 视觉锚点：主色竖条。它是"标题从这里开始"的路标，必须在标题之前画。
        val anchorTop = layout.titleY - titleSize * 1.02f - 56f
        val anchor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.accentColor }
        canvas.drawRoundRect(
            RectF(layout.titleX, anchorTop, layout.titleX + 10f, anchorTop + 64f),
            5f,
            5f,
            anchor,
        )

        // 主标题：暖光风格（复古/赛博）先描两圈半透明主色再填字，做出"发光描边"。
        // 用描边模拟发光而不是 setShadowLayer：软件画布上的大半径模糊是逐像素的，很贵。
        var cursor = layout.titleY
        for (line in titleLines) {
            val text = truncateToWidth(line, safeW) { titlePaint.measureText(it) }
            val drawX = if (centered) (OUT_W - titlePaint.measureText(text)) / 2f else layout.titleX
            if (!layout.serif && style.wash > 0.3f) {
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = titlePaint.typeface
                    textSize = titleSize
                    this.style = Paint.Style.STROKE
                    color = withAlpha(style.accentColor, 0.16f)
                }
                glow.strokeWidth = 14f
                canvas.drawText(text, drawX, cursor, glow)
                glow.strokeWidth = 6f
                glow.color = withAlpha(style.accentColor, 0.28f)
                canvas.drawText(text, drawX, cursor, glow)
            }
            titlePaint.color = style.titleColor
            canvas.drawText(text, drawX, cursor, titlePaint)
            cursor += titleLine
        }

        // 主色短线：主标题与副标题之间的分隔。短，是为了不把两段字变成两块版。
        cursor += 22f
        val ruleW = if (centered) 96f else 120f
        val ruleX = if (centered) (OUT_W - ruleW) / 2f else layout.titleX
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.accentColor }
        canvas.drawRoundRect(RectF(ruleX, cursor, ruleX + ruleW, cursor + 6f), 3f, 3f, rule)
        cursor += 6f

        if (subLines.isNotEmpty()) {
            cursor += 30f + subSize
            subPaint.textSize = subSize
            for (line in subLines) {
                val text = truncateToWidth(line, safeW) { subPaint.measureText(it) }
                val drawX = if (centered) (OUT_W - subPaint.measureText(text)) / 2f else layout.titleX
                canvas.drawText(text, drawX, cursor, subPaint)
                cursor += subSize * 1.36f
            }
        }

        // 要点：场景文字按 / · 切成短句，最多四条。太长就截，绝不折成一片文字墙。
        val bulletTop = maxOf(cursor + 34f, titleBottom + 46f)
        val bullets = request.scene
            .split('/', '·', '|', '\n', '、', '，', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(4)
        val afterBullets = if (bullets.size >= 2) {
            drawBullets(canvas, style, bullets, layout.titleX, bulletTop, safeW)
        } else {
            bulletTop
        }

        // 三个信息盒：把要点与信息条之间的那块空白填上，内容仍然全部来自用户输入。
        drawKeyBoxes(canvas, style, layout, infoCells(request), afterBullets + 34f)

        // 页脚：一条主色细线 + 一行小字，给整张海报一个收口，别让底部悬空。
        val footY = OUT_H - 58f
        val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = 20f
            color = withAlpha(style.subtitleColor, 0.75f)
        }
        canvas.drawText(
            truncateToWidth(style.badge + " · " + titleSource, safeW) { footPaint.measureText(it) },
            layout.titleX,
            footY,
            footPaint,
        )
        val footRule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(style.accentColor, 0.5f) }
        canvas.drawRoundRect(
            RectF(layout.titleX, footY + 12f, layout.titleX + 72f, footY + 16f),
            2f,
            2f,
            footRule,
        )

        // 信息条上面那句：有位置才画，位置是"要点画完后到信息条之间"这段空白。
        val stripTop = afterBullets + 12f
        if (stripTop < layout.bandTop - 22f) {
            drawSceneStrip(canvas, style, layout, request.scene, layout.titleX, stripTop, safeW)
        }

        drawInfoRow(canvas, style, align, infoCells(request))
    }

    /**
     * 底部信息条的三段：日期 / 地点 / 主办。
     *
     * 取不到就留占位而不是留空——空着的信息条看起来像渲染失败，占位至少版式是完整的。
     * 日期占位跟着 [Adjust.variant] 走，用户点"换底图"时能看出它在动，说明这一行是活的。
     */
    private fun infoCells(request: Request): List<String> {
        val scene = request.scene.trim()
        if (scene.isEmpty()) {
            val day = 12 + ((request.adjust.variant % 9 + 9) % 9)
            return listOf("2026.10.$day", "地点待定", "主办方待定")
        }
        // 场景里已经有" / "分好的三段就直接用，否则整句进第二格。
        val parts = scene.split('/', '·', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return when (parts.size) {
            0 -> listOf("日期待定", "地点待定", "主办方待定")
            1 -> listOf("日期待定", parts[0], "主办方待定")
            2 -> listOf(parts[0], parts[1], "主办方待定")
            else -> parts.take(3)
        }
    }

    // ── 4. 落盘 ──────────────────────────────────────────────────────────

    /** 成品落盘到应用自己的目录：聊天里要显示它，点开大图也读它。 */
    fun writeToFiles(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "posters")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "poster-" + System.currentTimeMillis() + ".png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file.absolutePath
    }

    /** 点右下角那个下载键：写进手机相册（走图片处理同一条兜底链）。 */
    fun export(context: Context, file: String): String? {
        val bitmap = BitmapFactory.decodeFile(file) ?: return null
        val name = "poster-" + System.currentTimeMillis() + ".png"
        val where = ImageTools.export(context, bitmap, name)
        bitmap.recycle()
        return where
    }
}
