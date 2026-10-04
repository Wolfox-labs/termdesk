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
    return PairRequest(url = url, token = token, name = data.getQueryParameter("name"))
}
