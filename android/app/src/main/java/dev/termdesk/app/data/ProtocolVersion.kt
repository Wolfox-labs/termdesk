package dev.termdesk.app.data

/**
 * The phone's side of protocol negotiation.
 *
 * The agent has read a protocol version into `auth.ok` for a while, and nobody on
 * this side ever looked at it: a mismatched pair kept talking, and the symptom was
 * a feature that quietly did nothing. This is the missing half.
 *
 * [PROTOCOL_VERSION] is deliberately a second copy of `pc-agent/src/protocol.js` —
 * Kotlin cannot import it. If the two drift, the agent reports the phone as a
 * version behind (`upgrade: agent`), which is visible rather than silent, but a
 * bump must be made in both files.
 *
 * Pure, so `AgentFramesTest` can pin the two rules that matter: an agent that
 * declares nothing is old, not incompatible, and an agent that requires a newer
 * app is refused with something the person can act on.
 */
object ProtocolVersion {

    /** What this app speaks. Mirrors PROTOCOL_VERSION in pc-agent/src/protocol.js. */
    const val PROTOCOL_VERSION = 1

    /** Which side should be updated, if either. */
    enum class Upgrade { NONE, APP, AGENT }

    /**
     * What the agent's answer means for this app.
     *
     * @param ok false when the agent requires a newer app than this one; the
     *   connection should not be presented as working.
     * @param upgrade which side to update.
     * @param reason a sentence to show when [ok] is false.
     */
    data class Verdict(val ok: Boolean, val upgrade: Upgrade, val reason: String?)

    /**
     * Judge `auth.ok`.
     *
     * @param agentProtocol value of `auth.ok.protocol`, or null when absent
     * @param agentMin value of `auth.ok.minV`, or null when absent
     */
    fun judge(agentProtocol: Int?, agentMin: Int?): Verdict {
        // An agent from before the handshake existed: old, not incompatible. Its
        // absence is itself the statement "I am v1".
        if (agentProtocol == null) return Verdict(true, Upgrade.AGENT, null)

        // It explicitly requires a newer app than this one speaks.
        if (agentMin != null && agentMin > PROTOCOL_VERSION) {
            return Verdict(
                ok = false,
                upgrade = Upgrade.APP,
                reason = "这台电脑要求协议 v$agentMin，本机 App 是 v$PROTOCOL_VERSION。请升级手机上的 TermDesk。",
            )
        }
        // It speaks something newer than we do. Features may not work; the person
        // is told which side to update instead of being left to guess.
        if (agentProtocol > PROTOCOL_VERSION) return Verdict(true, Upgrade.APP, null)
        if (agentProtocol < PROTOCOL_VERSION) return Verdict(true, Upgrade.AGENT, null)
        return Verdict(true, Upgrade.NONE, null)
    }
}
