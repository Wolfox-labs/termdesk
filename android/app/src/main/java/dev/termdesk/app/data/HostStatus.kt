package dev.termdesk.app.data

/**
 * Snapshot of the remote machine, mirroring the agent's `status` frame.
 * Fields are nullable where the agent may legitimately not know the value yet
 * (for example CPU usage on its very first sample).
 */
data class HostStatus(
    val hostname: String = "",
    val platform: String = "",
    val arch: String = "",
    val uptimeSeconds: Long = 0,
    val cpuModel: String = "",
    val cpuCores: Int = 0,
    val cpuUsagePercent: Double? = null,
    val memoryUsedBytes: Long = 0,
    val memoryTotalBytes: Long = 0,
    val memoryUsedPercent: Double = 0.0,
    val disks: List<DiskStatus> = emptyList(),
    /**
     * The sandbox's own footprint, present only when this status came from the
     * phone's own agent. On a PC these are zero: there the machine's numbers are
     * the interesting ones.
     *
     * Why it exists: Android denies an app /proc/stat, so a phone cannot report
     * the machine's CPU at all. What it CAN see is its own process tree, which is
     * exactly what the sandbox is - so this is both the only number available and
     * the more useful one ("how much is the sandbox eating").
     */
    val sandboxRssBytes: Long = 0,
    val sandboxProcessCount: Int = 0,
)

data class DiskStatus(
    val root: String,
    val usedBytes: Long,
    val totalBytes: Long,
    val usedPercent: Double,
)
