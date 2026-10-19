package dev.termdesk.app.data

import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest

data class ChatDraft(val text: String = "", val attachment: UploadedFile? = null) {
    fun toJson(): String = JSONObject().put("text", text).apply {
        attachment?.let { put("attachment", JSONObject().put("name", it.name).put("path", it.path).put("at", it.at)) }
    }.toString()

    companion object {
        fun fromJson(value: String?): ChatDraft = runCatching {
            val json = JSONObject(value ?: "{}")
            val file = json.optJSONObject("attachment")
            ChatDraft(json.optString("text"), file?.takeIf { it.optString("path").isNotBlank() }?.let {
                UploadedFile(it.optString("name"), it.optString("path"), it.optLong("at"))
            })
        }.getOrDefault(ChatDraft())

        fun key(scope: String, chatId: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$scope\u0000$chatId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Private app storage survives page disposal, rotation and process recreation. */
class ChatDraftStore(private val preferences: SharedPreferences) {
    fun read(scope: String, chatId: String) = ChatDraft.fromJson(preferences.getString(ChatDraft.key(scope, chatId), null))
    fun write(scope: String, chatId: String, draft: ChatDraft) {
        val editor = preferences.edit()
        val key = ChatDraft.key(scope, chatId)
        if (draft.text.isEmpty() && draft.attachment == null) editor.remove(key) else editor.putString(key, draft.toJson())
        editor.apply()
    }
}
