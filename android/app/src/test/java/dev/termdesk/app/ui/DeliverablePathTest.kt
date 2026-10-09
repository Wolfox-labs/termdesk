package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a produced file's folder is.
 *
 * The tap on a deliverable row opens the file section at the file's directory, and the
 * paths in these rows come from the agent: `E:\a\b.md` on Windows, `/home/u/b.md` on a
 * machine that is not Windows. Both arrive at the same phone, so both have to be cut the
 * same way — and the one case that cannot be a folder (`E:`) has to say so instead of
 * asking the agent to list something that is not a directory.
 */
class DeliverablePathTest {

    @Test
    fun `the folder is what comes before the last separator`() {
        assertEquals("E:\\aiPic\\servers", parentDirectoryOf("E:\\aiPic\\servers\\plan.md"))
        assertEquals("/home/u/work", parentDirectoryOf("/home/u/work/report.md"))
        assertEquals("E:\\aiPic", parentDirectoryOf("E:\\aiPic\\data.csv"))
    }

    @Test
    fun `a trailing separator does not produce an empty name`() {
        assertEquals("E:\\aiPic", parentDirectoryOf("E:\\aiPic\\report.md\\"))
        assertEquals("/home/u", parentDirectoryOf("/home/u/notes.txt/"))
    }

    @Test
    fun `a drive root keeps its separator, because E-colon is not a directory`() {
        assertEquals("E:\\", parentDirectoryOf("E:\\report.md"))
        assertEquals("/", parentDirectoryOf("/report.md"))
    }

    @Test
    fun `something with no folder above it says so`() {
        // A bare name is not something the file section can be pointed at: guessing a
        // directory from it would open the wrong one.
        assertNull(parentDirectoryOf("report.md"))
        assertNull(parentDirectoryOf(""))
    }
}
