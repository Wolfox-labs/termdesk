package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The frames the agent sends, turned into the models the UI reads.
 *
 * These were the tail of `AgentClient` — 19 conversions that take a JSON frame
 * and return a data class. They touched no connection, no socket and no state
 * flow, which is exactly why they are here: this is the only part of the phone's
 * data layer that can be tested without a device, and
 * `src/test/java/dev/termdesk/app/data/AgentFramesTest.kt` does that.
 *
 * What stays in `AgentClient` is the half that owns state: `applyChatEvent`,
 * `upsertChat`, `updateChatStatus`, `applyChatModels` and the terminal-list
 * updaters write StateFlows, so they belong with the client that owns them.
 *
 * Nothing here opens a socket or reads a file: a malformed frame yields null or
 * an empty list, never an exception escaping to the socket loop.
 */

    internal fun parseSearch(frame: JSONObject): SearchResults {
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
    internal fun parseApprovals(arr: JSONArray?): List<ChatApproval> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                parseApproval(o)?.let { add(it) }
            }
        }
    }
    internal fun parseApproval(o: JSONObject): ChatApproval? {
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
    internal fun parseChatList(arr: JSONArray?): List<ChatInfo> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                parseChatInfo(o)?.let { add(it) }
            }
        }
    }
    internal fun parseChatOwner(o: JSONObject?): ChatOwner? {
        if (o == null) return null
        val id = o.optString("deviceId").takeIf { it.isNotBlank() } ?: return null
        return ChatOwner(
            deviceId = id,
            deviceName = o.optString("deviceName").ifBlank { id },
            clientKind = o.optString("clientKind").ifBlank { "unknown" },
            acquiredAt = o.optLong("acquiredAt"),
            lastSeenAt = o.optLong("lastSeenAt"),
            leaseExpiresAt = o.optLong("leaseExpiresAt"),
        )
    }

    internal fun parseChatOwnership(o: JSONObject?): ChatOwnership {
        if (o == null) return ChatOwnership()
        return ChatOwnership(
            owner = parseChatOwner(o.optJSONObject("owner")),
            mode = o.optString("mode", "available"),
            queued = o.optBoolean("queued", false),
            leaseMs = o.optLong("leaseMs"),
        )
    }
    internal fun parseChatInfo(o: JSONObject): ChatInfo? {
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
            ownership = parseChatOwnership(o.optJSONObject("ownership")),
        )
    }
    internal fun parseChatEvents(arr: JSONArray): List<ChatEvent> {
        val out = ArrayList<ChatEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parseChatEvent(o))
        }
        return out
    }
    internal fun parseChatEvent(o: JSONObject): ChatEvent {
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
            queued = o.optBoolean("queued", false),
            files = parseChatFiles(meta?.optJSONArray("files")),
            poster = if (o.isNull("poster")) null else o.optString("poster"),
        )
    }

    /**
     * The files a turn handed over, when the event carried any.
     *
     * An entry without a path is dropped rather than shown: the point of the row is that a
     * tap opens the file, and there is nothing to open.
     */
    /**
     * `chat.commands`: the slash commands this conversation can run.
     *
     * The list is the kernel's own declaration (the PC asks its host), so only the
     * field names are pinned here: a renamed field must show as an absent command,
     * not as a row labelled "null".
     */
    internal fun parseChatCommands(frame: JSONObject): ChatCommands? {
        val chatId = frame.optString("chatId")
        if (chatId.isBlank()) return null
        val commands = frame.optJSONArray("commands")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name")
                    if (name.isBlank()) continue
                    add(
                        ChatCommand(
                            name = name,
                            description = o.optString("description"),
                            hint = if (o.isNull("hint")) null else o.optString("hint").ifBlank { null },
                        ),
                    )
                }
            }
        }.orEmpty()
        val note = if (frame.isNull("note")) null else frame.optString("note").ifBlank { null }
        return ChatCommands(
            chatId = chatId,
            supported = frame.optBoolean("supported", false),
            commands = commands,
            note = note,
        )
    }
    internal fun parseChatFiles(arr: JSONArray?): List<ChatFile> {
        if (arr == null) return emptyList()
        val out = ArrayList<ChatFile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path").takeIf { it.isNotBlank() && it != "null" } ?: continue
            out.add(
                ChatFile(
                    path = path,
                    description = o.optString("description").takeIf { it.isNotBlank() && it != "null" },
                ),
            )
        }
        return out
    }

    /**
     * One `notify` frame: something the PC decided the phone should be told about.
     *
     * Null when there is nothing to say — no title and no text. An empty notification is
     * worse than none: it is how people learn to swipe without reading, and then miss the
     * one that mattered. A frame with no id is dropped for the same reason: the id is what
     * lets a second turn about one conversation replace the first instead of stacking.
     */
    internal fun parseAgentNotification(o: JSONObject): AgentNotification? {
        val id = o.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val title = o.optString("title").takeIf { it.isNotBlank() && it != "null" }
        val text = o.optString("text").takeIf { it.isNotBlank() && it != "null" }
        if (title == null && text == null) return null
        return AgentNotification(
            id = id,
            kind = o.optString("kind").takeIf { it.isNotBlank() && it != "null" } ?: "turn_done",
            chatId = o.optString("chatId").takeIf { it.isNotBlank() && it != "null" },
            sessionId = o.optString("sessionId").takeIf { it.isNotBlank() && it != "null" },
            engine = o.optString("engine").takeIf { it.isNotBlank() && it != "null" },
            title = title ?: "TermDesk",
            text = text.orEmpty(),
            at = o.optLong("at"),
            whileAway = o.optBoolean("whileAway", false),
            requestId = o.optString("requestId").takeIf { it.isNotBlank() && it != "null" },
        )
    }
    internal fun parseProcesses(arr: JSONArray?): List<ProcessInfo> {
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
    internal fun parseServices(arr: JSONArray?): List<ServiceInfo> {
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
    internal fun parseListing(frame: JSONObject): DirectoryListing {
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
    internal fun parseCodexConfig(o: JSONObject?): CodexConfig? {
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
    internal fun parseKernelManifest(o: JSONObject?): KernelManifest? {
        if (o == null) return null
        fun bool(value: JSONObject?, key: String) = value?.optBoolean(key, false) ?: false
        val session = o.optJSONObject("session")?.let { s ->
            KernelSessionCapabilities(
                list = bool(s, "list"),
                read = bool(s, "read"),
                resume = bool(s, "resume"),
                fork = bool(s, "fork"),
                nativeTranscript = bool(s, "nativeTranscript"),
            )
        } ?: KernelSessionCapabilities()
        val turn = o.optJSONObject("turn")?.let { t ->
            KernelTurnCapabilities(
                cancel = t.optString("cancel", "none"),
                queue = t.optBoolean("queue", false),
                liveTerminals = t.optBoolean("liveTerminals", false),
            )
        } ?: KernelTurnCapabilities()
        val model = o.optJSONObject("model")?.let { m ->
            KernelModelCapabilities(
                source = m.optString("source", "default"),
                dynamic = m.optBoolean("dynamic", false),
                switch = m.optBoolean("switch", false),
                add = m.optBoolean("add", false),
            )
        } ?: KernelModelCapabilities()
        val config = o.optJSONObject("config")?.let { c ->
            val fields = c.optJSONArray("schema")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val f = arr.optJSONObject(i) ?: continue
                        add(
                            KernelConfigField(
                                id = f.optString("id"),
                                type = f.optString("type"),
                                support = f.optString("support", "unsupported"),
                                apply = f.optString("apply", "session"),
                                label = f.optString("label").takeIf { it.isNotBlank() },
                                valuesSource = f.optString("valuesSource").takeIf { it.isNotBlank() },
                                min = if (f.has("min")) f.optLong("min") else null,
                                max = if (f.has("max")) f.optLong("max") else null,
                                unit = f.optString("unit").takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
            } ?: emptyList()
            KernelConfigCapabilities(
                scope = c.optString("scope", "none"),
                liveApply = c.optBoolean("liveApply", false),
                requiresRestart = c.optBoolean("requiresRestart", false),
                schema = fields,
            )
        } ?: KernelConfigCapabilities()
        return KernelManifest(
            version = o.optInt("version", 1),
            known = o.optBoolean("known", false),
            transport = o.optString("transport").takeIf { it.isNotBlank() && it != "null" },
            driver = o.optString("driver", "none"),
            session = session,
            turn = turn,
            model = model,
            config = config,
        )
    }
    internal fun parseKernels(arr: JSONArray?): List<KernelInfo> {
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
                        manifest = parseKernelManifest(o.optJSONObject("manifest")),
                    ),
                )
            }
        }
    }
    /**
     * Agent processes running on the PC that its own agent did not start.
     *
     * A row without a usable pid is skipped: the pid is the only thing that makes
     * the row concrete ("which process"), and a row that cannot name one would
     * read as a conversation when it is not.
     */
    internal fun parseKernelRuns(arr: JSONArray?): List<KernelRun> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pid = o.optInt("pid", 0)
                if (pid <= 0) continue
                add(
                    KernelRun(
                        kernelId = o.optString("kernelId"),
                        label = o.optString("label"),
                        pid = pid,
                        name = o.optString("name"),
                        memBytes = o.optLong("memBytes", 0L),
                        // Defaults to false, which is the truthful default: this
                        // agent did not start the process and holds no handle to it.
                        attachable = o.optBoolean("attachable", false),
                        // "process" is the default because that is what an older
                        // agent's rows are: matched by executable name.
                        source = o.optString("source").takeIf { it.isNotBlank() } ?: "process",
                        // Null rather than 1 when absent: for a plain process row the
                        // count says nothing, and inventing 1 would make the two kinds
                        // of row indistinguishable in the UI.
                        processCount = if (o.has("processCount")) o.optInt("processCount") else null,
                    ),
                )
            }
        }
    }

    internal fun parseSessionList(arr: JSONArray?): List<SessionInfo> {        if (arr == null) return emptyList()
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
    internal fun parseWorkspaces(arr: JSONArray?): List<WorkspaceInfo> {
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
    internal fun parseSessionDetail(o: JSONObject): SessionDetail {
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
                            // What the harness injected, and why it is worth keeping: the label
                            // on a context block reads out of this ("目标", "工具任务", …). Without
                            // it every injected block was labelled a bare "上下文 · context",
                            // which says nothing about which of them is a goal and which is a
                            // skill catalogue.
                            sourceKind = m?.let { if (it.isNull("sourceKind")) null else it.optString("sourceKind") },
                            files = parseChatFiles(m?.optJSONArray("files")),
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
    internal fun parseStatus(obj: JSONObject?): HostStatus? {
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
        val sandbox = obj.optJSONObject("sandbox")
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
            sandboxRssBytes = sandbox?.optLong("rssBytes") ?: 0,
            sandboxProcessCount = sandbox?.optInt("processCount") ?: 0,
        )
    }
