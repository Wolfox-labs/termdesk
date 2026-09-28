package dev.termdesk.app.data

/** One recorded session on the PC, as listed by the agent. */
data class SessionInfo(
    val engine: String,
    val id: String,
    val title: String?,
    val cwd: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val sizeBytes: Long,
    val path: String,
)

/** A working directory that contains sessions. Drives the sidebar index. */
data class WorkspaceInfo(
    val cwd: String,
    val count: Int,
    val engines: List<String>,
    val latestAt: String?,
)

/** A session opened in full. */
data class SessionDetail(
    val engine: String,
    val id: String,
    val cwd: String?,
    val title: String?,
    val events: List<SessionEvent>,
    val totalEvents: Int,
    val truncated: Boolean,
)

/**
 * One recorded event, rendered as-is.
 *
 * The app never summarises or re-ranks this stream: the engines already record
 * their own turns, steps, tool calls and compaction summaries, and the user
 * asked for the native session rather than a derived view.
 */
data class SessionEvent(
    val kind: String,
    val role: String?,
    val text: String,
    val at: String?,
    val name: String?,
    val state: String?,
    val exitCode: Int?,
    val tokens: Int?,
) {
    val isMessage: Boolean get() = kind == "message"
    val isUser: Boolean get() = isMessage && role == "user"
    val isAssistant: Boolean get() = isMessage && role == "assistant"
    val isReasoning: Boolean get() = kind == "reasoning"
    val isTool: Boolean get() = kind == "tool" || kind == "tool_result"
    val isTurn: Boolean get() = kind == "turn"
    val isStep: Boolean get() = kind == "step"
    /** The engine's own summary or plan output — shown verbatim, never regenerated. */
    val isEngineOutput: Boolean get() = kind == "engine_summary" || kind == "engine_plan"
    val isCommand: Boolean get() = kind == "command" || kind == "command_result"
    val isUsage: Boolean get() = kind == "usage"

    /** True when the event carries nothing worth rendering on its own. */
    val isMarker: Boolean get() = isTurn || isStep || isCommand
}
