package dev.termdesk.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Just enough Markdown for a model's answer to be readable on a phone.
 *
 * The transcript is still the engine's own text — nothing is summarised or
 * reordered — but the *markers* are rendered instead of printed: bold, inline
 * code, links, headings and bullets. Unrendered `**` and `- ` are what made a
 * long answer read as one grey lump, and paragraph spacing is added for the same
 * reason.
 */
@Composable
fun MarkdownText(
    text: String,
    style: TextStyle,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val annotated = remember(text, style, color, codeBackground) { markdown(text, style, color, codeBackground) }
    Text(
        text = annotated,
        style = style.copy(lineHeight = style.fontSize * 1.42f),
        color = color,
        modifier = modifier,
    )
}

private val INLINE = Regex(
    "\\*\\*(.+?)\\*\\*" +          // **bold**
        "|__(.+?)__" +             // __bold__
        "|`([^`\\n]+)`" +          // `code`
        "|\\[([^\\]]+)\\]\\([^)]*\\)", // [label](target)
)

private fun markdown(
    text: String,
    style: TextStyle,
    color: Color,
    codeBackground: Color,
): AnnotatedString = buildAnnotatedString {
    val lines = text.split('\n')
    lines.forEachIndexed { index, raw ->
        if (index > 0) append('\n')
        val heading = Regex("^\\s*#{1,6}\\s+").find(raw)
        val bullet = Regex("^\\s*[-*+]\\s+").find(raw)
        when {
            heading != null -> {
                // A heading is its own emphasis; the hashes are not content.
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInline(raw.substring(heading.range.last + 1), style, color, codeBackground, bold = true)
                }
            }
            bullet != null -> {
                append("• ")
                appendInline(raw.substring(bullet.range.last + 1), style, color, codeBackground, bold = false)
            }
            else -> appendInline(raw, style, color, codeBackground, bold = false)
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(
    line: String,
    style: TextStyle,
    color: Color,
    codeBackground: Color,
    bold: Boolean,
) {
    var cursor = 0
    for (match in INLINE.findAll(line)) {
        if (match.range.first > cursor) {
            withStyle(SpanStyle(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)) {
                append(line.substring(cursor, match.range.first))
            }
        }
        val groups = match.groupValues
        when {
            groups[1].isNotEmpty() || groups[2].isNotEmpty() -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
            ) { append(groups[1].ifEmpty { groups[2] }) }

            groups[3].isNotEmpty() -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = style.fontSize * 0.94f,
                    background = codeBackground,
                ),
            ) { append(groups[3]) }

            groups[4].isNotEmpty() -> withStyle(
                SpanStyle(fontStyle = FontStyle.Italic),
            ) { append(groups[4]) }
        }
        cursor = match.range.last + 1
    }
    if (cursor < line.length) {
        withStyle(SpanStyle(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)) {
            append(line.substring(cursor))
        }
    }
}
