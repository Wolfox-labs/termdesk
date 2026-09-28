package dev.termdesk.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One Monokai for TermDesk.
 *
 * Colour values are taken from the project's own dual-mode preset
 * (wolfox-build/new-api web/src/styles/theme-presets.css, `data-theme-preset='monokai'`),
 * which itself derives from the MIT-licensed VS Code theme azemoh/vscode-one-monokai.
 * Both the paper-light and dark blocks are mirrored here, including the
 * deepened light-mode accents that exist to keep contrast >= 4.5:1 on paper.
 *
 * The larger-font tier is not cosmetic and is reproduced deliberately: the
 * project's override bumps every text step and tightens spacing, because
 * Monokai reads better with bigger glyphs. Ignoring it would make this app
 * visibly diverge from the desktop look the user already works in.
 *
 * Syntax palette (used for terminal and code surfaces):
 *   blue #61afef · green #98c379 · yellow #e5c07b · purple #c678dd
 *   red #e06c75 · cyan #56b6c2 · orange #d19a66 · comment #676f7d
 */
object Monokai {
    // --- dark: the defining pair ---
    val darkBackground = Color(0xFF282C34)
    val darkForeground = Color(0xFFABB2BF)
    val darkCard = Color(0xFF21252B)
    val darkPopover = Color(0xFF2B3038)
    val darkPrimary = Color(0xFF61AFEF)
    val darkOnPrimary = Color(0xFF282C34)
    val darkSecondary = Color(0xFF2C313A)
    val darkMuted = Color(0xFF2C313A)
    val darkMutedForeground = Color(0xFF7F848E)
    val darkAccent = Color(0xFF3E4451)
    val darkBorder = Color(0xFF3E4451)
    val darkSidebar = Color(0xFF21252B)
    val darkSkeletonBase = Color(0xFF2C313A)

    val destructive = Color(0xFFE06C75)
    val destructiveOn = Color(0xFFFFFFFF)
    val success = Color(0xFF98C379)
    val successOn = Color(0xFF1E242C)
    val warning = Color(0xFFE5C07B)
    val warningOn = Color(0xFF1E242C)
    val info = Color(0xFF56B6C2)
    val infoOn = Color(0xFF1E242C)
    val neutral = Color(0xFF5C6370)

    // --- light: warm paper, with accents deepened for contrast ---
    val lightBackground = Color(0xFFF8F8F2)
    val lightForeground = Color(0xFF49483E)
    val lightCard = Color(0xFFFFFFFF)
    val lightPrimary = Color(0xFF2F6FD0)
    val lightOnPrimary = Color(0xFFFFFFFF)
    val lightSecondary = Color(0xFFE8E8E0)
    val lightMuted = Color(0xFFEFEFE8)
    val lightMutedForeground = Color(0xFF6E6A5E)
    val lightAccent = Color(0xFFE2E0D6)
    val lightBorder = Color(0xFFDDD8CA)
    val lightInput = Color(0xFFD5D0C0)
    val lightSidebar = Color(0xFFF0EFE7)
    val lightSkeletonBase = Color(0xFFE8E6DC)

    val lightDestructive = Color(0xFFC95D63)
    val lightSuccess = Color(0xFF4D9D5D)
    val lightWarning = Color(0xFFB8862E)
    val lightInfo = Color(0xFF3A9BA5)

    // --- shared syntax hues ---
    val syntaxComment = Color(0xFF676F7D)
    val syntaxString = Color(0xFFE5C07B)
    val syntaxKeyword = Color(0xFFE06C75)
    val syntaxFunction = Color(0xFF98C379)
    val syntaxType = Color(0xFF61AFEF)
    val syntaxNumber = Color(0xFFC678DD)
    val syntaxOperator = Color(0xFF56B6C2)
    val syntaxParameter = Color(0xFFD19A66)
}

/**
 * Typography.
 *
 * These sit between Material's defaults and the project's `data-theme-scale='xl'`
 * tier. XL alone suits a desktop web app, but Android already multiplies every
 * `sp` by the user's system font scale — 1.45 on this device — so adopting XL
 * verbatim doubled the inflation and broke layouts: "80%" wrapped onto two lines
 * and disk captions split mid-value. Values here stay moderate and let the
 * system setting supply the user's preferred amount of extra scaling.
 */
val MonokaiTypography = Typography(
    headlineLarge = TextStyle(fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

/** Monospace stack for terminal and code surfaces. */
val MonospaceStyle = FontFamily.Monospace

/**
 * Compact steps for dense list rows (terminal output, process and service rows),
 * where even the moderate scale would be too tall.
 */
val DenseTypography = Typography(
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp),
)

/** Spacing and radius from the same preset (--spacing 0.3rem, --radius 0.375rem). */
object Metrics {
    /** 0.3rem at 16px root. */
    val spacing = 4.8f
    /** 0.375rem. */
    val radius = 6
}
