package app.mealmapper.data.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's Deepgram API key, encrypted with its own Android Keystore key (same scheme as the Gemini key). */
class DeepgramSettings(context: Context) {
    private val prefs = context.getSharedPreferences("deepgram_v1", Context.MODE_PRIVATE)

    private val _hasKey = MutableStateFlow(prefs.contains(CIPHERTEXT))
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    fun saveKey(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray())
        prefs.edit()
            .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        _hasKey.value = true
    }

    fun apiKey(): String? {
        val data = prefs.getString(CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        }.getOrNull()
    }

    fun clearKey() {
        prefs.edit().remove(CIPHERTEXT).remove(IV).apply()
        _hasKey.value = false
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "mealmapper_deepgram_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CIPHERTEXT = "key_ciphertext"
        const val IV = "key_iv"
    }
}
