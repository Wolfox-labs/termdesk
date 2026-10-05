package dev.termdesk.app.data

/**
 * One agent kernel discovered on the PC.
 *
 * Discovery is the PC's answer to "what can this machine talk to", so the picker
 * shows reality instead of a hard-coded list:
 *
 *   native  an adapter is wired into the chat pipeline — selectable
 *   acp     the kernel serves ACP but the adapter is not wired yet
 *   shim    CLI-shaped kernel; needs a manifest shim
 */
data class EngineInfo(
    val id: String,
    val available: Boolean,
    val path: String,
    /** True when the engine keeps real conversation state across submissions. */
    val multiTurn: Boolean,
    /** True when the engine reports per-step progress while running. */
    val progress: Boolean,
    /** Display name from discovery (falls back to the id). */
    val label: String = "",
    /** native | acp | shim */
    val tier: String = "native",
    /** Why it is or is not usable, straight from the PC (never invented here). */
    val detail: String = "",
    /** True when the kernel can continue a recorded session. */
    val resume: Boolean = false,
    /**
     * The PC's own verdict on whether a conversation may run on it.
     *
     * Null only when an older agent did not send one. The PC is the side that
     * knows whether an adapter is wired, so its answer is used as-is: the phone
     * re-deriving it is exactly how the ACP kernels ended up labelled 暂不可用
     * while the PC was already driving them.
     */
    val selectableOnPc: Boolean? = null,
) {
    val selectable: Boolean get() = selectableOnPc ?: (available && tier == "native")

    val displayName: String get() = label.ifBlank { id }
}

/** Lifecycle of a submitted task. */
enum class TaskStatus {
    RUNNING, COMPLETED, FAILED, CANCELLED, UNKNOWN;

    companion object {
        fun fromWire(s: String): TaskStatus = when (s) {
            "running" -> RUNNING
            "completed" -> COMPLETED
            "failed" -> FAILED
            "cancelled" -> CANCELLED
            else -> UNKNOWN
        }
    }
}

/**
 * One step in a task's progress stream.
 *
 * `kind` mirrors what the agent emits: the task lifecycle (turn), the commands
 * it ran, its final message, and its reasoning.
 */
data class TaskEvent(
    val kind: String,
    val text: String,
    val state: String?,
    val exitCode: Int?,
    val output: String,
    val tokens: Int?,
) {
    val isCommand: Boolean get() = kind == "command"
    val isMessage: Boolean get() = kind == "message"
    val isError: Boolean get() = kind == "error"
    val isWarning: Boolean get() = kind == "warning"
    val isTurn: Boolean get() = kind == "turn"
    val isSystem: Boolean get() = kind == "system"
    val isReasoning: Boolean get() = kind == "reasoning"
    val isFiles: Boolean get() = kind == "files"
}

/** A summary row in the task list. */
data class TaskSummary(
    val id: String,
    val engine: String,
    val prompt: String,
    val status: TaskStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val exitCode: Int?,
    val eventCount: Int,
)

/** Full detail for one task, including its event stream. */
data class TaskDetail(
    val id: String,
    val engine: String,
    val prompt: String,
    val cwd: String,
    val status: TaskStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val exitCode: Int?,
    val threadId: String?,
    val finalText: String,
    val events: List<TaskEvent>,
)
