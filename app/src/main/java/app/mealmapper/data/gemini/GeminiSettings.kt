package app.mealmapper.data.gemini

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

/**
 * The user's Gemini API key, encrypted with an Android Keystore key that never leaves the phone's secure
 * hardware. The key is never logged, never in the repo, never in BuildConfig.
 */
class GeminiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("gemini", Context.MODE_PRIVATE)

    private val _hasKey = MutableStateFlow(prefs.contains(KEY_CIPHERTEXT))
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    /** Google renames models every few months; the user can change it without a new build. */
    var model: String
        get() = prefs.getString(MODEL, null) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(MODEL, value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    fun saveKey(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray())
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        _hasKey.value = true
    }

    fun apiKey(): String? {
        val data = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        }.getOrNull()
    }

    fun clearKey() {
        prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
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

    companion object {
        /** GA model as of September 2026. Change in Settings if Google retires it. */
        const val DEFAULT_MODEL = "gemini-3.5-flash"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "mealmapper_gemini"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_CIPHERTEXT = "key_ciphertext"
        private const val KEY_IV = "key_iv"
        private const val MODEL = "model"
    }
}
