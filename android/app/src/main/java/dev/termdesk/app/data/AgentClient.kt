package dev.termdesk.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Keeps the retained terminal scrollback bounded on the phone. */
private const val MAX_TERM_LINES = 1500

/** Above this size the upload switches to a chunked session (P5-4). */
private const val SINGLE_SHOT_LIMIT = 32L * 1024 * 1024

/**
 * Per-request body size for chunked uploads. Must stay under the Cloudflare
 * free-tier 100 MB single-request cap; 4 MB also keeps memory use flat on a phone.
 */
private const val CHUNK_BYTES = 4L * 1024 * 1024

/**
 * Owns the WebSocket connection to the TermDesk PC agent.
 *
 * P0 scope: authenticate, then stream host status. Terminal, files and AI
 * engine routing attach to this same socket in later phases.
 */
class AgentClient(
    private val appContext: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket
        .build()

    private val _link = MutableStateFlow<LinkState>(LinkState.Idle)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _status = MutableStateFlow<HostStatus?>(null)
    val status: StateFlow<HostStatus?> = _status.asStateFlow()

    private val _processes = MutableStateFlow<List<ProcessInfo>>(emptyList())
    val processes: StateFlow<List<ProcessInfo>> = _processes.asStateFlow()

    private val _services = MutableStateFlow<List<ServiceInfo>>(emptyList())
    val services: StateFlow<List<ServiceInfo>> = _services.asStateFlow()

    /** Last action outcome, surfaced to the user as a transient message. */
    private val _lastAction = MutableStateFlow<ActionResult?>(null)
    val lastAction: StateFlow<ActionResult?> = _lastAction.asStateFlow()

    /** True while a list request is outstanding. */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ---- P2: filesystem ----

    private val _listing = MutableStateFlow<DirectoryListing?>(null)
    val listing: StateFlow<DirectoryListing?> = _listing.asStateFlow()

    /**
     * Directories the PC's agent allows browsing, as reported by `fs.roots`.
     *
     * The client asks for these after authenticating instead of hardcoding a
     * path: the home directory belongs to the PC, so assuming one would be
     * wrong on any other machine.
     */
    private val _fsRoots = MutableStateFlow<List<String>>(emptyList())
    val fsRoots: StateFlow<List<String>> = _fsRoots.asStateFlow()

    private val _openFile = MutableStateFlow<TextFile?>(null)
    val openFile: StateFlow<TextFile?> = _openFile.asStateFlow()

    /** Progress of an in-flight upload or download, as a 0..1 fraction. */
    private val _transfer = MutableStateFlow<TransferState?>(null)
    val transfer: StateFlow<TransferState?> = _transfer.asStateFlow()

    /** Result of the last file-name search, or null when not searching. */
    private val _search = MutableStateFlow<SearchResults?>(null)
    val search: StateFlow<SearchResults?> = _search.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** The file currently open in the phone's viewer, if any. */
    private val _preview = MutableStateFlow<FilePreview?>(null)
    val preview: StateFlow<FilePreview?> = _preview.asStateFlow()

    // ---- P3: terminal ----

    private val _termLines = MutableStateFlow<List<TermLine>>(emptyList())
    val termLines: StateFlow<List<TermLine>> = _termLines.asStateFlow()

    private val _termSession = MutableStateFlow<String?>(null)
    val termSession: StateFlow<String?> = _termSession.asStateFlow()

    /** True while a command is executing, so the UI can show a spinner. */
    private val _termBusy = MutableStateFlow(false)
    val termBusy: StateFlow<Boolean> = _termBusy.asStateFlow()

    /**
     * Which kernel the app runs on: the PC (`remote`) or this phone (`local`).
     *
     * One choice, made in Settings - not a switch on every screen. The terminal,
     * the conversations and the file browser all point at whatever is chosen
     * here, which is the whole point of calling it a kernel.
     */
    private val _kernelTarget = MutableStateFlow(
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_KERNEL_TARGET, "remote") ?: "remote",
    )
    val kernelTarget: StateFlow<String> = _kernelTarget.asStateFlow()

    /** Set when the agent reports the shell is not enabled on the PC. */
    private val _termUnavailable = MutableStateFlow<String?>(null)
    val termUnavailable: StateFlow<String?> = _termUnavailable.asStateFlow()

    // ---- Codex provider configuration ----

    private val _codexConfig = MutableStateFlow<CodexConfig?>(null)
    val codexConfig: StateFlow<CodexConfig?> = _codexConfig.asStateFlow()

    private val _codexTemplates = MutableStateFlow<List<CodexProviderTemplate>>(emptyList())
    val codexTemplates: StateFlow<List<CodexProviderTemplate>> = _codexTemplates.asStateFlow()

    // ---- local kernel (the sandbox that runs ON this phone) ----

    /** The agent that runs inside the phone's sandbox (null only without a context, i.e. in tests). */
    val localAgent: LocalAgent? = appContext?.let { LocalAgent(it) }

    private val localKernelInstaller = appContext?.let { LocalKernelInstaller(it, client) }

    /** Install state of the phone-side kernel, including any failure reason. */
    val localKernel: StateFlow<LocalKernelState> =
        localKernelInstaller?.state ?: MutableStateFlow(LocalKernelState()).asStateFlow()

    fun refreshLocalKernel() = localKernelInstaller?.refresh()

    /**
     * Install it: manifest -> download -> sha256 -> unpack -> run it once.
     *
     * The heavy work happens inside the installer on Dispatchers.IO; this only
     * supplies the address and the token, and refuses politely when there is no
     * connection instead of failing halfway through a 90 MB download.
     */
    fun installLocalKernel() {
        val installer = localKernelInstaller ?: return
        val base = httpBaseUrl()
        val tok = lastToken
        if (base == null || tok == null) {
            _lastAction.value = ActionResult("kernel.local", "install", false, "offline", "未连接到电脑")
            return
        }
        scope.launch {
            val result = installer.install(base, tok)
            _lastAction.value = ActionResult(
                "kernel.local", "install", result.installed, if (result.installed) "ready" else "failed", result.note,
            )
        }
    }

    /** Ask the PC what it would send, without downloading it. */
    fun loadLocalKernelManifest() {
        val installer = localKernelInstaller ?: return
        val base = httpBaseUrl()
        val tok = lastToken
        if (base == null || tok == null) return
        scope.launch { installer.loadManifest(base, tok) }
    }

    fun removeLocalKernel() {
        val installer = localKernelInstaller ?: return
        scope.launch { installer.remove() }
    }

    // ---- kernels (the PC kernel table: one source of truth) ----

    private val _engines = MutableStateFlow<List<KernelInfo>>(emptyList())
    val engines: StateFlow<List<KernelInfo>> = _engines.asStateFlow()

    // ---- existing sessions on disk ----

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private val _workspaces = MutableStateFlow<List<WorkspaceInfo>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceInfo>> = _workspaces.asStateFlow()

    private val _sessionDetail = MutableStateFlow<SessionDetail?>(null)
    val sessionDetail: StateFlow<SessionDetail?> = _sessionDetail.asStateFlow()

    /** True while a session listing or read is in flight. */
    private val _sessionsLoading = MutableStateFlow(false)
    val sessionsLoading: StateFlow<Boolean> = _sessionsLoading.asStateFlow()

    // ---- live chats (the conversation the user actually talks in) ----

    private val _chats = MutableStateFlow<List<ChatInfo>>(emptyList())
    val chats: StateFlow<List<ChatInfo>> = _chats.asStateFlow()

    /**
     * Model lists per chat, asked for on demand. Deliberately NOT part of
     * [ChatInfo]: a kernel can declare 1500 models and a chat summary has to
     * stay a summary.
     */
    private val _chatModels = MutableStateFlow<Map<String, ChatModels>>(emptyMap())
    val chatModels: StateFlow<Map<String, ChatModels>> = _chatModels.asStateFlow()

    /**
     * The command lines each conversation ran, keyed by chat id.
     *
     * Kept out of [ChatInfo] for the same reason the model list is: a chat
     * summary is a summary, and a build can run dozens of commands.
     */
    private val _chatTerminals = MutableStateFlow<Map<String, List<ChatTerminal>>>(emptyMap())
    val chatTerminals: StateFlow<Map<String, List<ChatTerminal>>> = _chatTerminals.asStateFlow()

    /** The terminal the panel is showing, with the output collected so far. */
    private val _terminalView = MutableStateFlow<TerminalView?>(null)
    val terminalView: StateFlow<TerminalView?> = _terminalView.asStateFlow()

    private companion object {
        const val PREFS = "termdesk"
        const val KEY_KERNEL_TARGET = "kernelTarget"

        /** Characters of one command's output the panel keeps in memory. */
        const val TERMINAL_TEXT_LIMIT = 48 * 1024
    }

    /** The last upload that finished, so `+` can attach it to the next message. */
    private val _lastUpload = MutableStateFlow<UploadedFile?>(null)
    val lastUpload: StateFlow<UploadedFile?> = _lastUpload.asStateFlow()

    fun clearLastUpload() {
        _lastUpload.value = null
    }

    /** The chat currently open, or null while the index is showing. */
    private val _activeChat = MutableStateFlow<ChatInfo?>(null)
    val activeChat: StateFlow<ChatInfo?> = _activeChat.asStateFlow()

    /**
     * The open chat's transcript, oldest first.
     *
     * Kept as a map keyed by seq so a streamed line can be replaced as more
     * text arrives, and a removed preview can be deleted, without rebuilding
     * the whole list from the server on every token.
     */
    private val _chatEvents = MutableStateFlow<List<ChatEvent>>(emptyList())
    val chatEvents: StateFlow<List<ChatEvent>> = _chatEvents.asStateFlow()

    /** Rebuilt from the frame stream; see [applyChatEvent]. */
    private var chatEventIndex = LinkedHashMap<Int, ChatEvent>()

    /**
     * Engine questions waiting for an answer on this phone.
     *
     * Kept with the connection state rather than inside a screen: a question that
     * arrives while another section is open must not be lost, and a reconnect
     * must not leave a stale dialog behind.
     */
    private val _approvals = MutableStateFlow<List<ChatApproval>>(emptyList())
    val approvals: StateFlow<List<ChatApproval>> = _approvals.asStateFlow()

    private val _chatSending = MutableStateFlow(false)
    val chatSending: StateFlow<Boolean> = _chatSending.asStateFlow()



    private var generation = 0
    /**
     * Total size the offline history snapshots may take on the phone.
     *
     * A session snapshot is the only thing that lets history stay readable with
     * the PC offline, but a snapshot of a long session is megabytes; without a
     * budget the cache grows with every session the user opens, forever.
     */
    private val historyCacheBudget = 48L * 1024 * 1024

    private fun cacheFile(name: String): File? = appContext?.let {
        val dir = File(it.filesDir, "history-cache").apply { mkdirs() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        File(dir, "$hash.json")
    }
    private fun cache(name: String, frame: JSONObject) {
        runCatching {
            val file = cacheFile(name) ?: return
            val text = frame.toString()
            // A single snapshot larger than a quarter of the budget would evict
            // everything else and still not fit: refuse it instead of thrashing.
            if (text.toByteArray().size > historyCacheBudget / 4) return
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text); tmp.renameTo(file)
            pruneHistoryCache()
        }
    }
    private fun cached(name: String): JSONObject? = runCatching { cacheFile(name)?.takeIf { it.exists() }?.readText()?.let { JSONObject(it) } }.getOrNull()

    /** Drop the oldest snapshots until the cache fits its budget. */
    private fun pruneHistoryCache() {
        val dir = cacheFile("index")?.parentFile ?: return
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (file in files) {
                if (total <= historyCacheBudget) break
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }
    init {
        pruneHistoryCache()
        cached("index")?.let {
            _sessions.value = parseSessionList(it.optJSONArray("sessions"))
            _workspaces.value = parseWorkspaces(it.optJSONArray("workspaces"))
        }
    }
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var lastUrl: String? = null
    private var lastToken: String? = null

    /** Which computer's credential slot this connection belongs to. */
    private var lastCredentialId: String = DeviceCredentials.LEGACY_ID

    /**
     * A credential the far side has just issued in exchange for a pairing code.
     *
     * The storable secret is handed to the caller instead of being written here:
     * which computer it belongs to is a question only the list can answer.
     */
    private val _issuedCredential = MutableStateFlow<String?>(null)
    val issuedCredential: StateFlow<String?> = _issuedCredential.asStateFlow()

    /** The issued credential has been stored; do not hand it out twice. */
    fun clearIssuedCredential() { _issuedCredential.value = null }

    @Volatile
    private var manuallyClosed = false

    /** Consecutive failed attempts, for the reconnect backoff. */
    private var attempt = 0

    /**
     * Reconnect the moment the phone gets a network.
     *
     * Turning Wi-Fi off and on again, or coming back into range, is a normal
     * thing to do; without this the app could sit in a failed state until it was
     * restarted, which reads as "it broke".
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            retryNow()
        }
    }

    fun registerNetworkCallback() {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.registerDefaultNetworkCallback(networkCallback) }
    }

    fun connect(url: String, token: String, computerId: String = DeviceCredentials.LEGACY_ID) {
        generation += 1
        lastUrl = url
        lastToken = token
        lastCredentialId = computerId
        manuallyClosed = false
        reconnectJob?.cancel()

        // Close any previous socket first. Without this a reconnect (or a
        // connect while one is already live) left the old socket open, so the
        // agent accumulated duplicate sessions from a single client.
        socket?.close(1000, "superseded")
        socket = null

        _link.value = LinkState.Connecting
        // A fresh attempt is a fresh sequence: keep the first retries quick.

        runCatching {
            val request = Request.Builder().url(url).build()
            socket = client.newWebSocket(request, Listener(token, generation))
        }.onFailure { _link.value = LinkState.Failed("节点地址无效：${it.message}") }
    }

    fun disconnect() {
        generation += 1
        manuallyClosed = true
        reconnectJob?.cancel()
        socket?.close(1000, "client closing")
        socket = null
        _link.value = LinkState.Idle
    }

    /**
     * Give up this phone's credential on the far side, best effort, then stop.
     *
     * Only a relay keeps a list of phones, so `viaRelay` decides whether there is
     * anything to tell: sending it to a direct agent would just earn an
     * "unknown frame" error. The frame goes out before the socket closes, and the
     * local record is removed by the caller either way — a phone that lost its
     * network must not stay listed as paired because the goodbye did not arrive.
     */
    fun unpairSelf(viaRelay: Boolean) {
        if (viaRelay) {
            val current = socket
            runCatching { current?.send(JSONObject().put("type", "device.unpair").toString()) }
        }
        disconnect()
    }

    // ---- P1: inventory and actions ----

    fun refreshProcesses(query: String = "") {
        _loading.value = true
        sendFrame(JSONObject().put("type", "procs.list").put("query", query))
    }

    fun refreshServices(query: String = "") {
        _loading.value = true
        sendFrame(JSONObject().put("type", "services.list").put("query", query))
    }

    fun killProcess(pid: Int) {
        sendFrame(JSONObject().put("type", "procs.kill").put("pid", pid))
    }

    fun controlService(name: String, action: String) {
        sendFrame(
            JSONObject()
                .put("type", "services.action")
                .put("name", name)
                .put("action", action),
        )
    }

    // ---- P3 operations ----

    /**
     * Choose the kernel. Everything that executes follows it.
     *
     * The scrollback is cleared because the two kernels are different machines:
     * mixing their output would make the transcript lie about where a command ran.
     * Connecting is the caller's job (see [connectLocal] and the PC URL it already
     * holds) because only the caller knows what "remote" points at.
     */
    fun setKernelTarget(target: String) {
        val clean = if (target == "local") "local" else "remote"
        if (_kernelTarget.value == clean) return
        _kernelTarget.value = clean
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_KERNEL_TARGET, clean)?.apply()
        _termLines.value = emptyList()
        _termBusy.value = false
        _termSession.value = null
    }

    /**
     * Bring up the agent inside the sandbox and talk to it.
     *
     * Same protocol, same client code as the PC: the local kernel is a second
     * machine, not a second implementation. The state is returned so the caller
     * can say why it failed when it did.
     */
    suspend fun connectLocal(): LocalAgentState? {
        val agent = localAgent ?: return null
        _termLines.value = emptyList()
        _termSession.value = null
        val state = agent.start()
        if (state.ready) connect(state.url, state.token) else _link.value = LinkState.Failed(state.note)
        return state
    }

    /** Stop the sandbox agent without forgetting the choice. */
    fun stopLocal() {
        localAgent?.stop()
        disconnect()
    }

    /** Open a terminal session, or reuse the existing one. */
    fun openTerminal() {
        if (_termSession.value != null) return
        sendFrame(JSONObject().put("type", "term.open"))
    }

    fun runCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        val sid = _termSession.value
        if (sid == null) {
            // Silence here is how "the terminal is broken" starts. Name the reason
            // the session is missing instead.
            val why = if (_kernelTarget.value == "local") {
                localAgent?.state?.value?.note?.takeIf { it.isNotBlank() } ?: "本地代理还没有启动"
            } else {
                "还没有连上电脑端代理"
            }
            appendTerm(why, TermLine.Stream.SYSTEM)
            return
        }

        // Echo locally so the terminal feels responsive before the round trip.
        appendTerm("❯ $trimmed", TermLine.Stream.INPUT)
        _termBusy.value = true
        sendFrame(JSONObject().put("type", "term.run").put("sessionId", sid).put("command", trimmed))
    }

    fun interruptCommand() {
        val sid = _termSession.value ?: return
        sendFrame(JSONObject().put("type", "term.interrupt").put("sessionId", sid))
    }

    fun closeTerminal() {
        val sid = _termSession.value ?: return
        sendFrame(JSONObject().put("type", "term.close").put("sessionId", sid))
        _termSession.value = null
        _termLines.value = emptyList()
        _termBusy.value = false
    }

    fun clearTerminal() {
        _termLines.value = emptyList()
    }

    private fun appendTerm(text: String, stream: TermLine.Stream) {
        val lines = text.split(Regex("\\r?\\n")).filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        _termLines.value = (_termLines.value + lines.map { TermLine(it, stream) })
            .takeLast(MAX_TERM_LINES)
    }

    // ---- Codex operations ----

    fun loadCodexConfig() {
        sendFrame(JSONObject().put("type", "codex.get"))
    }

    fun applyCodexProvider(
        providerId: String,
        model: String,
        apiKey: String?,
        reasoningEffort: String?,
        contextWindow: Long?,
    ) {
        val frame = JSONObject()
            .put("type", "codex.apply")
            .put("providerId", providerId)
            .put("model", model)
        if (!apiKey.isNullOrBlank()) frame.put("apiKey", apiKey)
        if (!reasoningEffort.isNullOrBlank()) frame.put("reasoningEffort", reasoningEffort)
        if (contextWindow != null && contextWindow > 0) frame.put("contextWindow", contextWindow)
        sendFrame(frame)
    }

    fun restoreCodexBackup(name: String?) {
        val frame = JSONObject().put("type", "codex.restore")
        if (!name.isNullOrBlank()) frame.put("name", name)
        sendFrame(frame)
    }

    /** Refresh the PC kernel table. Metadata only: it never runs a model. */
    fun loadEngines() {
        sendFrame(JSONObject().put("type", "kernels.list"))
    }

    // ---- existing-session operations ----

    /** Refresh the on-disk session index. Safe to call repeatedly. */
    fun loadSessions(engine: String? = null) {
        _sessionsLoading.value = true
        val frame = JSONObject().put("type", "sessions.list")
        if (!engine.isNullOrBlank()) frame.put("engine", engine)
        sendFrame(frame)
    }

    /**
     * Open one recorded session.
     *
     * The previous detail is cleared first so the view never shows a stale
     * conversation while the new one is loading.
     */
    fun openSession(session: SessionInfo) {
        if (_link.value !is LinkState.Connected) {
            val snapshot = cached("session-${session.engine}-${session.id}")
            if (snapshot != null) { _sessionDetail.value = parseSessionDetail(snapshot); _activeChat.value = null }
            else _lastAction.value = ActionResult("sessions.read", session.id, false, "not_cached", "这条记录尚未缓存，请在电脑内核在线时打开一次")
            return
        }
        _sessionDetail.value = null
        _sessionsLoading.value = true
        // Same single-column view as a live chat, so one closes the other.
        // The chat itself stays alive on the PC; only the view changes.
        _activeChat.value = null
        clearChatTranscript()
        sendFrame(
            JSONObject()
                .put("type", "sessions.read")
                .put("engine", session.engine)
                .put("sessionId", session.id)
                .put("path", session.path),
        )
    }

    /** Close the open session and return to the index. */
    fun closeSession() {
        _sessionDetail.value = null
    }

    // ---- live-chat operations ----

    /** Refresh the list of live chats. Safe to call repeatedly. */
    fun loadChats() {
        sendFrame(JSONObject().put("type", "chat.list"))
    }

    /**
     * Start a new conversation and open it.
     *
     * A chat holds a real long-lived runtime on the PC, so creating one is not
     * free (it will spawn a harness process on first send). [title] is only a
     * local label; the first message replaces it.
     *
     * [engine] is the kernel that will drive the chat (`codex` or `dsh`).
     * The new-chat UI requires an explicit choice; when omitted the agent
     * falls back to its own default, which is not the product behaviour.
     */
    fun createChat(
        cwd: String?,
        engine: String? = null,
        provider: String? = null,
        model: String? = null,
        title: String? = null,
        effort: String? = null,
    ) {
        val frame = JSONObject().put("type", "chat.create")
        if (!cwd.isNullOrBlank()) frame.put("cwd", cwd)
        if (!engine.isNullOrBlank()) frame.put("engine", engine)
        if (!provider.isNullOrBlank()) frame.put("provider", provider)
        if (!model.isNullOrBlank()) frame.put("model", model)
        if (!effort.isNullOrBlank()) frame.put("effort", effort)
        if (!title.isNullOrBlank()) frame.put("title", title)
        // Keep the previously open chat's transcript out of the new one.
        clearChatTranscript()
        _chatSending.value = true
        sendFrame(frame)
    }

    /**
     * Open an existing chat and load its transcript from scratch.
     *
     * The transcript is cleared first so the view can never show one chat's
     * conversation under another chat's title.
     */
    fun openChat(chatId: String) {
        // A chat and a recorded session occupy the same single-column view, so
        // opening one must clear the other or the wrong transcript stays up.
        _sessionDetail.value = null
        clearChatTranscript()
        sendFrame(JSONObject().put("type", "chat.read").put("chatId", chatId))
    }

    /** Send one user message. The answer streams back as `chat.event` frames. */
    /**
     * Answer a pending question.
     *
     * The dialog closes immediately: the engine is blocked on this answer, and
     * leaving it on screen during the round-trip would invite a second tap on a
     * question that is already decided. If the answer never arrives the agent
     * settles it on its own and the resolved frame puts things right.
     */
    fun respondApproval(requestId: String, optionId: String) {
        _approvals.value = _approvals.value.filterNot { it.requestId == requestId }
        sendFrame(
            JSONObject()
                .put("type", "chat.approve")
                .put("requestId", requestId)
                .put("optionId", optionId),
        )
    }

    fun sendChatMessage(chatId: String, text: String, model: String? = null, effort: String? = null) {
        _chatSending.value = true
        val frame = JSONObject().put("type", "chat.send").put("chatId", chatId).put("text", text)
        if (!model.isNullOrBlank()) frame.put("model", model)
        if (!effort.isNullOrBlank()) frame.put("effort", effort)
        sendFrame(frame)
    }

    /**
     * Change the model / reasoning effort of a live conversation.
     *
     * The kernel documents both as per-thread sticky settings, applied to the
     * turn and every following one, so this is a real change of what the next
     * reply will run on. Null clears the override back to the kernel default.
     */
    fun setChatConfig(chatId: String, model: String?, effort: String?) {
        val frame = JSONObject().put("type", "chat.config").put("chatId", chatId)
        frame.put("model", model ?: "")
        frame.put("effort", effort ?: "")
        sendFrame(frame)
    }

    /**
     * Switch this session's permission / agent mode.
     *
     * The kernel owns this setting and applies it to the live session, so it is
     * pushed now rather than carried to the next turn. A kernel that declares no
     * modes refuses, and the refusal is shown.
     */
    fun setChatMode(chatId: String, modeId: String) {
        sendFrame(JSONObject().put("type", "chat.config").put("chatId", chatId).put("mode", modeId))
    }

    /**
     * Ask what this conversation can be switched to.
     *
     * Metadata only: the PC answers from the kernel's own session declaration,
     * so no model runs for this. It also opens the session the first turn will
     * then use, which is why the answer carries that session's id.
     */
    fun requestChatModels(chatId: String) {
        sendFrame(JSONObject().put("type", "chat.models").put("chatId", chatId))
    }

    /**
     * Which command lines this conversation ran.
     *
     * Asking is also what tells the PC somebody is watching, so live output
     * starts flowing only while the panel is actually in use.
     */
    fun loadChatTerminals(chatId: String) {
        sendFrame(JSONObject().put("type", "chat.terminals").put("chatId", chatId))
    }

    /** Show one terminal: the output it already has, then whatever arrives. */
    fun openChatTerminal(chatId: String, terminalId: String, command: String, origin: String, canWrite: Boolean) {
        _terminalView.value = TerminalView(
            chatId = chatId,
            terminalId = terminalId,
            command = command,
            origin = origin,
            state = "running",
            canWrite = canWrite,
            loading = true,
        )
        sendFrame(
            JSONObject()
                .put("type", "chat.terminal.read")
                .put("chatId", chatId)
                .put("terminalId", terminalId),
        )
    }

    fun closeTerminalView() {
        _terminalView.value = null
    }

    /**
     * Type into a terminal we own.
     *
     * Nothing is drawn here: the PC echoes what was written back as
     * `chat.terminal.input`, and drawing both copies would show every line
     * twice — the same rule the transcript follows for the user's own messages.
     */
    fun sendTerminalInput(chatId: String, terminalId: String, data: String) {
        if (data.isEmpty()) return
        sendFrame(
            JSONObject()
                .put("type", "chat.terminal.input")
                .put("chatId", chatId)
                .put("terminalId", terminalId)
                .put("data", data),
        )
    }

    fun stopChatTerminal(chatId: String, terminalId: String) {
        sendFrame(
            JSONObject()
                .put("type", "chat.terminal.stop")
                .put("chatId", chatId)
                .put("terminalId", terminalId),
        )
    }

    /** Stop the current reply. The PC disposes the runtime; there is no per-turn cancel. */
    fun cancelChat(chatId: String) {
        sendFrame(JSONObject().put("type", "chat.cancel").put("chatId", chatId))
    }

    /** Forget a chat and release its runtime on the PC. */
    fun closeChat(chatId: String) {
        sendFrame(JSONObject().put("type", "chat.close").put("chatId", chatId))
    }

    fun resumeSession(session: SessionDetail) {
        val recorded = _sessions.value.find { it.id == session.id && it.engine == session.engine }
        _chatSending.value = true
        val frame = JSONObject().put("type", "chat.resume").put("engine", session.engine).put("sessionId", session.id)
        recorded?.let { frame.put("path", it.path) }
        sendFrame(frame)
    }

    /** Leave the conversation view without closing the chat itself. */
    fun leaveChat() {
        _activeChat.value = null
        clearChatTranscript()
    }

    private fun clearChatTranscript() {
        chatEventIndex = LinkedHashMap()
        _chatEvents.value = emptyList()
    }

    /** Surface a message that originated on the phone, not on the PC. */
    fun reportLocalMessage(text: String) {
        _lastAction.value = ActionResult("storage", "", true, "cleared", text)
    }

    fun clearLastAction() {
        _lastAction.value = null
    }

    // ---- P2 operations ----

    fun listDirectory(dirPath: String) {
        _loading.value = true
        sendFrame(JSONObject().put("type", "fs.list").put("path", dirPath))
    }

    fun readFile(filePath: String) {
        sendFrame(JSONObject().put("type", "fs.read").put("path", filePath))
    }

    fun closeOpenFile() {
        _openFile.value = null
    }

    fun writeFile(filePath: String, text: String) {
        sendFrame(JSONObject().put("type", "fs.write").put("path", filePath).put("text", text))
    }

    fun createEntry(dirPath: String, name: String, isDir: Boolean) {
        sendFrame(
            JSONObject()
                .put("type", "fs.mkdir")
                .put("path", dirPath)
                .put("name", name)
                .put("kind", if (isDir) "dir" else "file"),
        )
    }

    fun deleteEntry(targetPath: String) {
        sendFrame(JSONObject().put("type", "fs.delete").put("path", targetPath))
    }

    fun renameEntry(targetPath: String, newName: String) {
        sendFrame(JSONObject().put("type", "fs.rename").put("path", targetPath).put("name", newName))
    }

    /**
     * Transfer endpoints live on the same host and port as the WebSocket, but
     * over HTTP with a bearer token.
     */
    fun httpBaseUrl(): String? {
        val ws = lastUrl ?: return null
        return when {
            ws.startsWith("wss://") -> "https://" + ws.removePrefix("wss://")
            ws.startsWith("ws://") -> "http://" + ws.removePrefix("ws://")
            else -> null
        }
    }

    fun authToken(): String? = lastToken

    /** Download a remote file into the phone's app-private external dir. */
    fun downloadFile(remotePath: String, fileName: String) {
        val base = httpBaseUrl()
        val tok = lastToken
        if (base == null || tok == null) {
            _lastAction.value = ActionResult("fs.download", remotePath, false, "offline", "未连接到电脑")
            return
        }
        _transfer.value = TransferState(fileName, 0f, "准备下载…")
        scope.launch {
            runCatching {
                val encoded = URLEncoder.encode(remotePath, "UTF-8")
                val request = Request.Builder()
                    .url("$base/download?path=$encoded")
                    .header("Authorization", "Bearer $tok")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("empty response body")
                    val total = body.contentLength()
                    val dir = downloadDir()
                    val outFile = File(dir, fileName)
                    body.byteStream().use { input ->
                        outFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read = input.read(buffer)
                            var done = 0L
                            while (read >= 0) {
                                output.write(buffer, 0, read)
                                done += read
                                // Report progress only when the length is known.
                                if (total > 0) {
                                    _transfer.value = TransferState(fileName, done.toFloat() / total, "下载中…")
                                }
                                read = input.read(buffer)
                            }
                        }
                    }
                    outFile
                }
            }.onSuccess { file ->
                _transfer.value = null
                _lastAction.value = ActionResult(
                    "fs.download", remotePath, true, "downloaded",
                    "已保存到 ${file.absolutePath}",
                )
            }.onFailure { err ->
                _transfer.value = null
                _lastAction.value = ActionResult(
                    "fs.download", remotePath, false, "download_failed",
                    "下载失败：${err.message}",
                )
            }
        }
    }

    /**
     * Upload a phone file to the given remote directory.
     *
     * Small files go as one POST. Anything above [SINGLE_SHOT_LIMIT] opens a
     * chunked session so each request body stays under the Cloudflare free-tier
     * cap (100 MB) and the phone can stream from a content URI instead of
     * holding the whole file in RAM.
     */
    fun uploadFile(uri: Uri, remoteDir: String) {
        val base = httpBaseUrl()
        val tok = lastToken
        if (base == null || tok == null) {
            _lastAction.value = ActionResult("fs.upload", remoteDir, false, "offline", "未连接到电脑")
            return
        }
        val resolver = appContext?.contentResolver
        if (resolver == null) {
            _lastAction.value = ActionResult("fs.upload", remoteDir, false, "no_context", "无法访问文件")
            return
        }

        scope.launch {
            val displayName = queryDisplayName(resolver, uri) ?: "upload.bin"
            _transfer.value = TransferState(displayName, 0f, "准备上传…")
            runCatching {
                val remotePath = remoteDir.trimEnd('\\', '/') + "\\" + displayName
                val size = querySize(resolver, uri)
                if (size != null && size > SINGLE_SHOT_LIMIT) {
                    uploadChunked(base, tok, resolver, uri, remotePath, displayName, size)
                } else {
                    uploadSingleShot(base, tok, resolver, uri, remotePath, displayName)
                }
                remotePath
            }.onSuccess { remotePath ->
                _transfer.value = null
                _lastUpload.value = UploadedFile(displayName, remotePath, System.currentTimeMillis())
                _lastAction.value = ActionResult(
                    "fs.upload", remotePath, true, "uploaded",
                    "已上传 ${displayName}",
                )
                // Refresh the listing so the new file appears immediately.
                listDirectory(remoteDir)
            }.onFailure { err ->
                _transfer.value = null
                _lastAction.value = ActionResult(
                    "fs.upload", remoteDir, false, "upload_failed",
                    "上传失败：${err.message}",
                )
            }
        }
    }

    /** One-shot POST. Fine up to a few tens of MB; larger files use [uploadChunked]. */
    private fun uploadSingleShot(
        base: String,
        tok: String,
        resolver: ContentResolver,
        uri: Uri,
        remotePath: String,
        displayName: String,
    ) {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("cannot open selected file")
        val encoded = URLEncoder.encode(remotePath, "UTF-8")
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url("$base/upload?path=$encoded&overwrite=1")
            .header("Authorization", "Bearer $tok")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
        }
        _transfer.value = TransferState(displayName, 1f, "上传完成")
    }

    /**
     * Chunked upload (P5-4): open a session, PUT each chunk at its offset, commit.
     * Every request body is at most [CHUNK_BYTES], which stays under the
     * Cloudflare free-tier 100 MB single-request limit.
     */
    private fun uploadChunked(
        base: String,
        tok: String,
        resolver: ContentResolver,
        uri: Uri,
        remotePath: String,
        displayName: String,
        size: Long,
    ) {
        val encodedPath = URLEncoder.encode(remotePath, "UTF-8")
        val openReq = Request.Builder()
            .url("$base/upload/session?path=$encodedPath&size=$size&chunkSize=$CHUNK_BYTES&overwrite=1")
            .header("Authorization", "Bearer $tok")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val opened = client.newCall(openReq).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} 开启分片会话失败")
            JSONObject(response.body?.string() ?: "{}")
        }
        val uploadId = opened.optString("uploadId")
        if (uploadId.isEmpty()) error("会话响应缺少 uploadId")
        val chunkSize = opened.optLong("chunkSize", CHUNK_BYTES)
        val totalChunks = opened.optInt("totalChunks", 0)

        var index = 0
        var sent = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(chunkSize.toInt().coerceAtMost(CHUNK_BYTES.toInt()))
            while (sent < size) {
                val want = minOf(buf.size.toLong(), size - sent).toInt()
                var filled = 0
                while (filled < want) {
                    val n = input.read(buf, filled, want - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled <= 0) error("输入流提前结束")
                val slice = buf.copyOf(filled)
                val putReq = Request.Builder()
                    .url("$base/upload/session?uploadId=$uploadId&index=$index")
                    .header("Authorization", "Bearer $tok")
                    .put(slice.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(putReq).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code} 分片 $index 失败")
                }
                sent += filled
                index += 1
                if (totalChunks > 0) {
                    _transfer.value = TransferState(
                        displayName,
                        (sent.toFloat() / size).coerceIn(0f, 1f),
                        "上传中 $index/$totalChunks",
                    )
                }
            }
        } ?: error("cannot open selected file")

        val commitReq = Request.Builder()
            .url("$base/upload/session/commit?uploadId=$uploadId")
            .header("Authorization", "Bearer $tok")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(commitReq).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} 提交失败")
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** File size when the content resolver exposes one; null means "unknown". */
    private fun querySize(resolver: ContentResolver, uri: Uri): Long? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
            }
        }.getOrNull()
    }

    /** App-private external directory: no storage permission required. */
    // ---- file preview ------------------------------------------------------

    /**
     * Open a file in the phone's viewer.
     *
     * text goes to the editor; image and pdf are fetched into the app cache and
     * drawn locally (Android has a PDF rasteriser, so no conversion on the PC);
     * docx has its text extracted on the PC, because a phone has no Word engine
     * and shipping a layout converter would be a lie about fidelity. Anything
     * else is shown as information with "open with" and download.
     */
    fun openPreview(entry: FileEntry) {
        if (entry.isDir) return
        when (entry.kind) {
            "text" -> {
                _preview.value = null
                readFile(entry.path)
            }
            "docx" -> {
                _openFile.value = null
                _preview.value = FilePreview(
                    kind = "docx",
                    name = entry.name,
                    path = entry.path,
                    loading = true,
                    sizeBytes = entry.sizeBytes,
                )
                sendFrame(JSONObject().put("type", "fs.doctext").put("path", entry.path))
            }
            "image", "pdf" -> {
                _openFile.value = null
                _preview.value = FilePreview(
                    kind = entry.kind,
                    name = entry.name,
                    path = entry.path,
                    loading = true,
                    sizeBytes = entry.sizeBytes,
                )
                fetchPreviewBytes(entry)
            }
            else -> {
                _openFile.value = null
                _preview.value = FilePreview(
                    kind = "other",
                    name = entry.name,
                    path = entry.path,
                    sizeBytes = entry.sizeBytes,
                )
            }
        }
    }

    fun closePreview() {
        // The fetched copy exists only so the platform could decode it; leaving
        // every file the user glanced at would turn a cache into a leak.
        _preview.value?.localFile?.delete()
        _preview.value = null
    }

    // ---- file search -------------------------------------------------------

    /**
     * Search for files by name under [dirPath].
     *
     * The walk happens on the PC and is bounded there (count, depth, time), so a
     * broad query cannot hang the phone; the answer says when it was cut short.
     */
    fun searchFiles(dirPath: String, query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            clearSearch()
            return
        }
        _searching.value = true
        sendFrame(
            JSONObject()
                .put("type", "fs.search")
                .put("path", dirPath)
                .put("query", q)
                .put("limit", 200),
        )
    }

    fun clearSearch() {
        _search.value = null
        _searching.value = false
    }

    private fun parseSearch(frame: JSONObject): SearchResults {
        val arr = frame.optJSONArray("items")
        val items = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val isDir = o.optBoolean("isDir", false)
                    add(
                        FileEntry(
                            name = o.optString("name"),
                            path = o.optString("path"),
                            isDir = isDir,
                            sizeBytes = o.optLong("sizeBytes"),
                            mtime = if (o.isNull("mtime")) null else o.optString("mtime"),
                            kind = o.optString("kind", if (isDir) "dir" else "other"),
                        ),
                    )
                }
            }
        }
        return SearchResults(
            path = frame.optString("path"),
            query = frame.optString("query"),
            items = items,
            truncated = frame.optBoolean("truncated", false),
            scannedDirs = frame.optInt("scannedDirs"),
        )
    }

    /** Copy the previewed file into the phone's download folder for later use. */
    fun savePreviewToDownloads() {
        val open = _preview.value ?: return
        downloadFile(open.path, open.name)
    }

    /**
     * Fetch the bytes into the app cache.
     *
     * The cache, not the download folder: this copy exists only so the platform
     * can decode it, and a viewer must not litter the user's Downloads with
     * every file they glance at.
     */
    private fun fetchPreviewBytes(entry: FileEntry) {
        _preview.value?.localFile?.delete()
        val base = httpBaseUrl()
        val tok = lastToken
        if (base == null || tok == null) {
            _preview.value = _preview.value?.copy(loading = false, message = "未连接到电脑")
            return
        }
        scope.launch {
            runCatching {
                val encoded = URLEncoder.encode(entry.path, "UTF-8")
                val request = Request.Builder()
                    .url("$base/download?path=$encoded")
                    .header("Authorization", "Bearer $tok")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("响应为空")
                    val dir = previewDir()
                    val out = File(dir, safeFileName(entry.name))
                    body.byteStream().use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    out
                }
            }.onSuccess { file ->
                _preview.value = _preview.value?.copy(loading = false, localFile = file)
            }.onFailure { err ->
                _preview.value = _preview.value?.copy(loading = false, message = "无法读取：${err.message}")
            }
        }
    }

    private fun previewDir(): File {
        val ctx = appContext ?: return File("/data/local/tmp")
        val dir = File(ctx.cacheDir, "preview")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "preview.bin" }

    private fun downloadDir(): File {
        val ctx = appContext ?: return File("/sdcard/Download")
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sendFrame(obj: JSONObject): Boolean {
        val ws = socket
        if (ws == null || _link.value !is LinkState.Connected) {
            _loading.value = false
            _chatSending.value = false
            _sessionsLoading.value = false
            _termBusy.value = false
            _lastAction.value = ActionResult("", "", false, "offline", "电脑内核离线，操作未发送；已缓存内容仍可查看")
            return false
        }
        return ws.send(obj.toString())
    }

    /**
     * Reconnect after an unexpected drop.
     *
     * Backoff, not a single 3-second retry, and it never gives up on its own:
     * switching Wi-Fi off and on again, or walking out of range and back, must
     * not leave the app dead until it is restarted. A refused token is the one
     * case that stops retrying, because that cannot fix itself.
     */
    private fun scheduleReconnect() {
        if (manuallyClosed) return
        val url = lastUrl ?: return
        val token = lastToken ?: return
        reconnectJob?.cancel()
        val waitMs = minOf(30_000L, 2_000L shl minOf(attempt, 4))
        attempt += 1
        reconnectJob = scope.launch {
            delay(waitMs)
            if (!manuallyClosed) connect(url, token)
        }
    }

    /**
     * Retry immediately, ignoring any pending backoff.
     *
     * Called when the phone gains a network and when the app returns to the
     * foreground: both are moments where waiting out the backoff would look like
     * a broken app.
     */
    fun retryNow() {
        val url = lastUrl ?: return
        val token = lastToken ?: return
        if (manuallyClosed) return
        if (_link.value is LinkState.Connected) return
        attempt = 0
        reconnectJob?.cancel()
        connect(url, token)
    }

    private inner class Listener(private val token: String, private val epoch: Int) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (epoch != generation) { webSocket.close(1000, "superseded"); return }
            webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (epoch != generation) return
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (frame.optString("type")) {
                "device.paired" -> {
                    val permanent = frame.optString("token")
                    if (permanent.isNotBlank()) {
                        // Only a relay ever sends this frame, so it also answers
                        // "is this binding a relay one?" without guessing.
                        _issuedCredential.value = permanent
                        lastToken = permanent
                    }
                }
                "node.status" -> {
                    if (!frame.optBoolean("online")) {
                        _link.value = LinkState.NodeOffline(frame.optString("hostname", "电脑"))
                        _loading.value = false
                        _chatSending.value = false
                        _termBusy.value = false
                    }
                }
                "auth.ok" -> {
                    if (frame.has("nodeOnline") && !frame.optBoolean("nodeOnline")) {
                        _link.value = LinkState.NodeOffline(frame.optString("hostname", "电脑"))
                        return
                    }
                    _link.value = LinkState.Connected(frame.optString("hostname", "unknown"))
                    attempt = 0
                    webSocket.send(
                        JSONObject().put("type", "status.subscribe").put("intervalMs", 2000).toString(),
                    )
                    // Ask the agent which directories it allows. The home
                    // directory is a property of the PC, not of this app, so
                    // the client must not assume one.
                    sendFrame(JSONObject().put("type", "fs.roots"))
                    loadChats()
                    loadSessions()
                    _activeChat.value?.let { sendFrame(JSONObject().put("type", "chat.read").put("chatId", it.id)) }
                }
                "auth.fail" -> {
                    // Not a network problem, and not something retrying can fix: the
                    // far side does not recognise this phone's credential — it was
                    // revoked, or this address now belongs to a relay that never
                    // issued it. Say what to do instead of only what happened.
                    val reason = frame.optString("reason", "token 无效")
                    _link.value = LinkState.Failed("这台电脑不认这个凭据（$reason）：可能已被吊销，或这个地址换了中转。请在电脑上打开 /pair 重新配对")
                    manuallyClosed = true // a bad token will never fix itself by retrying
                }
                "status" -> _status.value = parseStatus(frame.optJSONObject("status"))
                "procs" -> {
                    _loading.value = false
                    _processes.value = parseProcesses(frame.optJSONArray("items"))
                }
                "services" -> {
                    _loading.value = false
                    _services.value = parseServices(frame.optJSONArray("items"))
                }
                "fs.listing" -> {
                    _loading.value = false
                    _listing.value = parseListing(frame)
                }
                "fs.roots" -> {
                    val arr = frame.optJSONArray("roots")
                    val roots = buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val r = arr.optString(i)
                                if (r.isNotBlank()) add(r)
                            }
                        }
                    }
                    _fsRoots.value = roots
                }
                "fs.results" -> {
                    _searching.value = false
                    _search.value = parseSearch(frame)
                }
                "fs.doctext" -> {
                    // The PC extracted a Word document's text. The viewer owns
                    // this, not the editor: the file itself stays on the PC.
                    _preview.value = _preview.value?.copy(
                        loading = false,
                        text = frame.optString("text"),
                    )
                }
                "fs.file" -> {
                    _openFile.value = TextFile(
                        path = frame.optString("path"),
                        text = frame.optString("text"),
                        sizeBytes = frame.optLong("sizeBytes"),
                    )
                }
                "fs.written" -> {
                    // Keep the editor in sync with what was actually stored.
                    val path = frame.optString("path")
                    _openFile.value = _openFile.value?.takeIf { it.path == path }
                }
                "term.opened" -> {
                    _termSession.value = frame.optString("sessionId")
                    _termUnavailable.value = null
                    // Replay scrollback so a reconnect does not lose history.
                    val sb = frame.optJSONArray("scrollback")
                    if (sb != null && _termLines.value.isEmpty()) {
                        val restored = buildList {
                            for (i in 0 until sb.length()) {
                                val o = sb.optJSONObject(i) ?: continue
                                add(
                                    TermLine(
                                        o.optString("line"),
                                        TermLine.Stream.fromWire(o.optString("stream")),
                                    ),
                                )
                            }
                        }
                        _termLines.value = restored.takeLast(MAX_TERM_LINES)
                    }
                }
                "term.output" -> {
                    val sid = frame.optString("sessionId")
                    if (sid == _termSession.value) {
                        appendTerm(frame.optString("text"), TermLine.Stream.fromWire(frame.optString("stream")))
                    }
                }
                "term.exit" -> {                    _termBusy.value = false
                    val code = frame.optInt("code", 0)
                    val error = if (frame.isNull("error")) null else frame.optString("error")
                    when (error) {
                        "shell_disabled" ->
                            _termUnavailable.value = "电脑端未启用终端，请用 --enable-shell 启动代理"
                        "no_such_session" -> {
                            _termSession.value = null
                            appendTerm("[终端已关闭]", TermLine.Stream.SYSTEM)
                        }
                        "interrupted" -> appendTerm("[已中断]", TermLine.Stream.SYSTEM)
                        "session_ended" -> appendTerm("[会话已结束]", TermLine.Stream.SYSTEM)
                        null -> if (code != 0) {
                            appendTerm("[退出码 $code]", TermLine.Stream.SYSTEM)
                        }
                        else -> appendTerm("[错误: $error]", TermLine.Stream.SYSTEM)
                    }
                }
                "action.result" -> {
                    val action = frame.optString("action")
                    if (action.startsWith("chat.")) _chatSending.value = false
                    _lastAction.value = ActionResult(
                        action = action,
                        target = frame.optString("target"),
                        ok = frame.optBoolean("ok", false),
                        code = frame.optString("code"),
                        message = frame.optString("message"),
                    )
                    // Same self-heal as the "error" branch: a send to a chat the
                    // restarted agent forgot re-attaches instead of failing.
                    if (action == "chat.send" && frame.optString("code") == "no_chat") reattachActiveChat()
                }
                "codex.config" -> {
                    _codexConfig.value = parseCodexConfig(frame.optJSONObject("config"))
                    val tpl = frame.optJSONArray("templates")
                    if (tpl != null && tpl.length() > 0) {
                        _codexTemplates.value = buildList {
                            for (i in 0 until tpl.length()) {
                                val o = tpl.optJSONObject(i) ?: continue
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
                "error" -> {
                    _loading.value = false
                    val msg = frame.optString("message")
                    if (msg.isNotEmpty()) _lastAction.value =
                        ActionResult(frame.optString("code"), "", false, frame.optString("code"), msg)
                    // The agent keeps chats in memory, so a phone that was showing
                    // a conversation when the agent restarted holds an id it no
                    // longer knows. Re-attach by the native session identity rather
                    // than leaving a dead conversation on screen.
                    if (frame.optString("code") == "no_chat") reattachActiveChat()
                    // A refused request must not leave a spinner turning forever.
                    _searching.value = false
                    _preview.value = _preview.value?.copy(loading = false, message = msg)
                }
                // The kernel table, straight from the PC registry.
                "kernels" -> _engines.value = parseKernels(frame.optJSONArray("kernels"))
                "sessions" -> {
                    _sessionsLoading.value = false
                    cache("index", frame)
                    _sessions.value = parseSessionList(frame.optJSONArray("sessions"))
                    _workspaces.value = parseWorkspaces(frame.optJSONArray("workspaces"))
                }
                "session" -> {
                    _sessionsLoading.value = false
                    cache("session-${frame.optString("engine")}-${frame.optString("id")}", frame)
                    _sessionDetail.value = parseSessionDetail(frame)
                }
                // ---- live chat frames ----
                "chats" -> {
                    _chats.value = parseChatList(frame.optJSONArray("chats"))
                    // The agent's pending list is authoritative, so a question it
                    // already settled (a timeout, or while this phone was away)
                    // disappears instead of leaving a stale dialog behind.
                    frame.optJSONArray("approvals")?.let { _approvals.value = parseApprovals(it) }
                }
                "chat.approval" -> applyApproval(frame)
                "chat.models" -> applyChatModels(frame)
                // ---- a conversation's command lines ----
                "chat.terminals" -> {
                    val chatId = frame.optString("chatId")
                    if (chatId.isNotBlank()) {
                        _chatTerminals.value = _chatTerminals.value +
                            (chatId to ChatTerminal.list(frame.optJSONArray("terminals")))
                        refreshTerminalViewFromList(chatId)
                    }
                }
                "chat.terminal" -> applyTerminalSnapshot(frame)
                "chat.terminal.output" -> appendTerminalOutput(frame)
                "chat.terminal.input" -> appendTerminalInput(frame)
                "chat" -> {
                    _chatSending.value = false
                    val info = parseChatInfo(frame)
                    if (info != null) {
                        _sessionDetail.value = null
                        _activeChat.value = info
                        upsertChat(info)
                    }
                    // A `chat` frame is either a creation reply or a full
                    // transcript. A transcript carries `events` and replaces
                    // everything: it is the authoritative snapshot. A creation
                    // reply does not, so ask for the transcript to pick up the
                    // chat's own opening lines.
                    if (frame.has("events")) {
                        chatEventIndex = LinkedHashMap()
                        _chatEvents.value = parseChatEvents(frame.optJSONArray("events") ?: JSONArray())
                        chatEventIndex = LinkedHashMap(
                            _chatEvents.value.associateBy { it.seq },
                        )
                    } else if (info != null) {
                        sendFrame(JSONObject().put("type", "chat.read").put("chatId", info.id))
                    }
                }
                "chat.sent" -> {
                    _chatSending.value = false
                    _activeChat.value?.let { upsertChat(it) }
                }
                "chat.event" -> applyChatEvent(frame)
                "chat.status" -> {
                    val chatId = frame.optString("chatId")
                    val status = frame.optString("status")
                    if (chatId.isNotEmpty() && status.isNotEmpty()) {
                        updateChatStatus(chatId, status)
                    }
                }
                "chat.turn" -> {
                    val chatId = frame.optString("chatId")
                    val state = frame.optString("state")
                    // A finished turn clears the in-flight flag even if the
                    // receipt frame was missed, so the composer never sticks.
                    if (state == "ended" || state == "failed" || state == "cancelled" || state == "idle") {
                        _chatSending.value = false
                    }
                    if (chatId.isNotEmpty() && state == "failed") {
                        updateChatStatus(chatId, "failed")
                    }
                }
                "chat.closed" -> {
                    val chatId = frame.optString("chatId")
                    _chats.value = _chats.value.filterNot { it.id == chatId }
                    _chatModels.value = _chatModels.value - chatId
                    _chatTerminals.value = _chatTerminals.value - chatId
                    if (_terminalView.value?.chatId == chatId) _terminalView.value = null
                    _approvals.value = _approvals.value.filterNot { it.chatId == chatId }
                    if (_activeChat.value?.id == chatId) {
                        _activeChat.value = null
                        clearChatTranscript()
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (epoch != generation) return
            socket = null
            _link.value = LinkState.Failed(t.message ?: "连接失败")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (epoch != generation) return
            socket = null
            when (code) {
                // A bad credential will never fix itself by retrying.
                4401 -> {
                    manuallyClosed = true
                    _link.value = LinkState.Failed("连接已断开 ($code $reason)")
                }
                /**
                 * The relay allows one connected phone per computer, and says so
                 * instead of letting the newcomer push the first one out. Nothing is
                 * wrong with this phone, so keep trying: when the other phone
                 * disconnects, this one comes back on its own.
                 */
                4409 -> {
                    _link.value = LinkState.Failed("这台电脑已有另一台手机在线（同一时间只允许一台）；它断开后会自动重连")
                    scheduleReconnect()
                }
                else -> {
                    if (!manuallyClosed) _link.value = LinkState.Failed("连接已断开 ($code $reason)")
                    scheduleReconnect()
                }
            }
        }
    }

    // ---- live chat parsing and reduction ----

    private fun parseApprovals(arr: JSONArray?): List<ChatApproval> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                parseApproval(o)?.let { add(it) }
            }
        }
    }

    private fun parseApproval(o: JSONObject): ChatApproval? {
        val requestId = o.optString("requestId").takeIf { it.isNotBlank() } ?: return null
        val options = o.optJSONArray("options")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    add(
                        ChatApprovalOption(
                            id = id,
                            label = item.optString("label").takeIf { it.isNotBlank() } ?: id,
                            style = item.optString("style"),
                        ),
                    )
                }
            }
        } ?: emptyList()
        if (options.isEmpty()) return null
        return ChatApproval(
            requestId = requestId,
            chatId = o.optString("chatId").takeIf { it.isNotBlank() },
            engine = o.optString("engine"),
            title = o.optString("title").takeIf { it.isNotBlank() } ?: "内核请求权限",
            detail = o.optString("detail"),
            kind = o.optString("kind"),
            options = options,
            fallback = o.optString("fallback").takeIf { it.isNotBlank() } ?: "deny",
            expiresAt = o.optLong("expiresAt").takeIf { it > 0 } ?: (System.currentTimeMillis() + 5 * 60_000),
        )
    }

    /**
     * Fold one approval frame into the pending list.
     *
     * A plain frame adds or refreshes the question; a `state: "resolved"` frame
     * removes it, so a question the agent settled by itself (a timeout, or this
     * phone being offline when it was asked) closes without the user touching it.
     */
    private fun applyApproval(frame: JSONObject) {
        val request = parseApproval(frame) ?: return
        val rest = _approvals.value.filterNot { it.requestId == request.requestId }
        _approvals.value = if (frame.optString("state") == "resolved") rest else rest + request
    }

    private fun parseChatList(arr: JSONArray?): List<ChatInfo> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                parseChatInfo(o)?.let { add(it) }
            }
        }
    }

    /**
     * Re-attach the open conversation after the agent forgot it.
     *
     * Chats are process-local handles on the PC; the kernel's session id is the
     * durable identity. Resuming is metadata-only on the agent side, so this
     * costs no model work and can run on every reconnect.
     */
    private fun reattachActiveChat(): Boolean {
        val chat = _activeChat.value ?: return false
        if (chat.engine != "codex") return false
        val native = chat.threadId?.takeIf { it.isNotBlank() } ?: chat.sessionId?.takeIf { it.isNotBlank() } ?: return false
        _chatSending.value = false
        return sendFrame(
            JSONObject().put("type", "chat.resume").put("engine", chat.engine).put("sessionId", native),
        )
    }

    private fun parseChatInfo(o: JSONObject): ChatInfo? {
        val id = o.optString("id")
        if (id.isEmpty()) return null
        return ChatInfo(
            id = id,
            // optString() turns a JSON null into the four-letter word "null",
            // which then showed up on screen as a model called null. Absent
            // means absent: the kernel default, not a name.
            title = if (o.isNull("title")) "" else o.optString("title"),
            cwd = o.optString("cwd"),
            provider = if (o.isNull("provider")) "" else o.optString("provider"),
            model = if (o.isNull("model")) "" else o.optString("model"),
            effort = if (o.isNull("effort")) "" else o.optString("effort"),
            mode = if (o.isNull("mode")) "" else o.optString("mode"),
            status = o.optString("status", "idle"),
            ready = o.optBoolean("ready", false),
            engine = o.optString("engine", "dsh"),
            threadId = if (o.isNull("threadId")) null else o.optString("threadId"),
            sessionId = if (o.isNull("sessionId")) null else o.optString("sessionId"),
            createdAt = o.optLong("createdAt"),
            lastUsedAt = o.optLong("lastUsedAt"),
            eventCount = o.optInt("eventCount"),
            lastError = if (o.isNull("lastError")) null else o.optString("lastError"),
        )
    }

    private fun parseChatEvents(arr: JSONArray): List<ChatEvent> {
        val out = ArrayList<ChatEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parseChatEvent(o))
        }
        return out
    }

    private fun parseChatEvent(o: JSONObject): ChatEvent {
        val meta = o.optJSONObject("meta")
        return ChatEvent(
            seq = o.optInt("seq"),
            at = o.optLong("at"),
            kind = o.optString("kind"),
            role = if (o.isNull("role")) null else o.optString("role"),
            text = o.optString("text"),
            name = if (o.isNull("name")) null else o.optString("name"),
            state = meta?.let { if (it.isNull("state")) null else it.optString("state") },
            exitCode = meta?.let { if (it.isNull("exitCode")) null else it.optInt("exitCode") },
            sourceKind = meta?.let { if (it.isNull("sourceKind")) null else it.optString("sourceKind") },
            streaming = o.optBoolean("streaming", false),
        )
    }

    /**
     * Merge one streamed transcript change into the open view.
     *
     * The agent sends the whole changed line on every frame, so a line that is
     * still being written is replaced in place rather than appended; the
     * `removed` flag deletes a preview that the runtime's authoritative message
     * superseded. Rebuilding from a server read on each token would make the
     * answer flicker while it is being written.
     */
    private fun applyChatEvent(frame: JSONObject) {
        val chatId = frame.optString("chatId")
        val active = _activeChat.value
        // Ignore output for a chat the user has left: its events would otherwise
        // appear inside whichever conversation is open now.
        if (active == null || chatId.isEmpty() || active.id != chatId) return

        val seq = frame.optInt("seq", -1)
        if (seq < 0) return

        if (frame.optBoolean("removed", false)) {
            chatEventIndex.remove(seq)
            _chatEvents.value = chatEventIndex.values.sortedBy { it.seq }
            return
        }

        val item = frame.optJSONObject("item") ?: return
        val event = parseChatEvent(item)
        chatEventIndex[event.seq] = event
        _chatEvents.value = chatEventIndex.values.sortedBy { it.seq }
    }

    /** Keep the kernel's answer for this chat; the picker reads it from here. */
    private fun applyChatModels(frame: JSONObject) {
        val chatId = frame.optString("chatId")
        if (chatId.isBlank()) return
        val models = frame.optJSONArray("models")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isNotBlank()) add(ModelChoice(id, o.optString("label").ifBlank { id }))
                }
            }
        }.orEmpty()
        val current = if (frame.isNull("current")) null else frame.optString("current").ifBlank { null }
        val note = if (frame.isNull("message")) null else frame.optString("message").ifBlank { null }
        val modeObj = frame.optJSONObject("modes")
        val modes = modeObj?.optJSONArray("availableModes")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isNotBlank()) add(ModelChoice(id, o.optString("name").ifBlank { id }))
                }
            }
        }.orEmpty()
        val currentMode = modeObj?.let { if (it.isNull("currentModeId")) null else it.optString("currentModeId").ifBlank { null } }
        _chatModels.value = _chatModels.value + (chatId to ChatModels(
            chatId = chatId,
            supported = frame.optBoolean("supported", false),
            current = current,
            models = models,
            modes = modes,
            currentMode = currentMode,
            note = note,
        ))
    }

    /**
     * How much of one command's output the panel keeps.
     *
     * The PC already caps what it sends; this cap is for the phone's own memory
     * when a command prints for a long time, and the tail is what matters.
     */
    private fun capTerminalText(text: String): String =
        if (text.length <= TERMINAL_TEXT_LIMIT) text else text.takeLast(TERMINAL_TEXT_LIMIT)

    /** The whole output of one terminal, as the PC has it now. */
    private fun applyTerminalSnapshot(frame: JSONObject) {
        val chatId = frame.optString("chatId")
        val terminalId = frame.optString("terminalId")
        val state = frame.optString("state", "running")
        val canWrite = frame.optBoolean("canWrite", false)
        val truncated = frame.optBoolean("truncated", false)
        val output = capTerminalText(frame.optString("output"))
        val view = _terminalView.value
        if (view != null && view.chatId == chatId && view.terminalId == terminalId) {
            _terminalView.value = view.copy(
                output = output,
                state = state,
                canWrite = canWrite,
                truncated = truncated,
                loading = false,
            )
        }
        updateTerminalInList(chatId, terminalId) {
            it.copy(state = state, canWrite = canWrite, truncated = truncated, bytes = output.length)
        }
    }

    /** Output that arrived while the panel is open. */
    private fun appendTerminalOutput(frame: JSONObject) {
        val chatId = frame.optString("chatId")
        val terminalId = frame.optString("terminalId")
        val chunk = frame.optString("chunk")
        if (chunk.isEmpty()) return
        val view = _terminalView.value
        if (view != null && view.chatId == chatId && view.terminalId == terminalId) {
            _terminalView.value = view.copy(output = capTerminalText(view.output + chunk))
        }
        updateTerminalInList(chatId, terminalId) { it.copy(bytes = it.bytes + chunk.length) }
    }

    /**
     * What was typed into a terminal that belongs to us.
     *
     * Drawn from the PC's echo rather than when the key was pressed, so the
     * panel can never show a line the PC did not actually receive.
     */
    private fun appendTerminalInput(frame: JSONObject) {
        val chatId = frame.optString("chatId")
        val terminalId = frame.optString("terminalId")
        val data = frame.optString("data")
        if (data.isEmpty()) return
        val view = _terminalView.value
        if (view != null && view.chatId == chatId && view.terminalId == terminalId) {
            _terminalView.value = view.copy(
                output = capTerminalText(view.output + "\n» " + data.trimEnd('\n')),
            )
        }
    }

    /** Follow a terminal's state in the list, so the panel's buttons stay honest. */
    private fun updateTerminalInList(
        chatId: String,
        terminalId: String,
        transform: (ChatTerminal) -> ChatTerminal,
    ) {
        val current = _chatTerminals.value[chatId] ?: return
        if (current.none { it.id == terminalId }) return
        _chatTerminals.value = _chatTerminals.value +
            (chatId to current.map { if (it.id == terminalId) transform(it) else it })
    }

    /**
     * Keep the open panel's state in step with the list the PC just sent.
     *
     * Without this the input box stays enabled after a command exits, and the
     * phone would offer to type into something that is already gone.
     */
    private fun refreshTerminalViewFromList(chatId: String) {
        val view = _terminalView.value ?: return
        if (view.chatId != chatId) return
        val listed = _chatTerminals.value[chatId]?.find { it.id == view.terminalId } ?: return
        _terminalView.value = view.copy(
            state = listed.state,
            canWrite = listed.canWrite,
            truncated = listed.truncated,
        )
    }

    private fun upsertChat(info: ChatInfo) {        val current = _chats.value
        val idx = current.indexOfFirst { it.id == info.id }
        _chats.value = if (idx >= 0) {
            current.toMutableList().also { it[idx] = info }
        } else {
            (current + info).sortedByDescending { it.lastUsedAt }
        }
    }

    private fun updateChatStatus(chatId: String, status: String) {
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(status = status) else it }
        _activeChat.value = _activeChat.value?.let {
            if (it.id == chatId) it.copy(status = status) else it
        }
    }

    private fun parseProcesses(arr: JSONArray?): List<ProcessInfo> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    ProcessInfo(
                        pid = o.optInt("pid"),
                        name = o.optString("name"),
                        cpuSeconds = if (o.isNull("cpuSeconds")) null else o.optDouble("cpuSeconds"),
                        memBytes = o.optLong("memBytes"),
                        threads = o.optInt("threads"),
                    ),
                )
            }
        }
    }

    private fun parseServices(arr: JSONArray?): List<ServiceInfo> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    ServiceInfo(
                        name = o.optString("name"),
                        displayName = o.optString("displayName"),
                        status = o.optString("status"),
                        startType = if (o.isNull("startType")) null else o.optString("startType"),
                        canStop = o.optBoolean("canStop", false),
                    ),
                )
            }
        }
    }

    private fun parseListing(frame: JSONObject): DirectoryListing {
        val arr = frame.optJSONArray("items")
        val items = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        FileEntry(
                            name = o.optString("name"),
                            path = o.optString("path"),
                            isDir = o.optBoolean("isDir", false),
                            sizeBytes = o.optLong("sizeBytes"),
                            mtime = if (o.isNull("mtime")) null else o.optString("mtime"),
                            kind = o.optString(
                                "kind",
                                if (o.optBoolean("isDir", false)) "dir" else "other",
                            ),
                        ),
                    )
                }
            }
        }
        return DirectoryListing(
            path = frame.optString("path"),
            parent = frame.optString("parent"),
            items = items,
        )
    }

    private fun parseCodexConfig(o: JSONObject?): CodexConfig? {
        if (o == null) return null

        fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }

        val providers = o.optJSONArray("providers")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { p ->
                    CodexProvider(
                        id = p.optString("id"),
                        name = if (p.isNull("name")) null else p.optString("name"),
                        baseUrl = if (p.isNull("baseUrl")) null else p.optString("baseUrl"),
                        wireApi = if (p.isNull("wireApi")) null else p.optString("wireApi"),
                        hasToken = p.optBoolean("hasToken", false),
                    )
                }
            }
        } ?: emptyList()

        val models = o.optJSONArray("models")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { m ->
                    CodexModel(
                        slug = m.optString("slug"),
                        displayName = m.optString("displayName", m.optString("slug")),
                        contextWindow = if (m.isNull("contextWindow")) null else m.optLong("contextWindow"),
                        maxContextWindow = if (m.isNull("maxContextWindow")) null else m.optLong("maxContextWindow"),
                        defaultReasoning = if (m.isNull("defaultReasoning")) null else m.optString("defaultReasoning"),
                        reasoningLevels = strings(m.optJSONArray("reasoningLevels")),
                        vision = m.optBoolean("vision", false),
                    )
                }
            }
        } ?: emptyList()

        val desktop = o.optJSONObject("desktop")

        return CodexConfig(
            configPath = o.optString("configPath"),
            modelsPath = o.optString("modelsPath"),
            exists = o.optBoolean("exists", false),
            model = if (o.isNull("model")) null else o.optString("model"),
            modelProvider = if (o.isNull("modelProvider")) null else o.optString("modelProvider"),
            reasoningEffort = if (o.isNull("reasoningEffort")) null else o.optString("reasoningEffort"),
            providers = providers,
            models = models,
            enabledReasoningEfforts = strings(desktop?.optJSONArray("enabledReasoningEfforts")),
            backups = strings(o.optJSONArray("backups")),
            modelsError = if (o.isNull("modelsError")) null else o.optString("modelsError"),
        )
    }

    // ---- kernel parser ----

    private fun parseKernels(arr: JSONArray?): List<KernelInfo> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    KernelInfo(
                        id = o.optString("id"),
                        available = o.optBoolean("available", false),
                        path = o.optString("path"),
                        multiTurn = o.optBoolean("multiTurn", false),
                        progress = o.optBoolean("progress", false),
                        label = o.optString("label"),
                        tier = o.optString("tier", "native"),
                        detail = o.optString("detail"),
                        resume = o.optBoolean("resume", false),
                        selectableOnPc = if (o.has("selectable")) o.optBoolean("selectable") else null,
                    ),
                )
            }
        }
    }

    // ---- existing-session parsers ----

    private fun parseSessionList(arr: JSONArray?): List<SessionInfo> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    SessionInfo(
                        engine = o.optString("engine"),
                        id = o.optString("id"),
                        title = if (o.isNull("title")) null else o.optString("title"),
                        cwd = if (o.isNull("cwd")) null else o.optString("cwd"),
                        createdAt = if (o.isNull("createdAt")) null else o.optString("createdAt"),
                        updatedAt = if (o.isNull("updatedAt")) null else o.optString("updatedAt"),
                        sizeBytes = o.optLong("sizeBytes"),
                        path = o.optString("path"),
                        canResume = o.optBoolean("canResume", false),
                        resumeNote = o.optString("resumeNote").takeIf { it.isNotBlank() && it != "null" },
                    ),
                )
            }
        }
    }

    private fun parseWorkspaces(arr: JSONArray?): List<WorkspaceInfo> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val engines = o.optJSONArray("engines")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }
                } ?: emptyList()
                add(
                    WorkspaceInfo(
                        cwd = o.optString("cwd"),
                        count = o.optInt("count"),
                        engines = engines,
                        latestAt = if (o.isNull("latestAt")) null else o.optString("latestAt"),
                    ),
                )
            }
        }
    }

    private fun parseSessionDetail(o: JSONObject): SessionDetail {
        val meta = o.optJSONObject("meta")
        val arr = o.optJSONArray("events")
        val events = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val m = e.optJSONObject("meta")
                    add(
                        SessionEvent(
                            kind = e.optString("kind"),
                            role = if (e.isNull("role")) null else e.optString("role"),
                            text = e.optString("text"),
                            at = if (e.isNull("at")) null else e.optString("at"),
                            name = if (e.isNull("name")) null else e.optString("name"),
                            state = m?.let { if (it.isNull("state")) null else it.optString("state") },
                            exitCode = m?.let { if (it.isNull("exitCode")) null else it.optInt("exitCode") },
                            tokens = m?.let { if (it.isNull("total")) null else it.optInt("total") },
                        ),
                    )
                }
            }
        }
        return SessionDetail(
            engine = meta?.optString("engine") ?: "",
            id = meta?.optString("id") ?: "",
            cwd = meta?.let { if (it.isNull("cwd")) null else it.optString("cwd") },
            title = meta?.let { if (it.isNull("title")) null else it.optString("title") },
            events = events,
            totalEvents = o.optInt("totalEvents", events.size),
            truncated = o.optBoolean("truncated", false),
            canResume = meta?.optBoolean("canResume", false) ?: false,
            resumeNote = meta?.optString("resumeNote")?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun parseStatus(obj: JSONObject?): HostStatus? {
        if (obj == null) return null
        val cpu = obj.optJSONObject("cpu")
        val mem = obj.optJSONObject("memory")
        val diskArr = obj.optJSONArray("disks")
        val disks = buildList {
            if (diskArr != null) {
                for (i in 0 until diskArr.length()) {
                    val d = diskArr.optJSONObject(i) ?: continue
                    add(
                        DiskStatus(
                            root = d.optString("root"),
                            usedBytes = d.optLong("usedBytes"),
                            totalBytes = d.optLong("totalBytes"),
                            usedPercent = d.optDouble("usedPercent", 0.0),
                        ),
                    )
                }
            }
        }
        return HostStatus(
            hostname = obj.optString("hostname"),
            platform = obj.optString("platform"),
            arch = obj.optString("arch"),
            uptimeSeconds = obj.optLong("uptimeSeconds"),
            cpuModel = cpu?.optString("model").orEmpty(),
            cpuCores = cpu?.optInt("cores") ?: 0,
            cpuUsagePercent = if (cpu?.isNull("usagePercent") == false) cpu.optDouble("usagePercent") else null,
            memoryUsedBytes = mem?.optLong("usedBytes") ?: 0,
            memoryTotalBytes = mem?.optLong("totalBytes") ?: 0,
            memoryUsedPercent = mem?.optDouble("usedPercent", 0.0) ?: 0.0,
            disks = disks,
        )
    }
}
