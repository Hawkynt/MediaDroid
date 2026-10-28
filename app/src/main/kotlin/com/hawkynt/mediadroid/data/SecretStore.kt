package com.hawkynt.mediadroid.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hawkynt.mediadroid.sources.Secrets
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The sources' passwords and keys, never in the state file: each is encrypted with an AES key that
 * lives in the Android Keystore and cannot leave it, and only the ciphertext is stored, by source id.
 */
class SecretStore private constructor(context: Context) : Secrets {

    private val file = File(context.filesDir, "source-secrets.json")
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val sealed: MutableMap<String, String> =
        runCatching { Json.decodeFromString(serializer, file.readText()).toMutableMap() }.getOrDefault(HashMap())

    @Synchronized
    override fun secretFor(sourceId: String): String? = sealed[sourceId]?.let { text ->
        runCatching {
            val bytes = Base64.decode(text, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Keeps [secret] for [sourceId]; null or empty forgets it. */
    @Synchronized
    fun save(sourceId: String, secret: String?) {
        if (secret.isNullOrEmpty()) {
            sealed.remove(sourceId)
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val box = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            sealed[sourceId] = Base64.encodeToString(box, Base64.NO_WRAP)
        }
        file.writeText(Json.encodeToString(serializer, sealed))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "mediadroid-source-secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256

        @Volatile
        private var instance: SecretStore? = null

        fun of(context: Context): SecretStore =
            instance ?: synchronized(this) {
                instance ?: SecretStore(context.applicationContext).also { instance = it }
            }
    }
}
