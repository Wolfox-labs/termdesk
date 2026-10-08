package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's first automated checks.
 *
 * Everything else on this side is verified by compiling and then by hand on a
 * device, which is why the frame parsers were pulled out of `AgentClient` into
 * `AgentFrames` in the first place: they are the part that can be pinned without
 * one.
 *
 * What these tests are for. Each parser is the only place a frame's field names
 * meet the models the UI reads, and the failures here are quiet ones: a renamed
 * field shows as a blank row, an absent field shows as the literal word "null"
 * (that happened — the transcript named a model `null`), a wrong default shows
 * as a session that claims it can be resumed when it cannot.
 *
 * What they are NOT. They pin TermDesk's own handling of a frame — which fields
 * it reads, which fallbacks it applies, what a payload missing a field produces
 * — and not the agent's side of the protocol: no frame here comes from a running
 * agent, and nothing checks that the agent sends these field names. That check
 * lives on the PC side, where the frames are produced (`npm test`).
 *
 * `org.json` deserves a note: android.jar ships it as a stub for unit tests,
 * where `put` returns null and `optString` returns defaults, so a JSONObject
 * cannot even be built. `app/build.gradle.kts` therefore puts the REAL
 * implementation on the unit-test classpath only (`testImplementation`) and
 * leaves `isReturnDefaultValues` off, so these parsers run against behaviour
 * that matches the device instead of a mock of it.
 */
class AgentFramesTest {

    @Test
    fun `a queued message is marked as waiting, and a sent one is not`() {
        // The PC queues a message typed while the previous answer is still coming, and
        // draws it immediately so it does not look lost. The badge is the only thing that
        // tells "waiting to be sent" apart from "sent, and being answered".
        val waiting = parseChatEvent(
            JSONObject()
                .put("seq", 3)
                .put("kind", "message")
                .put("role", "user")
                .put("text", "also check the tests")
                .put("queued", true),
        )
        val sent = parseChatEvent(
            JSONObject()
                .put("seq", 4)
                .put("kind", "message")
                .put("role", "user")
                .put("text", "and the docs"),
        )

        assertTrue("a message waiting its turn says so", waiting.queued)
        assertTrue("and it is still the user's own line", waiting.isUser)
        assertFalse("a message the kernel has already seen is not queued", sent.queued)
    }

    // ---- the session list's workspace index (what the sidebar groups by) ----

    @Test
    fun `no workspace payload is an empty index, not a crash`() {
        assertEquals(emptyList<WorkspaceInfo>(), parseWorkspaces(null))
        assertEquals(emptyList<WorkspaceInfo>(), parseWorkspaces(JSONArray()))
    }

    @Test
    fun `a workspace carries its directory, session count, engines and latest time`() {
        val engines = JSONArray().put("codex").put("dsh")
        val entry = JSONObject()
            .put("cwd", "E:\\aiPic\\termdesk")
            .put("count", 7)
            .put("engines", engines)
            .put("latestAt", "2026-10-08T02:57:35.334Z")

        val list = parseWorkspaces(JSONArray().put(entry))

        assertEquals(1, list.size)
        assertEquals("E:\\aiPic\\termdesk", list[0].cwd)
        assertEquals(7, list[0].count)
        assertEquals(listOf("codex", "dsh"), list[0].engines)
        assertEquals("2026-10-08T02:57:35.334Z", list[0].latestAt)
    }

    @Test
    fun `a workspace without engines or a time still arrives, so the group is not dropped`() {
        val entry = JSONObject().put("cwd", "/home/x/project").put("count", 1)

        val list = parseWorkspaces(JSONArray().put(entry))

        assertEquals(1, list.size)
        assertEquals(emptyList<String>(), list[0].engines)
        assertNull("an absent time stays absent: the UI sorts by it, it must not invent one", list[0].latestAt)
    }

    // ---- a conversation, as the chats frame describes it --------------------

    @Test
    fun `a conversation without an id is skipped rather than shown as a blank row`() {
        assertNull(parseChatInfo(JSONObject()))
        val withoutId = JSONObject().put("title", "有标题但没 id")
        assertNull(parseChatInfo(withoutId))
        assertEquals(
            emptyList<ChatInfo>(),
            parseChatList(JSONArray().put(JSONObject().put("title", "同样没有 id"))),
        )
    }

    @Test
    fun `an absent field stays absent instead of becoming the word null`() {
        // The regression this pins: optString() turns a JSON null into "null", and
        // the transcript then called the model `null`.
        val frame = JSONObject()
            .put("id", "c-1")
            .put("title", "检查本地情况")
            .put("cwd", "E:\\aiPic\\termdesk")
            .put("provider", JSONObject.NULL)
            .put("model", JSONObject.NULL)
            .put("effort", JSONObject.NULL)
            .put("mode", JSONObject.NULL)

        val chat = parseChatInfo(frame)

        assertNotNull(chat)
        assertEquals("", chat!!.model)
        assertEquals("", chat.provider)
        assertEquals("", chat.effort)
        assertEquals("", chat.mode)
        assertEquals("检查本地情况", chat.title)
        assertFalse("an absent model must not read as the four-letter word", chat.model == "null")
    }

    @Test
    fun `a conversation with nothing running is idle, on the default kernel`() {
        val chat = parseChatInfo(JSONObject().put("id", "c-2"))

        assertNotNull(chat)
        assertEquals("idle", chat!!.status)
        assertEquals("dsh", chat.engine)
        assertFalse("a fresh conversation is not running", chat.isRunning)
    }

    // ---- file search results ------------------------------------------------

    @Test
    fun `a search result keeps the directory it was answered for`() {
        val frame = JSONObject()
            .put("path", "E:\\aiPic\\termdesk\\pc-agent")
            .put("query", "chat")
            .put("truncated", false)
            .put("scannedDirs", 12)
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("name", "chat.js")
                        .put("path", "E:\\aiPic\\termdesk\\pc-agent\\src\\chat.js")
                        .put("isDir", false)
                        .put("sizeBytes", 90285L),
                ),
            )

        val results = parseSearch(frame)

        assertEquals("E:\\aiPic\\termdesk\\pc-agent", results.path)
        assertEquals("chat", results.query)
        assertEquals(1, results.items.size)
        assertFalse("a found file is not a directory", results.items[0].isDir)
    }

    // ---- processes running on the PC that its agent did not start ----------

    @Test
    fun `a running agent process keeps its kernel, pid and memory`() {
        val frame = JSONArray().put(
            JSONObject()
                .put("kernelId", "codex")
                .put("label", "Codex")
                .put("pid", 4242)
                .put("name", "codex.exe")
                .put("memBytes", 512L * 1024 * 1024)
                .put("attachable", false),
        )

        val runs = parseKernelRuns(frame)

        assertEquals(1, runs.size)
        assertEquals("codex", runs[0].kernelId)
        assertEquals("Codex", runs[0].displayName)
        assertEquals(4242, runs[0].pid)
        assertEquals(512L * 1024 * 1024, runs[0].memBytes)
        assertFalse("this agent holds no handle to a process it did not start", runs[0].attachable)
    }

    @Test
    fun `a desktop app instance keeps its process count and is marked as such`() {
        // The measured case this covers: one DeepSeek Harness conversation spread over
        // ten processes, invisible to a name match, reported as ONE row whose memory is
        // the whole tree. "2.1 GB" without "10 个进程" reads like one runaway process.
        val frame = JSONArray().put(
            JSONObject()
                .put("kernelId", "dsh")
                .put("label", "DeepSeek Harness")
                .put("pid", 5668)
                .put("name", "DeepSeek Harness.exe")
                .put("memBytes", 2151L * 1024 * 1024)
                .put("attachable", false)
                .put("source", "desktop-app")
                .put("processCount", 10),
        )

        val runs = parseKernelRuns(frame)

        assertEquals(1, runs.size)
        assertTrue("the row is an app instance, not one process", runs[0].fromDesktopApp)
        assertEquals(10, runs[0].processCount)
        assertEquals("DeepSeek Harness.exe", runs[0].name)
    }

    @Test
    fun `a plain process row reports no count, so the two kinds stay distinguishable`() {
        // Absent must stay absent: defaulting to 1 would make an ordinary name-matched
        // process row render identically to an app instance.
        val frame = JSONArray().put(
            JSONObject().put("kernelId", "mimo").put("label", "MiMo Code").put("pid", 44280).put("name", "mimo"),
        )

        val runs = parseKernelRuns(frame)

        assertEquals(1, runs.size)
        assertFalse(runs[0].fromDesktopApp)
        assertNull("no count is not the same as a count of one", runs[0].processCount)
    }

    @Test
    fun `an older agent's rows read as plain processes rather than as app instances`() {
        // `source` is a newer field. An agent that does not send it produced its rows by
        // matching executable names, so that is what they must be taken for.
        val frame = JSONArray().put(
            JSONObject().put("kernelId", "codex").put("label", "Codex").put("pid", 99).put("name", "codex"),
        )

        assertEquals("process", parseKernelRuns(frame)[0].source)
    }

    @Test
    fun `a row without a pid is skipped instead of shown as a nameless process`() {
        // The pid is the only thing that makes such a row concrete. A row that
        // cannot name one would read as a conversation, and it is not one.
        val frame = JSONArray()
            .put(JSONObject().put("kernelId", "codex").put("label", "Codex"))
            .put(JSONObject().put("kernelId", "dsh").put("label", "DSH").put("pid", 0))
            .put(JSONObject().put("kernelId", "opencode").put("label", "OpenCode").put("pid", 77))

        val runs = parseKernelRuns(frame)

        assertEquals(1, runs.size)
        assertEquals("opencode", runs[0].kernelId)
    }

    @Test
    fun `an empty or absent list of runs is empty, not a crash`() {
        assertEquals(emptyList<KernelRun>(), parseKernelRuns(null))
        assertEquals(emptyList<KernelRun>(), parseKernelRuns(JSONArray()))
    }

    @Test
    fun `attachable defaults to false when the agent does not say`() {
        // The truthful default: absent means we cannot attach, never that we can.
        val runs = parseKernelRuns(JSONArray().put(JSONObject().put("kernelId", "codex").put("pid", 9)))

        assertEquals(1, runs.size)
        assertFalse(runs[0].attachable)
    }

    // ---- protocol negotiation (the app's half of it) -------------------------

    @Test
    fun `an agent that declares no protocol is old, not incompatible`() {
        // Every agent built before the handshake existed looks like this. Refusing
        // it would lock out every installed app on upgrade day.
        val verdict = ProtocolVersion.judge(null, null)

        assertTrue("an absent version must still connect", verdict.ok)
        assertEquals(ProtocolVersion.Upgrade.AGENT, verdict.upgrade)
        assertNull(verdict.reason)
    }

    @Test
    fun `an agent requiring a newer app is refused with something actionable`() {
        val verdict = ProtocolVersion.judge(
            ProtocolVersion.PROTOCOL_VERSION,
            ProtocolVersion.PROTOCOL_VERSION + 1,
        )

        assertFalse("the agent demands more than this app speaks", verdict.ok)
        assertEquals(ProtocolVersion.Upgrade.APP, verdict.upgrade)
        assertNotNull(verdict.reason)
        // Assert on the numbers, not on prose: a version sentence that does not
        // name the versions is useless, and an ASCII check cannot be broken by the
        // encoding of the file it lives in.
        val reason = verdict.reason!!
        assertTrue(
            "the sentence has to name both versions, it said: $reason",
            reason.contains("v${ProtocolVersion.PROTOCOL_VERSION + 1}") &&
                reason.contains("v${ProtocolVersion.PROTOCOL_VERSION}"),
        )
        assertTrue("and it has to be a sentence, not a code", reason.length > 12)
    }

    @Test
    fun `a matching agent needs no upgrade, and a newer one blames this app`() {
        val same = ProtocolVersion.judge(ProtocolVersion.PROTOCOL_VERSION, ProtocolVersion.PROTOCOL_VERSION)
        assertEquals(ProtocolVersion.Upgrade.NONE, same.upgrade)
        assertTrue(same.ok)

        val newerAgent = ProtocolVersion.judge(ProtocolVersion.PROTOCOL_VERSION + 1, null)
        assertTrue("a newer agent still works", newerAgent.ok)
        assertEquals("but this app is the one to update", ProtocolVersion.Upgrade.APP, newerAgent.upgrade)
    }
}
