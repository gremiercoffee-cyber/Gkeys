package com.gremier.gkeys.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

val Context.gkeysDataStore: DataStore<Preferences> by preferencesDataStore(name = "gkeys_settings")

private fun settingsStore(context: Context): DataStore<Preferences> =
    context.applicationContext.gkeysDataStore

object GkeysSettings {

    val OPENAI_KEY = stringPreferencesKey("openai_key")
    val ANTHROPIC_KEY = stringPreferencesKey("anthropic_key")
    val KEY_REPEAT_SPEED = intPreferencesKey("key_repeat_speed")
    val DELETE_SPEED = intPreferencesKey("delete_speed")
    val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
    val VIBRATION_STRENGTH = intPreferencesKey("vibration_strength")
    val AUTO_POLISH_ENABLED = booleanPreferencesKey("auto_polish_enabled")
    val DEFAULT_LANGUAGE = stringPreferencesKey("default_language")
    val ONE_HANDED_MODE = stringPreferencesKey("one_handed_mode")
    val TOUCH_OFFSET_X = floatPreferencesKey("touch_offset_x")
    val TOUCH_OFFSET_Y = floatPreferencesKey("touch_offset_y")
    val TOUCH_OFFSET_SAMPLES = intPreferencesKey("touch_offset_samples")
    val RIGHT_HANDED_MODE = booleanPreferencesKey("right_handed_mode")
    val KEY_SIZE_PRESET = stringPreferencesKey("key_size_preset")

    const val KEY_SIZE_SMALL = "small"
    const val KEY_SIZE_DEFAULT = "default"
    const val KEY_SIZE_LARGE = "large"
    const val KEY_SIZE_EXTRA_LARGE = "extra_large"
    const val DEFAULT_KEY_SIZE_PRESET = KEY_SIZE_LARGE

    const val DEFAULT_KEY_REPEAT_MS = 50
    const val DEFAULT_DELETE_SPEED_MS = 50
    const val DEFAULT_VIBRATION = true
    const val DEFAULT_VIBRATION_STRENGTH = 20
    const val DEFAULT_AUTO_POLISH = true
    const val DEFAULT_LANGUAGE_VAL = "en"
    const val ONE_HANDED_OFF = "off"
    const val ONE_HANDED_LEFT = "left"
    const val ONE_HANDED_RIGHT = "right"

    fun openAiKey(context: Context): Flow<String> = flow {
        migrateOpenAiKeyIfNeeded(context)
        emit(SecureApiKeyStore.getOpenAiKey(context))
    }

    fun anthropicKey(context: Context): Flow<String> = flow {
        migrateAnthropicKeyIfNeeded(context)
        emit(SecureApiKeyStore.getAnthropicKey(context))
    }

    fun keyRepeatSpeed(context: Context): Flow<Int> =
        settingsStore(context).data.map { it[KEY_REPEAT_SPEED] ?: DEFAULT_KEY_REPEAT_MS }

    fun deleteSpeed(context: Context): Flow<Int> =
        settingsStore(context).data.map { it[DELETE_SPEED] ?: DEFAULT_DELETE_SPEED_MS }

    fun vibrationEnabled(context: Context): Flow<Boolean> =
        settingsStore(context).data.map { it[VIBRATION_ENABLED] ?: DEFAULT_VIBRATION }

    fun vibrationStrength(context: Context): Flow<Int> =
        settingsStore(context).data.map { it[VIBRATION_STRENGTH] ?: DEFAULT_VIBRATION_STRENGTH }

    fun autoPolishEnabled(context: Context): Flow<Boolean> =
        settingsStore(context).data.map { it[AUTO_POLISH_ENABLED] ?: DEFAULT_AUTO_POLISH }

    fun defaultLanguage(context: Context): Flow<String> =
        settingsStore(context).data.map { it[DEFAULT_LANGUAGE] ?: DEFAULT_LANGUAGE_VAL }

    fun oneHandedMode(context: Context): Flow<String> =
        settingsStore(context).data.map { it[ONE_HANDED_MODE] ?: ONE_HANDED_OFF }

    fun rightHandedMode(context: Context): Flow<Boolean> =
        settingsStore(context).data.map { it[RIGHT_HANDED_MODE] ?: false }

    fun keySizePreset(context: Context): Flow<String> =
        settingsStore(context).data.map { it[KEY_SIZE_PRESET] ?: KEY_SIZE_DEFAULT }

    suspend fun saveOpenAiKey(context: Context, key: String) {
        SecureApiKeyStore.saveOpenAiKey(context, key)
        settingsStore(context).edit { it.remove(OPENAI_KEY) }
    }

    suspend fun saveAnthropicKey(context: Context, key: String) {
        SecureApiKeyStore.saveAnthropicKey(context, key)
        settingsStore(context).edit { it.remove(ANTHROPIC_KEY) }
    }

    private suspend fun migrateOpenAiKeyIfNeeded(context: Context) {
        if (SecureApiKeyStore.getOpenAiKey(context).isNotBlank()) return
        val legacy = settingsStore(context).data.first()[OPENAI_KEY] ?: ""
        if (legacy.isNotBlank()) {
            SecureApiKeyStore.saveOpenAiKey(context, legacy)
            settingsStore(context).edit { it.remove(OPENAI_KEY) }
        }
    }

    private suspend fun migrateAnthropicKeyIfNeeded(context: Context) {
        if (SecureApiKeyStore.getAnthropicKey(context).isNotBlank()) return
        val legacy = settingsStore(context).data.first()[ANTHROPIC_KEY] ?: ""
        if (legacy.isNotBlank()) {
            SecureApiKeyStore.saveAnthropicKey(context, legacy)
            settingsStore(context).edit { it.remove(ANTHROPIC_KEY) }
        }
    }

    suspend fun saveKeyRepeatSpeed(context: Context, ms: Int) {
        settingsStore(context).edit { it[KEY_REPEAT_SPEED] = ms }
    }

    suspend fun saveDeleteSpeed(context: Context, ms: Int) {
        settingsStore(context).edit { it[DELETE_SPEED] = ms }
    }

    suspend fun saveVibration(context: Context, enabled: Boolean) {
        settingsStore(context).edit { it[VIBRATION_ENABLED] = enabled }
    }

    suspend fun saveVibrationStrength(context: Context, strength: Int) {
        settingsStore(context).edit { it[VIBRATION_STRENGTH] = strength.coerceIn(0, 100) }
    }

    suspend fun saveAutoPolish(context: Context, enabled: Boolean) {
        settingsStore(context).edit { it[AUTO_POLISH_ENABLED] = enabled }
    }

    suspend fun saveDefaultLanguage(context: Context, lang: String) {
        settingsStore(context).edit { it[DEFAULT_LANGUAGE] = lang }
    }

    suspend fun saveOneHandedMode(context: Context, mode: String) {
        settingsStore(context).edit { it[ONE_HANDED_MODE] = mode }
    }

    suspend fun saveRightHandedMode(context: Context, enabled: Boolean) {
        settingsStore(context).edit { prefs ->
            prefs[RIGHT_HANDED_MODE] = enabled
            if (enabled) {
                val current = prefs[KEY_SIZE_PRESET] ?: KEY_SIZE_DEFAULT
                if (current == KEY_SIZE_DEFAULT) {
                    prefs[KEY_SIZE_PRESET] = DEFAULT_KEY_SIZE_PRESET
                }
            }
        }
    }

    suspend fun saveKeySizePreset(context: Context, preset: String) {
        settingsStore(context).edit { it[KEY_SIZE_PRESET] = preset }
    }
}
