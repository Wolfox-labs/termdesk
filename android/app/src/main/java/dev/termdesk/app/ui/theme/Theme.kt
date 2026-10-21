package dev.termdesk.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.termdesk.app.BuildConfig

/**
 * TermDesk 主题 ——「数媒风 / Cyber Dark」。
 *
 * 【本次改动的核心】
 *  1. 全部颜色改为自包含：本文件不再引用 Monokai.*，新色板写在下面的 [CyberPalette] 里。
 *     → 你只需要替换 Theme.kt 这一个文件即可生效，不必先改调色板文件。
 *  2. 告别默认紫：Material3 里「选中态」的颜色来自 primary 和 secondaryContainer 两个槽位，
 *     旧主题这两个槽位偏紫，所以开关、勾选框、导航选中项全是紫色。
 *     现在统一改成「赛博青」#00E5FF 系（选中底 #0A2830 + 青色内容）。
 *  3. 深色为默认：底色改为带蓝的近黑 #0B0E14，文字 #E6EDF7，
 *     主文字对比度约 15:1（旧配色偏灰、层级不清）。
 *  4. 品红 #FF2E88 作为 third 强调色（录制中、删除确认、要抓眼球的地方）。
 *  5. 语义色补齐：成功 #2BE08A / 警告 #FFB020 / 错误 #FF4D4F，
 *     并把终端底色做得比页面底再深一档，长时间看日志不刺眼。
 *  6. 浅色主题保留，但改成「日光工作台」：白底 + 加深后的青色 #007A8C，
 *     保证白底上的可读性（亮青 #00E5FF 压白底是看不清的）。
 *
 * 【注意】其他文件里如果还有直接写死 `Monokai.xxx` 的地方，那些地方不会跟着变，
 * 建议后续逐步换成 `MaterialTheme.colorScheme.xxx` 或 `Semantic.current.xxx`。
 */
enum class ThemeMode { System, Dark, Light }

/**
 * 「数媒风」调色板。
 *
 * 命名沿用「深色为主、浅色为辅」的两套写法：无前缀 = 深色（默认主题），light* = 浅色主题。
 * 这些是给本文件内部和需要精确取色的组件用的；普通组件请优先读
 * `MaterialTheme.colorScheme` 或 `Semantic.current`。
 */
object CyberPalette {

    // ---------------- 深色（默认）：赛博暗黑 ----------------

    // 五层底，从深到浅。注意：不使用纯黑 #000000，纯黑会让层次和阴影全部消失。
    val ink = Color(0xFF0B0E14)          // 页面最底：带一点蓝的深夜色
    val card = Color(0xFF12161F)         // 卡片 / 面板 / 输入区
    val overlay = Color(0xFF1A1F2B)      // 浮层 / 弹窗 / 消息气泡
    val border = Color(0xFF232A38)       // 分隔线 / 1px 描边
    val borderStrong = Color(0xFF2E3648) // 按下 / 悬停 / 高亮边框

    // 四档文字。对比度以 #0B0E14 为基准。
    val fg = Color(0xFFE6EDF7)           // 主文字（约 15:1）
    val fgMuted = Color(0xFF9BA7BD)      // 次要文字：说明、时间、路径（约 7.5:1）
    val fgDisabled = Color(0xFF5A6478)   // 禁用 / 占位（约 3:1，只用于装饰性小字）
    val onAccent = Color(0xFF0B0E14)     // 压在亮色按钮上的深色字

    // 强调色
    val cyan = Color(0xFF00E5FF)         // 主色 · 赛博青：主按钮、进度、链接、选中描边
    val cyanPressed = Color(0xFF00B8D4)  // 主色按下一档
    val cyanGlow = Color(0x5900E5FF)     // 外发光用（35% 透明青），只给主按钮
    val cyanWash = Color(0x1F00E5FF)     // 选中底（12% 透明青）
    val cyanDeep = Color(0xFF0A2830)     // 选中底的实色版，给不接受透明度的槽位用
    val magenta = Color(0xFFFF2E88)      // 副色 · 品红：录制中、删除确认
    val violet = Color(0xFF7C4DFF)       // 点缀 · 电紫：只进渐变和插画，不做选中色

    // 语义色
    val lime = Color(0xFF2BE08A)         // 成功 / 已保存 / 在线
    val amber = Color(0xFFFFB020)        // 警告 / 离线 / 有未保存改动
    val red = Color(0xFFFF4D4F)          // 错误 / 连接断开 / 删除
    val neutral = Color(0xFF8B93A7)      // 离线灰点、未选中图标

    // 终端 & 语法高亮（终端底再深一档，语法色沿用霓虹系）
    val terminalBg = Color(0xFF070A0F)
    val terminalFg = Color(0xFFD6E2F0)
    val syntaxComment = Color(0xFF5A6478)
    val syntaxString = Color(0xFF2BE08A)
    val syntaxKeyword = Color(0xFFFF2E88)
    val syntaxFunction = Color(0xFF00E5FF)
    val syntaxType = Color(0xFF7C4DFF)
    val syntaxNumber = Color(0xFFFFB020)

    /** 品牌渐变：启动页标识、发送按钮、空状态插画。135° 方向由使用方决定。 */
    val brandGradient = listOf(cyan, violet)

    // ---------------- 浅色：「日光工作台」 ----------------

    val lightInk = Color(0xFFF4F6FA)          // 页面底：冷调浅灰，比纯白耐看
    val lightCard = Color(0xFFFFFFFF)         // 卡片
    val lightOverlay = Color(0xFFFFFFFF)      // 浮层 / 弹窗
    val lightBorder = Color(0xFFD8DEE9)       // 描边
    val lightBorderStrong = Color(0xFFB9C3D3) // 按下 / 悬停

    val lightFg = Color(0xFF131720)           // 主文字
    val lightFgMuted = Color(0xFF5A6478)      // 次要文字
    val lightFgDisabled = Color(0xFFA3ADBE)   // 禁用 / 占位
    val lightNeutral = Color(0xFF78839A)      // 未选中图标

    // 【重点】亮青 #00E5FF 压在白底上几乎看不清，浅色主题必须加深。
    val lightPrimary = Color(0xFF007A8C)      // 加深青（白底上约 5.1:1）
    val lightPrimaryPressed = Color(0xFF00606F)
    val lightCyanDeep = Color(0xFFDFF3F7)     // 浅色主题的选中底
    val lightMagenta = Color(0xFFC81E6E)      // 加深品红
    val lightViolet = Color(0xFF5B34D6)       // 加深电紫

    val lightSuccess = Color(0xFF0E9F6E)
    val lightWarning = Color(0xFFB26A00)
    val lightDanger = Color(0xFFD92D20)

    val lightTerminalBg = Color(0xFF22262E)   // 浅色模式下终端仍是深色（保持终端观感）
    val lightTerminalFg = Color(0xFFD6E2F0)
}

/**
 * 语义化状态色。放在 Material 方案之外，因为 Material 的三个槽位
 * （primary / secondary / tertiary）没有 success、warning 这类位置。
 * 组件统一从这里读，保证深色浅色两套永远同步。
 */
data class SemanticColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val info: Color,
    val onInfo: Color,
    val neutral: Color,
    /** 终端画布，比应用底色再深一档。 */
    val terminalBackground: Color,
    val terminalForeground: Color,
    val syntaxComment: Color,
    val syntaxString: Color,
    val syntaxKeyword: Color,
    val syntaxFunction: Color,
    val syntaxType: Color,
    val syntaxNumber: Color,
)

private val ModernDarkSemantics = SemanticColors(
    success = CyberPalette.lime,
    onSuccess = CyberPalette.onAccent,
    warning = CyberPalette.amber,
    onWarning = CyberPalette.onAccent,
    info = CyberPalette.cyan,
    onInfo = CyberPalette.onAccent,
    neutral = CyberPalette.neutral,
    terminalBackground = CyberPalette.terminalBg,
    terminalForeground = CyberPalette.terminalFg,
    syntaxComment = CyberPalette.syntaxComment,
    syntaxString = CyberPalette.syntaxString,
    syntaxKeyword = CyberPalette.syntaxKeyword,
    syntaxFunction = CyberPalette.syntaxFunction,
    syntaxType = CyberPalette.syntaxType,
    syntaxNumber = CyberPalette.syntaxNumber,
)

private val ModernLightSemantics = SemanticColors(
    success = CyberPalette.lightSuccess,
    onSuccess = CyberPalette.lightCard,
    warning = CyberPalette.lightWarning,
    onWarning = CyberPalette.lightCard,
    info = CyberPalette.lightPrimary,
    onInfo = CyberPalette.lightCard,
    neutral = CyberPalette.lightNeutral,
    terminalBackground = CyberPalette.lightTerminalBg,
    terminalForeground = CyberPalette.lightTerminalFg,
    // 终端在两种模式下都是深色画布，所以语法色沿用深色一套，不再加深。
    syntaxComment = CyberPalette.syntaxComment,
    syntaxString = CyberPalette.syntaxString,
    syntaxKeyword = CyberPalette.syntaxKeyword,
    syntaxFunction = CyberPalette.syntaxFunction,
    syntaxType = CyberPalette.syntaxType,
    syntaxNumber = CyberPalette.syntaxNumber,
)

private val ClassicDarkSemantics = SemanticColors(
    success = Monokai.success,
    onSuccess = Monokai.successOn,
    warning = Monokai.warning,
    onWarning = Monokai.warningOn,
    info = Monokai.info,
    onInfo = Monokai.infoOn,
    neutral = Monokai.neutral,
    terminalBackground = Color(0xFF22262E),
    terminalForeground = Color(0xFFD6DAE2),
    syntaxComment = Monokai.syntaxComment,
    syntaxString = Monokai.syntaxString,
    syntaxKeyword = Monokai.syntaxKeyword,
    syntaxFunction = Monokai.syntaxFunction,
    syntaxType = Monokai.syntaxType,
    syntaxNumber = Monokai.syntaxNumber,
)

private val ClassicLightSemantics = SemanticColors(
    success = Monokai.lightSuccess,
    onSuccess = Monokai.lightOnPrimary,
    warning = Monokai.lightWarning,
    onWarning = Monokai.lightOnPrimary,
    info = Monokai.lightInfo,
    onInfo = Monokai.lightOnPrimary,
    neutral = Monokai.lightMutedForeground,
    terminalBackground = Color(0xFF2B3038),
    terminalForeground = Color(0xFFD6DAE2),
    syntaxComment = Monokai.syntaxComment,
    syntaxString = Monokai.syntaxString,
    syntaxKeyword = Monokai.syntaxKeyword,
    syntaxFunction = Monokai.syntaxFunction,
    syntaxType = Monokai.syntaxType,
    syntaxNumber = Monokai.syntaxNumber,
)

private val ClassicDarkScheme = darkColorScheme(
    primary = Monokai.darkPrimary,
    onPrimary = Monokai.darkOnPrimary,
    primaryContainer = Monokai.darkAccent,
    onPrimaryContainer = Monokai.darkForeground,
    secondary = Monokai.darkSecondary,
    onSecondary = Monokai.darkForeground,
    secondaryContainer = Monokai.darkAccent,
    onSecondaryContainer = Monokai.darkForeground,
    background = Monokai.darkBackground,
    onBackground = Monokai.darkForeground,
    surface = Monokai.darkBackground,
    onSurface = Monokai.darkForeground,
    surfaceVariant = Monokai.darkCard,
    onSurfaceVariant = Monokai.darkMutedForeground,
    outline = Monokai.darkBorder,
    error = Monokai.destructive,
    onError = Monokai.destructiveOn,
)

private val ClassicLightScheme = lightColorScheme(
    primary = Monokai.lightPrimary,
    onPrimary = Monokai.lightOnPrimary,
    primaryContainer = Monokai.lightAccent,
    onPrimaryContainer = Monokai.lightForeground,
    secondary = Monokai.lightSecondary,
    onSecondary = Monokai.lightForeground,
    secondaryContainer = Monokai.lightAccent,
    onSecondaryContainer = Monokai.lightForeground,
    background = Monokai.lightBackground,
    onBackground = Monokai.lightForeground,
    surface = Monokai.lightCard,
    onSurface = Monokai.lightForeground,
    surfaceVariant = Monokai.lightSidebar,
    onSurfaceVariant = Monokai.lightMutedForeground,
    outline = Monokai.lightBorder,
    error = Monokai.lightDestructive,
    onError = Monokai.lightOnPrimary,
)

private val classicUi = BuildConfig.UI_LAYOUT == "classic"

val LocalSemanticColors = staticCompositionLocalOf {
    if (classicUi) ClassicDarkSemantics else ModernDarkSemantics
}

/** 便捷访问：`Semantic.current.success`。 */
object Semantic {
    val current: SemanticColors
        @Composable get() = LocalSemanticColors.current
}

/** 仪表盘/进度条的红黄绿阈值，沿用霓虹色板。 */
object MeterChars {
    /** 非 Composable 场景的兜底值。 */
    val ok = if (classicUi) Monokai.success else CyberPalette.lime
    val warn = if (classicUi) Monokai.warning else CyberPalette.amber
    val danger = if (classicUi) Monokai.destructive else CyberPalette.red
}

/**
 * 百分比仪表的阈值配色。读取当前方案，保证浅色/深色都正确。
 */
object MeterColor {
    @Composable
    fun forPercent(percent: Double): Color {
        val s = Semantic.current
        return when {
            percent >= 90 -> MaterialTheme.colorScheme.error
            percent >= 75 -> s.warning
            else -> s.success
        }
    }
}

private val DarkScheme = darkColorScheme(
    // —— 主色：赛博青。所有「选中 / 激活」的默认来源，这里彻底替换掉旧的紫色。
    primary = CyberPalette.cyan,
    onPrimary = CyberPalette.onAccent,
    primaryContainer = CyberPalette.cyanDeep,
    onPrimaryContainer = CyberPalette.cyan,
    inversePrimary = CyberPalette.lightPrimary,

    // —— 次要色：低调的蓝灰，用于不抢戏的控件。
    secondary = CyberPalette.fgMuted,
    onSecondary = CyberPalette.onAccent,
    // 【关键】NavigationBar 选中指示器、FilterChip 选中态、FilledTonalButton 都读这两个槽位。
    // 旧主题这里偏紫，就是「选中变难看紫」的真正来源。
    secondaryContainer = CyberPalette.cyanDeep,
    onSecondaryContainer = CyberPalette.cyan,

    // —— 第三色：品红，用于录制中 / 危险强调。
    tertiary = CyberPalette.magenta,
    onTertiary = CyberPalette.onAccent,
    tertiaryContainer = Color(0xFF3A0E22),
    onTertiaryContainer = Color(0xFFFFB3D1),

    // —— 底色与表面
    background = CyberPalette.ink,
    onBackground = CyberPalette.fg,
    surface = CyberPalette.ink,
    onSurface = CyberPalette.fg,
    surfaceVariant = CyberPalette.card,
    onSurfaceVariant = CyberPalette.fgMuted,
    surfaceTint = CyberPalette.cyan,
    inverseSurface = CyberPalette.fg,
    inverseOnSurface = CyberPalette.card,

    // —— 描边
    outline = CyberPalette.border,
    outlineVariant = CyberPalette.borderStrong,

    // —— 错误
    error = CyberPalette.red,
    onError = CyberPalette.onAccent,
    errorContainer = Color(0xFF4A1417),
    onErrorContainer = Color(0xFFFFD6D6),

    scrim = Color(0xFF000000),
)

private val LightScheme = lightColorScheme(
    primary = CyberPalette.lightPrimary,
    onPrimary = CyberPalette.lightCard,
    primaryContainer = CyberPalette.lightCyanDeep,
    onPrimaryContainer = CyberPalette.lightPrimary,
    inversePrimary = CyberPalette.cyan,

    secondary = CyberPalette.lightFgMuted,
    onSecondary = CyberPalette.lightCard,
    secondaryContainer = CyberPalette.lightCyanDeep,
    onSecondaryContainer = CyberPalette.lightPrimary,

    tertiary = CyberPalette.lightMagenta,
    onTertiary = CyberPalette.lightCard,
    tertiaryContainer = Color(0xFFFCE0EC),
    onTertiaryContainer = CyberPalette.lightMagenta,

    background = CyberPalette.lightInk,
    onBackground = CyberPalette.lightFg,
    surface = CyberPalette.lightCard,
    onSurface = CyberPalette.lightFg,
    surfaceVariant = CyberPalette.lightInk,
    onSurfaceVariant = CyberPalette.lightFgMuted,
    surfaceTint = CyberPalette.lightPrimary,
    inverseSurface = CyberPalette.lightFg,
    inverseOnSurface = CyberPalette.lightCard,

    outline = CyberPalette.lightBorder,
    outlineVariant = CyberPalette.lightBorderStrong,

    error = CyberPalette.lightDanger,
    onError = CyberPalette.lightCard,
    errorContainer = Color(0xFFFDE3E1),
    onErrorContainer = Color(0xFF7A1710),

    scrim = Color(0xFF000000),
)

@Composable
fun TermDeskTheme(
    mode: ThemeMode = if (classicUi) ThemeMode.Dark else ThemeMode.Light,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = when {
            classicUi && dark -> ClassicDarkScheme
            classicUi -> ClassicLightScheme
            dark -> DarkScheme
            else -> LightScheme
        },
        typography = MonokaiTypography, // 字体排版沿用原有定义，本次不动
    ) {
        CompositionLocalProvider(
            LocalSemanticColors provides when {
                classicUi && dark -> ClassicDarkSemantics
                classicUi -> ClassicLightSemantics
                dark -> ModernDarkSemantics
                else -> ModernLightSemantics
            },
            content = content,
        )
    }
}
