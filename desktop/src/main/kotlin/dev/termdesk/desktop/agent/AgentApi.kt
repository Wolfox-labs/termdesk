package dev.termdesk.desktop.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The agent's state vocabulary, as typed DTOs.
 *
 * The desktop window is a CLIENT of the same agent the phone talks to, and it
 * reads the agent's own JSON instead of re-deriving anything (which kernels
 * exist, which are selectable, what the public address is). One source of truth;
 * `ignoreUnknownKeys` keeps the window working when the agent grows a field.
 */

@Serializable
data class TunnelState(
    val running: Boolean = false,
    val url: String? = null,
    val mode: String? = null,
    val stable: Boolean = false,
    val error: String? = null,
) {
    /** The same wording the phone's pairing page uses for the same states. */
    val label: String
        get() = when {
            !running -> "未启动"
            stable -> "固定域名"
            else -> "临时地址"
        }
}

@Serializable
data class AgentStatus(
    val ok: Boolean = false,
    val version: String? = null,
    val hostname: String? = null,
    val platform: String? = null,
    val port: Int? = null,
    val host: String? = null,
    val shell: Boolean = false,
    val roots: List<String> = emptyList(),
    val lanUrls: List<String> = emptyList(),
    val uptimeMs: Long = 0,
    val tunnel: TunnelState = TunnelState(),
    val apk: Boolean = false,
    val relay: Boolean = false,
)

@Serializable
data class Kernel(
    val id: String,
    val label: String = "",
    val tier: String = "",
    val transport: String? = null,
    val available: Boolean = false,
    val selectable: Boolean = false,
    val path: String? = null,
    val detail: String? = null,
)

@Serializable
private data class KernelsResponse(val ok: Boolean = false, val kernels: List<Kernel> = emptyList())

/** The QR as a module grid, so the window draws squares instead of parsing SVG. */
@Serializable
data class QrGrid(val size: Int = 0, val rows: List<String> = emptyList())

@Serializable
data class PairInfo(
    val ok: Boolean = false,
    val url: String? = null,
    val payload: String? = null,
    val hostname: String? = null,
    val port: Int? = null,
    val qr: QrGrid? = null,
)

/**
 * Loopback JSON client.
 *
 * These endpoints are loopback-only on purpose (they describe this computer and
 * the pairing payload carries the token), so no authentication is needed here —
 * being on this machine is the credential.
 */
class AgentApi(private val port: Int) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    private suspend fun get(path: String, timeoutSeconds: Long = 12): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) response.body() else null
        }.getOrNull()
    }

    /** Null means "the agent is not answering" — the window's stopped state. */
    suspend fun status(): AgentStatus? =
        get("/status.json")?.let { runCatching { json.decodeFromString<AgentStatus>(it) }.getOrNull() }

    /** The kernel probe can spawn an ACP handshake, so it gets a longer timeout. */
    suspend fun kernels(): List<Kernel> =
        get("/kernels.json", timeoutSeconds = 25)
            ?.let { runCatching { json.decodeFromString<KernelsResponse>(it).kernels }.getOrNull() }
            ?: emptyList()

    suspend fun pair(): PairInfo? =
        get("/pair.json")?.let { runCatching { json.decodeFromString<PairInfo>(it) }.getOrNull() }

    /**
     * True when SOMETHING is answering on the port, even if it is an older agent
     * that predates the JSON endpoints.
     *
     * Without this the window would report "已停止" for an agent that is plainly
     * running, which is worse than useless: the user would restart a healthy
     * process to "fix" it.
     */
    suspend fun health(): Boolean = get("/healthz", timeoutSeconds = 4) != null
}