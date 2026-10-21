package dev.termdesk.app.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp


/** One line in the `@` panel: what it is, what you get back, and it needs nothing from you. */
@Composable
internal fun MentionRow(mention: ChatMention, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = mention.badge,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "@" + mention.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = mention.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One line in the skeleton picker: what it looks like, and that it ships inside the app. */
@Composable
internal fun TemplateRow(template: PageTemplate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = template.badge,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = template.id,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = template.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 一套 PPT 蓝图在面板里的一行。
 *
 * 和 [TemplateRow] 同一个形状，只是数据源不同（PPT 的资源是一份章节蓝图，
 * 不是一份 HTML）。两套模板各自有自己的行，是因为它们的字段本来就不同——
 * 硬合成一个反而要到处写分支。
 */
@Composable
internal fun DeckRow(deck: DeckTemplate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = deck.badge,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = deck.id,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = deck.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One palette in the colour picker.
 *
 * The four squares are the actual colours the page will use — bg, card, primary, text —
 * so choosing is looking, not reading. Naming a colour ("科技蓝") is not enough to know
 * what the page will look like, and that gap is exactly what the user reported.
 */
@Composable
internal fun ColorChip(scheme: ColorScheme, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            scheme.swatch.forEach { hex ->
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(parseHex(hex)),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = (if (selected) "✓ " else "") + scheme.id,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** `#RRGGBB` / `#AARRGGBB` → Compose 的 Color；认不出来给中性灰，绝不抛。 */
internal fun parseHex(hex: String): Color {
    val body: String = hex.removePrefix("#")
    val value: Long = body.toLongOrNull(16) ?: return Color(0xFF888888)
    return when (body.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> Color(0xFF888888)
    }
}

/**
 * 把重新渲染好的草稿装回输入框，**光标放到末尾**。
 *
 * 这不是讲究：`TextFieldValue(text)` 的默认选区是 `TextRange(0)`——也就是把光标扔回
 * 最前面。真机上就是这个症状：改一次配色，光标跳到行首，接着打的字全插到了
 * `@网页生成` 前面。挑模板那条路径早就为同一件事踩过一次坑（见 `ChatMentions.insertion`），
 * 所以凡是由程序主动换掉草稿的地方，都必须自己把光标位置一并给出来。
 */
internal fun caretAtEnd(text: String): TextFieldValue = TextFieldValue(text, TextRange(text.length))

/**
 * 预览条上那句「还有几处要交给模型补」。
 *
 * 数字来自**真正填过的那份骨架**，不是估计：剩下几个 `{{...}}` 就是几个。
 * 用户能一眼看出这次到底是「骨架基本填好了、模型只补空」还是「全交给它写」。
 */
internal fun remainingHint(filledSkeleton: String): String {
    if (filledSkeleton.isEmpty()) return " · 骨架没读到，这次让模型自己写"
    val left = WebTemplates.remainingSlots(filledSkeleton)
    return if (left > 0) " · 还有 " + left + " 处交给模型补" else " · 槽位已填满"
}

/** One small pill in the conversation's bottom bar: label above value. */
@Composable
internal fun ComposerChip(
    label: String,
    value: String,
    withChevron: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (withChevron) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
internal fun Composer(
    /**
     * 文本与光标一起传进来。
     *
     * 这里收 `TextFieldValue` 而不是 `String`，是因为「@模板」这条路径要**指定光标位置**：
     * 只传 String 的话，输入框在外面换掉文本时会保留原来的选区，插入完标记光标还停在
     * 用户敲 @ 的地方（见 `ChatMentions.insertion`）。正常打字时这一对原样透传，
     * 不改任何行为。
     */
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    sending: Boolean,
    running: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    /** 输入框左边那个圆形加号：点了先问「相册还是文件」。 */
    onAttach: (() -> Unit)? = null,
    /**
     * How many messages are waiting for the link to come back.
     *
     * Shown rather than silently held: someone who typed a message on a train
     * needs to know it is not lost, and needs to know when it finally goes.
     */
    pendingCount: Int = 0,
    /** How many were dropped for being too old to be worth sending. */
    droppedCount: Int = 0,
) {
    Column(Modifier.fillMaxWidth()) {
        if (pendingCount > 0) {
            Text(
                text = "待发 $pendingCount 条 · 重连后自动发送",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
        if (droppedCount > 0) {
            Text(
                // Said out loud: a message that expired is a message the person
                // expected to be sent, and finding out by silence is worse.
                text = "$droppedCount 条待发消息已过期未发出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // 输入框左边一个圆形加号：上传附件的第一入口。
        // 点了先问「相册还是文件」，再跳到对应的系统选择器（相册、内存、微信/QQ 文件都能到）。
        //
        // 用 40dp 的 Box 自己画，不用 IconButton：后者自带 48dp 最小触控区，配一个 38dp
        // 的圆会让圆比输入框矮半截、还顶到屏幕左边被裁掉。输入框 minHeight 是 56dp，
        // 底部留 8dp 之后圆心的位置正好压在首行文字的中线上。
        if (onAttach != null) {
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onAttach),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "上传附件",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("输入消息", style = MaterialTheme.typography.bodyMedium) },
            textStyle = MaterialTheme.typography.bodyMedium,
            maxLines = 5,
            shape = RoundedCornerShape(11.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(),
        )
        Spacer(Modifier.width(6.dp))
        // Two buttons while a reply is streaming, where there used to be one that turned into
        // "stop" — on the reasoning that a second prompt mid-turn would be rejected. The PC
        // queues a message typed mid-turn now (ChatManager.drainQueue), so that reasoning is
        // false and the button outlived it: waiting became something the person can ask for
        // instead of something they are refused.
        if (running) {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Outlined.Stop,
                    contentDescription = "停止",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        // Shown while running only once there is something to send, so the row stays calm
        // when there is not, and appears the moment somebody types.
        if (!running || draft.text.isNotBlank()) {
            // Disabled only by what the person can control themselves. It used to also be
            // disabled while a send was in flight, which quietly removed the queue on ACP
            // kernels: there the acknowledgement arrives when the turn *ends*, so the button
            // stayed dead for the whole turn and nothing could be typed into the queue at all.
            // The PC accepts and orders the second message either way (ChatManager.drainQueue).
            if (sending && !running) {
                // "In flight" means the gap between tapping and the turn starting — not the
                // whole turn, which would claim the machine is still receiving what it has
                // already begun answering.
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            IconButton(onClick = onSend, enabled = canSend && draft.text.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = if (running) "发送（排队）" else "发送",
                    tint = if (canSend && draft.text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
    }
    }
}

/**
 * One line of the conversation.
 *
 * Alignment and colour carry the speaker, so no avatars and no bubbles are
 * needed: the user's own words sit right, the model's answer sits left, and
 * everything the engine did around it is indented as engine output. The user
 * explicitly did not want a chat-bubble look, so the transcript reads as
 * annotated text rather than a messaging app.
 */
