package com.gremier.gkeys.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Stores sensitive API keys in Android EncryptedSharedPreferences.
 *
 * Hardened so that a corrupted keyset (which can happen after the app signing
 * key changes or the Android keystore is reset) self-heals instead of crashing
 * the keyboard process.
 */
object SecureApiKeyStore {

    private const val TAG = "SecureApiKeyStore"
    private const val PREFS_NAME = "gkeys_secure_keys"
    private const val KEY_OPENAI = "openai_api_key"
    private const val KEY_ANTHROPIC = "anthropic_api_key"
    private const val KEY_GOOGLE_STT = "google_stt_api_key"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun buildPrefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun prefs(context: Context): SharedPreferences? {
        cachedPrefs?.let { return it }
        val appContext = context.applicationContext
        return try {
            buildPrefs(appContext).also { cachedPrefs = it }
        } catch (e: Throwable) {
            Log.e(TAG, "Encrypted key store unreadable — resetting", e)
            resetCorruptedStore(appContext)
            try {
                buildPrefs(appContext).also { cachedPrefs = it }
            } catch (e2: Throwable) {
                Log.e(TAG, "Encrypted key store still unreadable after reset", e2)
                null
            }
        }
    }

    /** Deletes the encrypted prefs file so it can be recreated from scratch. */
    private fun resetCorruptedStore(context: Context) {
        try {
            cachedPrefs = null
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(prefsDir, "$PREFS_NAME.xml").delete()
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to delete corrupted key store", e)
        }
    }

    fun getOpenAiKey(context: Context): String = readKey(context, KEY_OPENAI)

    fun getAnthropicKey(context: Context): String = readKey(context, KEY_ANTHROPIC)

    fun getGoogleSttKey(context: Context): String = readKey(context, KEY_GOOGLE_STT)

    private fun readKey(context: Context, key: String): String {
        return try {
            prefs(context)?.getString(key, "") ?: ""
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read key $key — resetting store", e)
            resetCorruptedStore(context)
            ""
        }
    }

    fun saveOpenAiKey(context: Context, key: String) = writeKey(context, KEY_OPENAI, key)

    fun saveAnthropicKey(context: Context, key: String) = writeKey(context, KEY_ANTHROPIC, key)

    fun saveGoogleSttKey(context: Context, key: String) = writeKey(context, KEY_GOOGLE_STT, key)

    private fun writeKey(context: Context, key: String, value: String) {
        try {
            prefs(context)?.edit()?.putString(key, value.trim())?.apply()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write key $key", e)
        }
    }
}
