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
    /** ACP: the session's permission / agent mode, blank for the kernel's own. */
    val mode: String,
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
 * A question from the engine, waiting for the person holding the phone.
 *
 * Both kernels ask the same thing in their own words — Codex before a command or
 * a patch, ACP before a tool call — and the agent turns either one into this, so
 * the phone has one dialog instead of one per kernel. The options are the
 * agent's own vocabulary, which means a label here is exactly what the answer
 * will mean to the kernel.
 */
data class ChatApproval(
    val requestId: String,
    val chatId: String?,
    val engine: String,
    val title: String,
    val detail: String,
    val kind: String,
    val options: List<ChatApprovalOption>,
    /** What the agent settles on if nobody answers. */
    val fallback: String,
    /** When it does that, in epoch milliseconds. */
    val expiresAt: Long,
) {
    fun remainingMs(now: Long = System.currentTimeMillis()): Long = (expiresAt - now).coerceAtLeast(0)

    val fallbackText: String
        get() = when (fallback) {
            "allow_once" -> "允许一次"
            "allow_always" -> "总是允许"
            else -> "拒绝"
        }
}

/** One answer the kernel will accept. */
data class ChatApprovalOption(val id: String, val label: String, val style: String)

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

/** One model a kernel says this conversation can switch to. */
data class ModelChoice(val id: String, val label: String)

/**
 * What a live conversation can be switched to, as the kernel declared it.
 *
 * ACP kernels only declare this once a session exists, so it arrives when the
 * phone asks for it ([AgentClient.requestChatModels]) instead of riding along in
 * every chat frame: one kernel declares 1556 models, and a summary is not the
 * place to carry them.
 */
data class ChatModels(
    val chatId: String,
    val supported: Boolean,
    val current: String?,
    val models: List<ModelChoice>,
    /**
     * Permission / agent modes the kernel declares for this session - OpenCode
     * has build and plan, Command Code has five including "Bypass Permissions".
     * Empty when the kernel declares none: never a guessed list.
     */
    val modes: List<ModelChoice> = emptyList(),
    val currentMode: String? = null,
    /** Why there is no list, when there is none (a kernel with no model API). */
    val note: String?,
) {
    /** The label for an id, so the UI never has to show a raw slug alone. */
    fun labelOf(id: String): String = models.firstOrNull { it.id == id }?.label ?: id

    fun modeLabelOf(id: String): String = modes.firstOrNull { it.id == id }?.label ?: id
}

/**
 * A file the phone just put on the PC.

 * The upload itself is the file browser's own machinery; this is only the
 * receipt, so the conversation can attach the file the user picked with `+`
 * to the next message without asking them to type a path.
 */
data class UploadedFile(val name: String, val path: String, val at: Long)
