package dev.termdesk.app.ui

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import dev.termdesk.app.data.ImageTools
import dev.termdesk.app.data.PosterRenderer
import dev.termdesk.app.data.PosterStyles
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp


/**
 * 作品表单里的输入框统一走 ASCII 键盘。
 *
 * 真机实测：手机上三个输入法默认都是中文，input text 敲进去的 "Digital Media Art Week"
 * 会被当成拼音转成"滴啊A让他…"——用户手打英文标题同样中招。设成 Ascii 键盘后
 * 输入法不再做候选转换，敲什么就是什么（想打中文仍可自己切回中文输入法）。
 */
internal val MentionFieldKeyboard = KeyboardOptions(
    keyboardType = KeyboardType.Ascii,
    imeAction = ImeAction.Next,
)


/** 当前这张海报的配方：右上角那些快捷键就是拿它再画一遍。 */
internal data class PosterRecipe(
    val styleId: String,
    val title: String,
    val subtitle: String,
    val scene: String,
    val adjust: PosterRenderer.Adjust,
)

/**
 * 海报生成的表单——**内联在聊天页的面板区**，不再是 AlertDialog 悬浮窗。
 *
 * 为什么改成内联：用户点名"不要单独设置悬浮 UI 窗口，都在文本框那里弹出操作就可以"。
 * 真机上悬浮窗正好压在聊天气泡上，边看对话边写标题做不到；面板区本来就贴着输入框，
 * 和「网页生成」那套选完接着问的位置、手感完全一致。
 *
 * 字段一个没动：风格（胶囊单选）、主标题、副标题、场景需求，加取消 / 生成海报。
 * **这里仍然没有、也不该有任何底图预览**——底图是私密资源，界面只让用户选"感觉"，
 * 落到哪张图由 [PosterStyles] 在后台决定。
 */
@Composable
internal fun PosterFormDialog(
    styleId: String,
    title: String,
    subtitle: String,
    scene: String,
    busy: Boolean,
    onStyle: (String) -> Unit,
    onTitle: (String) -> Unit,
    onSubtitle: (String) -> Unit,
    onScene: (String) -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "做一张海报 · 选感觉、写标题",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 小标题下压一条主色分隔线：这块和旁边那些追问面板是一个长相。
        HorizontalDivider(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Text(
            text = "风格",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PosterStyles.all.forEach { style ->
                ComposerChip(
                    style.badge,
                    if (style.id == styleId) "已选" else "点我",
                ) {
                    onStyle(style.id)
                }
            }
        }
        Text(
            text = PosterStyles.byId(styleId)?.hint.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            enabled = !busy,
            singleLine = true,
            label = { Text("主标题", style = MaterialTheme.typography.bodySmall) },
            placeholder = {
                Text("例如：新媒体创意大赛", style = MaterialTheme.typography.bodySmall)
            },
            keyboardOptions = MentionFieldKeyboard,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = subtitle,
            onValueChange = onSubtitle,
            enabled = !busy,
            singleLine = true,
            label = { Text("副标题（可留空）", style = MaterialTheme.typography.bodySmall) },
            keyboardOptions = MentionFieldKeyboard,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = scene,
            onValueChange = onScene,
            enabled = !busy,
            maxLines = 2,
            label = { Text("场景需求（可留空）", style = MaterialTheme.typography.bodySmall) },
            placeholder = {
                Text("例如：面向全院学生，扫码报名", style = MaterialTheme.typography.bodySmall)
            },
            keyboardOptions = MentionFieldKeyboard,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        if (busy) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "正在绘制…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // 原来悬浮窗底部那两个键右对齐；这里保持一样的位置、一样的文案、一样的回调。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
            // 主标题空着不让点：没有标题的海报画出来只是一张底图。
            TextButton(onClick = onStart, enabled = title.isNotBlank() && !busy) {
                Text(if (busy) "绘制中…" else "生成海报")
            }
        }
    }
}

/** 点开看大图：整屏一张海报 + 下载与关闭。 */
@Composable
internal fun PosterPreviewDialog(
    file: String,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onTweak: ((PosterRenderer.Tweak) -> Unit)? = null,
) {
    // 只加载这一张，而且是在 IO 线程上——大图在主线程解码会卡住界面。
    val bitmap by produceState<android.graphics.Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file) }
    }
    // 用户点名的布局：**右上角**是可以再次处理的快捷键，**右下角**是一个小的下载标志。
    // 上一版把这些挂件摆在聊天卡片旁边（竖排贴在图上），既不顺手也挤掉图的宽度。
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(onClick = onClose),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "生成的海报",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 56.dp),
                )
            } ?: Text(
                text = "图片读取中…",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )

            // 左上角：关掉。整块背景也能点关，这个只是给一个明确的目标。
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Text("✕", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }

            // 右上角：可再次处理的快捷键。
            if (onTweak != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    posterKeys.forEach { key ->
                        PosterQuickKey(key.label) { onTweak.invoke(key.tweak) }
                    }
                }
            }

            // 右下角：小的下载标志。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "↓",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * 图片处理的表单——**内联在聊天页的面板区**，不再是 AlertDialog 悬浮窗。
 *
 * 为什么改成内联：用户点名"不要单独设置悬浮 UI 窗口，都在文本框那里弹出操作就可以"。
 * 处理图片本来就是盯着结果反复调的事，悬浮窗既挡聊天气泡、也挡刚处理好的那张图；
 * 面板区贴着输入框，和「网页生成」那套追问是同一个位置。
 *
 * 用户点名的五样东西，顺序照旧，一个不少：
 *  1. 【从相册选择图片】—— 其实是系统文件选择器，所以相册、内存库、微信/QQ 文件都能进；
 *  2. 【压缩质量】滑动条，0~100%，默认 80；
 *  3. 【输出格式】JPG / PNG / WEBP 单选；
 *  4. 【水印文字】输入框，可留空；
 *  5. 【开始处理】+ 【取消】，处理时显示进度。
 *
 * 处理过程全在手机上（[ImageTools]），不走内核：压缩、转格式、打水印都是确定性操作，
 * 交给模型只会把图传来传去。
 */
@Composable
internal fun ImageFormDialog(
    pickedCount: Int,
    quality: Int,
    format: String,
    watermark: String,
    busy: Boolean,
    progress: Pair<Int, Int>,
    /** 可选格式来自「图片处理」功能包；空表示没装包（入口本来也被门控挡着）。 */
    formats: List<String> = emptyList(),
    watermarkHint: String = "",
    onPickImages: () -> Unit,
    onQuality: (Int) -> Unit,
    onFormat: (String) -> Unit,
    onWatermark: (String) -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "处理图片 · 在手机上直接做完",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 小标题下压一条主色分隔线：这块和旁边那些追问面板是一个长相。
        HorizontalDivider(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Button(
            onClick = onPickImages,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (pickedCount == 0) "从相册选择图片"
                else "已选 " + pickedCount + " 张 · 重新选择",
            )
        }
        Text(
            text = "相册、内存、微信 / QQ 文件都能从这里进（可多选）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = "压缩质量 · " + quality + "%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = quality.toFloat(),
            onValueChange = { onQuality(it.roundToInt()) },
            valueRange = 0f..100f,
            enabled = !busy,
        )

        Spacer(Modifier.height(4.dp))
        Text(
            text = "输出格式",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            formats.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy) { onFormat(option) },
                ) {
                    RadioButton(
                        selected = option == format,
                        onClick = { onFormat(option) },
                        enabled = !busy,
                    )
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "水印文字（可留空）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = watermark,
            onValueChange = onWatermark,
            enabled = !busy,
            singleLine = true,
            placeholder = {
                Text(
                    watermarkHint.ifBlank { "例如：© 数智融媒" },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            keyboardOptions = MentionFieldKeyboard,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )

        if (busy) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "正在处理 " + progress.first + " / " + progress.second + " …",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // 处理中「取消」和「开始处理」都按住不让点：中途点掉会让用户以为没做完。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
            // 一张图都没选就不让点：按下去也只能空转。
            TextButton(onClick = onStart, enabled = pickedCount > 0 && !busy) {
                Text(if (busy) "处理中…" else "开始处理")
            }
        }
    }
}

/**
 * 文案撰写的表单——**内联在聊天页的面板区**，不再是 AlertDialog 悬浮窗。
 *
 * 为什么改成内联：用户点名"不要单独设置悬浮 UI 窗口，都在文本框那里弹出操作就可以"，
 * 悬浮窗正好压住聊天气泡。三栏照旧，一栏不少：
 *  - 类型：点一下在面板区展开一行 chips 直接选（原来的搜索式选择浮层已经删掉）；
 *  - 卖点：一个输入框，空着不让发——没有卖点的文案生成出来必然是废话；
 *  - 语气：三个单选按钮，选了就亮；
 *  - 底部两个按钮：取消 / 开始生成。
 *
 * 为什么用表单而不是多轮问答：这三件事本来就是并列的一次性输入，多轮问要等三个来回。
 */
@Composable
internal fun CopyFormDialog(
    fields: CopyFieldSet,
    type: String,
    point: String,
    tone: String,
    onPickType: () -> Unit,
    onPoint: (String) -> Unit,
    onTone: (String) -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "写文案 · 填三栏就开工",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 小标题下压一条主色分隔线：这块和旁边那些追问面板是一个长相。
        HorizontalDivider(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Text(
            text = "文案类型",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onPickType)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = type,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "▾",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "核心卖点",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = point,
            onValueChange = onPoint,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            placeholder = {
                Text(
                    "例如：防脱发洗发水 / 平价美白",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            keyboardOptions = MentionFieldKeyboard,
            textStyle = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            shape = RoundedCornerShape(9.dp),
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = "语气风格",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        fields.tones.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onTone(option) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == tone, onClick = { onTone(option) })
                Text(
                    text = option,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("取消") }
            // 卖点空着就不让点：生成出来的必然是废话，不如先在按钮上拦住。
            TextButton(onClick = onStart, enabled = point.isNotBlank()) {
                Text("开始生成")
            }
        }
    }
}
