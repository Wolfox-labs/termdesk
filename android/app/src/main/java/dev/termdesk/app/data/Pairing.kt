package dev.termdesk.app.data

import android.content.Intent
import android.net.Uri

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
    return parsePairUri(data.toString())
}

/**
 * 从一串配对链接里取出地址和凭据。
 *
 * 两种来源共用这一段：系统相机扫完把链接当 intent 交给 App，或者 App 自己扫完
 * 直接把字符串交过来。解析只写一份，两条路就不会解析出两种结果。
 */
fun parsePairUri(raw: String): PairRequest? {
    val data = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
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
