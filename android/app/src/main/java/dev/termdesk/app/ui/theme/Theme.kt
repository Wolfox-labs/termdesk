package dev.termdesk.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * TermDesk themes.
 *
 * 两套配色 × 两档明暗，全部可以在设置里选，不再按构建变体编译成两个 APK：
 *
 *  - [Palette.Classic] —— One Monokai：深色 #282c34 画布，浅色暖纸 #f8f8f2。
 *  - [Palette.Cyber]   —— 数媒风：近黑 #0B0E14 画布 + 赛博青 #00E5FF，
 *    浅色是「日光工作台」（白底 + 加深青）。这套配色来自 2026-10-21 那版挂起的界面工作。
 *
 * [ThemeMode.System] 跟随系统，显式选择则在系统切换时保持不变。
 */
enum class ThemeMode { System, Dark, Light }

/** 配色方案。名字进设置页，别改；改的是 [ThemeMode] 之外的这一维。 */
enum class Palette {
    Classic,
    Cyber,
    ;

    /** 设置页里的短名。 */
    val label: String
        get() = when (this) {
            Classic -> "经典"
            Cyber -> "数媒"
        }

    /** 设置页里的一句说明，讲清楚这套配色是什么来头。 */
    val hint: String
        get() = when (this) {
            Classic -> "One Monokai：深色 #282c34 画布，暖纸浅色"
            Cyber -> "赛博青 #00E5FF 配近黑 #0B0E14，浅色是白底加深青"
        }
}

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

private val CyberDarkScheme = darkColorScheme(
    // —— 主色：赛博青。所有「选中 / 激活」的默认来源。
    primary = CyberPalette.cyan,
    onPrimary = CyberPalette.onAccent,
    primaryContainer = CyberPalette.cyanDeep,
    onPrimaryContainer = CyberPalette.cyan,
    inversePrimary = CyberPalette.lightPrimary,

    // —— 次要色：低调的蓝灰，用于不抢戏的控件。
    secondary = CyberPalette.fgMuted,
    onSecondary = CyberPalette.onAccent,
    // 【关键】NavigationBar 选中指示器、FilterChip 选中态、FilledTonalButton 都读这两个槽位。
    // 经典配色这里偏紫，就是「选中变难看紫」的真正来源。
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

private val CyberLightScheme = lightColorScheme(
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

/**
 * Semantic state colours live outside the Material scheme, because Material's
 * slots have no place for success / warning / info. Components read them from
 * here so light and dark stay in sync.
 */
data class SemanticColors(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val info: Color,
    val onInfo: Color,
    val neutral: Color,
    /** Terminal canvas, slightly darker than the app background. */
    val terminalBackground: Color,
    val terminalForeground: Color,
    val syntaxComment: Color,
    val syntaxString: Color,
    val syntaxKeyword: Color,
    val syntaxFunction: Color,
    val syntaxType: Color,
    val syntaxNumber: Color,
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

private val CyberDarkSemantics = SemanticColors(
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

private val CyberLightSemantics = SemanticColors(
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

val LocalSemanticColors = staticCompositionLocalOf { ClassicDarkSemantics }

/** Convenience accessor: `Semantic.success`. */
object Semantic {
    val current: SemanticColors
        @Composable get() = LocalSemanticColors.current
}

/**
 * Traffic-light thresholds for meters.
 *
 * 原来是写死的三原色常量；配色变成运行时选项之后，这里必须跟着当前方案走，
 * 否则换到 Cyber 之后仪表盘还是旧绿旧黄。调用点都在 Composable 里，所以用
 * `@Composable get()` 而不是静态 val。
 */
object MeterChars {
    val ok: Color @Composable get() = Semantic.current.success
    val warn: Color @Composable get() = Semantic.current.warning
    val danger: Color @Composable get() = MaterialTheme.colorScheme.error
}

/**
 * Threshold colour for a percentage meter. Reads the active scheme so the meter
 * stays correct in every palette and mode.
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

@Composable
fun TermDeskTheme(
    mode: ThemeMode = ThemeMode.System,
    palette: Palette = Palette.Classic,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }

    val scheme = when {
        palette == Palette.Cyber && dark -> CyberDarkScheme
        palette == Palette.Cyber -> CyberLightScheme
        dark -> ClassicDarkScheme
        else -> ClassicLightScheme
    }
    val semantics = when {
        palette == Palette.Cyber && dark -> CyberDarkSemantics
        palette == Palette.Cyber -> CyberLightSemantics
        dark -> ClassicDarkSemantics
        else -> ClassicLightSemantics
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = MonokaiTypography,
    ) {
        CompositionLocalProvider(
            LocalSemanticColors provides semantics,
            content = content,
        )
    }
}
