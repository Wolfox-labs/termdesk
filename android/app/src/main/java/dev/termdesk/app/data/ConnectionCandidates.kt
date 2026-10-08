package dev.termdesk.app.data

/**
 * Which address to try next when one does not answer.
 *
 * Why: the relay became the single entry point, so a phone holding only that
 * address is dead when the relay is — even with the computer on the same Wi-Fi, or
 * on Tailscale where Cloudflare is not involved at all. The PC now hands over its
 * own addresses at pairing time (`more=` in the QR payload); this decides what to
 * do with them.
 *
 * The product decisions, kept here so they are testable rather than implied by a
 * retry loop:
 *
 *   - the address that WORKED is remembered, not the one that was tried last. A
 *     phone that fell back to the LAN should keep using the LAN until it stops
 *     working, instead of re-dialling a dead relay on every reconnect.
 *   - a network change resets to the first address. Coming back on a different
 *     Wi-Fi is exactly when the relay is most likely to work again, and it is the
 *     address the person paired with.
 *   - with one address there is nothing to rotate: the list is the list.
 */
object ConnectionCandidates {

    /**
     * The ordered addresses to try: [primary], then [more], de-duplicated.
     *
     * Blank entries are dropped rather than dialled: an empty `url` builds a
     * request that fails with a confusing message, which is worse than not trying.
     */
    fun list(primary: String?, more: List<String?>?): List<String> {
        val out = mutableListOf<String>()
        for (candidate in listOf(primary) + (more ?: emptyList())) {
            val url = candidate?.trim().orEmpty()
            if (url.isEmpty()) continue
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) continue
            if (out.contains(url)) continue
            out.add(url)
        }
        return out
    }

    /**
     * The address to use, given which one last worked.
     *
     * [lastWorking] is an address, not an index: the list can change between
     * connections (a new pairing, a PC that gained an address), and an index would
     * then point at a different computer's address.
     */
    fun current(candidates: List<String>, lastWorking: String?): String? {
        if (candidates.isEmpty()) return null
        if (lastWorking != null && candidates.contains(lastWorking)) return lastWorking
        return candidates.first()
    }

    /**
     * The address to try after [failed] did not answer.
     *
     * Cycles, so a phone on a train keeps trying all of them rather than parking
     * on the last one; with a single candidate it returns that one, which is what
     * "keep retrying the only address there is" means.
     */
    fun after(candidates: List<String>, failed: String?): String? {
        if (candidates.isEmpty()) return null
        if (failed == null) return candidates.first()
        val index = candidates.indexOf(failed)
        if (index < 0) return candidates.first()
        return candidates[(index + 1) % candidates.size]
    }

    /** One line for the connection banner, so the person can see it is trying others. */
    fun describe(candidates: List<String>, failed: String?): String? {
        if (candidates.size <= 1) return null
        val next = after(candidates, failed) ?: return null
        return "${hostOf(failed ?: "")} 连不上，试 ${hostOf(next)}"
    }

    /** The host part, for a message a person reads rather than a URL they parse. */
    fun hostOf(url: String): String {
        val withoutScheme = url.substringAfter("://", url)
        return withoutScheme.substringBefore('/').ifBlank { url }
    }
}
