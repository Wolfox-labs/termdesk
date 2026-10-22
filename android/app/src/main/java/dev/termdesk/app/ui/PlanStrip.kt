package dev.termdesk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.termdesk.app.data.ChatEvent

/**
 * 内核自己那张任务表。
 *
 * 桌面端把「任务 1 进行中 · 11 待处理」摆在输入框上方，手机上要的是同一件事：跑长活的时候
 * 能一眼看出还剩多少、正在做哪一条，而不是从一串工具日志里猜。
 *
 * 数据来源只有一条：agent 把 `todo/write` 折成 `engine_plan` 事件，条目里带
 * `[x] 完成 / [~] 进行中 / [ ] 待办` 三种记号（同时也是 ACP `plan` 的写法）。
 * 取**最后一条**：todo 表是覆盖语义，旧的那张已经不作数了。
 */
internal data class PlanRow(val text: String, val state: Int) {
    companion object {
        const val TODO = 0
        const val DOING = 1
        const val DONE = 2
    }
}

internal data class PlanSummary(val rows: List<PlanRow>) {
    val done: Int get() = rows.count { it.state == PlanRow.DONE }
    val doing: Int get() = rows.count { it.state == PlanRow.DOING }
    val todo: Int get() = rows.count { it.state == PlanRow.TODO }
    val current: PlanRow? get() = rows.firstOrNull { it.state == PlanRow.DOING }
}

/** 一行一条任务；认 `[x]` `[~]` `[ ]` 三种前缀，没有前缀的一律当待办。 */
internal fun parsePlanLines(text: String): List<PlanRow> = text.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { raw ->
        val line = raw.removePrefix("- ").removePrefix("* ").trim()
        when {
            line.startsWith("[x]", ignoreCase = true) -> PlanRow(line.drop(3).trim(), PlanRow.DONE)
            line.startsWith("[~]") -> PlanRow(line.drop(3).trim(), PlanRow.DOING)
            line.startsWith("[-]") -> PlanRow(line.drop(3).trim(), PlanRow.DOING)
            line.startsWith("[ ]") -> PlanRow(line.drop(3).trim(), PlanRow.TODO)
            else -> PlanRow(line, PlanRow.TODO)
        }
    }
    .filter { it.text.isNotEmpty() }
    .toList()

/** 这个会话此刻的任务表；没有就返回 null（不画占位行）。 */
internal fun planOf(events: List<ChatEvent>): PlanSummary? {
    val latest = events.lastOrNull { it.kind == "engine_plan" } ?: return null
    val rows = parsePlanLines(latest.text)
    return if (rows.isEmpty()) null else PlanSummary(rows)
}

/**
 * 输入框上方那条任务带。
 *
 * 默认只露一行：数字 + 正在做的那条。点一下展开全部——跑长活时它得占地方，
 * 不跑的时候它不该抢注意力。
 */
@Composable
internal fun TaskStrip(plan: PlanSummary) {
    var open by remember(plan.rows.size) { mutableStateOf(false) }
    val summary = buildString {
        if (plan.doing > 0) append(plan.doing).append(" 进行中")
        if (plan.todo > 0) {
            if (isNotEmpty()) append(" · ")
            append(plan.todo).append(" 待处理")
        }
        if (isNotEmpty()) append(" · ")
        append("共 ").append(plan.rows.size).append(" 项")
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { open = !open }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "任务",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (open) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        val doing = plan.current
        if (!open && doing != null) {
            Text(
                text = "▸ " + doing.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (open) {
            plan.rows.take(20).forEach { row ->
                Text(
                    text = when (row.state) {
                        PlanRow.DONE -> "✓ " + row.text
                        PlanRow.DOING -> "▸ " + row.text
                        else -> "○ " + row.text
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (row.state) {
                        PlanRow.DONE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        PlanRow.DOING -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (plan.rows.size > 20) {
                Text(
                    text = "还有 ${plan.rows.size - 20} 项未显示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
