package dev.termdesk.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ActionResultTest {
    @Test fun `identical consecutive save acknowledgements reach state flow`() {
        val results = MutableStateFlow<ActionResult?>(null)
        val first = ActionResult("fs.write", "/demo.txt", true, "written", "已保存 demo.txt")
        results.value = first
        val second = ActionResult("fs.write", "/demo.txt", true, "written", "已保存 demo.txt")
        results.value = second
        assertTrue(second.eventId > first.eventId)
        assertSame(second, results.value)
        assertNotEquals(first, results.value)
    }

    @Test fun `repeated failures also remain distinct without snackbar clearing`() {
        val results = MutableStateFlow(ActionResult("fs.write", "/demo.txt", false, "EACCES", "没有写入权限"))
        val second = ActionResult("fs.write", "/demo.txt", false, "EACCES", "没有写入权限")
        results.value = second
        assertSame(second, results.value)
    }
}
