package dev.termdesk.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.termdesk.app.data.ComputerStore
import dev.termdesk.app.data.AgentClient
import dev.termdesk.app.data.ActionResult
import dev.termdesk.app.data.ChatApproval
import dev.termdesk.app.data.ChatEvent
import dev.termdesk.app.data.ChatInfo
import dev.termdesk.app.data.ChatModels
import dev.termdesk.app.data.ChatTerminal
import dev.termdesk.app.data.TerminalView
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.CodexProviderTemplate
import dev.termdesk.app.data.DirectoryListing
import dev.termdesk.app.data.KernelInfo
import dev.termdesk.app.data.LocalAgentState
import dev.termdesk.app.data.LocalKernelState
import dev.termdesk.app.data.PairedComputer
import dev.termdesk.app.data.FileEntry
import dev.termdesk.app.data.FilePreview
import dev.termdesk.app.data.SearchResults
import dev.termdesk.app.data.Storage
import dev.termdesk.app.data.StorageEntry
import dev.termdesk.app.data.StorageUse
import dev.termdesk.app.data.HostStatus
import dev.termdesk.app.data.LinkState
import dev.termdesk.app.data.ProcessInfo
import dev.termdesk.app.data.ServiceInfo
import dev.termdesk.app.data.SessionDetail
import dev.termdesk.app.data.SessionInfo
import dev.termdesk.app.data.TermLine
import dev.termdesk.app.data.UploadedFile
import dev.termdesk.app.data.TextFile
import dev.termdesk.app.data.TransferState
import dev.termdesk.app.data.WorkspaceInfo
import dev.termdesk.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the long-lived connection to the PC and the last known host status.
 * The stored address/token let the app reconnect on its own after a restart.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("termdesk", Context.MODE_PRIVATE)

    /** The computers this phone is paired with, and their credentials. */
    private val computers = ComputerStore(app.applicationContext)
    private val client = AgentClient(app.applicationContext)

    val link: StateFlow<LinkState> = client.link
    val status: StateFlow<HostStatus?> = client.status
    val processes: StateFlow<List<ProcessInfo>> = client.processes
    val services: StateFlow<List<ServiceInfo>> = client.services
    val lastAction: StateFlow<ActionResult?> = client.lastAction
    val loading: StateFlow<Boolean> = client.loading

    // ---- P2 ----
    val listing: StateFlow<DirectoryListing?> = client.listing
    val openFile: StateFlow<TextFile?> = client.openFile
    val transfer: StateFlow<TransferState?> = client.transfer
    val preview: StateFlow<FilePreview?> = client.preview

    /**
     * Directories the PC allows browsing, reported by the agent after auth.
     *
     * Collected by the file view so that [startPath] resolves once the agent
     * answers, without the app assuming any path of its own.
     */
    val fsRoots: StateFlow<List<String>> = client.fsRoots

    // ---- P3 ----
    val termLines: StateFlow<List<TermLine>> = client.termLines
    val termSession: StateFlow<String?> = client.termSession
    val termBusy: StateFlow<Boolean> = client.termBusy
    val kernelTarget: StateFlow<String> = client.kernelTarget
    val termUnavailable: StateFlow<String?> = client.termUnavailable

    fun openTerminal() = client.openTerminal()
    fun runCommand(command: String) = client.runCommand(command)
    /** One kernel for the whole app: chosen in Settings, obeyed everywhere. */
    fun setKernelTarget(target: String) {
        val clean = if (target == "local") "local" else "remote"
        client.setKernelTarget(clean)
        if (clean == "local") {
            // "Local" is a process that may not be running yet: bring it up, then
            // talk to it exactly like the PC.
            viewModelScope.launch { client.connectLocal() }
        } else {
            client.connect(savedUrl, savedToken)
        }
    }

    /** The sandbox agent: its state, and a way to stop it. */
    val localAgentState: StateFlow<LocalAgentState>? get() = client.localAgent?.state
    fun stopLocalAgent() = client.stopLocal()
    fun interruptCommand() = client.interruptCommand()
    fun closeTerminal() = client.closeTerminal()
    fun clearTerminal() = client.clearTerminal()

    // ---- Codex provider configuration ----
    val codexConfig: StateFlow<CodexConfig?> = client.codexConfig
    val codexTemplates: StateFlow<List<CodexProviderTemplate>> = client.codexTemplates

    fun loadCodexConfig() = client.loadCodexConfig()
    fun applyCodexProvider(
        providerId: String,
        model: String,
        apiKey: String?,
        reasoningEffort: String?,
        contextWindow: Long?,
    ) = client.applyCodexProvider(providerId, model, apiKey, reasoningEffort, contextWindow)

    fun restoreCodexBackup(name: String?) = client.restoreCodexBackup(name)

    // ---- local kernel (the sandbox that runs on this phone) ----

    /** Install state of the phone-side kernel: download, unpack, run, or why not. */
    val localKernel: StateFlow<LocalKernelState> = client.localKernel

    fun refreshLocalKernel() = client.refreshLocalKernel()
    fun loadLocalKernelManifest() = client.loadLocalKernelManifest()
    fun installLocalKernel() = client.installLocalKernel()
    fun removeLocalKernel() = client.removeLocalKernel()

    // ---- kernels (the PC kernel table) ----
    //
    // There is no separate "task" world any more: every run is an agent chat,
    // and the one-shot `ai.*` surface is gone from the protocol. What remains
    // here is the kernel list itself, read from the PC registry, because the
    // new-chat sheet and Settings both have to show what this machine can
    // actually run on.
    val engines: StateFlow<List<KernelInfo>> = client.engines

    fun loadEngines() = client.loadEngines()

    /**
     * Default kernel for new conversations, chosen in Settings.
     *
     * The kernel is the *target* the phone drives: a PC kernel (codex/dsh) runs
     * on the computer and therefore owns its files, terminal and processes. The
     * choice is a user preference, not a per-chat guess, so it lives in prefs and
     * the new-chat sheet only pre-selects it (the user can still override).
     */
    private val _defaultEngine = MutableStateFlow(prefs.getString(KEY_ENGINE, null))
    val defaultEngine: StateFlow<String?> = _defaultEngine.asStateFlow()

    fun setDefaultEngine(engine: String?) {
        val clean = engine?.takeIf { it.isNotBlank() }
        _defaultEngine.value = clean
        prefs.edit().putString(KEY_ENGINE, clean).apply()
    }

    /**
     * Where new conversations work, chosen on the home page.
     *
     * It is a standing choice like the kernel, not something asked per
     * conversation: the home page shows it, tapping it changes it, and the file
     * browser starts from the same place. Empty means "whatever the agent
     * reports first" (see [startPath]).
     */
    private val _workingDir = MutableStateFlow(
        prefs.getString(KEY_START_PATH, null)?.takeIf { it.isNotBlank() } ?: "",
    )
    val workingDirectory: StateFlow<String> = _workingDir.asStateFlow()

    fun setWorkingDirectory(path: String) {
        val clean = path.trim()
        _workingDir.value = clean
        prefs.edit().putString(KEY_START_PATH, clean).apply()
    }

    /** Default working directory for a new conversation, mirroring the file browser. */
    val defaultCwd: String get() = startPath

    // ---- live chats ----

    val chats: StateFlow<List<ChatInfo>> = client.chats
    val activeChat: StateFlow<ChatInfo?> = client.activeChat
    val chatEvents: StateFlow<List<ChatEvent>> = client.chatEvents
    val chatSending: StateFlow<Boolean> = client.chatSending

    /**
     * Engine questions waiting for an answer (see [ChatApproval]).
     *
     * The kernel is blocked while one is pending, so this is connection state,
     * not screen state: the dialog follows the conversation it belongs to.
     */
    val approvals: StateFlow<List<ChatApproval>> = client.approvals

    fun respondApproval(requestId: String, optionId: String) = client.respondApproval(requestId, optionId)

    fun loadChats() = client.loadChats()

    /**
     * Create an agent conversation. [engine] is the kernel (`codex` | `dsh`);
     * [provider]/[model] are optional and follow the kernel when omitted.
     * The kernel comes from the home page's standing choice, so nothing is asked
     * here; the model and the directory can still be changed once it is open.
     */
    fun createChat(
        cwd: String? = null,
        engine: String? = null,
        provider: String? = null,
        model: String? = null,
        title: String? = null,
        effort: String? = null,
    ) = client.createChat(cwd, engine, provider, model, title, effort)
    fun openChat(chatId: String) = client.openChat(chatId)
    fun sendChatMessage(chatId: String, text: String, model: String? = null, effort: String? = null) =
        client.sendChatMessage(chatId, text, model, effort)

    /** The last finished upload, so `+` can attach it to the next message. */
    val lastUpload: StateFlow<UploadedFile?> = client.lastUpload

    fun clearLastUpload() = client.clearLastUpload()

    /** Model lists per chat, answered by the kernel itself. */
    val chatModels: StateFlow<Map<String, ChatModels>> = client.chatModels

    /** Ask what the open conversation can switch to (no model runs for this). */
    fun requestChatModels(chatId: String) = client.requestChatModels(chatId)

    // ---- the open conversation's command lines ----

    /** What this conversation ran. Asking is also what starts live output. */
    val chatTerminals: StateFlow<Map<String, List<ChatTerminal>>> = client.chatTerminals
    val terminalView: StateFlow<TerminalView?> = client.terminalView

    fun loadChatTerminals(chatId: String) = client.loadChatTerminals(chatId)
    fun openChatTerminal(chatId: String, terminalId: String, command: String, origin: String, canWrite: Boolean) =
        client.openChatTerminal(chatId, terminalId, command, origin, canWrite)
    fun sendTerminalInput(chatId: String, terminalId: String, data: String) =
        client.sendTerminalInput(chatId, terminalId, data)
    fun stopChatTerminal(chatId: String, terminalId: String) = client.stopChatTerminal(chatId, terminalId)
    fun closeTerminalView() = client.closeTerminalView()

    /** Switch the session's permission / agent mode (the kernel applies it now). */
    fun setChatMode(chatId: String, modeId: String) = client.setChatMode(chatId, modeId)

    /** Change what the open conversation runs on from now on (model / effort). */
    fun configureChat(chatId: String, model: String?, effort: String?) =
        client.setChatConfig(chatId, model, effort)
    fun cancelChat(chatId: String) = client.cancelChat(chatId)
    fun closeChat(chatId: String) = client.closeChat(chatId)
    fun leaveChat() = client.leaveChat()

    // ---- recorded sessions on disk (the drawer's directory index) ----

    val sessions: StateFlow<List<SessionInfo>> = client.sessions
    val workspaces: StateFlow<List<WorkspaceInfo>> = client.workspaces
    val sessionDetail: StateFlow<SessionDetail?> = client.sessionDetail

    fun loadSessions(engine: String? = null) = client.loadSessions(engine)
    fun openSession(session: SessionInfo) = client.openSession(session)
    fun closeSession() = client.closeSession()
    fun resumeSession(session: SessionDetail) = client.resumeSession(session)

    // ---- theme ----

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.Dark.name)!!) }
            .getOrDefault(ThemeMode.Dark),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    /**
     * Where the file browser starts, and the agent address it defaults to.
     *
     * Both are deployment specifics, so neither is baked in: the pairing screen
     * takes the address, and the file browser is driven by the roots the agent
     * reports (`fs.roots`). A literal path here would be wrong on any other
     * machine and would publish one user's home directory.
     */
    val startPath: String
        get() = _workingDir.value.takeIf { it.isNotBlank() }
            ?: client.fsRoots.value.firstOrNull()
            ?: ""

    fun refreshProcesses(query: String = "") = client.refreshProcesses(query)
    fun refreshServices(query: String = "") = client.refreshServices(query)
    fun killProcess(pid: Int) = client.killProcess(pid)
    fun controlService(name: String, action: String) = client.controlService(name, action)
    fun clearLastAction() = client.clearLastAction()

    fun listDirectory(path: String) = client.listDirectory(path)
    fun readFile(path: String) = client.readFile(path)
    fun closeOpenFile() = client.closeOpenFile()

    // ---- file viewer ----
    fun openPreview(entry: FileEntry) = client.openPreview(entry)
    fun closePreview() = client.closePreview()
    fun savePreviewToDownloads() = client.savePreviewToDownloads()

    // ---- file search ----
    val search: StateFlow<SearchResults?> = client.search
    val searching: StateFlow<Boolean> = client.searching

    fun searchFiles(dirPath: String, query: String) = client.searchFiles(dirPath, query)
    fun clearSearch() = client.clearSearch()

    // ---- on-phone storage ----

    /**
     * What the app is using on this phone, measured rather than assumed.
     * Recomputed on demand because the answer changes as files are viewed.
     */
    private val _storage = MutableStateFlow<StorageUse?>(null)
    val storage: StateFlow<StorageUse?> = _storage.asStateFlow()

    /**
     * Recount on a background thread.
     *
     * The local sandbox is 72,405 files and 5,630 symlinks - about 1.9 GB.
     * Walking it inside composition froze Settings and then crashed the app: the
     * ANR trace ended in Storage.kt's recursion. Measurement is I/O, so it runs
     * on IO, the screen says "正在统计…" meanwhile, and a fresh answer is reused
     * for a short while so reopening Settings does not re-walk the tree.
     */
    private var storageJob: Job? = null
    private var storageCache: StorageUse? = null
    private var storageCacheAt = 0L

    fun loadStorage() {
        // Reopening Settings should not re-walk 72,405 files: the answer is
        // good for a short while, and "正在统计…" on every visit is its own bug.
        val cache = storageCache
        if (cache != null && System.currentTimeMillis() - storageCacheAt < STORAGE_TTL_MS) {
            _storage.value = cache
            return
        }
        storageJob?.cancel()
        _storage.value = null
        storageJob = viewModelScope.launch {
            val use = withContext(Dispatchers.IO) { Storage.inspect(getApplication()) }
            storageCache = use
            storageCacheAt = System.currentTimeMillis()
            _storage.value = use
        }
    }

    fun clearStorage(entry: StorageEntry) {
        storageJob?.cancel()
        storageCache = null
        _storage.value = null
        storageJob = viewModelScope.launch {
            // Deleting a gigabyte is as blocking as counting it.
            val freed = withContext(Dispatchers.IO) { Storage.clear(entry) }
            client.reportLocalMessage("已清理 ${entry.label}，释放 ${Storage.format(freed)}")
            val use = withContext(Dispatchers.IO) { Storage.inspect(getApplication()) }
            _storage.value = use
        }
    }
    fun writeFile(path: String, text: String) = client.writeFile(path, text)
    fun createEntry(dir: String, name: String, isDir: Boolean) = client.createEntry(dir, name, isDir)
    fun deleteEntry(path: String) = client.deleteEntry(path)
    fun renameEntry(path: String, newName: String) = client.renameEntry(path, newName)
    fun downloadFile(path: String, name: String) = client.downloadFile(path, name)
    fun uploadFile(uri: android.net.Uri, remoteDir: String) = client.uploadFile(uri, remoteDir)

    val savedUrl: String get() = computers.active()?.url ?: DEFAULT_URL
    val savedToken: String get() = computers.activeCredential()

    // ---- the computers this phone is paired with ----

    private val _computerList = MutableStateFlow(computers.list())
    val computerList: StateFlow<List<PairedComputer>> = _computerList.asStateFlow()

    private val _activeComputerId = MutableStateFlow(computers.activeId())
    val activeComputerId: StateFlow<String?> = _activeComputerId.asStateFlow()

    private fun refreshComputers() {
        _computerList.value = computers.list()
        _activeComputerId.value = computers.activeId()
    }

    /** Switch to another computer that is already paired. */
    fun selectComputer(id: String) {
        val target = computers.list().firstOrNull { it.id == id } ?: return
        computers.select(id)
        refreshComputers()
        val token = computers.credentialFor(id)
        if (token.isBlank()) {
            client.disconnect()
            client.reportLocalMessage("${target.name} 的凭据不在了，请重新配对")
            return
        }
        client.connect(target.url, token, id)
    }

    /**
     * Unbind one computer.
     *
     * Order matters: tell the far side while the connection is still up, then
     * forget locally. Over a relay this is what removes the phone from that
     * computer's list; a direct agent has no list and nothing to tell.
     */
    fun forgetComputer(id: String) {
        val target = computers.list().firstOrNull { it.id == id } ?: return
        val isActive = computers.activeId() == id
        if (isActive) client.unpairSelf(target.relay)
        computers.forget(id)
        refreshComputers()
        val next = computers.active() ?: run { client.disconnect(); null }
        if (next != null) selectComputer(next.id)
        client.reportLocalMessage("已解除与 ${target.name} 的绑定")
    }

    init {
        client.registerNetworkCallback()
        computers.migrateLegacy()
        refreshComputers()
        // A credential the far side has just issued belongs to the computer we are
        // connecting to, and only the list knows which one that is.
        viewModelScope.launch {
            client.issuedCredential.collect { token ->
                if (token.isNullOrBlank()) return@collect
                val id = activeComputerId.value ?: return@collect
                computers.rememberCredential(id, token)
                computers.markRelay(id)
                refreshComputers()
                client.clearIssuedCredential()
            }
        }
        // The name a computer calls itself is the only honest label for the list.
        viewModelScope.launch {
            client.link.collect { state ->
                if (state !is LinkState.Connected) return@collect
                val id = activeComputerId.value ?: return@collect
                computers.rename(id, state.hostname)
                refreshComputers()
            }
        }
        // Debug-only deployment handoff from a trusted ADB session. Never an exported intent.
        val pairingFile = java.io.File(app.filesDir, "pairing.json")
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && pairingFile.exists()) {
            runCatching {
                val handoff = org.json.JSONObject(pairingFile.readText())
                val url = handoff.getString("url")
                require(url.startsWith("wss://"))
                val computer = computers.upsert(url = url, name = null, relay = true)
                computers.rememberCredential(computer.id, handoff.getString("token"))
                refreshComputers()
            }
            pairingFile.delete()
        }
        if (client.kernelTarget.value == "local") {
            // The choice survived the restart, so the sandbox agent has to come up
            // instead of the PC socket. There is no token to read yet - the agent
            // writes it when it starts.
            viewModelScope.launch { client.connectLocal() }
        } else {
            val active = computers.active()
            val token = computers.activeCredential()
            if (active != null && token.isNotBlank()) {
                client.connect(active.url, token, active.id)
            }
        }
    }

    fun connect(url: String, token: String, relay: Boolean = url.startsWith("wss://")) {
        // A blank code means "use the credential this computer already has". The
        // screen used to pre-fill its field with the saved credential, so pressing
        // 连接 a second time sent whatever was in the box — including a value that
        // was already replaced by a fresh pairing, which overwrote the working
        // credential with a dead one.
        val existing = computers.list().firstOrNull { it.url == url }
        val effective = token.ifBlank { existing?.let { computers.credentialFor(it.id) }.orEmpty() }
        if (effective.isBlank()) {
            client.reportLocalMessage("这台电脑还没有凭据：请在电脑上打开 /pair，用那个码配对")
            return
        }
        val computer = computers.upsert(url = url, name = null, relay = relay, id = existing?.id)
        computers.rememberCredential(computer.id, effective)
        refreshComputers()
        client.connect(computer.url, effective, computer.id)
    }

    fun disconnect() {
        client.disconnect()
    }

    /** Retry now: used when a network appears and when the app comes forward. */
    fun retryNow() = client.retryNow()

    fun forgetDevice() {
        val active = computers.active()
        if (active != null) { forgetComputer(active.id); return }
        client.disconnect()
    }

    override fun onCleared() {
        client.disconnect()
        super.onCleared()
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_START_PATH = "startPath"
        const val KEY_THEME = "themeMode"
        const val KEY_ENGINE = "defaultEngine"

        /** How long a storage measurement stays believable. */
        const val STORAGE_TTL_MS = 30_000L

        /**
         * Loopback placeholder only: the real agent address is a deployment
         * specific, entered on the pairing screen and stored in preferences.
         */
        const val DEFAULT_URL = "ws://127.0.0.1:7420"
    }
}
