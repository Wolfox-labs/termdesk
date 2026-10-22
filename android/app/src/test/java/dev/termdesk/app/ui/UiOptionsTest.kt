package dev.termdesk.app.ui

import dev.termdesk.app.ui.theme.Palette
import dev.termdesk.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置里那两个新选项的"约定"。
 *
 * 只钉不依赖 Android 的部分：这两个枚举是纯数据，界面上怎么显示、面板贴哪一边，
 * 都由它们自己说清楚。真机上的切换效果要靠模拟器看，不在单测范围内。
 */
class UiOptionsTest {

    @Test
    fun `布局只有两套，默认是新版，并且只有经典那套把面板放左边`() {
        assertEquals(listOf("新版", "经典"), UiLayout.entries.map { it.label })
        assertEquals(UiLayout.Modern, UiLayout.entries.first())
        assertFalse("新版的面板在右侧", UiLayout.Modern.panelAtStart)
        assertTrue("经典的面板在左侧", UiLayout.Classic.panelAtStart)
    }

    @Test
    fun `配色两套都有短名和一句说明，长文案不许留空`() {
        assertEquals(listOf("经典", "数媒"), Palette.entries.map { it.label })
        Palette.entries.forEach { option ->
            assertTrue("${option.name} 缺说明", option.hint.isNotBlank())
            assertTrue("${option.name} 的短名要足够短", option.label.length <= 4)
        }
    }

    @Test
    fun `明暗三档没被动过，System 仍然是第一项默认值`() {
        assertEquals(listOf(ThemeMode.System, ThemeMode.Dark, ThemeMode.Light), ThemeMode.entries)
    }
}
