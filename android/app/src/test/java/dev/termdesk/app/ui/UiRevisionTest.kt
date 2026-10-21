package dev.termdesk.app.ui

import dev.termdesk.app.data.ChatEvent
import dev.termdesk.app.data.ImageTools
import dev.termdesk.app.data.parseChatFiles
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-19 这批改动的钉子。
 *
 * 每一条都对应一个**用户在真机上提的**问题，而不是"看着顺手"：
 *  - 过程文字默认折叠，但仍可展开查看诊断；
 *  - @ 产物在手机上找不到 → 电脑端推 deliverable，手机端要解得出来；
 *  - 输入框里冒出 #RRGGBB → 只写颜色名；
 *  - PPT 停在"必问项"上白等一轮 → 改成用占位稿；
 *  - 图片降采样上限写着 2048、实际放行到 4095。
 */
class UiRevisionTest {

    private fun event(seq: Int, kind: String, role: String? = null, text: String = "t$seq") =
        ChatEvent(
            seq = seq, at = 0L, kind = kind, role = role, text = text, name = null,
            state = null, exitCode = null, sourceKind = null, streaming = false,
        )

    // Process classification must not remove the original expandable history.

    @Test
    fun `过程行与答案产出保持不同类型`() {
        val process = listOf(
            "tool", "tool_result", "reasoning", "command", "command_result", "step",
            "engine_plan", "engine_summary", "usage", "engine_log", "engine_note",
        )
        for (kind in process) {
            assertTrue("「$kind」属于可展开过程", isProcessRow(TranscriptRow.One(event(1, kind))))
        }

        val kept = listOf(
            event(1, "message", role = "user"),
            event(2, "message", role = "assistant"),
            event(3, "deliverable"),
            event(4, "poster"),
            event(5, "error"),
        )
        for (e in kept) {
            assertFalse("「${e.kind}/${e.role}」必须留下", isProcessRow(TranscriptRow.One(e)))
        }
    }

    @Test
    fun `折叠过程仍保留在用户和模型消息之间`() {
        val events = listOf(
            event(1, "message", role = "user"),
            event(2, "tool"),
            event(3, "tool_result"),
            event(4, "reasoning"),
            event(5, "tool"),
            event(6, "message", role = "assistant"),
        )
        val shown = foldTranscript(events)
        assertEquals(3, shown.size)
        assertTrue(shown[1] is TranscriptRow.Run)
        assertEquals(4, (shown[1] as TranscriptRow.Run).events.size)
    }

    // ── ④ 产物卡片：电脑端推的 files 手机端解得出来 ──────────────────────

    @Test
    fun `产物条目能解析成可点可下载的文件`() {
        val arr = JSONArray(
            """
            [
              {"path":"E:\\probe\\report.pptx","description":null},
              {"path":"/home/u/notes.md","description":"这一轮的文案"},
              {"description":"没有路径的条目应当丢掉"},
              {"path":"   "}
            ]
            """.trimIndent(),
        )
        val files = parseChatFiles(arr)
        assertEquals("只有带路径的两条能留下", 2, files.size)
        assertEquals("Windows 路径取末段", "report.pptx", files[0].name)
        assertEquals("POSIX 路径取末段", "notes.md", files[1].name)
        assertEquals("模型自己写的说明要带上", "这一轮的文案", files[1].description)
    }

    @Test
    fun `产物事件是 deliverable，不会被当成过程行`() {
        val deliverable = event(1, "deliverable")
        assertTrue(deliverable.isDeliverable)
        assertFalse("产出卡片必须在列表里", isProcessRow(TranscriptRow.One(deliverable)))
    }

    // ── ⑤ 配色只写颜色名 ────────────────────────────────────────────────

    @Test
    fun `配色写进草稿只留名字，色值只进指令`() {
        val tech = ColorSchemes.byId("科技蓝")!!
        assertEquals("写回草稿的那一段", "配色：科技蓝", ColorSchemes.directive(tech))
        assertFalse("草稿里不许出现十六进制色号", ColorSchemes.directive(tech).contains("#"))

        // 拆开看：给内核的说明仍然带着色值，否则模型只能猜。
        assertTrue("内核仍然要拿到色值", ColorSchemes.summary(tech).contains("#"))

        // 整段草稿渲染一遍：只多一行「配色：科技蓝」，没有一个 #。
        val draft = MentionDraft.render(
            MentionDraft.withColors(MentionDraft.parse("@网页生成 模板：官网首页"), tech),
        )
        assertTrue(draft.contains("配色：科技蓝"))
        assertFalse("整段草稿里也不许有色号", draft.contains("#"))
    }

    // ── ② PPT 不再停在"必问项"上 ────────────────────────────────────────

    @Test
    fun `PPT 缺数字时用占位稿，不再停下来问一轮`() {
        val deck = DeckTemplates.all.first()
        val out = DeckTemplates.instruction(
            deck = deck,
            blueprint = "",
            sections = emptyList(),
            colors = ColorSchemes.byId("科技蓝")!!,
            theme = "TermDesk 2026 Roadmap",
            pages = 12,
            rows = emptyList(),
            free = "",
            askBudget = 3,
        )
        assertTrue("要明确说别停下来问", out.contains("不要停下来问我"))
        assertTrue("要走占位稿", out.contains("待补充"))
        assertFalse("旧的「必问项」措辞不能再出现", out.contains("必问项"))
        assertFalse("不能再要求先问一次数字", out.contains("先问我一次数字"))
    }

    // ── ③ 图片降采样真的卡在 2048 ───────────────────────────────────────

    @Test
    fun `降采样让长边落在 2048 以内`() {
        assertEquals("本来就没超，不缩", 1, ImageTools.sampleFor(1080))
        assertEquals("2800 折半到 1400", 2, ImageTools.sampleFor(2800))
        assertEquals("4000 折半到 2000", 2, ImageTools.sampleFor(4000))
        assertEquals("正好 4096 折半到 2048", 2, ImageTools.sampleFor(4096))
        assertEquals("8000 要缩两次到 2000", 4, ImageTools.sampleFor(8000))
        assertEquals("竖图同理（长边说了算）", 2, ImageTools.sampleFor(3000))
    }

    @Test
    fun `图片结果行带上体积变化`() {
        val text = ImageTools.noteText(
            ImageTools.Result(saved = 1, failed = 0, where = "相册 Pictures/TermDesk", inBytes = 527613, outBytes = 208240),
        )
        assertTrue("要说清原图多大", text.contains("515 KB"))
        assertTrue("要说清压完多大", text.contains("203 KB"))
        assertTrue("要给出降幅", text.contains("小了 61%"))
        assertTrue("仍然保留用户点名的那半句", text.contains("已保存至相册"))

        // 一张都没成时不许再报「✅ …已保存」。
        val failed = ImageTools.noteText(
            ImageTools.Result(saved = 0, failed = 2, where = "相册 Pictures/TermDesk", reason = "读不出这张图"),
        )
        assertFalse("0 成功不能报成功", failed.contains("✅"))
        assertFalse("0 成功不能提位置", failed.contains("位置："))
        assertTrue("要给出原因", failed.contains("读不出这张图"))

        assertNotNull("noteText 永远有内容", text)
    }
}
