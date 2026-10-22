package dev.termdesk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 会话目录：每一轮用户提问就是一章。
 *
 * 桌面端 DSH 有会话目录，长会话里能直接跳到某一轮；手机上原来只能一路往上滑。
 * 目录项只认**用户自己说的话**——模型的旁白不是章节，工具调用更不是。
 */
internal data class TurnOutline(
    /** 在聊天列表里的位置，跳转直接用它。 */
    val index: Int,
    val title: String,
    val at: Long,
)

/**
 * 从已经折好的行里挑出每一轮的起点。
 *
 * @param offset 列表头部可能有一行「作品已完成」，它占一个 item，序号要跟着挪。
 */
internal fun turnOutline(rows: List<TranscriptRow>, offset: Int = 0): List<TurnOutline> =
    rows.mapIndexedNotNull { index, row ->
        val event = (row as? TranscriptRow.One)?.event ?: return@mapIndexedNotNull null
        if (!event.isUser) return@mapIndexedNotNull null
        val title = event.text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        TurnOutline(
            index = index + offset,
            title = title.ifBlank { "（空消息）" },
            at = event.at,
        )
    }

/**
 * 目录弹层。
 *
 * 倒序：最近的一轮在最上面，和"我刚问过什么"的顺序一致；点一条跳到那条。
 */
@Composable
internal fun OutlineDialog(
    outline: List<TurnOutline>,
    onJump: (TurnOutline) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话目录 · ${outline.size} 轮", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                outline.asReversed().forEach { turn ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(turn) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = formatChatTime(turn.at),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = turn.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
