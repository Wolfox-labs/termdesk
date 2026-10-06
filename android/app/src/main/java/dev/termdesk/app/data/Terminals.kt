package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One command line a conversation ran.
 *
 * [origin] is the part that decides what the phone may offer:
 *
 *   kernel  the kernel asked this agent to run it (ACP), so the process is ours
 *           and the phone can type into it;
 *   agent   the kernel ran it inside its own process (Codex). The app-server
 *           reports it and can stop it, but has no channel to write to it — the
 *           phone says so instead of showing an input box that does nothing.
 */
data class ChatTerminal(
    val id: String,
    val command: String,
    val cwd: String,
    val origin: String,
    val state: String,
    val exitCode: Int?,
    val startedAt: Long,
    val bytes: Int,
    val truncated: Boolean,
    val canWrite: Boolean,
) {
    val running: Boolean get() = state == "running"
    val ours: Boolean get() = origin == "kernel"

    companion object {
        fun from(json: JSONObject): ChatTerminal = ChatTerminal(
            id = json.optString("id"),
            command = json.optString("command"),
            cwd = json.optString("cwd"),
            origin = json.optString("origin", "agent"),
            state = json.optString("state", "running"),
            exitCode = if (json.isNull("exitCode")) null else json.optInt("exitCode"),
            startedAt = json.optLong("startedAt", 0L),
            bytes = json.optInt("bytes", 0),
            truncated = json.optBoolean("truncated", false),
            canWrite = json.optBoolean("canWrite", false),
        )

        fun list(array: JSONArray?): List<ChatTerminal> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { from(it) }
            }
        }
    }
}

/**
 * What the terminal panel is showing.
 *
 * The output is kept here rather than in the transcript: a command's output is
 * not a conversation turn, and mixing the two would put a build log in the
 * middle of what the agent said.
 */
data class TerminalView(
    val chatId: String,
    val terminalId: String,
    val command: String,
    val origin: String,
    val state: String,
    val canWrite: Boolean,
    val output: String = "",
    val truncated: Boolean = false,
    /** Set while waiting for the PC's first answer about this terminal. */
    val loading: Boolean = false,
) {
    /** How the panel should treat the input box. */
    val writable: Boolean get() = canWrite && state == "running"
}
