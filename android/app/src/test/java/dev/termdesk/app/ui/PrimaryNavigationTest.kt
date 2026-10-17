package dev.termdesk.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the four destinations are drawn.
 *
 * Settings is a nested surface with its own back stack: its root and every
 * sub-page hide the bar, and leaving Settings puts it back. The rule is a plain
 * function so this stays a fact rather than a screenshot.
 */
class PrimaryNavigationTest {

    @Test
    fun `settings root and every sub-page hide the bar`() {
        assertFalse(navigationVisible(Section.Settings, conversationOpen = false, keyboardVisible = false))
        // The conversation flag cannot put it back: a settings page opened from a
        // conversation still has to be free of the bar.
        assertFalse(navigationVisible(Section.Settings, conversationOpen = true, keyboardVisible = false))
    }

    @Test
    fun `leaving settings restores the normal rule`() {
        listOf(Section.Home, Section.Sessions, Section.Files, Section.Terminal).forEach { section ->
            assertTrue(
                "bar is back on ${section.label}",
                navigationVisible(section, conversationOpen = false, keyboardVisible = false),
            )
        }
    }

    @Test
    fun `an open conversation still hides it outside settings`() {
        assertFalse(navigationVisible(Section.Sessions, conversationOpen = true, keyboardVisible = false))
        assertFalse(navigationVisible(Section.Home, conversationOpen = false, keyboardVisible = true))
    }

    @Test
    fun `system keeps the bar, since it has no tab of its own`() {
        assertTrue(navigationVisible(Section.System, conversationOpen = false, keyboardVisible = false))
    }
}
