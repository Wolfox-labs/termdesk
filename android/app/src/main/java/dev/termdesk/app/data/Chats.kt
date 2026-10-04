package dev.termdesk.app.data

/**
 * A live conversation held by the PC agent.
 *
 * Unlike [SessionInfo], which describes a conversation recorded on disk by
 * Codex or the desktop app, a chat is a runtime the agent owns: it keeps one
 * DSH SDK process per chat, so a follow-up genuinely continues the same
 * conversation instead of replaying a transcript into a fresh process.
 */
data class ChatInfo(
    val id: String,
    val title: String,
    val cwd: String,
    val provider: String,
    val model: String,
    /**
     * Reasoning effort the conversation currently uses (low / high / ...), or
     * blank for "kernel default". The kernel treats model and effort as sticky
     * per-thread settings, so this is real state, not a display hint.
     */
    val effort: String,
    /** running | idle | stopped | failed */
    val status: String,
    /** True once the runtime handshake finished and prompts can be accepted. */
    val ready: Boolean,
    /** codex | dsh — which kernel this chat runs on. */
    val engine: String,
    /** The kernel's own session id (Codex thread id once the first turn ran). */
    val threadId: String?,
    val sessionId: String?,
    val createdAt: Long,
    val lastUsedAt: Long,
    val eventCount: Int,
    val lastError: String?,
) {
    val isRunning: Boolean get() = status == "running"
    val isFailed: Boolean get() = status == "failed"
}

/**
 * One line of a conversation, shown exactly as the runtime produced it.
 *
 * The app never rewrites, summarises, or reorders this stream. The kinds mirror
 * the agent's own vocabulary so a reader can tell the human's words apart from
 * the model's answer, its reasoning, the tools it ran, and the context the
 * harness injected on its own.
 */
data class ChatEvent(
    val seq: Int,
    val at: Long,
    val kind: String,
    val role: String?,
    val text: String,
    val name: String?,
    val state: String?,
    val exitCode: Int?,
    val sourceKind: String?,
    /** True while the model is still streaming this line. */
    val streaming: Boolean,
) {
    val isUser: Boolean get() = kind == "message" && role == "user"
    val isAssistant: Boolean get() = kind == "message" && role == "assistant"
    val isReasoning: Boolean get() = kind == "reasoning"

    /**
     * Context the runtime injected into its own conversation (plugin context,
     * the skill catalog, a runtime snapshot). It is real conversation content
     * and is kept, but it must never be shown as something the user typed.
     */
    val isInjectedContext: Boolean get() = kind == "context"
    val isTool: Boolean get() = kind == "tool" || kind == "tool_result"
    val isCommand: Boolean get() = kind == "command" || kind == "command_result"
    val isTurn: Boolean get() = kind == "turn"
    val isStep: Boolean get() = kind == "step"
    /** The agent's own local notes about the chat's lifecycle. */
    val isLocal: Boolean get() = kind == "local"
    val isError: Boolean get() = kind == "error"
    val isEngineLog: Boolean get() = kind == "engine_log" || kind == "engine_note"

    /** Engine bookkeeping with no text of its own, drawn as a thin divider. */
    val isMarker: Boolean get() = isTurn || isStep || (isEngineLog && text.isBlank())

    val hasText: Boolean get() = text.isNotBlank()
}
