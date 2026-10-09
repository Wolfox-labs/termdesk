package dev.termdesk.app.ui

import dev.termdesk.app.data.ChatEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folding a transcript without lying about it.
 *
 * The tail of a real agent session is dozens of process rows in a column — on a 339dp screen at
 * a 1.35 font scale that is several screens of plumbing between two sentences, which is what the
 * owner complained about. Folding fixes that only if the rules are the ones a reader would
 * choose: the answer, their own words, an error and a delivered file must never disappear into a
 * "过程 · 12" line.
 */
class TranscriptFoldTest {

    private fun event(seq: Int, kind: String, name: String? = null, role: String? = null) = ChatEvent(
        seq = seq, at = 0L, kind = kind, role = role, text = "t$seq", name = name,
        state = null, exitCode = null, sourceKind = null, streaming = false,
    )

    @Test
    fun `a long run of process rows becomes one line`() {
        val events = listOf(
            event(1, "tool", "pwsh"),
            event(2, "tool_result"),
            event(3, "reasoning"),
            event(4, "tool", "edit"),
            event(5, "tool_result"),
        )
        val rows = foldTranscript(events)
        assertEquals("five process rows fold into one", 1, rows.size)
        val run = rows.first()
        assertTrue("and it is a run", run is TranscriptRow.Run)
        assertEquals("holding all of them", 5, (run as TranscriptRow.Run).events.size)
    }

    @Test
    fun `what somebody came to read is never folded`() {
        val events = listOf(
            event(1, "message", role = "user"),
            event(2, "tool", "pwsh"),
            event(3, "tool_result"),
            event(4, "tool", "read"),
            event(5, "tool_result"),
            event(6, "error"),
            event(7, "deliverable"),
            event(8, "message", role = "assistant"),
        )
        val rows = foldTranscript(events)
        val folded = rows.filterIsInstance<TranscriptRow.Run>()
        assertEquals("only the process run folds", 1, folded.size)
        assertEquals("and it is the middle one", listOf(2, 3, 4, 5), folded[0].events.map { it.seq })
        val singles = rows.filterIsInstance<TranscriptRow.One>().map { it.event.seq }
        assertEquals("messages, the error and the deliverable stand alone",
            listOf(1, 6, 7, 8), singles)
    }

    @Test
    fun `a short run is left exactly as it is`() {
        // Three rows are not worth a tap to see; folding them would hide more than it saves.
        val events = listOf(
            event(1, "message", role = "user"),
            event(2, "tool", "pwsh"),
            event(3, "tool_result"),
            event(4, "message", role = "assistant"),
        )
        val rows = foldTranscript(events)
        assertTrue("nothing is folded", rows.all { it is TranscriptRow.One })
        assertEquals("and nothing is lost", listOf(1, 2, 3, 4), rows.map { (it as TranscriptRow.One).event.seq })
    }

    @Test
    fun `order is preserved, folded or not`() {
        val events = (1..9).map { event(it, if (it % 2 == 0) "tool_result" else "tool", "pwsh") }
        val rows = foldTranscript(events)
        val flattened = rows.flatMap {
            when (it) {
                is TranscriptRow.One -> listOf(it.event.seq)
                is TranscriptRow.Run -> it.events.map { e -> e.seq }
            }
        }
        assertEquals("every event, in the order it arrived", (1..9).toList(), flattened)
    }

    @Test
    fun `every row has its own key`() {
        // A duplicated key is how a lazy list draws the wrong line after a fold.
        val events = listOf(
            event(1, "tool", "pwsh"), event(2, "tool_result"), event(3, "tool", "pwsh"),
            event(4, "tool_result"), event(5, "message", role = "assistant"),
            event(6, "tool", "edit"), event(7, "tool_result"), event(8, "tool", "edit"),
            event(9, "tool_result"),
        )
        val keys = foldTranscript(events).map { it.key }
        assertEquals("keys are unique", keys.size, keys.toSet().size)
    }
}
