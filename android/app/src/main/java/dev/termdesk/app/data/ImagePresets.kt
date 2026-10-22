package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「图片处理」里那些**可调的东西**：输出格式、默认质量、水印提示。
 *
 * 处理本身是本地像素运算（不上电脑、不调模型），所以包里没有图片、也没有代码，只有
 * 这份选项表。好处和另外四个功能一样：多加一种输出格式、改一次默认质量，不用发新版 App。
 *
 * 读不到包就是 null——那条 @ 菜单会被门控挡住，表单也不会自己冒出来。
 */
internal data class ImagePresets(
    /** 可选输出格式，按显示顺序。 */
    val formats: List<String>,
    /** 默认质量 0..100。 */
    val defaultQuality: Int,
    /** 默认格式，必须出现在 [formats] 里，否则退回第一条。 */
    val defaultFormat: String,
    /** 水印输入框的灰字提示。 */
    val watermarkHint: String,
) {
    /** 真正生效的默认格式：配置写错了也不会让单选框一个都不选中。 */
    val safeDefaultFormat: String
        get() = if (defaultFormat in formats) defaultFormat else formats.firstOrNull().orEmpty()

    companion object {
        const val PACK_PATH = "data/image-presets.json"

        fun load(source: FeatureSource): ImagePresets? {
            val text = runCatching {
                source.open(PACK_PATH)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull().orEmpty()
            if (text.isBlank()) return null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val formats = (json.optJSONArray("formats") ?: JSONArray())
                .let { array -> (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) } }
                .ifEmpty { emptyList() }
            if (formats.isEmpty()) return null
            return ImagePresets(
                formats = formats,
                defaultQuality = json.optInt("defaultQuality", 80).coerceIn(0, 100),
                defaultFormat = json.optString("defaultFormat").trim(),
                watermarkHint = json.optString("watermarkHint").trim(),
            )
        }
    }
}
