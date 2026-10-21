package dev.termdesk.app.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.ChatInfo


/**
 * 聊天页顶部那一条。
 *
 * 左边「最近会话」——用户的原话是"少走两步"：正在聊天时想换一个会话，不该先退出去、
 * 再找列表。正中间「TermDesk」——它就是这个 App 的招牌，点一下回主页（主页占满屏幕）。
 * 两边各留一块等宽的空白，招牌才真的在正中间，而不是"看着差不多"。
 */
@Composable
internal fun ChatTopStrip(onOpenChats: () -> Unit, onHome: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "☰ 最近会话",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenChats)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "TermDesk",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onHome)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        // 和左边那块等宽，招牌才会落在正中间。
        Spacer(Modifier.width(84.dp))
    }
}

/**
 * 从屏幕左边拉出来的「最近会话」。
 *
 * 用 Dialog 而不是抽屉：这一层只需要"浮在上面、点一下就切走"，不必和分区导航那套纠缠；
 * `usePlatformDefaultWidth = false` 才能自己占到整屏、把列表贴在左边。
 */
@Composable
internal fun RecentChatsPanel(
    chats: List<ChatInfo>,
    currentId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 「从左边滑出来，占半个屏幕」——用户的原话。
        // 上一版是一块写死 272dp 的面板：在 393dp 宽的机器上占了七成屏，加上整屏的暗色遮罩，
        // 看上去就是"点一下整屏都被它占住了"。现在宽度取屏幕的一半，并且真的滑进来。
        val halfWidth = (LocalConfiguration.current.screenWidthDp * 0.5f).dp
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val slide by animateDpAsState(
            targetValue = if (shown) 0.dp else -halfWidth,
            animationSpec = tween(durationMillis = 220),
            label = "recentChatsSlide",
        )
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(halfWidth)
                    .fillMaxHeight()
                    .offset(x = slide)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "最近会话",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "按最近使用从上到下排，点一条就切过去",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (chats.isEmpty()) {
                    Text(
                        text = "还没有会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    chats.forEach { item ->
                        val here = item.id == currentId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (here) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable { onPick(item.id) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (here) FontWeight.Medium else FontWeight.Normal,
                                    color = if (here) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = item.eventCount.toString() + " 条 · " +
                                        formatChatTime(item.lastUsedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                }
            }
            // 右边这片点一下就关，不用去够返回键。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = onDismiss),
            )
        }
    }
}
