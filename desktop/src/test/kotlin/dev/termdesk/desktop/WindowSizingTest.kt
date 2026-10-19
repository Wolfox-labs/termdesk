package dev.termdesk.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowSizingTest {
    @Test
    fun smallWorkAreaBoundsBothInitialAndMinimumSize() {
        val size = calculateWindowSize(750f, 500f, 1.5f, "2560x1440")

        assertEquals(750f, size.width)
        assertEquals(500f, size.height)
        assertEquals(750f, size.minimumWidth)
        assertEquals(500f, size.minimumHeight)
    }

    @Test
    fun invalidRequestedSizeUsesResponsiveDefault() {
        val size = calculateWindowSize(1100f, 700f, 1f, "NaNx-5")

        assertEquals(1034f, size.width)
        assertEquals(630f, size.height)
        assertEquals(900f, size.minimumWidth)
        assertEquals(620f, size.minimumHeight)
    }

    @Test
    fun highDpiDefaultUsesPhysicalResolutionAndClampsToAvailableArea() {
        val size = calculateWindowSize(1600f, 952f, 2f)

        assertEquals(1280f, size.width)
        assertEquals(720f, size.height)
        assertEquals(900f, size.minimumWidth)
        assertEquals(620f, size.minimumHeight)
    }
}
