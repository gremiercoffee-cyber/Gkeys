package com.gremier.gkeys.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores sensitive API keys in Android EncryptedSharedPreferences.
 */
object SecureApiKeyStore {

    private const val TAG = "SecureApiKeyStore"
    private const val PREFS_NAME = "gkeys_secure_keys"
    private const val KEY_OPENAI = "openai_api_key"
    private const val KEY_ANTHROPIC = "anthropic_api_key"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences? {
        cachedPrefs?.let { return it }
        return try {
            val appContext = context.applicationContext
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { cachedPrefs = it }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open encrypted key store", e)
            null
        }
    }

    fun getOpenAiKey(context: Context): String =
        prefs(context)?.getString(KEY_OPENAI, "") ?: ""

    fun getAnthropicKey(context: Context): String =
        prefs(context)?.getString(KEY_ANTHROPIC, "") ?: ""

    fun saveOpenAiKey(context: Context, key: String) {
        prefs(context)?.edit()?.putString(KEY_OPENAI, key.trim())?.apply()
    }

    fun saveAnthropicKey(context: Context, key: String) {
        prefs(context)?.edit()?.putString(KEY_ANTHROPIC, key.trim())?.apply()
    }
}
