package dev.termdesk.app.data

import android.content.ContentResolver
import android.content.Context
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

    /** Set when the agent reports the shell is not enabled on the PC. */
    private val _termUnavailable = MutableStateFlow<String?>(null)
    val termUnavailable: StateFlow<String?> = _termUnavailable.asStateFlow()

    // ---- Codex provider configuration ----

    private val _codexConfig = MutableStateFlow<CodexConfig?>(null)
    val codexConfig: StateFlow<CodexConfig?> = _codexConfig.asStateFlow()

    private val _codexTemplates = MutableStateFlow<List<CodexProviderTemplate>>(emptyList())
    val codexTemplates: StateFlow<List<CodexProviderTemplate>> = _codexTemplates.asStateFlow()

    // ---- P4: AI tasks ----

    private val _engines = MutableStateFlow<List<EngineInfo>>(emptyList())
    val engines: StateFlow<List<EngineInfo>> = _engines.asStateFlow()

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

    private val _chatSending = MutableStateFlow(false)
    val chatSending: StateFlow<Boolean> = _chatSending.asStateFlow()

    private val _tasks = MutableStateFlow<List<TaskSummary>>(emptyList())
    val tasks: StateFlow<List<TaskSummary>> = _tasks.asStateFlow()

    /** Detail of the task whose view is open, replaced as the agent reports more. */
    private val _activeTask = MutableStateFlow<TaskDetail?>(null)
    val activeTask: StateFlow<TaskDetail?> = _activeTask.asStateFlow()

    /** Events for the task currently streaming, oldest first. */
    private val _liveEvents = MutableStateFlow<List<TaskEvent>>(emptyList())
    val liveEvents: StateFlow<List<TaskEvent>> = _liveEvents.asStateFlow()

    private val credentials = appContext?.let { DeviceCredentials(it) }
    private var generation = 0
    private fun cacheFile(name: String): File? = appContext?.let {
        val dir = File(it.filesDir, "history-cache").apply { mkdirs() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        File(dir, "$hash.json")
    }
    private fun cache(name: String, frame: JSONObject) {
        runCatching {
            val file = cacheFile(name) ?: return
            val text = frame.toString()
            if (text.toByteArray().size > 5 * 1024 * 1024) return
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text); tmp.renameTo(file)
            val files = file.parentFile?.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() } ?: emptyList()
            var bytes = files.sumOf { it.length() }
            for (old in files) if (bytes > 20 * 1024 * 1024 && old != file) { bytes -= old.length(); old.delete() }
        }
    }
    private fun cached(name: String): JSONObject? = runCatching { cacheFile(name)?.takeIf { it.exists() }?.readText()?.let { JSONObject(it) } }.getOrNull()
    init {
        cached("index")?.let {
            _sessions.value = parseSessionList(it.optJSONArray("sessions"))
            _workspaces.value = parseWorkspaces(it.optJSONArray("workspaces"))
        }
    }
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var lastUrl: String? = null
    private var lastToken: String? = null

    @Volatile
    private var manuallyClosed = false

    fun connect(url: String, token: String) {
        generation += 1
        lastUrl = url
        lastToken = token
        manuallyClosed = false
        reconnectJob?.cancel()

        // Close any previous socket first. Without this a reconnect (or a
        // connect while one is already live) left the old socket open, so the
        // agent accumulated duplicate sessions from a single client.
        socket?.close(1000, "superseded")
        socket = null

        _link.value = LinkState.Connecting

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

    /** Open a terminal session, or reuse the existing one. */
    fun openTerminal() {
        if (_termSession.value != null) return
        sendFrame(JSONObject().put("type", "term.open"))
    }

    fun runCommand(command: String) {
        val sid = _termSession.value ?: return
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

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

    // ---- P4 operations ----

    fun loadEngines() {
        sendFrame(JSONObject().put("type", "ai.engines"))
    }

    fun loadTasks() {
        sendFrame(JSONObject().put("type", "ai.tasks"))
    }

    fun submitTask(engine: String, prompt: String, cwd: String?, resume: Boolean) {
        val text = prompt.trim()
        if (text.isEmpty()) return
        val frame = JSONObject()
            .put("type", "ai.submit")
            .put("engine", engine)
            .put("prompt", text)
            .put("resume", resume)
        if (!cwd.isNullOrBlank()) frame.put("cwd", cwd)
        sendFrame(frame)
    }

    fun openTask(taskId: String) {
        // Clear immediately so the detail view never shows the previous task's
        // events while the new one is loading.
        _liveEvents.value = emptyList()
        _activeTask.value = null
        sendFrame(JSONObject().put("type", "ai.task").put("taskId", taskId))
    }

    /**
     * Re-fetch the open task without clearing the current events first.
     * Using openTask() for a live refresh would blank the step stream on every
     * update and make it flicker.
     */
    private fun refreshActiveTask() {
        val id = _activeTask.value?.id ?: return
        sendFrame(JSONObject().put("type", "ai.task").put("taskId", id))
    }

    fun closeTask() {
        _activeTask.value = null
        _liveEvents.value = emptyList()
    }

    fun cancelTask(taskId: String) {
        sendFrame(JSONObject().put("type", "ai.cancel").put("taskId", taskId))
    }

    fun resetEngineSession(engine: String) {
        sendFrame(JSONObject().put("type", "ai.reset").put("engine", engine))
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
        _preview.value = null
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

    /** Reconnect with backoff after an unexpected drop. */
    private fun scheduleReconnect() {
        if (manuallyClosed) return
        val url = lastUrl ?: return
        val token = lastToken ?: return
        reconnectJob = scope.launch {
            delay(3000)
            if (!manuallyClosed) connect(url, token)
        }
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
                        credentials?.write(permanent)
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
                    _link.value = LinkState.Failed("鉴权失败：${frame.optString("reason", "token 无效")}")
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
                }
                // ---- P4 frames ----
                "ai.engines" -> _engines.value = parseEngines(frame.optJSONArray("engines"))
                "ai.tasks" -> _tasks.value = parseTaskSummaries(frame.optJSONArray("tasks"))
                "ai.started" -> {
                    // Refresh the list so a new task appears immediately.
                    loadTasks()
                }
                "ai.event" -> {
                    // The agent only signals that something changed; pull the
                    // current detail rather than reconstructing state from deltas.
                    refreshActiveTask()
                }
                "ai.finished" -> {
                    loadTasks()
                    // Keep the open detail view in sync with the final state.
                    refreshActiveTask()
                }
                "ai.task" -> {
                    val detail = parseTaskDetail(frame.optJSONObject("task"))
                    if (detail != null) {
                        _activeTask.value = detail
                        _liveEvents.value = detail.events
                    }
                }
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
                "chats" -> _chats.value = parseChatList(frame.optJSONArray("chats"))
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
            if (code == 4401 || code == 4409) manuallyClosed = true
            if (!manuallyClosed) _link.value = LinkState.Failed("连接已断开 ($code $reason)")
            scheduleReconnect()
        }
    }

    // ---- live chat parsing and reduction ----

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
            title = o.optString("title"),
            cwd = o.optString("cwd"),
            provider = o.optString("provider"),
            model = o.optString("model"),
            effort = o.optString("effort"),
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

    private fun upsertChat(info: ChatInfo) {
        val current = _chats.value
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

    // ---- P4 parsers ----

    private fun parseEngines(arr: JSONArray?): List<EngineInfo> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    EngineInfo(
                        id = o.optString("id"),
                        available = o.optBoolean("available", false),
                        path = o.optString("path"),
                        multiTurn = o.optBoolean("multiTurn", false),
                        progress = o.optBoolean("progress", false),
                        label = o.optString("label"),
                        tier = o.optString("tier", "native"),
                        detail = o.optString("detail"),
                        resume = o.optBoolean("resume", false),
                    ),
                )
            }
        }
    }

    private fun parseTaskSummaries(arr: JSONArray?): List<TaskSummary> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    TaskSummary(
                        id = o.optString("id"),
                        engine = o.optString("engine"),
                        prompt = o.optString("prompt"),
                        status = TaskStatus.fromWire(o.optString("status")),
                        startedAt = o.optLong("startedAt"),
                        finishedAt = if (o.isNull("finishedAt")) null else o.optLong("finishedAt"),
                        exitCode = if (o.isNull("exitCode")) null else o.optInt("exitCode"),
                        eventCount = o.optInt("eventCount"),
                    ),
                )
            }
        }
    }

    private fun parseTaskDetail(o: JSONObject?): TaskDetail? {
        if (o == null) return null
        val eventsArr = o.optJSONArray("events")
        val events = buildList {
            if (eventsArr != null) {
                for (i in 0 until eventsArr.length()) {
                    val e = eventsArr.optJSONObject(i) ?: continue
                    add(
                        TaskEvent(
                            kind = e.optString("kind"),
                            text = e.optString("text"),
                            state = if (e.isNull("state")) null else e.optString("state"),
                            exitCode = if (e.isNull("exitCode")) null else e.optInt("exitCode"),
                            output = e.optString("output"),
                            tokens = if (e.isNull("tokens")) null else e.optInt("tokens"),
                        ),
                    )
                }
            }
        }
        return TaskDetail(
            id = o.optString("id"),
            engine = o.optString("engine"),
            prompt = o.optString("prompt"),
            cwd = o.optString("cwd"),
            status = TaskStatus.fromWire(o.optString("status")),
            startedAt = o.optLong("startedAt"),
            finishedAt = if (o.isNull("finishedAt")) null else o.optLong("finishedAt"),
            exitCode = if (o.isNull("exitCode")) null else o.optInt("exitCode"),
            threadId = if (o.isNull("threadId")) null else o.optString("threadId"),
            finalText = o.optString("finalText"),
            events = events,
        )
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
