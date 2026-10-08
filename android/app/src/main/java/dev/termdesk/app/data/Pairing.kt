package dev.termdesk.app.data

import android.content.Intent

/**
 * A pairing request that arrived as a `termdesk://pair` link.
 *
 * The link is what the PC's pairing page encodes: scanning it with the phone's
 * own camera hands the address and token straight to the app, so nobody types a
 * 43-character secret on a phone keyboard.
 */
data class PairRequest(
    val url: String,
    val token: String,
    val name: String?,
    /**
     * The page that produced this code said the address is a relay.
     *
     * A relay is the side that keeps a list of phones, so this is what decides
     * whether "unbind this phone" has anything to tell after the local record is
     * gone.
     */
    val relay: Boolean = false,
    /**
     * More addresses to try when [url] does not answer.
     *
     * The PC hands over its own LAN and Tailscale addresses here. It has to
     * happen at pairing time: once the relay is unreachable, nothing can tell
     * this phone where else to look.
     */
    val more: List<String> = emptyList(),
) {
    val isUsable: Boolean get() = url.isNotBlank() && token.isNotBlank()
}

fun parsePairIntent(intent: Intent?): PairRequest? {
    val data = intent?.data ?: return null
    if (data.scheme?.lowercase() != "termdesk") return null
    if (data.host?.lowercase() != "pair") return null
    val url = data.getQueryParameter("url").orEmpty().trim()
    val token = data.getQueryParameter("token").orEmpty().trim()
    if (url.isEmpty() || token.isEmpty()) return null
    return PairRequest(
        url = url,
        token = token,
        name = data.getQueryParameter("name"),
        relay = data.getQueryParameter("relay") == "1",
        // Space-separated in the payload: a URL is full of characters that make
        // a comma ambiguous, and this keeps one parameter for the whole list.
        more = data.getQueryParameter("more").orEmpty()
            .split(' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() },
    )
}
