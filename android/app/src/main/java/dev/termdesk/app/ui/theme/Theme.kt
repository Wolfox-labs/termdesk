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
 * Two variants of One Monokai, matching the project's dual-mode preset:
 *   - dark  — the classic #282c34 canvas, used by default
 *   - light — the warm-paper variant (#f8f8f2 canvas) with deepened accents
 *
 * The default follows the system setting, with an explicit override available
 * from the app so the choice survives a system switch.
 */
enum class ThemeMode { System, Dark, Light }

private val DarkScheme = darkColorScheme(
    primary = Monokai.darkPrimary,
    onPrimary = Monokai.darkOnPrimary,
    primaryContainer = Monokai.darkAccent,
    onPrimaryContainer = Monokai.darkForeground,
    secondary = Monokai.darkSecondary,
    onSecondary = Monokai.darkForeground,
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

private val LightScheme = lightColorScheme(
    primary = Monokai.lightPrimary,
    onPrimary = Monokai.lightOnPrimary,
    primaryContainer = Monokai.lightAccent,
    onPrimaryContainer = Monokai.lightForeground,
    secondary = Monokai.lightSecondary,
    onSecondary = Monokai.lightForeground,
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

private val DarkSemantics = SemanticColors(
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

private val LightSemantics = SemanticColors(
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

val LocalSemanticColors = staticCompositionLocalOf { DarkSemantics }

/** Convenience accessor: `Semantic.success`. */
object Semantic {
    val current: SemanticColors
        @Composable get() = LocalSemanticColors.current
}

/** Traffic-light thresholds for meters, in the Monokai palette. */
object MeterChars {
    /** Non-composable fallbacks for use outside composition. */
    val ok = Monokai.success
    val warn = Monokai.warning
    val danger = Monokai.destructive
}

/**
 * Threshold colour for a percentage meter. Reads the active scheme so the meter
 * stays correct in both light and dark mode.
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
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }

    androidx.compose.material3.MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = MonokaiTypography,
    ) {
        CompositionLocalProvider(
            LocalSemanticColors provides if (dark) DarkSemantics else LightSemantics,
            content = content,
        )
    }
}
