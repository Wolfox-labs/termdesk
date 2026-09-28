package dev.termdesk.app.data

/** A process on the remote host. cpuSeconds/startTime are null when the OS denies access. */
data class ProcessInfo(
    val pid: Int,
    val name: String,
    val cpuSeconds: Double?,
    val memBytes: Long,
    val threads: Int,
)

/** A Windows service. */
data class ServiceInfo(
    val name: String,
    val displayName: String,
    val status: String,
    val startType: String?,
    val canStop: Boolean,
) {
    val isRunning: Boolean get() = status.equals("Running", ignoreCase = true)
}

/** Outcome of a privileged action (kill / service control). */
data class ActionResult(
    val action: String,
    val target: String,
    val ok: Boolean,
    val code: String,
    val message: String,
)
