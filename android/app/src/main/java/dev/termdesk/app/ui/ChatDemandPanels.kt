package dev.termdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Local demand prompt for entering the key points used by web and deck templates. */
@Composable
internal fun PointsDemandPanel(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
    ) {
        Text(
            text = "这次要讲哪几个要点？一条一条写，用「、」隔开就会分成几张卡片" +
                "（不想填就点跳过，交给模型按主题拟）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("例如：专业介绍、实验室开放", style = MaterialTheme.typography.bodySmall)
                },
                textStyle = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                shape = RoundedCornerShape(9.dp),
            )
            TextButton(onClick = onConfirm) {
                Text("确定", style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = onSkip, modifier = Modifier.padding(start = 4.dp)) {
            Text("跳过，交给模型按主题拟", style = MaterialTheme.typography.labelSmall)
        }
    }
}
