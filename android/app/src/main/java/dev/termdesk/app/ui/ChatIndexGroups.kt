package dev.termdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.ChatInfo
import dev.termdesk.app.data.KernelRun
import dev.termdesk.app.data.SessionGroups
import dev.termdesk.app.data.SessionInfo
import dev.termdesk.app.data.SessionSort

/**
 * The session list, grouped by the directory each conversation works in.
 *
 * The ordering rules are in `data/SessionGroups.kt` and pinned by tests; this file
 * is only how they are drawn. The shape is what the product asked for: one
 * collapsible header per workspace, conversations inside it newest first — or A→Z,
 * when [sort] asks for that — and the workspaces themselves always in name order,
 * because a person looks for a project by name rather than by when they last
 * touched it.
 *
 * Both tabs share it on purpose. "进行中" and "历史" differ in which conversations
 * they contain, not in how a person finds one, and two implementations of the same
 * list is how the two tabs drift apart.
 *
 * A header carries the full path in its accessible description even though it
 * shows only the short name: two projects can share a folder name, and the header
 * must not be the reason someone opens the wrong one.
 */
@Composable
internal fun GroupedSessionList(
    chats: List<ChatInfo>,
    sessions: List<SessionInfo>,
    emptyTitle: String,
    emptyHint: String,
    onOpenChat: (String) -> Unit,
    onCloseChat: (String) -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
    onCreateChat: () -> Unit,
    /** Shown for the "历史" tab, which has to re-scan rather than create. */
    onScanSessions: (() -> Unit)? = null,
    /**
     * Whether a live row offers an x. True on "进行中" (those are runtimes the agent
     * holds and ending one is real); false where the list is history.
     */
    allowClose: Boolean = true,
    /**
     * Agents the PC is running that its own agent did not start.
     *
     * Rendered in a section of their own, never mixed into the groups: these are
     * processes, not conversations, and the agent holds no handle to them — so
     * they carry no action, only the fact that they are running.
     */
    externalRuns: List<KernelRun> = emptyList(),
    /** The PC's own sentence for an empty [externalRuns], or null. */
    externalRunsNote: String? = null,
    /** How the conversations inside each workspace are ordered. See [SessionSort]. */
    sort: SessionSort = SessionSort.Default,
) {
    val groups = remember(chats, sessions, sort) {
        // Named, not trailing: the lambda would otherwise bind to `sort`.
        SessionGroups.build(chats, sessions, nameOf = { workspaceShortName(it) }, sort = sort)
    }

    if (groups.isEmpty() && externalRuns.isEmpty()) {
        EmptyIndex(title = emptyTitle, hint = emptyHint, onCreateChat = onCreateChat, onScanSessions = onScanSessions)
        return
    }

    // Collapsed groups are remembered as a set of keys, so a workspace that
    // disappears and comes back does not silently re-collapse (and, more to the
    // point, so expanding one does not disturb another).
    var collapsed by remember { mutableStateOf(emptySet<String>()) }

    // Flattened by hand rather than nesting `items` inside the lazy scope: a
    // conditional `items` call is not valid there, and one flat list of typed
    // entries is easier to reason about than three interleaved generators.
    val entries = buildList {
        for (group in groups) {
            add(Entry.Header(group, group.key in collapsed))
            if (group.key in collapsed) continue
            for (chat in group.live) add(Entry.Live(chat))
            for (session in group.recorded) add(Entry.Recorded(session))
        }
        // Last, and in its own section: "something is running over there that I
        // cannot open" is useful, but it is not a conversation and must not look
        // like one.
        if (externalRuns.isNotEmpty()) {
            add(Entry.ExternalHeader(externalRuns.size, externalRunsNote))
            for (run in externalRuns) add(Entry.External(run))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            when (entry) {
                is Entry.Header -> WorkspaceHeader(
                    group = entry.group,
                    collapsed = entry.collapsed,
                    onToggle = {
                        collapsed = if (entry.collapsed) collapsed - entry.group.key
                        else collapsed + entry.group.key
                    },
                )
                is Entry.Live -> ChatRow(
                    chat = entry.chat,
                    onOpen = { onOpenChat(entry.chat.id) },
                    onClose = { onCloseChat(entry.chat.id) },
                    allowClose = allowClose,
                )
                is Entry.Recorded -> RecordedRow(
                    session = entry.session,
                    onOpen = { onOpenSession(entry.session) },
                )
                is Entry.ExternalHeader -> ExternalHeader(count = entry.count, note = entry.note)
                is Entry.External -> ExternalRunRow(run = entry.run)
            }
        }
    }
}

/** One drawn row: a workspace header, a conversation, a session, or a process. */
private sealed interface Entry {
    /** What the lazy list keys on, so a group and a conversation cannot collide. */
    val key: String

    data class Header(val group: SessionGroups.Group, val collapsed: Boolean) : Entry {
        override val key: String get() = "ws:${group.key}"
    }

    data class Live(val chat: ChatInfo) : Entry {
        override val key: String get() = "live:${chat.id}"
    }

    data class Recorded(val session: SessionInfo) : Entry {
        override val key: String get() = "rec:${session.engine}:${session.id}"
    }

    data class ExternalHeader(val count: Int, val note: String?) : Entry {
        override val key: String get() = "ext:header"
    }

    data class External(val run: KernelRun) : Entry {
        override val key: String get() = "ext:${run.kernelId}:${run.pid}"
    }
}

/**
 * "Running on the PC, not started from here."
 *
 * The heading states what the section is so a row is never mistaken for a
 * conversation: these cannot be opened, because this agent did not start them and
 * holds no handle to them.
 */
@Composable
private fun ExternalHeader(count: Int, note: String?) {
    Column(Modifier.fillMaxWidth().padding(start = 2.dp, top = 10.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "电脑上直接运行",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!note.isNullOrBlank()) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One such process, or one desktop app instance: what it is, which pid, how much. */
@Composable
private fun ExternalRunRow(run: KernelRun) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = run.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append("PID ")
                    append(run.pid)
                    // For a desktop app the memory is the whole process tree, so
                    // saying how many processes it stands for is what stops "2.1 GB"
                    // from looking like one runaway process. A plain process row has
                    // no count, and inventing "1 个进程" there would say nothing.
                    if (run.fromDesktopApp && (run.processCount ?: 0) > 1) {
                        append(" · ")
                        append(run.processCount)
                        append(" 个进程")
                    }
                    append(" · ")
                    // `formatBytes` is the one already in Components.kt: a second copy
                    // for this row would be a second answer to the same question.
                    append(formatBytes(run.memBytes))
                    append(
                        if (run.fromDesktopApp) {
                            " · 桌面里在跑，手机不能接管"
                        } else {
                            " · 在电脑上运行，手机不能接管"
                        },
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One workspace: a tap target that folds its conversations away. */
@Composable
private fun WorkspaceHeader(
    group: SessionGroups.Group,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Outlined.KeyboardArrowRight else Icons.Outlined.KeyboardArrowDown,
            // Two projects can share a folder name, so the full path is what a
            // screen reader gets even though the row shows the short name.
            contentDescription = if (collapsed) "展开 ${group.key}" else "收起 ${group.key}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = group.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = group.count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A recorded session: same shape, but it opens a transcript instead of a runtime. */
@Composable
private fun RecordedRow(session: SessionInfo, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                sessionTitle(session),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                KernelBadge(session.engine)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = buildString {
                        session.cwd?.takeIf { it.isNotBlank() }?.let { append(workspaceShortName(it)) }
                        if (isNotEmpty()) append(" · ")
                        append(formatRelativeTime(session.updatedAt))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * What the list says when it has nothing to show.
 *
 * The sentence explains where conversations come from, because an empty list with
 * only "none" reads as "the app is broken" rather than "nothing is running yet".
 */
@Composable
private fun EmptyIndex(
    title: String,
    hint: String,
    onCreateChat: () -> Unit,
    onScanSessions: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        if (onScanSessions != null) {
            TextButton(onClick = onScanSessions) { Text("重新扫描") }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onCreateChat)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text("新建对话", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
