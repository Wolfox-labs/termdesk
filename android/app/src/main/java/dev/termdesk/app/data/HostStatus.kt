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
)

data class DiskStatus(
    val root: String,
    val usedBytes: Long,
    val totalBytes: Long,
    val usedPercent: Double,
)
