package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Native identities survive a PC chat wrapper being recreated during resume. */
internal fun phoneChatIdentity(chat: ChatInfo): String = chat.engine + ":" +
    (chat.threadId?.takeIf { it.isNotBlank() }
        ?: chat.sessionId?.takeIf { it.isNotBlank() } ?: chat.id)

/** Phone-owned events are stored separately from the server's authoritative transcript. */
internal class PhoneChatEvents(private val directory: File?) {
    private val memory = mutableMapOf<String, List<ChatEvent>>()

    @Synchronized
    fun read(scope: String, identity: String): List<ChatEvent> {
        val key = ChatDraft.key(scope, identity)
        return memory.getOrPut(key) {
            runCatching {
                val file = directory?.resolve("$key.json") ?: return@getOrPut emptyList()
                if (!file.exists()) emptyList() else parseChatEvents(JSONArray(file.readText()))
            }.getOrDefault(emptyList())
        }
    }

    @Synchronized
    fun append(scope: String, identity: String, text: String, poster: String? = null): List<ChatEvent> {
        val events = read(scope, identity).toMutableList()
        val previous = if (poster == null) null else events.lastOrNull { it.kind == "poster" }
        val event = if (previous != null) previous.copy(text = text, poster = poster) else ChatEvent(
            seq = (events.maxOfOrNull { it.seq } ?: 1_000_000_000) + 1,
            at = System.currentTimeMillis(), kind = if (poster == null) "local" else "poster",
            role = null, text = text, name = null, state = null, exitCode = null,
            sourceKind = "phone", streaming = false, poster = poster,
        )
        if (previous != null) events[events.indexOf(previous)] = event else events.add(event)
        write(scope, identity, events)
        return events
    }

    @Synchronized
    fun migrate(scope: String, previous: String, current: String) {
        if (previous == current) return
        val old = read(scope, previous)
        if (old.isEmpty()) return
        val combined = (read(scope, current) + old)
            .distinctBy { listOf(it.kind, it.at, it.text, it.poster) }
            .sortedBy { it.at }
            .mapIndexed { index, event -> event.copy(seq = 1_000_000_001 + index) }
        write(scope, current, combined)
        write(scope, previous, emptyList())
    }

    private fun write(scope: String, identity: String, events: List<ChatEvent>) {
        val key = ChatDraft.key(scope, identity)
        val root = directory
        if (root != null) {
            root.mkdirs()
            val json = JSONArray()
            for (event in events) json.put(JSONObject()
                .put("seq", event.seq).put("at", event.at).put("kind", event.kind)
                .put("text", event.text).put("poster", event.poster)
                .put("meta", JSONObject().put("sourceKind", "phone")))
            val target = File(root, "$key.json")
            val temporary = File(root, "$key.tmp")
            temporary.writeText(json.toString())
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        memory[key] = events.toList()
    }
}

/** Server removals affect server rows only; local cards stay in their own history. */
internal fun withPhoneChatEvents(server: List<ChatEvent>, phone: List<ChatEvent>): List<ChatEvent> =
    (server + phone).sortedBy { it.seq }
