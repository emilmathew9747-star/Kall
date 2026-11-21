package com.emil.jarvis

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM encryption with a key that never leaves the Android Keystore. */
class KeyVault {
    private val alias = "jarvis_api_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? = try {
        val b = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12))
    } catch (e: Exception) {
        null
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val vault = KeyVault()

    /** The Gemini API key is stored encrypted; it is never in the APK. */
    var apiKey: String
        get() = sp.getString("k", null)?.let { vault.decrypt(it) } ?: ""
        set(v) = sp.edit().putString("k", if (v.isBlank()) null else vault.encrypt(v.trim())).apply()

    /** Editable on purpose: Google renames Live models often. Check ai.google.dev/gemini-api/docs/live */
    var model: String
        get() = sp.getString("model", DEFAULT_MODEL)!!
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var voice: String
        get() = sp.getString("voice", "Aoede")!!
        set(v) = sp.edit().putString("voice", v).apply()

    var talkOver: Boolean
        get() = sp.getBoolean("talkOver", true)
        set(v) = sp.edit().putBoolean("talkOver", v).apply()

    var localFirst: Boolean
        get() = sp.getBoolean("localFirst", true)
        set(v) = sp.edit().putBoolean("localFirst", v).apply()

    companion object {
        const val DEFAULT_MODEL = "models/gemini-2.5-flash-native-audio-preview-09-2025"
        val VOICES = listOf("Aoede", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Zephyr")
    }
}
