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