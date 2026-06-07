package com.gremier.gkeys.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gkeys_settings")

object GkeysSettings {

    val OPENAI_KEY = stringPreferencesKey("openai_key")
    val ANTHROPIC_KEY = stringPreferencesKey("anthropic_key")
    val KEY_REPEAT_SPEED = intPreferencesKey("key_repeat_speed")
    val DELETE_SPEED = intPreferencesKey("delete_speed")
    val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
    val AUTO_POLISH_ENABLED = booleanPreferencesKey("auto_polish_enabled")
    val DEFAULT_LANGUAGE = stringPreferencesKey("default_language")
    val ONE_HANDED_MODE = stringPreferencesKey("one_handed_mode")

    const val DEFAULT_KEY_REPEAT_MS = 50
    const val DEFAULT_DELETE_SPEED_MS = 50
    const val DEFAULT_VIBRATION = true
    const val DEFAULT_AUTO_POLISH = true
    const val DEFAULT_LANGUAGE_VAL = "en"
    const val ONE_HANDED_OFF = "off"
    const val ONE_HANDED_LEFT = "left"
    const val ONE_HANDED_RIGHT = "right"

    fun openAiKey(context: Context): Flow<String> =
        context.dataStore.data.map { it[OPENAI_KEY] ?: "" }

    fun anthropicKey(context: Context): Flow<String> =
        context.dataStore.data.map { it[ANTHROPIC_KEY] ?: "" }

    fun keyRepeatSpeed(context: Context): Flow<Int> =
        context.dataStore.data.map { it[KEY_REPEAT_SPEED] ?: DEFAULT_KEY_REPEAT_MS }

    fun deleteSpeed(context: Context): Flow<Int> =
        context.dataStore.data.map { it[DELETE_SPEED] ?: DEFAULT_DELETE_SPEED_MS }

    fun vibrationEnabled(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[VIBRATION_ENABLED] ?: DEFAULT_VIBRATION }

    fun autoPolishEnabled(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[AUTO_POLISH_ENABLED] ?: DEFAULT_AUTO_POLISH }

    fun defaultLanguage(context: Context): Flow<String> =
        context.dataStore.data.map { it[DEFAULT_LANGUAGE] ?: DEFAULT_LANGUAGE_VAL }

    fun oneHandedMode(context: Context): Flow<String> =
        context.dataStore.data.map { it[ONE_HANDED_MODE] ?: ONE_HANDED_OFF }

    suspend fun saveOpenAiKey(context: Context, key: String) {
        context.dataStore.edit { it[OPENAI_KEY] = key }
    }

    suspend fun saveAnthropicKey(context: Context, key: String) {
        context.dataStore.edit { it[ANTHROPIC_KEY] = key }
    }

    suspend fun saveKeyRepeatSpeed(context: Context, ms: Int) {
        context.dataStore.edit { it[KEY_REPEAT_SPEED] = ms }
    }

    suspend fun saveDeleteSpeed(context: Context, ms: Int) {
        context.dataStore.edit { it[DELETE_SPEED] = ms }
    }

    suspend fun saveVibration(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[VIBRATION_ENABLED] = enabled }
    }

    suspend fun saveAutoPolish(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[AUTO_POLISH_ENABLED] = enabled }
    }

    suspend fun saveDefaultLanguage(context: Context, lang: String) {
        context.dataStore.edit { it[DEFAULT_LANGUAGE] = lang }
    }

    suspend fun saveOneHandedMode(context: Context, mode: String) {
        context.dataStore.edit { it[ONE_HANDED_MODE] = mode }
    }
}
