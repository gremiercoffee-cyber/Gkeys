package com.gremier.gkeys.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.gremier.gkeys.ime.layout.KeyboardLayoutMetrics
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
    val POLISH_LEVEL = stringPreferencesKey("polish_level")
    val DEFAULT_LANGUAGE = stringPreferencesKey("default_language")
    val ONE_HANDED_MODE = stringPreferencesKey("one_handed_mode")
    val TOUCH_OFFSET_X = floatPreferencesKey("touch_offset_x")
    val TOUCH_OFFSET_Y = floatPreferencesKey("touch_offset_y")
    val TOUCH_OFFSET_SAMPLES = intPreferencesKey("touch_offset_samples")
    val ADAPTIVE_TOUCH_ENABLED = booleanPreferencesKey("adaptive_touch_enabled")
    val RIGHT_HANDED_MODE = booleanPreferencesKey("right_handed_mode")
    val VOICE_TRANSLATE_FROM = stringPreferencesKey("voice_translate_from")
    val VOICE_TRANSLATE_TO = stringPreferencesKey("voice_translate_to")
    val KEY_SIZE_PRESET = stringPreferencesKey("key_size_preset")
    val KEYBOARD_HEIGHT_DP = intPreferencesKey("keyboard_height_dp")
    val ONE_HANDED_WIDTH_FRACTION = floatPreferencesKey("one_handed_width_fraction")
    val DEEPGRAM_KEY = stringPreferencesKey("deepgram_key")
    private val LEGACY_GOOGLE_STT_KEY = stringPreferencesKey("google_stt_key")
    val VOICE_BUBBLE_ENABLED = booleanPreferencesKey("voice_bubble_enabled")
    val VOICE_BUBBLE_MODE_ACTIVE = booleanPreferencesKey("voice_bubble_mode_active")
    val DEFAULT_TO_VOICE_BUBBLE = booleanPreferencesKey("default_to_voice_bubble")
    val VOICE_BUBBLE_POS_X = intPreferencesKey("voice_bubble_pos_x")
    val VOICE_BUBBLE_POS_Y = intPreferencesKey("voice_bubble_pos_y")
    val AI_BAR_WAND_ENABLED = booleanPreferencesKey("ai_bar_wand_enabled")
    val AI_BAR_POLISH_BUTTON_ENABLED = booleanPreferencesKey("ai_bar_polish_button_enabled")
    val AI_BAR_LIVE_TRANSCRIBE_ENABLED = booleanPreferencesKey("ai_bar_live_transcribe_enabled")
    val SPEECH_PROFILE = stringPreferencesKey("speech_profile")
    val AI_INSTRUCTIONS = stringPreferencesKey("ai_instructions")
    val THEME_MODE = stringPreferencesKey("theme_mode")

    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"
    const val DEFAULT_THEME_MODE = THEME_DARK

    const val LANG_EN = "en"
    const val LANG_HE = "he"
    const val DEFAULT_VOICE_TRANSLATE_FROM = LANG_EN
    const val DEFAULT_VOICE_TRANSLATE_TO = LANG_HE

    const val KEY_SIZE_SMALL = "small"
    const val KEY_SIZE_DEFAULT = "default"
    const val KEY_SIZE_LARGE = "large"
    const val KEY_SIZE_EXTRA_LARGE = "extra_large"
    const val DEFAULT_KEY_SIZE_PRESET = KEY_SIZE_LARGE

    const val DEFAULT_KEYBOARD_HEIGHT_DP = 220
    const val MIN_KEYBOARD_HEIGHT_DP = 160
    const val MAX_KEYBOARD_HEIGHT_DP = 320

    const val DEFAULT_KEY_REPEAT_MS = 50
    const val DEFAULT_DELETE_SPEED_MS = 50
    const val DEFAULT_VIBRATION = true
    const val DEFAULT_VIBRATION_STRENGTH = 20
    const val DEFAULT_AUTO_POLISH = true
    const val DEFAULT_ADAPTIVE_TOUCH = true

    const val POLISH_FORMAL = "formal"
    const val POLISH_NATURAL = "natural"
    const val POLISH_RAW = "raw"
    const val DEFAULT_POLISH_LEVEL = POLISH_NATURAL
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

    fun deepgramKey(context: Context): Flow<String> = flow {
        migrateDeepgramKeyIfNeeded(context)
        emit(SecureApiKeyStore.getDeepgramKey(context))
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

    fun polishLevel(context: Context): Flow<String> =
        settingsStore(context).data.map { prefs ->
            prefs[POLISH_LEVEL] ?: when (prefs[AUTO_POLISH_ENABLED]) {
                false -> POLISH_RAW
                else -> DEFAULT_POLISH_LEVEL
            }
        }

    fun polishLevelLabel(level: String): String = when (level) {
        POLISH_FORMAL -> "Formal"
        POLISH_RAW -> "Raw"
        else -> "Natural"
    }

    fun polishLevelShortLabel(level: String): String = when (level) {
        POLISH_FORMAL -> "Frm"
        POLISH_RAW -> "Raw"
        else -> "Nat"
    }

    fun polishLevelLetter(level: String): String = when (level) {
        POLISH_FORMAL -> "F"
        POLISH_RAW -> "R"
        else -> "N"
    }

    /** Tap order for the toolbar polish-mode button (default first). */
    val polishLevelCycle: List<String> = listOf(POLISH_NATURAL, POLISH_FORMAL, POLISH_RAW)

    fun nextPolishLevel(level: String): String {
        val cycle = polishLevelCycle
        val idx = cycle.indexOf(level).takeIf { it >= 0 } ?: 0
        return cycle[(idx + 1) % cycle.size]
    }

    val polishLevels: List<String> = listOf(POLISH_FORMAL, POLISH_NATURAL, POLISH_RAW)

    fun defaultLanguage(context: Context): Flow<String> =
        settingsStore(context).data.map { it[DEFAULT_LANGUAGE] ?: DEFAULT_LANGUAGE_VAL }

    fun oneHandedMode(context: Context): Flow<String> =
        settingsStore(context).data.map { it[ONE_HANDED_MODE] ?: ONE_HANDED_OFF }

    fun rightHandedMode(context: Context): Flow<Boolean> =
        settingsStore(context).data.map { it[RIGHT_HANDED_MODE] ?: false }

    fun keySizePreset(context: Context): Flow<String> =
        settingsStore(context).data.map { it[KEY_SIZE_PRESET] ?: KEY_SIZE_DEFAULT }

    fun keyboardHeightDp(context: Context): Flow<Int> =
        settingsStore(context).data.map {
            (it[KEYBOARD_HEIGHT_DP] ?: DEFAULT_KEYBOARD_HEIGHT_DP)
                .coerceIn(MIN_KEYBOARD_HEIGHT_DP, MAX_KEYBOARD_HEIGHT_DP)
        }

    fun oneHandedWidthFraction(context: Context): Flow<Float> =
        settingsStore(context).data.map { prefs ->
            (prefs[ONE_HANDED_WIDTH_FRACTION] ?: KeyboardLayoutMetrics.DEFAULT_ONE_HANDED_KEY_AREA_FRACTION)
                .coerceIn(
                    KeyboardLayoutMetrics.MIN_ONE_HANDED_KEY_AREA_FRACTION,
                    KeyboardLayoutMetrics.MAX_ONE_HANDED_KEY_AREA_FRACTION
                )
        }

    const val DEFAULT_VOICE_BUBBLE_ENABLED = true
    const val DEFAULT_AI_BAR_FEATURE_ENABLED = true

    fun aiBarWandEnabled(context: Context): Flow<Boolean> =
        settingsStore(context).data.map { it[AI_BAR_WAND_ENABLED] ?: DEFAULT_AI_BAR_FEATURE_ENABLED }

    fun aiBarPolishButtonEnabled(context: Context): Flow<Boolean> =
        settingsStore(context).data.map { it[AI_BAR_POLISH_BUTTON_ENABLED] ?: DEFAULT_AI_BAR_FEATURE_ENABLED }

    fun aiBarLiveTranscribeEnabled(context: Context): Flow<Boolean> =
        settingsStore(context).data.map { it[AI_BAR_LIVE_TRANSCRIBE_ENABLED] ?: DEFAULT_AI_BAR_FEATURE_ENABLED }

    fun voiceBubbleEnabled(context: Context): Flow<Boolean> =
        settingsStore(context).data.map { it[VOICE_BUBBLE_ENABLED] ?: DEFAULT_VOICE_BUBBLE_ENABLED }

    /** True only while the user is actively in voice-bubble mode (not the same as "default to bubble"). */
    fun voiceBubbleModeActive(context: Context): Flow<Boolean> =
        settingsStore(context).data.map { prefs ->
            val enabled = prefs[VOICE_BUBBLE_ENABLED] ?: DEFAULT_VOICE_BUBBLE_ENABLED
            if (!enabled) return@map false
            prefs[VOICE_BUBBLE_MODE_ACTIVE] ?: false
        }

    fun defaultToVoiceBubble(context: Context): Flow<Boolean> =
        settingsStore(context).data.map { it[DEFAULT_TO_VOICE_BUBBLE] ?: false }

    fun adaptiveTouchEnabled(context: Context): Flow<Boolean> =
        settingsStore(context).data.map { it[ADAPTIVE_TOUCH_ENABLED] ?: DEFAULT_ADAPTIVE_TOUCH }

    fun speechProfile(context: Context): Flow<String> =
        settingsStore(context).data.map { it[SPEECH_PROFILE] ?: "" }

    fun aiInstructions(context: Context): Flow<String> =
        settingsStore(context).data.map { it[AI_INSTRUCTIONS] ?: "" }

    fun voiceTranslateFrom(context: Context): Flow<String> =
        settingsStore(context).data.map { it[VOICE_TRANSLATE_FROM] ?: DEFAULT_VOICE_TRANSLATE_FROM }

    fun voiceTranslateTo(context: Context): Flow<String> =
        settingsStore(context).data.map { it[VOICE_TRANSLATE_TO] ?: DEFAULT_VOICE_TRANSLATE_TO }

    fun languageDisplayName(code: String): String = when (code) {
        LANG_HE -> "Hebrew"
        LANG_EN -> "English"
        else -> code
    }

    val voiceLanguageCodes: List<String> = listOf(LANG_EN, LANG_HE)

    suspend fun saveOpenAiKey(context: Context, key: String) {
        SecureApiKeyStore.saveOpenAiKey(context, key)
        settingsStore(context).edit { it.remove(OPENAI_KEY) }
    }

    suspend fun saveAnthropicKey(context: Context, key: String) {
        SecureApiKeyStore.saveAnthropicKey(context, key)
        settingsStore(context).edit { it.remove(ANTHROPIC_KEY) }
    }

    suspend fun saveDeepgramKey(context: Context, key: String) {
        SecureApiKeyStore.saveDeepgramKey(context, key)
        settingsStore(context).edit {
            it.remove(DEEPGRAM_KEY)
            it.remove(LEGACY_GOOGLE_STT_KEY)
        }
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

    private suspend fun migrateDeepgramKeyIfNeeded(context: Context) {
        if (SecureApiKeyStore.getDeepgramKey(context).isNotBlank()) return
        val legacyDeepgram = settingsStore(context).data.first()[DEEPGRAM_KEY] ?: ""
        if (legacyDeepgram.isNotBlank()) {
            SecureApiKeyStore.saveDeepgramKey(context, legacyDeepgram)
            settingsStore(context).edit { it.remove(DEEPGRAM_KEY) }
            return
        }
        val legacyGoogle = settingsStore(context).data.first()[LEGACY_GOOGLE_STT_KEY] ?: ""
        if (legacyGoogle.isNotBlank()) {
            SecureApiKeyStore.saveDeepgramKey(context, legacyGoogle)
            settingsStore(context).edit { it.remove(LEGACY_GOOGLE_STT_KEY) }
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

    suspend fun savePolishLevel(context: Context, level: String) {
        val normalized = when (level) {
            POLISH_FORMAL, POLISH_NATURAL, POLISH_RAW -> level
            else -> DEFAULT_POLISH_LEVEL
        }
        settingsStore(context).edit { it[POLISH_LEVEL] = normalized }
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

    suspend fun saveKeyboardHeightDp(context: Context, heightDp: Int) {
        settingsStore(context).edit {
            it[KEYBOARD_HEIGHT_DP] = heightDp.coerceIn(MIN_KEYBOARD_HEIGHT_DP, MAX_KEYBOARD_HEIGHT_DP)
        }
    }

    suspend fun saveOneHandedWidthFraction(context: Context, fraction: Float) {
        settingsStore(context).edit {
            it[ONE_HANDED_WIDTH_FRACTION] = fraction.coerceIn(
                KeyboardLayoutMetrics.MIN_ONE_HANDED_KEY_AREA_FRACTION,
                KeyboardLayoutMetrics.MAX_ONE_HANDED_KEY_AREA_FRACTION
            )
        }
    }

    suspend fun saveVoiceBubbleEnabled(context: Context, enabled: Boolean) {
        settingsStore(context).edit {
            it[VOICE_BUBBLE_ENABLED] = enabled
            if (!enabled) {
                it[VOICE_BUBBLE_MODE_ACTIVE] = false
            }
        }
    }

    suspend fun saveVoiceBubbleModeActive(context: Context, active: Boolean) {
        settingsStore(context).edit { it[VOICE_BUBBLE_MODE_ACTIVE] = active }
    }

    suspend fun saveDefaultToVoiceBubble(context: Context, enabled: Boolean) {
        settingsStore(context).edit { it[DEFAULT_TO_VOICE_BUBBLE] = enabled }
    }

    fun voiceBubblePosition(context: Context): Flow<Pair<Int, Int>?> =
        settingsStore(context).data.map { prefs ->
            val x = prefs[VOICE_BUBBLE_POS_X] ?: return@map null
            val y = prefs[VOICE_BUBBLE_POS_Y] ?: return@map null
            if (x < 0 || y < 0) null else x to y
        }

    suspend fun saveVoiceBubblePosition(context: Context, x: Int, y: Int) {
        settingsStore(context).edit {
            it[VOICE_BUBBLE_POS_X] = x
            it[VOICE_BUBBLE_POS_Y] = y
        }
    }

    suspend fun loadVoiceBubblePosition(context: Context): Pair<Int, Int>? =
        voiceBubblePosition(context).first()

    suspend fun saveAdaptiveTouchEnabled(context: Context, enabled: Boolean) {
        settingsStore(context).edit { it[ADAPTIVE_TOUCH_ENABLED] = enabled }
    }

    suspend fun saveVoiceTranslateFrom(context: Context, lang: String) {
        settingsStore(context).edit { it[VOICE_TRANSLATE_FROM] = lang }
    }

    suspend fun saveVoiceTranslateTo(context: Context, lang: String) {
        settingsStore(context).edit { it[VOICE_TRANSLATE_TO] = lang }
    }

    suspend fun saveAiBarWandEnabled(context: Context, enabled: Boolean) {
        settingsStore(context).edit { it[AI_BAR_WAND_ENABLED] = enabled }
    }

    suspend fun saveAiBarPolishButtonEnabled(context: Context, enabled: Boolean) {
        settingsStore(context).edit { it[AI_BAR_POLISH_BUTTON_ENABLED] = enabled }
    }

    suspend fun saveAiBarLiveTranscribeEnabled(context: Context, enabled: Boolean) {
        settingsStore(context).edit { it[AI_BAR_LIVE_TRANSCRIBE_ENABLED] = enabled }
    }

    suspend fun saveSpeechProfile(context: Context, profile: String) {
        settingsStore(context).edit { it[SPEECH_PROFILE] = profile.trim() }
    }

    suspend fun saveAiInstructions(context: Context, instructions: String) {
        settingsStore(context).edit { it[AI_INSTRUCTIONS] = instructions.trim() }
    }

    fun themeMode(context: Context): Flow<String> =
        settingsStore(context).data.map { it[THEME_MODE] ?: DEFAULT_THEME_MODE }

    suspend fun isDarkTheme(context: Context): Boolean =
        isDarkThemeMode(themeMode(context).first())

    fun isDarkThemeMode(mode: String): Boolean = mode != THEME_LIGHT

    suspend fun saveThemeMode(context: Context, mode: String) {
        settingsStore(context).edit { it[THEME_MODE] = mode }
    }
}
