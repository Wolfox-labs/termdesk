package dev.termdesk.app.data

/** What the UI needs to know about the link to the PC. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data class Connected(val hostname: String) : LinkState
    data class Failed(val reason: String) : LinkState
}

/** Progress of an upload or download. [fraction] is 0..1, or 0 when unknown. */
data class TransferState(
    val fileName: String,
    val fraction: Float,
    val label: String,
)

/** One rendered terminal line. */
data class TermLine(val text: String, val stream: Stream) {
    enum class Stream {
        INPUT, STDOUT, STDERR, SYSTEM;

        companion object {
            fun fromWire(value: String): Stream = when (value) {
                "stdout" -> STDOUT
                "stderr" -> STDERR
                "system" -> SYSTEM
                else -> STDOUT
            }
        }
    }
}
