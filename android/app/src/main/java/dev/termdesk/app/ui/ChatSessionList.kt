package dev.termdesk.app.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.ChatInfo
import dev.termdesk.app.data.SessionSort
import dev.termdesk.app.ui.theme.Semantic


/**
 * The one bar. At the list level it shows the section and a new-chat action; in
 * a conversation the same row becomes back + title + details, so there is never
 * a second stacked header.
 */
@Composable
internal fun SessionBar(
    title: String,
    open: Boolean,
    expanded: Boolean,
    onLeading: () -> Unit,
    onToggleDetail: () -> Unit,
    onNew: () -> Unit,
    canResume: Boolean,
    onResume: () -> Unit,
    onOpenTerminals: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLeading) {
            Icon(
                imageVector = if (open) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Menu,
                contentDescription = if (open) "返回" else "分区",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        if (canResume) {
            TextButton(onClick = onResume) { Text("继续对话") }
        }
        if (open) {
            IconButton(onClick = onOpenTerminals) {
                Icon(
                    Icons.Outlined.Terminal,
                    contentDescription = "命令行",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleDetail) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp
                    else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起详情" else "展开详情",
                    tint = if (expanded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            IconButton(onClick = onNew) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "新建对话",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Two states of one list: live conversations and recorded history. */
@Composable
internal fun SessionTabs(
    tab: Int,
    chatCount: Int,
    sessionCount: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("进行中" to chatCount, "历史" to sessionCount).forEachIndexed { index, (label, count) ->
            val selected = index == tab
            Text(
                text = if (count > 0) "$label $count" else label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The order switch for the list below: newest first, or A→Z by title.
 *
 * It is drawn at the end of the kernel row, and that placement is the point. On the tab
 * row above it had nothing that could give when the text grew: measured on the test phone
 * (339dp wide, a 1.35 font scale) the three chips filled 96% of the width. The owner's
 * phone is wider (400dp) but set to 1.45, which lands in the same marginal place, and any
 * narrower screen would have clipped it. The kernel chips beside it scroll, so a squeeze
 * is absorbed there and this stays whole at any font scale.
 *
 * The label names the order IN FORCE rather than the one a tap would switch to: a chip
 * reading "名称" while the list is sorted by time reads as a button already pressed.
 */
@Composable
internal fun SortSwitch(sort: SessionSort, onSort: (SessionSort) -> Unit) {
    Text(
        text = "排序：${sort.label}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable {
                onSort(if (sort == SessionSort.Recent) SessionSort.Name else SessionSort.Recent)
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * Which kernel's conversations are listed.
 *
 * The hierarchy is kernel -> workspace -> conversation, and this is the outermost level.
 * The reason it exists: one machine had 313 recorded sessions across every kernel at
 * once, so "find my OpenCode conversation" meant reading Codex's and MiMo's history too.
 *
 * "全部" is offered first and is the default, because narrowing is a way to find one
 * thing faster, not a mode the person should have to leave. Only kernels that have
 * something to show appear at all — a tab that lands on an empty screen is a button that
 * cannot work, which this project has had to remove more than once.
 *
 * Horizontally scrollable rather than wrapped: five kernels at a 1.45 font scale exceed
 * 400dp, and a wrapped second row would push the list down for a control used rarely.
 * It also shares its row with the order switch, which is what lets the switch stay whole
 * when the text grows: this row can scroll, the switch cannot shrink.
 */
@Composable
internal fun EngineScopeRow(
    engines: List<String>,
    selected: String?,
    labelOf: (String) -> String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val choices = listOf<String?>(null) + engines
        for (choice in choices) {
            val isSelected = choice == selected
            Text(
                text = if (choice == null) "全部" else labelOf(choice),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(choice) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
internal fun KernelBadge(engine: String?) {
    val text = when (engine?.lowercase()) {
        null, "" -> return
        "codex" -> "CODEX"
        "dsh" -> "DSH"
        else -> engine.uppercase()
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Index of live chats, shown when no conversation is open.
 *
 * Live chats are not the same thing as recorded sessions: a session is history
 * on disk, a chat is a runtime that can still be talked to. They are listed
 * separately so the distinction stays visible.
 */
@Composable
internal fun ChatRow(
    chat: ChatInfo,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    /**
     * Whether closing belongs on this row.
     *
     * It does on "进行中" — those are runtimes the agent is holding, and ending one
     * is a real action. It does not on "历史": a recorded session on disk is not
     * something the phone can stop, and an x that refuses to do anything teaches
     * people to distrust the other x.
     */
    allowClose: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(chat)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chat.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${chat.cwd} · ${chat.eventCount} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatChatTime(chat.lastUsedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (allowClose) {
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "关闭会话",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
internal fun StatusDot(chat: ChatInfo) {
    val color = when {
        chat.isRunning -> MaterialTheme.colorScheme.primary
        chat.isFailed -> MaterialTheme.colorScheme.error
        chat.ready -> Semantic.current.success
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
