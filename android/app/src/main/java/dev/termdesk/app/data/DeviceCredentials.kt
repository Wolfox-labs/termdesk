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
    fun read(): String {
        val encoded = prefs.getString("deviceCredential", null)
        if (encoded != null) return runCatching {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
                String(doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
            }
        }.getOrDefault("")
        val legacy = prefs.getString("token", "") ?: ""
        if (legacy.isNotEmpty()) write(legacy)
        return legacy
    }
    fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("deviceCredential", Base64.encodeToString(data, Base64.NO_WRAP)).remove("token").apply()
    }
    fun forget() { prefs.edit().remove("deviceCredential").remove("token").apply() }
}
