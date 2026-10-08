package dev.termdesk.app.data

/**
 * One agent kernel discovered on the PC.
 *
 * Discovery is the PC's answer to "what can this machine talk to", so the picker
 * shows reality instead of a hard-coded list:
 *
 *   native  an adapter is wired into the chat pipeline — selectable
 *   acp     the kernel serves ACP and the shared adapter drives it
 *   shim    CLI-shaped kernel; needs a manifest shim
 *
 * The whole list comes from one place on the PC (`kernels/registry.js`, exposed
 * as `kernels.list`). It used to come from a separate `ai.engines` discovery that
 * had its own idea of which kernels existed - which is exactly how the picker
 * ended up disagreeing with what a conversation could actually run on.
 */
data class KernelInfo(
    val id: String,
    val available: Boolean,
    val path: String,
    /** True when the kernel keeps real conversation state across submissions. */
    val multiTurn: Boolean,
    /** True when the kernel reports per-step progress while running. */
    val progress: Boolean,
    /** Display name from discovery (falls back to the id). */
    val label: String = "",
    /** native | acp | shim | unsupported */
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

/**
 * An agent process running on the PC that the PC's own agent did not start.
 *
 * Why it exists: a conversation only appeared on the phone once TermDesk's agent
 * had created it, so an agent the person launched in their own terminal was
 * invisible — they discovered it by trying to attach to its session and being
 * refused. These rows are that missing half: "an agent is running over there".
 *
 * What a row is NOT: a conversation. The match is by process name (two builds of
 * the same tool look identical here), and this agent holds no handle to the
 * process, so nothing can be opened or typed into. [attachable] carries that
 * verdict from the PC so the UI cannot offer an action that would fail.
 */
data class KernelRun(
    val kernelId: String,
    val label: String,
    val pid: Int,
    /** The process name as the machine reported it, shown when it differs. */
    val name: String,
    val memBytes: Long,
    /** False for every row today; a field rather than an assumption. */
    val attachable: Boolean = false,
) {
    val displayName: String get() = label.ifBlank { kernelId }
}