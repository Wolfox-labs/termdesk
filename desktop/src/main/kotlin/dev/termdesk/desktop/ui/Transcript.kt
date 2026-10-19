package dev.termdesk.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termdesk.app.ui.theme.Semantic
import dev.termdesk.desktop.workbench.Line

/**
 * One transcript event.
 *
 * The console is a reader here, so the renderer's job is to keep a real answer
 * readable and fold everything that is machinery: tool calls, reasoning and
 * engine chatter are one or three lines until asked to open, instead of pushing
 * the conversation off screen.
 */
@Composable
internal fun TranscriptLine(line: Line) {
    when {
        line.kind == "turn" -> Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(Modifier.weight(1f))
            if (line.text.isNotBlank()) {
                Text(
                    line.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        line.kind == "message" && line.role == "user" -> UserBubble(line)
        line.kind == "message" -> AssistantText(line)
        line.kind == "error" -> MessageBlock(line.text, MaterialTheme.colorScheme.error)
        line.kind == "reasoning" -> FoldedText(
            label = "思考",
            text = line.text,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        line.kind == "tool" || line.kind == "tool_result" || line.kind == "command" -> FoldedText(
            label = (line.name.ifBlank { line.kind }) + if (line.streaming) " · 进行中" else "",
            text = line.text,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            mono = true,
        )
        line.kind == "local" || line.kind == "engine_note" || line.kind == "engine_log" -> Text(
            line.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            maxLines = if (line.kind == "engine_log") 3 else 6,
            overflow = TextOverflow.Ellipsis,
        )
        line.kind == "context" -> FoldedText(
            label = "上下文",
            text = line.text,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        line.text.isNotBlank() -> MessageBlock(line.text, MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun UserBubble(line: Line) {
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                "你",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
            if (line.files.isNotEmpty()) FileRow(line.files)
        }
    }
}

@Composable
private fun AssistantText(line: Line) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        MarkdownText(line.text + if (line.streaming) " ▍" else "")
        if (line.files.isNotEmpty()) FileRow(line.files)
    }
}

@Composable
private fun FileRow(files: List<String>) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        files.take(4).forEach { path ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    fileName(path).ifBlank { path },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageBlock(text: String, color: androidx.compose.ui.graphics.Color) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        )
    }
}

/** A one-line piece of machinery that opens on click. */
@Composable
private fun FoldedText(label: String, text: String, tint: androidx.compose.ui.graphics.Color, mono: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (open) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!open && text.isNotBlank()) {
                Text(
                    text.lineSequence().firstOrNull().orEmpty().take(120),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (open) {
            SelectionContainer {
                Text(
                    text,
                    style = if (mono) {
                        MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, bottom = 4.dp),
                )
            }
        }
    }
}

// ---- markdown-lite ----------------------------------------------------------

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val marker: String, val text: String) : Block
    data class Code(val lang: String, val body: String) : Block
}

private val bulletPattern = Regex("^(\\d+[.)]|[-*+])\\s+(.*)$")

private fun parseBlocks(source: String): List<Block> {
    val out = mutableListOf<Block>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) out.add(Block.Paragraph(paragraph.toString().trim()))
        paragraph.setLength(0)
    }

    var inCode = false
    var language = ""
    val code = StringBuilder()
    for (raw in source.lines()) {
        val trimmed = raw.trimStart()
        if (trimmed.startsWith("```")) {
            if (inCode) {
                out.add(Block.Code(language, code.toString().trimEnd()))
                code.setLength(0)
                inCode = false
                language = ""
            } else {
                flush()
                inCode = true
                language = trimmed.removePrefix("```").trim()
            }
            continue
        }
        if (inCode) {
            code.append(raw).append('\n')
            continue
        }
        when {
            trimmed.isBlank() -> flush()
            trimmed.startsWith("#") -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                out.add(Block.Heading(level, trimmed.drop(level).trim()))
            }
            bulletPattern.matches(trimmed) -> {
                flush()
                val match = bulletPattern.matchEntire(trimmed)!!
                out.add(Block.Bullet(match.groupValues[1], match.groupValues[2]))
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(raw.trimEnd())
            }
        }
    }
    if (inCode) out.add(Block.Code(language, code.toString().trimEnd())) else flush()
    return out
}

private val inlinePattern = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`|\\[[^]]+]\\([^)]+\\))")

@Composable
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    val inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    var cursor = 0
    for (match in inlinePattern.findAll(text)) {
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.removeSurrounding("**"))
            }
            token.startsWith("`") -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = inlineCodeBackground),
            ) { append(token.removeSurrounding("`")) }
            token.startsWith("[") -> {
                val label = token.substringAfter('[').substringBefore(']')
                val url = token.substringAfter('(').substringBeforeLast(')')
                withStyle(SpanStyle(color = linkColor)) { append(label) }
                if (url.isNotBlank() && !url.startsWith("http")) append(" ($url)")
            }
            else -> append(token)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

@Composable
private fun MarkdownText(text: String) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(Modifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is Block.Paragraph -> SelectionContainer {
                    Text(
                        inline(block.text),
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 21.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
                is Block.Heading -> Text(
                    inline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyMedium
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                )
                is Block.Bullet -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (block.marker == "-" || block.marker == "*" || block.marker == "+") "•" else block.marker,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(16.dp),
                    )
                    SelectionContainer {
                        Text(inline(block.text), style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp)
                    }
                }
                is Block.Code -> Surface(
                    color = Semantic.current.terminalBackground,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        if (block.lang.isNotBlank()) {
                            Text(
                                block.lang,
                                style = MaterialTheme.typography.labelSmall,
                                color = Semantic.current.syntaxComment,
                            )
                        }
                        SelectionContainer {
                            Text(
                                block.body,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 17.sp,
                                    fontStyle = FontStyle.Normal,
                                ),
                                color = Semantic.current.terminalForeground,
                            )
                        }
                    }
                }
            }
        }
    }
}
