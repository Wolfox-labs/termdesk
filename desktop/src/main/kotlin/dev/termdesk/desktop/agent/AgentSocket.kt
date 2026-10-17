package dev.termdesk.desktop.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger

/**
 * The workbench's connection to the PC agent.
 *
 * The same frame protocol the phone speaks, over the JDK's own WebSocket — no
 * second HTTP stack, no browser. Authentication is the agent's one credential;
 * the peer is loopback because the agent this window starts is for this machine.
 */
class AgentSocket(
    private val port: Int,
    private val token: String,
    private val deviceId: String,
    private val deviceName: String,
    private val onFrame: (JsonObject) -> Unit,
    private val onState: (connected: Boolean, reason: String?) -> Unit,
    private val urlOverride: String? = null,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val generation = AtomicInteger(0)
    private var socket: WebSocket? = null

    fun connect() {
        val epoch = generation.incrementAndGet()
        onState(false, null)
        val url = urlOverride?.takeIf { it.isNotBlank() } ?: "ws://127.0.0.1:$port/"
        http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .buildAsync(URI.create(url), Listener(epoch))
            .whenComplete { ws, err ->
                if (err != null) {
                    if (epoch == generation.get()) onState(false, err.message ?: "连接失败")
                    return@whenComplete
                }
                if (epoch != generation.get()) {
                    ws.abort()
                    return@whenComplete
                }
                socket = ws
                ws.sendText(authFrame(), true)
            }
    }

    fun send(type: String, payload: JsonObject = JsonObject(emptyMap())) {
        val frame = buildJsonObject {
            put("type", type)
            payload.forEach { (key, value) -> put(key, value) }
        }
        socket?.sendText(frame.toString(), true)
    }

    fun close() {
        generation.incrementAndGet()
        runCatching { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "window closing") }
        socket = null
    }

    private fun authFrame(): String = buildJsonObject {
        put("type", "auth")
        put("token", token)
        put("v", 1)
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("clientKind", "desktop")
    }.toString()

    private inner class Listener(private val epoch: Int) : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (epoch != generation.get()) return null
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                val frame = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                if (frame != null) {
                    when (frame.string("type")) {
                        "auth.ok" -> onState(true, null)
                        "auth.fail" -> onState(false, frame.string("reason").ifBlank { "凭据无效" })
                        else -> onFrame(frame)
                    }
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (epoch == generation.get()) {
                onState(false, if (reason.isBlank()) "连接已断开 ($statusCode)" else reason)
            }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            if (epoch == generation.get()) onState(false, error.message ?: "连接出错")
        }
    }
}

internal fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

internal fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

internal fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
