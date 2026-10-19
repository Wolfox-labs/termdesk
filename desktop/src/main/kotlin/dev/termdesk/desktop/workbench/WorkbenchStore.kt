package dev.termdesk.desktop.workbench

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.termdesk.desktop.agent.AgentSocket
import dev.termdesk.desktop.agent.bool
import dev.termdesk.desktop.agent.int
import dev.termdesk.desktop.agent.long
import dev.termdesk.desktop.agent.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

data class ConsoleDevice(
    val id: String,
    val name: String,
    val url: String,
    val token: String,
    val local: Boolean,
)

data class ChatRow(
    val id: String,
    val title: String,
    val engine: String,
    val status: String,
    val owner: String,
    val canWrite: Boolean,
    val updatedAt: Long,
    val cwd: String = "",
)

data class SessionRow(
    val engine: String,
    val id: String,
    val title: String,
    val updatedAt: String,
    val path: String,
    val canResume: Boolean,
    val note: String,
    val cwd: String = "",
)

data class KernelRow(
    val id: String,
    val label: String,
    val tier: String,
    val transport: String,
    val available: Boolean,
    val enabled: Boolean,
    val path: String,
    val detail: String,
    val custom: Boolean,
    val override: Boolean = false,
    val autoPath: String = "",
    val selectable: Boolean = false,
    val command: String = "",
)

/** One phone this computer knows, local or relay. */
data class DeviceRow(
    val id: String,
    val name: String,
    val source: String,
    val lastSeenAt: String,
    val createdAt: String,
    val revoked: Boolean,
    val legacy: Boolean,
)

data class Line(
    val seq: Int,
    val kind: String,
    val role: String,
    val text: String,
    val name: String,
    val streaming: Boolean,
    val files: List<String>,
)

data class ApprovalRow(
    val requestId: String,
    val chatId: String,
    val chatTitle: String,
    val engine: String,
    val title: String,
    val detail: String,
    val options: List<Pair<String, String>>,
)

data class NoticeRow(val id: String, val kind: String, val chatId: String?, val title: String, val text: String, val at: Long)

data class FrameRow(val at: Long, val type: String, val detail: String)

data class ChatTab(val id: String, val title: String)

/**
 * The remote console's state: machines, their live chats, approvals, notices and
 * the frame stream that explains what the connection is doing.
 *
 * It is deliberately a console, not an editor: the transcript is a read-only
 * view with a composer, and everything that is not a human/assistant/tool result
 * is folded instead of dumped.
 */
class WorkbenchStore(
    private val scope: CoroutineScope,
    private val port: Int,
) {
    val devices = mutableStateListOf<ConsoleDevice>()

    var activeDeviceId by mutableStateOf("local")
        private set
    var connected by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set
    var reason by mutableStateOf<String?>(null)
        private set

    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)

    val chats = mutableStateListOf<ChatRow>()
    val sessions = mutableStateListOf<SessionRow>()
    val kernels = mutableStateListOf<KernelRow>()
    /** The phones paired with the selected computer. */
    val phones = mutableStateListOf<DeviceRow>()
    val lines = mutableStateListOf<Line>()
    val approvals = mutableStateListOf<ApprovalRow>()
    val notices = mutableStateListOf<NoticeRow>()
    val frameLog = mutableStateListOf<FrameRow>()
    val tabs = mutableStateListOf<ChatTab>()

    var activeId by mutableStateOf<String?>(null)
        private set
    /** "chat" for a live conversation, "record" for a recorded session. */
    var detailSource by mutableStateOf("")
        private set
    var activeTitle by mutableStateOf("")
        private set
    var activeEngine by mutableStateOf("")
        private set
    var activeCwd by mutableStateOf("")
        private set
    var activeProvider by mutableStateOf("")
        private set
    var activeModel by mutableStateOf("")
        private set
    var activeSessionId by mutableStateOf("")
        private set
    var activeThreadId by mutableStateOf("")
        private set
    var ownership by mutableStateOf("")
        private set
    var canWrite by mutableStateOf(true)
        private set
    var sending by mutableStateOf(false)
        private set

    var showContext by mutableStateOf(false)
    var selectedEngine by mutableStateOf("")
    var cwd by mutableStateOf(System.getProperty("user.home"))

    /** When the kernel table was last re-read on the PC. */
    var scannedAt by mutableStateOf(0L)
        private set
    /** The agent's own sentence when a record has no replayable body. */
    var sessionNote by mutableStateOf("")
        private set
    var sessionTotal by mutableStateOf(0)
        private set
    var sessionTruncated by mutableStateOf(false)
        private set
    /** A transcript request is in flight; the detail pane says so instead of "空". */
    var detailLoading by mutableStateOf(false)
        private set

    private var socket: AgentSocket? = null
    private val deviceId = desktopDeviceId()
    private val deviceName = "TermDesk 控制台 · ${System.getProperty("user.name") ?: "user"}"
    private val timeFormat = SimpleDateFormat("HH:mm:ss")

    init {
        reloadDevices()
    }

    val contextCount: Int get() = lines.count { it.kind == "context" }

    fun reloadDevices() {
        devices.clear()
        devices.add(ConsoleDevice("local", "本机", "ws://127.0.0.1:$port", "", local = true))
        devices.addAll(PeerStore.load())
        if (devices.none { it.id == activeDeviceId }) activeDeviceId = "local"
    }

    fun addPeer(name: String, url: String, token: String): String? {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("ws://") && !cleanUrl.startsWith("wss://")) return "地址必须以 ws:// 或 wss:// 开头"
        if (token.isBlank()) return "需要令牌：在电脑上打开 /pair 页面复制"
        val peer = ConsoleDevice(
            id = "peer-" + UUID.randomUUID().toString().take(6),
            name = name.trim().ifBlank { cleanUrl.substringAfter("://").substringBefore("/") },
            url = cleanUrl,
            token = token.trim(),
            local = false,
        )
        PeerStore.add(peer)
        reloadDevices()
        return null
    }

    fun removePeer(id: String) {
        PeerStore.remove(id)
        if (activeDeviceId == id) {
            activeDeviceId = "local"
            disconnect()
        }
        reloadDevices()
    }

    fun connect(targetId: String = activeDeviceId) {
        val device = devices.firstOrNull { it.id == targetId } ?: return
        activeDeviceId = device.id
        val token = if (device.local) readAgentToken() else device.token
        if (token.isBlank()) {
            reason = if (device.local) "本机还没有 ~/.termdesk/token：先启动代理"
            else "这台电脑没有令牌"
            return
        }
        connecting = true
        reason = null
        socket?.close()
        resetSession()
        socket = AgentSocket(
            port = if (device.local) port else 0,
            token = token,
            deviceId = this.deviceId,
            deviceName = deviceName,
            onFrame = { frame -> scope.launch(Dispatchers.Main) { onFrame(frame) } },
            onState = { ok, why ->
                scope.launch(Dispatchers.Main) {
                    connected = ok
                    connecting = false
                    if (!ok) reason = why
                    if (ok) {
                        reason = null
                        error = null
                        send("chat.list")
                        send("sessions.list")
                        send("kernels.list")
                        send("devices.list")
                    }
                }
            },
            urlOverride = device.url,
        ).also { it.connect() }
    }

    fun disconnect() {
        socket?.close()
        socket = null
        connected = false
        connecting = false
        sending = false
    }

    fun send(type: String, payload: JsonObject = JsonObject(emptyMap())) {
        socket?.send(type, payload)
    }

    fun refresh() {
        if (connected) {
            send("chat.list")
            send("sessions.list")
            send("kernels.list")
            send("devices.list")
        } else {
            connect()
        }
    }

    // ---- management ---------------------------------------------------------

    fun loadPhones() = send("devices.list")

    fun renamePhone(id: String, name: String) {
        send("devices.rename", buildJsonObject {
            put("id", id)
            put("name", name)
        })
    }

    fun revokePhone(id: String) = send("devices.revoke", buildJsonObject { put("id", id) })

    fun removePhone(id: String) = send("devices.remove", buildJsonObject { put("id", id) })

    fun manageKernel(action: String, id: String, entry: JsonObject? = null) {
        send("kernels.manage", buildJsonObject {
            put("action", action)
            put("id", id)
            if (entry != null) put("entry", entry)
        })
    }

    fun registerKernel(id: String, label: String, bin: String, args: List<String>, transport: String) {
        manageKernel(
            "register",
            id,
            buildJsonObject {
                put("id", id)
                put("label", label)
                put("bin", bin)
                put("transport", transport)
                put("args", kotlinx.serialization.json.JsonArray(args.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            },
        )
    }

    fun removeKernel(id: String) = manageKernel("remove", id)

    /** Re-read the machine. There is no cached table on the agent, so this is a real scan. */
    fun rescanKernels() = send("kernels.list")

    /** Spawns one ACP handshake for an ACP kernel; a plain re-stat for the others. */
    fun probeKernel(id: String) = manageKernel("probe", id)

    fun setKernelPath(id: String, path: String) {
        send("kernels.manage", buildJsonObject {
            put("action", "setPath")
            put("id", id)
            put("path", path)
        })
    }

    fun clearKernelPath(id: String) = manageKernel("clearPath", id)

    /** Open Explorer/Finder on the PC at this path. Local console only. */
    fun revealPath(path: String) {
        if (path.isBlank()) return
        send("path.reveal", buildJsonObject { put("path", path) })
    }

    fun setKernelDefault(id: String) {
        selectedEngine = id
        info = "新会话默认使用 ${kernels.firstOrNull { it.id == id }?.label ?: id}"
    }

    fun deleteSession(row: SessionRow) {
        send("sessions.delete", buildJsonObject {
            put("engine", row.engine)
            put("sessionId", row.id)
            put("path", row.path)
        })
    }

    fun closeChat(id: String) {
        send("chat.close", buildJsonObject { put("chatId", id) })
    }

    fun closeDetail() = clearActive()

    fun takeoverChat(id: String) {
        send("chat.claim", buildJsonObject {
            put("chatId", id)
            put("force", true)
        })
    }

    /** Open a recorded session read-only; it is a record, not a live chat. */
    fun openRecord(row: SessionRow) {
        lines.clear()
        sessionNote = ""
        sessionTotal = 0
        sessionTruncated = false
        detailLoading = true
        activeId = row.id
        activeTitle = row.title
        activeEngine = row.engine
        activeCwd = row.cwd
        activeProvider = ""
        activeModel = ""
        activeSessionId = row.id
        activeThreadId = ""
        ownership = "历史记录"
        canWrite = false
        send("sessions.read", buildJsonObject {
            put("engine", row.engine)
            put("sessionId", row.id)
            put("path", row.path)
            put("cwd", row.cwd)
        })
    }

    fun newChat() {
        error = null
        val engine = selectedEngine.ifBlank { kernels.firstOrNull { it.available }?.id.orEmpty() }
        if (engine.isBlank()) {
            error = "没有可用的内核：先到代理控制台确认内核清单"
            return
        }
        send("chat.create", buildJsonObject {
            put("engine", engine)
            put("cwd", cwd)
        })
    }

    fun open(chatId: String) {
        if (tabs.none { it.id == chatId }) {
            val row = chats.firstOrNull { it.id == chatId }
            tabs.add(ChatTab(chatId, row?.title ?: "会话"))
        }
        activeId = chatId
        lines.clear()
        detailLoading = true
        send("chat.read", buildJsonObject { put("chatId", chatId) })
    }

    fun closeTab(chatId: String) {
        tabs.removeAll { it.id == chatId }
        if (activeId == chatId) {
            val next = tabs.lastOrNull()
            if (next != null) open(next.id) else clearActive()
        }
    }

    fun resume(row: SessionRow) {
        if (!row.canResume) {
            error = row.note.ifBlank { "这台电脑说这条记录不能继续" }
            return
        }
        send("chat.resume", buildJsonObject {
            put("engine", row.engine)
            put("sessionId", row.id)
            put("path", row.path)
        })
    }

    fun sendMessage(text: String) {
        val chatId = activeId ?: run {
            error = "先新建或打开一个会话"
            return
        }
        if (!canWrite) {
            error = "只读：${ownership.ifBlank { "另一台设备" }} 正在使用这条会话，先接管"
            return
        }
        sending = true
        send("chat.send", buildJsonObject {
            put("chatId", chatId)
            put("text", text)
            put("requestId", "d-" + UUID.randomUUID())
        })
    }

    fun cancel() {
        activeId?.let { send("chat.cancel", buildJsonObject { put("chatId", it) }) }
    }

    fun takeover() {
        activeId?.let {
            send("chat.claim", buildJsonObject {
                put("chatId", it)
                put("force", true)
            })
        }
    }

    fun approve(approval: ApprovalRow, optionId: String) {
        send("chat.approve", buildJsonObject {
            put("requestId", approval.requestId)
            put("optionId", optionId)
        })
        approvals.removeAll { it.requestId == approval.requestId }
    }

    fun dismissNotices() {
        notices.clear()
    }

    private fun clearActive() {
        activeId = null
        detailSource = ""
        activeTitle = ""
        activeEngine = ""
        activeCwd = ""
        activeProvider = ""
        activeModel = ""
        activeSessionId = ""
        activeThreadId = ""
        ownership = ""
        canWrite = true
        lines.clear()
        detailLoading = false
        sessionNote = ""
        sessionTotal = 0
        sessionTruncated = false
    }

    private fun resetSession() {
        chats.clear()
        sessions.clear()
        kernels.clear()
        lines.clear()
        approvals.clear()
        notices.clear()
        frameLog.clear()
        tabs.clear()
        clearActive()
        sending = false
    }

    private fun onFrame(frame: JsonObject) {
        val type = frame.string("type")
        if (type != "chat.event") record(type, frame)
        when (type) {
            "chats" -> {
                val rows = frame.array("chats").mapNotNull(::chatRow)
                chats.clear()
                chats.addAll(rows)
                rows.firstOrNull { it.id == activeId }?.let { active ->
                    canWrite = active.canWrite
                    ownership = active.owner
                    sending = active.status == "running"
                }
                if (frame.containsKey("approvals")) {
                    approvals.clear()
                    frame.array("approvals").mapNotNull(::approvalOf).forEach { approvals.add(it) }
                }
            }

            "chat" -> {
                val id = frame.string("id")
                if (id.isNotBlank()) {
                    activeId = id
                    detailSource = "chat"
                    activeTitle = frame.string("title").ifBlank { "未命名会话" }
                    activeEngine = frame.string("engine")
                    activeCwd = frame.string("cwd")
                    activeProvider = frame.string("provider")
                    activeModel = frame.string("model")
                    activeSessionId = frame.string("sessionId")
                    activeThreadId = frame.string("threadId")
                    sending = frame.string("status") == "running"
                    readOwnership(frame)
                    detailLoading = false
                    sessionNote = ""
                    chatRow(frame)?.let { row ->
                        chats.removeAll { it.id == row.id }
                        chats.add(0, row)
                        val index = tabs.indexOfFirst { it.id == row.id }
                        if (index >= 0) tabs[index] = tabs[index].copy(title = row.title)
                        else tabs.add(ChatTab(row.id, row.title))
                    }
                }
                if (frame.containsKey("events")) {
                    lines.clear()
                    frame.array("events").forEach { event -> lineOf(event)?.let { lines.add(it) } }
                } else if (id.isNotBlank()) {
                    send("chat.read", buildJsonObject { put("chatId", id) })
                }
            }

            "chat.event" -> {
                val chatId = frame.string("chatId")
                if (chatId.isNotBlank() && chatId != activeId) return
                val seq = frame.int("seq")
                if (frame.bool("removed")) {
                    lines.removeAll { it.seq == seq }
                    return
                }
                val item = frame["item"] as? JsonObject ?: return
                lineOf(item)?.let { line ->
                    val index = lines.indexOfFirst { it.seq == line.seq }
                    if (index >= 0) lines[index] = line else lines.add(line)
                }
            }

            "chat.status" -> {
                val id = frame.string("chatId")
                val status = frame.string("status")
                val index = chats.indexOfFirst { it.id == id }
                if (index >= 0) chats[index] = chats[index].copy(status = status)
            }

            "chat.turn" -> {
                when (frame.string("state")) {
                    "ended", "failed", "cancelled", "idle" -> sending = false
                }
                if (frame.string("state") == "failed") error = "这一轮失败了：${frame.string("error")}"
            }

            "chat.approval" -> {
                val approval = approvalOf(frame) ?: return
                approvals.removeAll { it.requestId == approval.requestId }
                if (frame.string("state") != "resolved") approvals.add(approval)
            }

            "chat.closed" -> {
                val id = frame.string("chatId")
                chats.removeAll { it.id == id }
                tabs.removeAll { it.id == id }
                if (activeId == id) {
                    val next = tabs.lastOrNull()
                    if (next != null) open(next.id) else clearActive()
                }
            }

            "sessions" -> {
                sessions.clear()
                sessions.addAll(frame.array("sessions").mapNotNull(::sessionRow))
            }

            "kernels" -> {
                val rows = frame.array("kernels").mapNotNull { kernel ->
                    val id = kernel.string("id")
                    if (id.isBlank()) null else KernelRow(
                        id = id,
                        label = kernel.string("label").ifBlank { id },
                        tier = kernel.string("tier"),
                        transport = kernel.string("transport"),
                        available = kernel.bool("available"),
                        enabled = kernel.bool("enabled"),
                        path = kernel.string("path"),
                        detail = kernel.string("detail"),
                        custom = kernel.bool("custom"),
                        override = kernel.bool("override"),
                        autoPath = kernel.string("autoPath"),
                        selectable = kernel.bool("selectable"),
                        command = kernel.string("command"),
                    )
                }
                kernels.clear()
                kernels.addAll(rows)
                val at = frame.long("scannedAt")
                if (at > 0) scannedAt = at
                if (selectedEngine.isBlank()) {
                    selectedEngine = rows.firstOrNull { it.available && it.enabled }?.id.orEmpty()
                }
            }

            "devices" -> {
                val rows = frame.array("devices").mapNotNull { device ->
                    val id = device.string("id")
                    if (id.isBlank()) null else DeviceRow(
                        id = id,
                        name = device.string("name").ifBlank { id },
                        source = device.string("source").ifBlank { "local" },
                        lastSeenAt = device.string("lastSeenAt"),
                        createdAt = device.string("createdAt"),
                        revoked = device.bool("revoked"),
                        legacy = device.bool("legacy"),
                    )
                }
                phones.clear()
                phones.addAll(rows)
            }

            "session" -> {
                // A recorded session opened for inspection. The agent wraps the
                // facts in `meta` (engine, cwd, title, resume verdict, bodyNote),
                // which is why the flat read below is a fallback, not the shape.
                val meta = frame["meta"] as? JsonObject
                val id = meta?.string("id")?.ifBlank { frame.string("id") } ?: frame.string("id")
                if (id.isNotBlank()) {
                    activeId = id
                    detailSource = "record"
                    activeTitle = listOf(
                        meta?.string("title"),
                        frame.string("title"),
                        sessions.firstOrNull { it.id == id }?.title,
                    ).firstOrNull { !it.isNullOrBlank() } ?: "历史记录"
                    activeEngine = (meta?.string("engine") ?: frame.string("engine"))
                    activeCwd = (meta?.string("cwd") ?: frame.string("cwd"))
                    ownership = "历史记录"
                    canWrite = false
                    sessionNote = meta?.string("bodyNote") ?: frame.string("bodyNote")
                    sessionTotal = frame.int("totalEvents")
                    sessionTruncated = frame.bool("truncated")
                    lines.clear()
                    frame.array("events").forEachIndexed { index, event ->
                        lineOf(event, index + 1)?.let { lines.add(it) }
                    }
                    detailLoading = false
                }
            }

            "notify" -> {
                val title = frame.string("title")
                val text = frame.string("text")
                if (title.isNotBlank() || text.isNotBlank()) {
                    notices.add(
                        0,
                        NoticeRow(
                            id = frame.string("id").ifBlank { UUID.randomUUID().toString() },
                            kind = frame.string("kind").ifBlank { "notice" },
                            chatId = frame.string("chatId").ifBlank { null },
                            title = title.ifBlank { "通知" },
                            text = text,
                            at = frame.long("at").takeIf { it > 0 } ?: System.currentTimeMillis(),
                        ),
                    )
                    while (notices.size > 20) notices.removeAt(notices.lastIndex)
                }
            }

            "action.result" -> {
                val ok = frame.bool("ok")
                val message = frame.string("message")
                val action = frame.string("action")
                if (ok) info = message else error = message.ifBlank { "操作失败" }
                if (!ok) sending = false
                if (ok) {
                    when {
                        action.startsWith("devices.") -> send("devices.list")
                        action.startsWith("kernels.") -> send("kernels.list")
                        action == "sessions.delete" -> {
                            send("sessions.list")
                            clearActive()
                        }
                    }
                }
            }

            "error" -> {
                error = frame.string("message").ifBlank { "代理返回了一个错误" }
                sending = false
            }
        }
    }

    private fun record(type: String, frame: JsonObject) {
        val detail = when (type) {
            "chat.event" -> ""
            "chat" -> frame.string("title").ifBlank { frame.string("id") }
            "chat.status" -> frame.string("chatId").take(12) + " → " + frame.string("status")
            "action.result" -> frame.string("action") + " " + frame.string("message").take(80)
            "notify" -> frame.string("title").take(60)
            "kernels" -> "${frame.array("kernels").size} 个"
            "devices" -> "${frame.array("devices").size} 台手机"
            "chats" -> "${frame.array("chats").size} 个会话"
            "sessions" -> "${frame.array("sessions").size} 条记录"
            else -> ""
        }
        frameLog.add(FrameRow(System.currentTimeMillis(), type, detail))
        while (frameLog.size > 200) frameLog.removeAt(0)
    }

    private fun readOwnership(frame: JsonObject) {
        val own = frame["ownership"] as? JsonObject ?: return
        canWrite = own.string("mode") == "owner"
        val owner = own["owner"] as? JsonObject
        ownership = owner?.string("deviceName").orEmpty()
    }

    private fun chatRow(frame: JsonObject): ChatRow? {
        val id = frame.string("id")
        if (id.isBlank()) return null
        val own = frame["ownership"] as? JsonObject
        val owner = own?.get("owner") as? JsonObject
        return ChatRow(
            id = id,
            title = frame.string("title").ifBlank { "未命名会话" },
            engine = frame.string("engine"),
            status = frame.string("status").ifBlank { "idle" },
            owner = owner?.string("deviceName").orEmpty(),
            canWrite = own?.string("mode") == "owner" ?: false,
            updatedAt = frame["lastUsedAt"]?.toString()?.toLongOrNull() ?: 0L,
            cwd = frame.string("cwd"),
        )
    }

    private fun sessionRow(frame: JsonObject): SessionRow? {
        val id = frame.string("id")
        if (id.isBlank()) return null
        return SessionRow(
            engine = frame.string("engine"),
            id = id,
            title = frame.string("title"),
            updatedAt = frame.string("updatedAt").ifBlank { frame.string("createdAt") },
            path = frame.string("path"),
            canResume = frame.bool("canResume"),
            note = frame.string("resumeNote"),
            cwd = frame.string("cwd"),
        )
    }

    private fun approvalOf(frame: JsonObject): ApprovalRow? {
        val requestId = frame.string("requestId")
        if (requestId.isBlank()) return null
        val chatId = frame.string("chatId")
        return ApprovalRow(
            requestId = requestId,
            chatId = chatId,
            chatTitle = chats.firstOrNull { it.id == chatId }?.title
                ?: frame.string("chatTitle").ifBlank { "会话" },
            engine = frame.string("engine"),
            title = frame.string("title").ifBlank { "需要你的确认" },
            detail = frame.string("detail"),
            options = frame.array("options").mapNotNull {
                val optionId = it.string("id")
                if (optionId.isBlank()) null else optionId to it.string("label").ifBlank { optionId }
            },
        )
    }

    private fun lineOf(frame: JsonObject, fallbackSeq: Int = 0): Line? {
        // Live chat events carry a sequence the agent assigns. A recorded
        // session's transcript (sessions.read) is a plain event list without
        // one, so the read order is the sequence; dropping those events because
        // "seq" was absent is what made every history record look empty.
        val hasSeq = frame.containsKey("seq")
        if (!hasSeq && fallbackSeq == 0) return null
        val meta = frame["meta"] as? JsonObject
        val files = (meta?.get("files") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { (it["path"] as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        return Line(
            seq = if (hasSeq) frame.int("seq") else fallbackSeq,
            kind = frame.string("kind"),
            role = frame.string("role"),
            text = frame.string("text"),
            name = frame.string("name"),
            streaming = frame.bool("streaming"),
            files = files,
        )
    }

    private fun JsonObject.array(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun readAgentToken(): String {
        val file = File(termdeskHome(), ".termdesk/token")
        return runCatching { if (file.isFile) file.readText().trim() else "" }.getOrDefault("")
    }

    /** The frame log uses the wall clock, so an entry says when it happened. */
    fun frameTime(at: Long): String = timeFormat.format(Date(at))

    private companion object {
        fun desktopDeviceId(): String {
            val file = File(termdeskHome(), ".termdesk/desktop-device")
            runCatching {
                if (file.isFile) {
                    val existing = file.readText().trim()
                    if (existing.isNotBlank()) return existing
                }
                file.parentFile?.mkdirs()
                val id = "desktop-" + UUID.randomUUID().toString().take(8)
                file.writeText(id)
                return id
            }
            return "desktop-" + UUID.randomUUID().toString().take(8)
        }
    }
}

@Serializable
private data class PeerRecord(val id: String, val name: String, val url: String, val token: String)

/** TERMDESK_HOME lets a verification run use its own token/device registry. */
internal fun termdeskHome(): File =
    File(System.getenv("TERMDESK_HOME")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"))

/** Peers are local machine facts, kept beside the agent's own state. */
private object PeerStore {
    private val file = File(termdeskHome(), ".termdesk/console-peers.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): List<ConsoleDevice> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString<List<PeerRecord>>(file.readText()).map {
            ConsoleDevice(it.id, it.name, it.url, it.token, local = false)
        }
    }.getOrDefault(emptyList())

    fun add(device: ConsoleDevice) {
        val next = load().filterNot { it.id == device.id } + device
        save(next)
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    private fun save(devices: List<ConsoleDevice>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                json.encodeToString(
                    devices.map { PeerRecord(it.id, it.name, it.url, it.token) },
                ),
            )
        }
    }
}
