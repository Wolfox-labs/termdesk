package dev.termdesk.app.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termdesk.app.data.ChatEvent
import dev.termdesk.app.ui.theme.Semantic

/**
 * One line of a conversation, drawn exactly as the runtime produced it.
 *
 * Split out of ChatSection.kt: the section owns the layout of the screen (tabs,
 * index, composer) while this file owns the shape of a single transcript line.
 * The app never rewrites, summarises or reorders the stream - the kinds mirror
 * the agent's own vocabulary so a reader can tell the human's words apart from
 * the model's answer, its reasoning, and the tools it ran.
 */

@Composable
internal fun ChatEventRow(event: ChatEvent) {
    when {
        event.kind == "turn" -> TurnMarker(event)
        event.kind == "step" -> Spacer(Modifier.height(0.dp))
        event.isUser -> UserLine(event)
        event.isAssistant -> AssistantLine(event)
        event.isReasoning -> ReasoningLine(event)
        event.isInjectedContext -> ContextLine(event)
        event.isTool -> ToolLine(event)
        event.isCommand -> CommandLine(event)
        event.isError -> ErrorLine(event)
        event.isLocal || event.isEngineLog -> EngineNoteLine(event)
        event.kind == "engine_plan" -> EngineOutputLine(event, label = "计划")
        event.kind == "engine_summary" -> EngineOutputLine(event, label = "摘要")
        event.kind == "usage" -> EngineOutputLine(event, label = "用量")
        event.hasText -> PlainLine(event)
        else -> Spacer(Modifier.height(0.dp))
    }
}

@Composable
internal fun UserLine(event: ChatEvent) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            MarkdownText(
                text = event.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            // A message typed while the answer was still coming waits its turn on the PC.
            // It is drawn the moment it is typed — a message that disappears until later
            // reads as a message that was lost — so this line is what stops "waiting" from
            // looking like "ignored".
            if (event.queued) {
                Text(
                    text = "排队中 · 这一轮答完就发",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun AssistantLine(event: ChatEvent) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(0.98f)) {
            MarkdownText(
                text = event.text,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (event.streaming) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "▍",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun ReasoningLine(event: ChatEvent) {
    EngineBlock(
        label = "思考",
        text = event.text,
        accent = MaterialTheme.colorScheme.onSurfaceVariant,
        streaming = event.streaming,
    )
}

/**
 * Context the runtime injected into its own conversation.
 *
 * Labelled explicitly, because it is neither the user's message nor the model's
 * answer and must not be mistaken for either.
 */
@Composable
internal fun ContextLine(event: ChatEvent) {
    val source = event.sourceKind ?: "context"
    EngineBlock(
        label = "上下文 · $source",
        text = event.text,
        accent = Semantic.current.info,
        streaming = event.streaming,
    )
}

@Composable
internal fun ToolLine(event: ChatEvent) {
    EngineBlock(
        label = if (event.kind == "tool") (event.name ?: "工具") else "工具结果",
        text = event.text,
        accent = Semantic.current.syntaxKeyword,
        streaming = event.streaming,
    )
}

@Composable
internal fun CommandLine(event: ChatEvent) {
    val label = buildString {
        append("命令")
        event.exitCode?.let { append(" · 退出码 $it") }
    }
    EngineBlock(
        label = label,
        text = event.text,
        accent = Semantic.current.syntaxString,
        streaming = event.streaming,
    )
}

@Composable
internal fun ErrorLine(event: ChatEvent) {
    EngineBlock(
        label = "错误",
        text = event.text,
        accent = MaterialTheme.colorScheme.error,
        streaming = event.streaming,
    )
}

/** Engine plan / summary / usage, shown verbatim and collapsed like other engine output. */
@Composable
internal fun EngineOutputLine(event: ChatEvent, label: String) {
    EngineBlock(
        label = label,
        text = event.text,
        accent = Semantic.current.syntaxType,
        streaming = event.streaming,
    )
}

@Composable
internal fun EngineNoteLine(event: ChatEvent) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            event.text.ifBlank { event.kind },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun PlainLine(event: ChatEvent) {
    MarkdownText(
        text = event.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Engine output: monospaced, indented, with a coloured rail and its own label.
 *
 * Collapsed by default so a long thinking trace or tool dump never buries the
 * answer. The header is one summary line (label + line/character count) with an
 * expand arrow; tapping it toggles the body. A streaming block stays open while
 * it is still growing, and collapses again once the stream ends.
 */
@Composable
internal fun EngineBlock(
    label: String,
    text: String,
    accent: Color,
    streaming: Boolean = false,
) {
    var expanded by remember { mutableStateOf(streaming) }
    // The block that was watched live folds itself up when the stream ends.
    LaunchedEffect(streaming) {
        if (!streaming) expanded = false
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) {
                    Icons.Outlined.KeyboardArrowDown
                } else {
                    Icons.Outlined.KeyboardArrowRight
                },
                contentDescription = if (expanded) "收起" else "展开",
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                engineSummaryLabel(label, text),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (streaming) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "生成中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        if (expanded && text.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(20.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One-line fold summary: label plus a size hint (lines when multi-line, else characters). */
internal fun engineSummaryLabel(label: String, text: String): String {
    if (text.isBlank()) return label
    val lines = text.count { it == '\n' } + 1
    return if (lines > 1) "$label · $lines 行" else "$label · ${text.length} 字"
}

/**
 * The engine's own turn boundary, drawn as a thin rule.
 *
 * Kept visible rather than hidden: seeing where a turn ended is how the user
 * tells "still thinking" from "finished".
 */
@Composable
internal fun TurnMarker(event: ChatEvent) {
    val reason = event.state
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = if (reason == null) " 完成 " else " 完成 · $reason ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
