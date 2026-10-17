package dev.termdesk.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What the phone shows about the computer whose Codex config is being edited. */
data class CodexTargetState(
    val targetId: String? = null,
    val targetName: String = "",
    val targetUrl: String = "",
    val online: Boolean? = null,
    val loading: Boolean = false,
    val config: CodexConfig? = null,
    val templates: List<CodexProviderTemplate> = emptyList(),
    val error: String? = null,
    val result: String? = null,
)

/** One save, as the page built it. */
data class CodexApplyRequest(
    val providerId: String,
    val providerName: String,
    val baseUrl: String,
    val wireApi: String,
    val models: List<String>,
    val model: String,
    val apiKey: String?,
    val clearApiKey: Boolean,
    val reasoningEffort: String?,
    val reasoningLevels: List<String>,
    val contextWindow: Long?,
)

/**
 * A second, deliberately short-lived connection to one paired computer.
 *
 * The settings page must be able to edit a computer's Codex config even while
 * the app is driving the phone's own sandbox, so this socket is independent of
 * [AgentClient]'s main link: opening it never moves the working target, and
 * closing it never disconnects a conversation. Every request carries an epoch;
 * a frame from a superseded socket is dropped instead of overwriting the
 * computer the person is now looking at.
 */
class CodexConfigClient(private val scope: CoroutineScope) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(CodexTargetState())
    val state: StateFlow<CodexTargetState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var generation = 0
    private var timeout: Job? = null
    private var target: PairedComputer? = null
    private var credential = ""
    private var clientId = ""
    private var clientName = ""

    fun open(target: PairedComputer, token: String, deviceId: String, deviceName: String) {
        this.target = target
        credential = token
        clientId = deviceId
        clientName = deviceName
        generation += 1
        val epoch = generation
        timeout?.cancel()
        socket?.close(1000, "superseded")
        socket = null
        _state.value = CodexTargetState(
            targetId = target.id,
            targetName = target.name,
            targetUrl = target.url,
            loading = true,
        )

        val request = Request.Builder().url(target.url).build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (epoch != generation) {
                    webSocket.close(1000, "superseded")
                    return
                }
                webSocket.send(
                    JSONObject()
                        .put("type", "auth")
                        .put("token", token)
                        .put("v", ProtocolVersion.PROTOCOL_VERSION)
                        .put("deviceId", deviceId)
                        .put("deviceName", deviceName)
                        .put("clientKind", "android")
                        .toString(),
                )
                armTimeout(epoch)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (epoch != generation) return
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (frame.optString("type")) {
                    "auth.ok" -> {
                        _state.update { it.copy(online = true) }
                        webSocket.send(JSONObject().put("type", "codex.get").toString())
                        armTimeout(epoch)
                    }

                    "auth.fail" -> {
                        fail(epoch, "电脑认证失败：${frame.optString("reason", "凭据无效")}")
                    }

                    "codex.config" -> {
                        val config = parseCodexConfig(frame.optJSONObject("config"))
                        val templates = parseTemplates(frame.optJSONArray("templates"))
                        timeout?.cancel()
                        _state.update {
                            it.copy(
                                online = true,
                                loading = false,
                                config = config,
                                templates = if (templates.isNotEmpty()) templates else it.templates,
                                error = null,
                            )
                        }
                    }

                    "action.result" -> {
                        val action = frame.optString("action")
                        if (!action.startsWith("codex.")) return
                        val ok = frame.optBoolean("ok")
                        val message = frame.optString("message")
                        timeout?.cancel()
                        _state.update {
                            it.copy(
                                // A successful apply is followed by a fresh config frame;
                                // keep the spinner until that lands, so "done" means read back.
                                loading = ok,
                                error = if (ok) it.error else message,
                                result = message,
                            )
                        }
                        if (ok) armTimeout(epoch, 15_000)
                    }

                    "error" -> {
                        val message = frame.optString("message")
                        if (message.isNotEmpty()) fail(epoch, message)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(epoch, "无法连接 ${target.name}：${t.message ?: "网络错误"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (epoch != generation || code == 1000) return
                fail(epoch, "连接已断开：$reason")
            }
        })
        socket = ws
    }

    fun reload() {
        val ws = socket
        if (ws == null || _state.value.online != true) {
            // A dropped config link is reopened on demand; the main link and the
            // working target are untouched.
            val t = target
            if (t == null) {
                _state.update { it.copy(error = "还没有连接到电脑") }
            } else {
                open(t, credential, clientId, clientName)
            }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        ws.send(JSONObject().put("type", "codex.get").toString())
        armTimeout(generation)
    }

    fun apply(request: CodexApplyRequest) {
        val ws = socket
        if (ws == null) {
            _state.update { it.copy(error = "还没有连接到电脑，配置未发送") }
            return
        }
        val frame = JSONObject()
            .put("type", "codex.apply")
            .put("providerId", request.providerId)
            .put("providerName", request.providerName)
            .put("baseUrl", request.baseUrl)
            .put("wireApi", request.wireApi)
            .put("models", JSONArray(request.models))
            .put("model", request.model)
            .put("reasoningLevels", JSONArray(request.reasoningLevels))
        request.reasoningEffort?.takeIf { it.isNotBlank() }?.let { frame.put("reasoningEffort", it) }
        request.contextWindow?.takeIf { it > 0 }?.let { frame.put("contextWindow", it) }
        request.apiKey?.takeIf { it.isNotBlank() }?.let { frame.put("apiKey", it) }
        if (request.clearApiKey) frame.put("clearApiKey", true)
        _state.update { it.copy(loading = true, error = null, result = "正在写入…") }
        ws.send(frame.toString())
        armTimeout(generation, 25_000)
    }

    fun restore(name: String?) {
        val ws = socket
        if (ws == null) {
            _state.update { it.copy(error = "还没有连接到电脑，恢复未发送") }
            return
        }
        val frame = JSONObject().put("type", "codex.restore")
        if (!name.isNullOrBlank()) frame.put("name", name)
        _state.update { it.copy(loading = true, error = null, result = "正在恢复…") }
        ws.send(frame.toString())
        armTimeout(generation, 25_000)
    }

    fun close() {
        generation += 1
        timeout?.cancel()
        timeout = null
        socket?.close(1000, "settings page closed")
        socket = null
        target = null
        credential = ""
        _state.value = CodexTargetState()
    }

    private fun fail(epoch: Int, message: String) {
        if (epoch != generation) return
        timeout?.cancel()
        _state.update { it.copy(loading = false, online = false, error = message) }
    }

    private fun armTimeout(epoch: Int, millis: Long = 10_000) {
        timeout?.cancel()
        timeout = scope.launch {
            delay(millis)
            if (epoch != generation) return@launch
            if (!_state.value.loading) return@launch
            _state.update {
                it.copy(loading = false, error = "电脑没有回应：请确认它正在运行 TermDesk 代理，且这台手机已配对")
            }
        }
    }

    private fun parseTemplates(array: JSONArray?): List<CodexProviderTemplate> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(
                    CodexProviderTemplate(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        baseUrl = o.optString("baseUrl"),
                        wireApi = o.optString("wireApi"),
                        models = o.optJSONArray("models")?.let { a ->
                            (0 until a.length()).map { a.optString(it) }
                        } ?: emptyList(),
                        contextWindow = o.optLong("contextWindow"),
                        reasoningLevels = o.optJSONArray("reasoningLevels")?.let { a ->
                            (0 until a.length()).map { a.optString(it) }
                        } ?: emptyList(),
                        defaultReasoning = o.optString("defaultReasoning"),
                        keyPrefix = o.optString("keyPrefix", "sk-"),
                    ),
                )
            }
        }
    }
}
