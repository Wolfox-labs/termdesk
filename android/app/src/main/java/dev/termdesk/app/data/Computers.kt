package dev.termdesk.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One computer this phone is paired with.
 *
 * A binding is an address plus this phone's own credential on that computer. The
 * secret lives in [DeviceCredentials] under the same [id], so forgetting a
 * computer cannot leave a usable credential behind.
 */
data class PairedComputer(
    val id: String,
    /** What the computer calls itself, once it has answered at least once. */
    val name: String,
    val url: String,
    /**
     * True when this binding goes through a relay. A relay is the only side that
     * keeps a list of phones, so it is also the only side where "unbind me" means
     * anything — and the only one that can be reached from any network.
     */
    val relay: Boolean,
    val pairedAt: Long,
    val lastUsedAt: Long,
)

/**
 * The computers this phone knows about.
 *
 * Why a list, when a single saved address used to do: the product is several
 * people, each with their own computer and their own phone — and one person may
 * own two computers. One address and one token could only ever describe one
 * machine, so pairing the second one silently threw the first away, and there was
 * nowhere to say "this one is my desktop, that one is the laptop".
 *
 * The list holds no secrets: only the address, the name and when it was last used.
 */
class ComputerStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val credentials = DeviceCredentials(context)

    fun list(): List<PairedComputer> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PairedComputer(
                    id = id,
                    name = item.optString("name").takeIf { it.isNotBlank() } ?: hostOf(url),
                    url = url,
                    relay = item.optBoolean("relay", url.startsWith("wss://")),
                    pairedAt = item.optLong("pairedAt", 0L),
                    lastUsedAt = item.optLong("lastUsedAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> list().any { it.id == id } }

    fun active(): PairedComputer? {
        val id = activeId() ?: return null
        return list().firstOrNull { it.id == id }
    }

    fun credentialFor(id: String): String = credentials.read(id)

    fun activeCredential(): String = activeId()?.let { credentialFor(it) } ?: ""

    /**
     * Add a computer, or update the one already paired at this address.
     *
     * Matching on the address is what makes re-pairing the same machine (a new
     * code, a rotated credential) an update instead of a duplicate row.
     */
    fun upsert(url: String, name: String?, relay: Boolean, id: String? = null): PairedComputer {
        val now = System.currentTimeMillis()
        val existing = list().firstOrNull { it.id == id } ?: list().firstOrNull { it.url == url }
        val computer = PairedComputer(
            id = existing?.id ?: id ?: "c-" + UUID.randomUUID().toString().take(8),
            name = name?.takeIf { it.isNotBlank() } ?: existing?.name ?: hostOf(url),
            url = url,
            relay = relay || existing?.relay == true,
            pairedAt = existing?.pairedAt ?: now,
            lastUsedAt = now,
        )
        write(list().filterNot { it.id == computer.id } + computer)
        select(computer.id)
        return computer
    }

    fun select(id: String) {
        if (list().none { it.id == id }) return
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        touch(id)
    }

    fun touch(id: String) {
        val updated = list().map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it }
        write(updated)
    }

    /** Learn what the computer calls itself; called when it answers. */
    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        val current = list().firstOrNull { it.id == id } ?: return
        if (current.name == name) return
        write(list().map { if (it.id == id) it.copy(name = name) else it })
    }

    fun markRelay(id: String) {
        val current = list().firstOrNull { it.id == id } ?: return
        if (current.relay) return
        write(list().map { if (it.id == id) it.copy(relay = true) else it })
    }

    fun rememberCredential(id: String, token: String) = credentials.write(token, id)

    /** Remove a computer and its credential. The caller unbinds on the far side first. */
    fun forget(id: String) {
        credentials.forget(id)
        val remaining = list().filterNot { it.id == id }
        write(remaining)
        if (activeId() == id || prefs.getString(KEY_ACTIVE, null) == id) {
            prefs.edit().putString(KEY_ACTIVE, remaining.firstOrNull()?.id ?: "").apply()
        }
    }

    /**
     * Turn the one binding older builds kept into the first entry of the list.
     *
     * Upgrading must not look like "you were never paired": the credential and the
     * address are already on the phone, so they become a row like any other.
     */
    fun migrateLegacy() {
        if (prefs.contains(KEY_LIST)) return
        val legacyToken = credentials.read(DeviceCredentials.LEGACY_ID)
        val legacyUrl = prefs.getString("url", null)
        if (legacyToken.isBlank() || legacyUrl.isNullOrBlank()) { write(emptyList()); return }
        val now = System.currentTimeMillis()
        write(
            listOf(
                PairedComputer(
                    // The legacy credential keeps its own storage slot, so the id has
                    // to be the one DeviceCredentials already uses for it.
                    id = DeviceCredentials.LEGACY_ID,
                    name = hostOf(legacyUrl),
                    url = legacyUrl,
                    relay = legacyUrl.startsWith("wss://"),
                    pairedAt = now,
                    lastUsedAt = now,
                ),
            ),
        )
        prefs.edit().putString(KEY_ACTIVE, DeviceCredentials.LEGACY_ID).apply()
    }

    private fun write(computers: List<PairedComputer>) {
        val array = JSONArray()
        computers.forEach { computer ->
            array.put(
                JSONObject()
                    .put("id", computer.id)
                    .put("name", computer.name)
                    .put("url", computer.url)
                    .put("relay", computer.relay)
                    .put("pairedAt", computer.pairedAt)
                    .put("lastUsedAt", computer.lastUsedAt),
            )
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    /** A readable stand-in for a computer that has not answered yet. */
    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host?.takeIf { it.isNotBlank() } ?: url
    }.getOrDefault(url)

    private companion object {
        const val PREFS = "termdesk"
        const val KEY_LIST = "computers"
        const val KEY_ACTIVE = "activeComputer"
    }
}
