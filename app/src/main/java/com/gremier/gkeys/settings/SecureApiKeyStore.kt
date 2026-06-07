package com.gremier.gkeys.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores sensitive API keys in Android EncryptedSharedPreferences.
 */
object SecureApiKeyStore {

    private const val PREFS_NAME = "gkeys_secure_keys"
    private const val KEY_OPENAI = "openai_api_key"
    private const val KEY_ANTHROPIC = "anthropic_api_key"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getOpenAiKey(context: Context): String =
        prefs(context).getString(KEY_OPENAI, "") ?: ""

    fun getAnthropicKey(context: Context): String =
        prefs(context).getString(KEY_ANTHROPIC, "") ?: ""

    fun saveOpenAiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_OPENAI, key.trim()).apply()
    }

    fun saveAnthropicKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_ANTHROPIC, key.trim()).apply()
    }
}
