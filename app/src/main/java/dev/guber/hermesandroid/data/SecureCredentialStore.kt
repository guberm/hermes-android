package dev.guber.hermesandroid.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the gateway origin and bearer/refresh tokens encrypted with an Android Keystore AES key.
 * Tokens never enter logs, URLs, or the app's unencrypted preferences.
 */
class SecureCredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(connection: StoredConnection) {
        val payload = JSONObject()
            .put("endpoint", connection.endpoint.origin.toString())
            .put("access_token", connection.auth.accessToken)
            .put("refresh_token", connection.auth.refreshToken)
            .put("expires_at", connection.auth.expiresAtEpochSeconds)
            .put("provider", connection.auth.provider ?: JSONObject.NULL)
            .toString()
        prefs.edit().putString(KEY_PAYLOAD, encrypt(payload)).apply()
    }

    fun load(): StoredConnection? {
        val encoded = prefs.getString(KEY_PAYLOAD, null) ?: return null
        return runCatching {
            val value = JSONObject(decrypt(encoded))
            val endpoint = GatewayEndpoint.parse(value.getString("endpoint")).getOrThrow()
            val auth = AuthSession(
                accessToken = value.getString("access_token"),
                refreshToken = value.optString("refresh_token"),
                expiresAtEpochSeconds = value.optLong("expires_at", 0),
                provider = value.optString("provider").ifBlank { null },
            )
            if (auth.accessToken.isBlank()) null else StoredConnection(endpoint, auth)
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > GCM_IV_BYTES) { "Stored credential payload is invalid" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, combined.copyOfRange(0, GCM_IV_BYTES)),
        )
        return cipher.doFinal(combined.copyOfRange(GCM_IV_BYTES, combined.size)).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "hermes_secure_connection"
        const val KEY_PAYLOAD = "encrypted_connection"
        const val KEY_ALIAS = "hermes_android_connection_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
