/** Device credential encrypted with a non-exportable Android Keystore key. */
package dev.termdesk.app.data

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * This phone's credentials, one per computer it is paired with.
 *
 * The encryption key never leaves the Keystore, and each credential is stored
 * under its own name, so two computers cannot end up sharing a secret and
 * forgetting one cannot touch the other. [LEGACY_ID] is the slot older builds
 * used for their single binding: it is kept so an upgrade finds its credential
 * where it left it, and it is migrated into the computer list on first run.
 */
class DeviceCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("termdesk", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("termdesk.device", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("termdesk.device", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun slot(id: String) = if (id == LEGACY_ID) "deviceCredential" else "deviceCredential.$id"

    fun read(id: String = LEGACY_ID): String {
        val encoded = prefs.getString(slot(id), null)
        if (encoded != null) return runCatching {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
                String(doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
            }
        }.getOrDefault("")
        // Only the original single-binding slot had a plaintext predecessor.
        if (id != LEGACY_ID) return ""
        val legacy = prefs.getString("token", "") ?: ""
        if (legacy.isNotEmpty()) write(legacy, id)
        return legacy
    }

    fun write(value: String, id: String = LEGACY_ID) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val edit = prefs.edit().putString(slot(id), Base64.encodeToString(data, Base64.NO_WRAP))
        if (id == LEGACY_ID) edit.remove("token")
        edit.apply()
    }

    fun forget(id: String = LEGACY_ID) {
        val edit = prefs.edit().remove(slot(id))
        if (id == LEGACY_ID) edit.remove("token")
        edit.apply()
    }

    companion object {
        /** The slot the one-binding builds wrote to; also the first list entry. */
        const val LEGACY_ID = "legacy"
    }
}
