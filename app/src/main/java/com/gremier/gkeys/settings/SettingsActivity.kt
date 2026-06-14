package com.gremier.gkeys.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gremier.gkeys.BuildConfig
import com.gremier.gkeys.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REQUEST_MIC_PERMISSION = "request_mic_permission"
        const val EXTRA_REQUEST_OVERLAY_PERMISSION = "request_overlay_permission"
        const val EXTRA_SHOW_OVERLAY_RESTRICTED_HELP = "show_overlay_restricted_help"
    }

    private lateinit var etOpenAiKey: TextInputEditText
    private lateinit var etAnthropicKey: TextInputEditText
    private lateinit var etDeepgramKey: TextInputEditText
    private lateinit var etSpeechProfile: TextInputEditText
    private lateinit var etAiInstructions: TextInputEditText
    private lateinit var sliderKeyRepeat: Slider
    private lateinit var sliderDeleteSpeed: Slider
    private lateinit var switchVibration: SwitchMaterial
    private lateinit var sliderVibration: Slider
    private lateinit var radioPolishLevel: RadioGroup
    private lateinit var spinnerVoiceFrom: Spinner
    private lateinit var spinnerVoiceTo: Spinner
    private lateinit var switchDefaultLang: SwitchMaterial
    private lateinit var switchRightHanded: SwitchMaterial
    private lateinit var sliderKeySize: Slider
    private lateinit var sliderKeyboardHeight: Slider
    private lateinit var tvKeySizeLabel: TextView
    private lateinit var tvKeyboardHeightLabel: TextView
    private lateinit var tvAppVersion: TextView
    private lateinit var btnMicPermission: MaterialButton
    private lateinit var btnPhotoPermission: MaterialButton
    private lateinit var btnOverlayPermission: MaterialButton
    private lateinit var switchVoiceBubbleEnabled: SwitchMaterial
    private lateinit var radioAiBarVoiceInput: RadioGroup
    private lateinit var rvAiBarOrder: FullyExpandedRecyclerView
    private lateinit var switchDefaultVoiceBubble: SwitchMaterial
    private lateinit var voiceBubbleDefaultRow: android.view.View
    private lateinit var switchAdaptiveTouch: SwitchMaterial
    private lateinit var switchExperimentalSwipeTyping: SwitchMaterial
    private lateinit var switchDailyAiLearning: SwitchMaterial
    private lateinit var switchRawTextSamples: SwitchMaterial
    private lateinit var tvAdaptiveTouchStats: TextView
    private lateinit var tvPredictionDebug: TextView
    private lateinit var btnResetAdaptiveTouch: MaterialButton
    private lateinit var radioOneHanded: RadioGroup
    private lateinit var btnEnableKeyboard: MaterialButton
    private lateinit var cardCrash: com.google.android.material.card.MaterialCardView
    private lateinit var tvCrashLog: TextView
    private lateinit var btnCopyCrash: MaterialButton
    private lateinit var btnClearCrash: MaterialButton
    private var crashScreenShown = false
    private var overlayRestrictedStep = 0
    private var settingsLoaded = false
    private var suppressPolishAutoSave = false
    private var suppressVoiceInputAutoSave = false
    private var suppressAiBarOrderAutoSave = false
    private lateinit var orderAdapter: AiBarOrderDragAdapter
    private var suppressThemeAutoSave = false
    private lateinit var radioTheme: RadioGroup

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateMicPermissionButton()
        if (granted) {
            Toast.makeText(this, "Microphone enabled for Gkeys", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice dictation", Toast.LENGTH_LONG).show()
        }
    }

    private val photoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updatePhotoPermissionButton()
        if (granted) {
            Toast.makeText(this, "Photos enabled — screenshots will appear in the clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Photo permission is needed to show screenshots in the clipboard", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_settings)
            setupSettingsTabs()
            bindViews()
            loadSettings()
            setupListeners()
            checkKeyboardEnabled()
            updateMicPermissionButton()
            updatePhotoPermissionButton()
            tvAppVersion.text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setupCrashCardListeners()
            refreshCrashCard()
            if (intent.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false)) {
                requestMicPermissionIfNeeded()
            }
            if (intent.getBooleanExtra(EXTRA_REQUEST_OVERLAY_PERMISSION, false)) {
                if (intent.getBooleanExtra(EXTRA_SHOW_OVERLAY_RESTRICTED_HELP, false)) {
                    overlayRestrictedStep = 0
                    requestOverlayPermissionIfNeeded()
                } else {
                    requestOverlayPermissionIfNeeded()
                }
            }
        } catch (e: Throwable) {
            com.gremier.gkeys.diag.CrashLogger.record(this, e)
            crashScreenShown = true
            showCrashScreen(buildString {
                appendLine("Settings failed to open:")
                appendLine(e.toString())
            })
        }
    }

    /** Minimal, dependency-free screen that always renders, even if the normal UI can't. */
    private fun showCrashScreen(crashText: String) {
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(0xFF0F0F1A.toInt())
            setPadding(32, 48, 32, 48)
        }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val title = TextView(this).apply {
            text = "Gkeys — last crash"
            setTextColor(0xFFFF8A80.toInt())
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        val body = TextView(this).apply {
            text = crashText
            setTextColor(0xFFE5E7EB.toInt())
            textSize = 12f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val copyBtn = android.widget.Button(this).apply {
            text = "Copy crash log"
            setOnClickListener {
                try {
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Gkeys crash", crashText))
                    Toast.makeText(this@SettingsActivity, "Copied", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {}
            }
        }
        val clearBtn = android.widget.Button(this).apply {
            text = "Clear & open settings"
            setOnClickListener {
                try { com.gremier.gkeys.diag.CrashLogger.clear(this@SettingsActivity) } catch (_: Throwable) {}
                recreate()
            }
        }
        column.addView(title)
        column.addView(copyBtn)
        column.addView(clearBtn)
        column.addView(body)
        scroll.addView(column)
        setContentView(scroll)
    }

    private fun setupCrashCardListeners() {
        btnCopyCrash.setOnClickListener {
            val crash = tvCrashLog.text?.toString().orEmpty()
            if (crash.isNotBlank()) {
                val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("Gkeys crash", crash))
                Toast.makeText(this, "Crash log copied", Toast.LENGTH_SHORT).show()
            }
        }
        btnClearCrash.setOnClickListener {
            com.gremier.gkeys.diag.CrashLogger.clear(this)
            refreshCrashCard()
        }
    }

    override fun onPause() {
        if (settingsLoaded && !crashScreenShown) {
            lifecycleScope.launch {
                try {
                    GkeysSettings.saveOpenAiKey(this@SettingsActivity, etOpenAiKey.text.toString().trim())
                    GkeysSettings.saveAnthropicKey(this@SettingsActivity, etAnthropicKey.text.toString().trim())
                    GkeysSettings.saveDeepgramKey(this@SettingsActivity, etDeepgramKey.text.toString().trim())
                    GkeysSettings.saveSpeechProfile(this@SettingsActivity, etSpeechProfile.text.toString())
                    GkeysSettings.saveAiInstructions(this@SettingsActivity, etAiInstructions.text.toString())
                } catch (e: Exception) {
                    android.util.Log.e("SettingsActivity", "onPause save failed", e)
                }
            }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // When the crash screen is showing, the normal views were never bound.
        if (crashScreenShown) return
        try {
            updateMicPermissionButton()
            updatePhotoPermissionButton()
            updateOverlayPermissionButton()
            refreshAdaptiveTouchStats()
            checkKeyboardEnabled()
            refreshCrashCard()
            refreshPolishLevelRadio()
        } catch (e: Throwable) {
            android.util.Log.e("SettingsActivity", "onResume failed", e)
        }
    }

    private fun setupSettingsTabs() {
        val tabs = findViewById<TabLayout>(R.id.settings_tabs)
        val panels = listOf(
            findViewById<android.view.View>(R.id.panel_setup),
            findViewById(R.id.panel_ai),
            findViewById(R.id.panel_keyboard),
            findViewById(R.id.panel_toolbar)
        )
        listOf("Setup", "AI", "Keyboard", "Toolbar").forEach { label ->
            tabs.addTab(tabs.newTab().setText(label))
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                panels.forEachIndexed { index, panel ->
                    panel.visibility = if (index == tab.position) {
                        android.view.View.VISIBLE
                    } else {
                        android.view.View.GONE
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun bindViews() {
        etOpenAiKey = findViewById(R.id.et_openai_key)
        etAnthropicKey = findViewById(R.id.et_anthropic_key)
        etDeepgramKey = findViewById(R.id.et_deepgram_key)
        etSpeechProfile = findViewById(R.id.et_speech_profile)
        enableNestedScrollInParent(etSpeechProfile)
        etAiInstructions = findViewById(R.id.et_ai_instructions)
        enableNestedScrollInParent(etAiInstructions)
        sliderKeyRepeat = findViewById(R.id.slider_key_repeat)
        sliderDeleteSpeed = findViewById(R.id.slider_delete_speed)
        switchVibration = findViewById(R.id.switch_vibration)
        sliderVibration = findViewById(R.id.slider_vibration)
        radioPolishLevel = findViewById(R.id.radio_polish_level)
        spinnerVoiceFrom = findViewById(R.id.spinner_voice_from)
        spinnerVoiceTo = findViewById(R.id.spinner_voice_to)
        setupVoiceLanguageSpinners()
        switchDefaultLang = findViewById(R.id.switch_default_lang)
        switchRightHanded = findViewById(R.id.switch_right_handed)
        sliderKeySize = findViewById(R.id.slider_key_size)
        sliderKeyboardHeight = findViewById(R.id.slider_keyboard_height)
        tvKeySizeLabel = findViewById(R.id.tv_key_size_label)
        tvKeyboardHeightLabel = findViewById(R.id.tv_keyboard_height_label)
        tvAppVersion = findViewById(R.id.tv_app_version)
        btnMicPermission = findViewById(R.id.btn_mic_permission)
        btnPhotoPermission = findViewById(R.id.btn_photo_permission)
        btnOverlayPermission = findViewById(R.id.btn_overlay_permission)
        switchVoiceBubbleEnabled = findViewById(R.id.switch_voice_bubble_enabled)
        radioAiBarVoiceInput = findViewById(R.id.radio_ai_bar_voice_input)
        rvAiBarOrder = findViewById(R.id.rv_ai_bar_order)
        setupAiBarOrderLists()
        switchDefaultVoiceBubble = findViewById(R.id.switch_default_voice_bubble)
        voiceBubbleDefaultRow = findViewById(R.id.voice_bubble_default_row)
        switchAdaptiveTouch = findViewById(R.id.switch_adaptive_touch)
        switchExperimentalSwipeTyping = findViewById(R.id.switch_experimental_swipe_typing)
        switchDailyAiLearning = findViewById(R.id.switch_daily_ai_learning)
        switchRawTextSamples = findViewById(R.id.switch_raw_text_samples)
        tvAdaptiveTouchStats = findViewById(R.id.tv_adaptive_touch_stats)
        tvPredictionDebug = findViewById(R.id.tv_prediction_debug)
        btnResetAdaptiveTouch = findViewById(R.id.btn_reset_adaptive_touch)
        radioOneHanded = findViewById(R.id.radio_one_handed)
        btnEnableKeyboard = findViewById(R.id.btn_enable_keyboard)
        cardCrash = findViewById(R.id.card_crash)
        tvCrashLog = findViewById(R.id.tv_crash_log)
        btnCopyCrash = findViewById(R.id.btn_copy_crash)
        btnClearCrash = findViewById(R.id.btn_clear_crash)
        radioTheme = findViewById(R.id.radio_theme)
    }

    private fun refreshCrashCard() {
        val crash = com.gremier.gkeys.diag.CrashLogger.lastCrash(this)
        if (crash.isNullOrBlank()) {
            cardCrash.visibility = android.view.View.GONE
        } else {
            cardCrash.visibility = android.view.View.VISIBLE
            tvCrashLog.text = crash
        }
    }

    private fun loadSettings() {
        lifecycleScope.launch {
          try {
            etOpenAiKey.setText(GkeysSettings.openAiKey(this@SettingsActivity).first())
            etAnthropicKey.setText(GkeysSettings.anthropicKey(this@SettingsActivity).first())
            etDeepgramKey.setText(GkeysSettings.deepgramKey(this@SettingsActivity).first())
            etSpeechProfile.setText(GkeysSettings.speechProfile(this@SettingsActivity).first())
            etAiInstructions.setText(GkeysSettings.aiInstructions(this@SettingsActivity).first())
            sliderKeyRepeat.value = clampToSlider(sliderKeyRepeat, GkeysSettings.keyRepeatSpeed(this@SettingsActivity).first().toFloat())
            sliderDeleteSpeed.value = clampToSlider(sliderDeleteSpeed, GkeysSettings.deleteSpeed(this@SettingsActivity).first().toFloat())
            switchVibration.isChecked = GkeysSettings.vibrationEnabled(this@SettingsActivity).first()
            sliderVibration.value = clampToSlider(sliderVibration, GkeysSettings.vibrationStrength(this@SettingsActivity).first().toFloat())
            sliderVibration.isEnabled = switchVibration.isChecked
            when (GkeysSettings.polishLevel(this@SettingsActivity).first()) {
                GkeysSettings.POLISH_FORMAL -> selectPolishRadio(R.id.radio_polish_formal)
                GkeysSettings.POLISH_RAW -> selectPolishRadio(R.id.radio_polish_raw)
                else -> selectPolishRadio(R.id.radio_polish_natural)
            }
            selectSpinnerLanguage(spinnerVoiceFrom, GkeysSettings.voiceTranslateFrom(this@SettingsActivity).first())
            selectSpinnerLanguage(spinnerVoiceTo, GkeysSettings.voiceTranslateTo(this@SettingsActivity).first())
            switchDefaultLang.isChecked = GkeysSettings.defaultLanguage(this@SettingsActivity).first() == "he"
            switchRightHanded.isChecked = GkeysSettings.rightHandedMode(this@SettingsActivity).first()
            sliderKeySize.value = clampToSlider(sliderKeySize, presetToSlider(GkeysSettings.keySizePreset(this@SettingsActivity).first()))
            updateKeySizeLabel(sliderKeySize.value.toInt())
            sliderKeyboardHeight.value = clampToSlider(
                sliderKeyboardHeight,
                GkeysSettings.keyboardHeightDp(this@SettingsActivity).first().toFloat()
            )
            updateKeyboardHeightLabel(sliderKeyboardHeight.value.toInt())
            suppressThemeAutoSave = true
            when (GkeysSettings.themeMode(this@SettingsActivity).first()) {
                GkeysSettings.THEME_LIGHT -> radioTheme.check(R.id.radio_theme_light)
                else -> radioTheme.check(R.id.radio_theme_dark)
            }
            suppressThemeAutoSave = false
            switchVoiceBubbleEnabled.isChecked =
                GkeysSettings.voiceBubbleEnabled(this@SettingsActivity).first()
            suppressVoiceInputAutoSave = true
            selectVoiceInputRadio(
                when (GkeysSettings.aiBarVoiceInputMode(this@SettingsActivity).first()) {
                    GkeysSettings.AI_BAR_VOICE_MIC -> R.id.radio_voice_input_mic
                    GkeysSettings.AI_BAR_VOICE_LIVE -> R.id.radio_voice_input_live
                    else -> R.id.radio_voice_input_both
                }
            )
            suppressVoiceInputAutoSave = false
            rebuildAiBarOrderUi(
                order = GkeysSettings.aiBarOrder(this@SettingsActivity).first(),
                wandEnabled = GkeysSettings.aiBarWandEnabled(this@SettingsActivity).first(),
                polishEnabled = GkeysSettings.aiBarPolishButtonEnabled(this@SettingsActivity).first(),
                clearAllEnabled = GkeysSettings.aiBarClearAllEnabled(this@SettingsActivity).first(),
                clipboardEnabled = GkeysSettings.aiBarClipboardToolbarEnabled(this@SettingsActivity).first(),
                numpadEnabled = GkeysSettings.aiBarNumpadEnabled(this@SettingsActivity).first(),
                oneHandedEnabled = GkeysSettings.aiBarOneHandedEnabled(this@SettingsActivity).first(),
                micToolbarEnabled = GkeysSettings.aiBarMicToolbarEnabled(this@SettingsActivity).first(),
                voiceBubbleToolbarEnabled = GkeysSettings.voiceBubbleEnabled(this@SettingsActivity).first(),
            )
            GkeysSettings.persistMigratedAiBarOrders(this@SettingsActivity)
            switchDefaultVoiceBubble.isChecked =
                GkeysSettings.defaultToVoiceBubble(this@SettingsActivity).first()
            updateVoiceBubbleSettingsUi()
            switchAdaptiveTouch.isChecked =
                GkeysSettings.adaptiveTouchEnabled(this@SettingsActivity).first()
            switchExperimentalSwipeTyping.isChecked =
                GkeysSettings.experimentalSwipeTypingEnabled(this@SettingsActivity).first()
            switchDailyAiLearning.isChecked =
                GkeysSettings.dailyAiLearningEnabled(this@SettingsActivity).first()
            switchRawTextSamples.isChecked =
                GkeysSettings.allowRawTextSamples(this@SettingsActivity).first()
            refreshAdaptiveTouchStats()
            refreshPredictionDebug()
            when (GkeysSettings.oneHandedMode(this@SettingsActivity).first()) {
                GkeysSettings.ONE_HANDED_LEFT -> radioOneHanded.check(R.id.radio_one_hand_left)
                GkeysSettings.ONE_HANDED_RIGHT -> radioOneHanded.check(R.id.radio_one_hand_right)
                else -> radioOneHanded.check(R.id.radio_one_hand_off)
            }
            settingsLoaded = true
          } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "loadSettings failed", e)
          }
        }
    }

    private fun clampToSlider(slider: Slider, value: Float): Float {
        val stepped = if (slider.stepSize > 0f) {
            Math.round((value - slider.valueFrom) / slider.stepSize) * slider.stepSize + slider.valueFrom
        } else value
        return stepped.coerceIn(slider.valueFrom, slider.valueTo)
    }

    private fun setupListeners() {
        etOpenAiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) autoSave { GkeysSettings.saveOpenAiKey(this@SettingsActivity, etOpenAiKey.text.toString().trim()) }
        }
        etAnthropicKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) autoSave { GkeysSettings.saveAnthropicKey(this@SettingsActivity, etAnthropicKey.text.toString().trim()) }
        }
        etDeepgramKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) autoSave { GkeysSettings.saveDeepgramKey(this@SettingsActivity, etDeepgramKey.text.toString().trim()) }
        }
        etSpeechProfile.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) autoSave { GkeysSettings.saveSpeechProfile(this@SettingsActivity, etSpeechProfile.text.toString()) }
        }
        etAiInstructions.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) autoSave { GkeysSettings.saveAiInstructions(this@SettingsActivity, etAiInstructions.text.toString()) }
        }
        etAiInstructions.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                autoSave { GkeysSettings.saveAiInstructions(this@SettingsActivity, s?.toString().orEmpty()) }
            }
        })

        sliderKeyRepeat.addOnChangeListener { _, _, fromUser ->
            if (fromUser) autoSave { GkeysSettings.saveKeyRepeatSpeed(this@SettingsActivity, sliderKeyRepeat.value.toInt()) }
        }
        sliderDeleteSpeed.addOnChangeListener { _, _, fromUser ->
            if (fromUser) autoSave { GkeysSettings.saveDeleteSpeed(this@SettingsActivity, sliderDeleteSpeed.value.toInt()) }
        }
        switchVibration.setOnCheckedChangeListener { _, checked ->
            sliderVibration.isEnabled = checked
            autoSave { GkeysSettings.saveVibration(this@SettingsActivity, checked) }
        }
        sliderVibration.addOnChangeListener { _, _, fromUser ->
            if (fromUser) autoSave { GkeysSettings.saveVibrationStrength(this@SettingsActivity, sliderVibration.value.toInt()) }
        }

        radioPolishLevel.setOnCheckedChangeListener { _, _ ->
            if (!settingsLoaded || suppressPolishAutoSave) return@setOnCheckedChangeListener
            autoSave { GkeysSettings.savePolishLevel(this@SettingsActivity, polishLevelFromRadio()) }
        }

        spinnerVoiceFrom.onItemSelectedListener = simpleSaveListener {
            autoSave { GkeysSettings.saveVoiceTranslateFrom(this@SettingsActivity, spinnerLanguageCode(spinnerVoiceFrom)) }
        }
        spinnerVoiceTo.onItemSelectedListener = simpleSaveListener {
            autoSave { GkeysSettings.saveVoiceTranslateTo(this@SettingsActivity, spinnerLanguageCode(spinnerVoiceTo)) }
        }

        switchDefaultLang.setOnCheckedChangeListener { _, checked ->
            autoSave { GkeysSettings.saveDefaultLanguage(this@SettingsActivity, if (checked) "he" else "en") }
        }
        switchRightHanded.setOnCheckedChangeListener { _, checked ->
            if (checked && sliderKeySize.value < 2f) {
                sliderKeySize.value = 2f
                updateKeySizeLabel(2)
                autoSave { GkeysSettings.saveKeySizePreset(this@SettingsActivity, sliderToPreset(2)) }
            }
            autoSave { GkeysSettings.saveRightHandedMode(this@SettingsActivity, checked) }
        }
        sliderKeySize.addOnChangeListener { _, value, fromUser ->
            updateKeySizeLabel(value.toInt())
            if (fromUser) autoSave { GkeysSettings.saveKeySizePreset(this@SettingsActivity, sliderToPreset(value.toInt())) }
        }
        sliderKeyboardHeight.addOnChangeListener { _, value, fromUser ->
            updateKeyboardHeightLabel(value.toInt())
            if (fromUser) autoSave { GkeysSettings.saveKeyboardHeightDp(this@SettingsActivity, value.toInt()) }
        }

        radioOneHanded.setOnCheckedChangeListener { _, _ ->
            autoSave {
                val oneHanded = when (radioOneHanded.checkedRadioButtonId) {
                    R.id.radio_one_hand_left -> GkeysSettings.ONE_HANDED_LEFT
                    R.id.radio_one_hand_right -> GkeysSettings.ONE_HANDED_RIGHT
                    else -> GkeysSettings.ONE_HANDED_OFF
                }
                GkeysSettings.saveOneHandedMode(this@SettingsActivity, oneHanded)
            }
        }

        switchVoiceBubbleEnabled.setOnCheckedChangeListener { _, checked ->
            updateVoiceBubbleSettingsUi()
            if (!checked) {
                switchDefaultVoiceBubble.isChecked = false
            }
            autoSave { saveVoiceBubbleSettings() }
            refreshAiBarOrderVoiceRows()
        }
        switchDefaultVoiceBubble.setOnCheckedChangeListener { _, _ ->
            autoSave { saveVoiceBubbleSettings() }
        }

        radioTheme.setOnCheckedChangeListener { _, checkedId ->
            if (!settingsLoaded || suppressThemeAutoSave) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                val mode = when (checkedId) {
                    R.id.radio_theme_light -> GkeysSettings.THEME_LIGHT
                    else -> GkeysSettings.THEME_DARK
                }
                GkeysSettings.saveThemeMode(this@SettingsActivity, mode)
                recreate()
            }
        }

        radioAiBarVoiceInput.setOnCheckedChangeListener { _, _ ->
            if (!settingsLoaded || suppressVoiceInputAutoSave) return@setOnCheckedChangeListener
            autoSave {
                GkeysSettings.saveAiBarVoiceInputMode(
                    this@SettingsActivity,
                    voiceInputModeFromRadio()
                )
            }
            refreshAiBarOrderVoiceRows()
        }

        switchAdaptiveTouch.setOnCheckedChangeListener { _, checked ->
            autoSave { GkeysSettings.saveAdaptiveTouchEnabled(this@SettingsActivity, checked) }
        }
        switchExperimentalSwipeTyping.setOnCheckedChangeListener { _, checked ->
            autoSave { GkeysSettings.saveExperimentalSwipeTypingEnabled(this@SettingsActivity, checked) }
        }
        switchDailyAiLearning.setOnCheckedChangeListener { _, checked ->
            autoSave {
                GkeysSettings.saveDailyAiLearningEnabled(this@SettingsActivity, checked)
                PredictionSettingsScreen.onDailyAiLearningChanged(this@SettingsActivity, checked)
            }
        }
        switchRawTextSamples.setOnCheckedChangeListener { _, checked ->
            autoSave { GkeysSettings.saveAllowRawTextSamples(this@SettingsActivity, checked) }
        }

        btnMicPermission.setOnClickListener { requestMicPermissionIfNeeded() }
        btnPhotoPermission.setOnClickListener { requestPhotoPermissionIfNeeded() }
        btnOverlayPermission.setOnClickListener { requestOverlayPermissionIfNeeded() }
        btnResetAdaptiveTouch.setOnClickListener {
            com.gremier.gkeys.ime.touch.AdaptiveTouchStore.reset(this)
            refreshAdaptiveTouchStats()
            Toast.makeText(this, "Touch model reset", Toast.LENGTH_SHORT).show()
        }
        btnEnableKeyboard.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    private fun autoSave(block: suspend () -> Unit) {
        if (!settingsLoaded) return
        lifecycleScope.launch {
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "autoSave failed", e)
            }
        }
    }

    private suspend fun saveVoiceBubbleSettings() {
        GkeysSettings.saveVoiceBubbleEnabled(this, switchVoiceBubbleEnabled.isChecked)
        GkeysSettings.saveDefaultToVoiceBubble(
            this,
            switchDefaultVoiceBubble.isChecked && switchVoiceBubbleEnabled.isChecked
        )
        if (!switchVoiceBubbleEnabled.isChecked) {
            GkeysSettings.saveVoiceBubbleModeActive(this, false)
        }
    }

    private fun simpleSaveListener(onSave: () -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
            onSave()
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun updateVoiceBubbleSettingsUi() {
        val enabled = switchVoiceBubbleEnabled.isChecked
        switchDefaultVoiceBubble.isEnabled = enabled
        voiceBubbleDefaultRow.alpha = if (enabled) 1f else 0.45f
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMicPermissionIfNeeded() {
        if (hasMicPermission()) {
            Toast.makeText(this, "Microphone already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun updateMicPermissionButton() {
        btnMicPermission.text = if (hasMicPermission()) {
            "✓ Microphone allowed"
        } else {
            "Allow microphone access"
        }
    }

    private fun photoPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasPhotoPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, photoPermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPhotoPermissionIfNeeded() {
        if (hasPhotoPermission()) {
            Toast.makeText(this, "Photos already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        photoPermissionLauncher.launch(photoPermission())
    }

    private fun updatePhotoPermissionButton() {
        if (!::btnPhotoPermission.isInitialized) return
        btnPhotoPermission.text = if (hasPhotoPermission()) {
            "✓ Photos allowed"
        } else {
            "Allow photos access"
        }
    }

    private fun hasOverlayPermission(): Boolean =
        OverlayPermissionHelper.hasOverlayPermission(this)

    private fun requestOverlayPermissionIfNeeded() {
        if (hasOverlayPermission()) {
            Toast.makeText(this, "Overlay permission already allowed", Toast.LENGTH_SHORT).show()
            overlayRestrictedStep = 0
            updateOverlayPermissionButton()
            return
        }
        if (OverlayPermissionHelper.needsRestrictedSettingsUnlock() && overlayRestrictedStep == 0) {
            OverlayPermissionHelper.showRestrictedSettingsHelp(this) {
                overlayRestrictedStep = 1
                OverlayPermissionHelper.openAppInfo(this)
                updateOverlayPermissionButton()
            }
        } else {
            OverlayPermissionHelper.openOverlayToggle(this)
        }
    }

    private fun refreshAdaptiveTouchStats() {
        if (!::tvAdaptiveTouchStats.isInitialized) return
        val engine = com.gremier.gkeys.ime.touch.AdaptiveTouchIntelligence(this, lifecycleScope)
        engine.load()
        tvAdaptiveTouchStats.text = engine.statsSummary()
    }

    private fun refreshPredictionDebug() {
        if (!::tvPredictionDebug.isInitialized) return
        val profile = com.gremier.gkeys.ime.personalization.PersonalLanguageProfileStore.latestDebugSummary(this)
        val reasons = com.gremier.gkeys.ime.suggestions.ContextualCandidateReranker.lastDebugExplanations
        tvPredictionDebug.text = buildString {
            append(profile)
            if (reasons.isNotEmpty()) {
                append("\nLatest suggestion reasons:\n")
                append(reasons.joinToString("\n"))
            }
        }
    }

    private fun updateOverlayPermissionButton() {
        btnOverlayPermission.text = when {
            hasOverlayPermission() -> "✓ Display over other apps allowed"
            OverlayPermissionHelper.needsRestrictedSettingsUnlock() && overlayRestrictedStep == 1 ->
                "Step 2: Allow display over other apps"
            OverlayPermissionHelper.needsRestrictedSettingsUnlock() ->
                "Step 1: Allow restricted settings"
            else -> "Allow display over other apps"
        }
    }

    private fun setupVoiceLanguageSpinners() {
        val names = resources.getStringArray(R.array.voice_translate_language_names)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinnerVoiceFrom.adapter = adapter
        spinnerVoiceTo.adapter = adapter
    }

    private fun selectSpinnerLanguage(spinner: Spinner, code: String) {
        val index = GkeysSettings.voiceLanguageCodes.indexOf(code).coerceAtLeast(0)
        spinner.setSelection(index)
    }

    private fun spinnerLanguageCode(spinner: Spinner): String {
        val index = spinner.selectedItemPosition.coerceIn(0, GkeysSettings.voiceLanguageCodes.lastIndex)
        return GkeysSettings.voiceLanguageCodes[index]
    }

    private fun refreshPolishLevelRadio() {
        if (!::radioPolishLevel.isInitialized) return
        lifecycleScope.launch {
            try {
                suppressPolishAutoSave = true
                when (GkeysSettings.polishLevel(this@SettingsActivity).first()) {
                    GkeysSettings.POLISH_FORMAL -> selectPolishRadio(R.id.radio_polish_formal)
                    GkeysSettings.POLISH_RAW -> selectPolishRadio(R.id.radio_polish_raw)
                    else -> selectPolishRadio(R.id.radio_polish_natural)
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "refreshPolishLevelRadio failed", e)
            } finally {
                suppressPolishAutoSave = false
            }
        }
    }

    private fun selectPolishRadio(id: Int) {
        if (radioPolishLevel.checkedRadioButtonId != id) {
            radioPolishLevel.check(id)
        }
    }

    private fun selectVoiceInputRadio(id: Int) {
        if (radioAiBarVoiceInput.checkedRadioButtonId != id) {
            radioAiBarVoiceInput.check(id)
        }
    }

    private fun voiceInputModeFromRadio(): String = when (radioAiBarVoiceInput.checkedRadioButtonId) {
        R.id.radio_voice_input_mic -> GkeysSettings.AI_BAR_VOICE_MIC
        R.id.radio_voice_input_live -> GkeysSettings.AI_BAR_VOICE_LIVE
        else -> GkeysSettings.AI_BAR_VOICE_BOTH
    }

    private fun polishLevelFromRadio(): String = when (radioPolishLevel.checkedRadioButtonId) {
        R.id.radio_polish_formal -> GkeysSettings.POLISH_FORMAL
        R.id.radio_polish_raw -> GkeysSettings.POLISH_RAW
        else -> GkeysSettings.POLISH_NATURAL
    }

    private fun presetToSlider(preset: String): Float = when (preset) {
        GkeysSettings.KEY_SIZE_SMALL -> 0f
        GkeysSettings.KEY_SIZE_LARGE -> 2f
        GkeysSettings.KEY_SIZE_EXTRA_LARGE -> 3f
        else -> 1f
    }

    private fun sliderToPreset(step: Int): String = when (step) {
        0 -> GkeysSettings.KEY_SIZE_SMALL
        2 -> GkeysSettings.KEY_SIZE_LARGE
        3 -> GkeysSettings.KEY_SIZE_EXTRA_LARGE
        else -> GkeysSettings.KEY_SIZE_DEFAULT
    }

    private fun updateKeySizeLabel(step: Int) {
        tvKeySizeLabel.text = when (step) {
            0 -> "Small"
            2 -> "Large"
            3 -> "Extra Large"
            else -> "Default"
        }
    }

    private fun updateKeyboardHeightLabel(heightDp: Int) {
        tvKeyboardHeightLabel.text = "$heightDp dp"
    }

    private fun checkKeyboardEnabled() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val isEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        btnEnableKeyboard.text = if (isEnabled) "✓ Gkeys is enabled" else "Enable Gkeys Keyboard"
    }

    private fun enableNestedScrollInParent(editText: TextInputEditText) {
        editText.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
    }

    private var aiBarOrderState = com.gremier.gkeys.ime.AiBarLayout.DEFAULT_ORDER

    private fun setupAiBarOrderLists() {
        val touchHelper = ItemTouchHelper(createOrderDragCallback())
        orderAdapter = AiBarOrderDragAdapter(
            rows = mutableListOf(),
            onOrderChanged = { order ->
                if (!settingsLoaded || suppressAiBarOrderAutoSave) return@AiBarOrderDragAdapter
                val normalized = com.gremier.gkeys.ime.AiBarLayout.normalizeOrder(order)
                aiBarOrderState = normalized
                autoSave { GkeysSettings.saveAiBarOrder(this@SettingsActivity, normalized) }
            },
            onToggleChanged = { id, enabled ->
                if (!settingsLoaded || suppressAiBarOrderAutoSave) return@AiBarOrderDragAdapter
                saveAiBarItemEnabled(id = id, enabled = enabled)
            },
        )
        orderAdapter.attachTouchHelper(touchHelper)
        orderAdapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() = refreshOrderListHeights()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                refreshOrderListHeights()
        })
        rvAiBarOrder.adapter = orderAdapter
        touchHelper.attachToRecyclerView(rvAiBarOrder)
    }

    private fun refreshOrderListHeights() {
        rvAiBarOrder.post {
            rvAiBarOrder.requestLayout()
            (rvAiBarOrder.parent as? android.view.View)?.requestLayout()
        }
    }

    private fun createOrderDragCallback(): ItemTouchHelper.SimpleCallback {
        return object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == androidx.recyclerview.widget.RecyclerView.NO_POSITION ||
                    to == androidx.recyclerview.widget.RecyclerView.NO_POSITION
                ) return false
                orderAdapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                direction: Int,
            ) = Unit

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?,
                actionState: Int,
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.alpha = 0.92f
                    viewHolder.itemView.translationZ = 12f
                }
            }

            override fun clearView(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                viewHolder.itemView.translationZ = 0f
                recyclerView.post { recyclerView.requestLayout() }
            }
        }
    }

    private fun rebuildAiBarOrderUi(
        order: List<String>,
        wandEnabled: Boolean,
        polishEnabled: Boolean,
        clearAllEnabled: Boolean,
        clipboardEnabled: Boolean,
        numpadEnabled: Boolean,
        oneHandedEnabled: Boolean,
        micToolbarEnabled: Boolean,
        voiceBubbleToolbarEnabled: Boolean,
    ) {
        suppressAiBarOrderAutoSave = true
        val fullOrder = com.gremier.gkeys.ime.AiBarLayout.normalizeOrder(order)
        aiBarOrderState = fullOrder
        orderAdapter.updateRows(
            fullOrder.map { id ->
                AiBarOrderRow(
                    id = id,
                    enabled = itemEnabled(
                        id, wandEnabled, polishEnabled, clearAllEnabled, clipboardEnabled,
                        numpadEnabled, oneHandedEnabled, micToolbarEnabled, voiceBubbleToolbarEnabled,
                    ),
                    canToggle = itemCanToggle(id),
                    isFixed = false,
                )
            }
        )
        suppressAiBarOrderAutoSave = false
        refreshOrderListHeights()
    }

    private fun itemCanToggle(id: String): Boolean = when (id) {
        com.gremier.gkeys.ime.AiBarLayout.RAW_POLISH -> false
        com.gremier.gkeys.ime.AiBarLayout.LIVE -> false
        com.gremier.gkeys.ime.AiBarLayout.MIC -> voiceInputIncludesMic()
        com.gremier.gkeys.ime.AiBarLayout.SETTINGS,
        com.gremier.gkeys.ime.AiBarLayout.UNDO,
        com.gremier.gkeys.ime.AiBarLayout.SELECT_ALL,
        -> false
        else -> true
    }

    private fun itemEnabled(
        id: String,
        wandEnabled: Boolean,
        polishEnabled: Boolean,
        clearAllEnabled: Boolean,
        clipboardEnabled: Boolean,
        numpadEnabled: Boolean,
        oneHandedEnabled: Boolean,
        micToolbarEnabled: Boolean,
        voiceBubbleToolbarEnabled: Boolean,
    ): Boolean = when (id) {
        com.gremier.gkeys.ime.AiBarLayout.WAND -> wandEnabled
        com.gremier.gkeys.ime.AiBarLayout.POLISH -> polishEnabled
        com.gremier.gkeys.ime.AiBarLayout.RAW_POLISH -> polishEnabled
        com.gremier.gkeys.ime.AiBarLayout.CLEAR_ALL -> clearAllEnabled
        com.gremier.gkeys.ime.AiBarLayout.CLIPBOARD -> clipboardEnabled
        com.gremier.gkeys.ime.AiBarLayout.NUMPAD -> numpadEnabled
        com.gremier.gkeys.ime.AiBarLayout.ONE_HANDED -> oneHandedEnabled
        com.gremier.gkeys.ime.AiBarLayout.MIC -> micToolbarEnabled
        com.gremier.gkeys.ime.AiBarLayout.LIVE -> voiceInputIncludesLive()
        com.gremier.gkeys.ime.AiBarLayout.BUBBLE -> voiceBubbleToolbarEnabled
        else -> true
    }

    private fun saveAiBarItemEnabled(id: String, enabled: Boolean) {
        autoSave {
            when (id) {
                com.gremier.gkeys.ime.AiBarLayout.WAND ->
                    GkeysSettings.saveAiBarWandEnabled(this@SettingsActivity, enabled)
                com.gremier.gkeys.ime.AiBarLayout.POLISH ->
                    GkeysSettings.saveAiBarPolishButtonEnabled(this@SettingsActivity, enabled)
                com.gremier.gkeys.ime.AiBarLayout.CLEAR_ALL ->
                    GkeysSettings.saveAiBarClearAllEnabled(this@SettingsActivity, enabled)
                com.gremier.gkeys.ime.AiBarLayout.CLIPBOARD ->
                    GkeysSettings.saveAiBarClipboardToolbarEnabled(this@SettingsActivity, enabled)
                com.gremier.gkeys.ime.AiBarLayout.NUMPAD ->
                    GkeysSettings.saveAiBarNumpadEnabled(this@SettingsActivity, enabled)
                com.gremier.gkeys.ime.AiBarLayout.ONE_HANDED ->
                    GkeysSettings.saveAiBarOneHandedEnabled(this@SettingsActivity, enabled)
                com.gremier.gkeys.ime.AiBarLayout.MIC ->
                    GkeysSettings.saveAiBarMicToolbarEnabled(this@SettingsActivity, enabled)
                com.gremier.gkeys.ime.AiBarLayout.BUBBLE ->
                    GkeysSettings.saveVoiceBubbleEnabled(this@SettingsActivity, enabled)
            }
            if (id == com.gremier.gkeys.ime.AiBarLayout.BUBBLE) {
                updateVoiceBubbleSettingsUi()
            }
        }
    }

    private suspend fun rebuildAiBarOrderUiFromPrefs() {
        rebuildAiBarOrderUi(
            order = GkeysSettings.aiBarOrder(this@SettingsActivity).first(),
            wandEnabled = GkeysSettings.aiBarWandEnabled(this@SettingsActivity).first(),
            polishEnabled = GkeysSettings.aiBarPolishButtonEnabled(this@SettingsActivity).first(),
            clearAllEnabled = GkeysSettings.aiBarClearAllEnabled(this@SettingsActivity).first(),
            clipboardEnabled = GkeysSettings.aiBarClipboardToolbarEnabled(this@SettingsActivity).first(),
            numpadEnabled = GkeysSettings.aiBarNumpadEnabled(this@SettingsActivity).first(),
            oneHandedEnabled = GkeysSettings.aiBarOneHandedEnabled(this@SettingsActivity).first(),
            micToolbarEnabled = GkeysSettings.aiBarMicToolbarEnabled(this@SettingsActivity).first(),
            voiceBubbleToolbarEnabled = GkeysSettings.voiceBubbleEnabled(this@SettingsActivity).first(),
        )
    }

    private fun refreshAiBarOrderVoiceRows() {
        if (!settingsLoaded) return
        lifecycleScope.launch { rebuildAiBarOrderUiFromPrefs() }
    }

    private fun voiceInputIncludesMic(): Boolean =
        when (radioAiBarVoiceInput.checkedRadioButtonId) {
            R.id.radio_voice_input_live -> false
            else -> true
        }

    private fun voiceInputIncludesLive(): Boolean =
        when (radioAiBarVoiceInput.checkedRadioButtonId) {
            R.id.radio_voice_input_mic -> false
            else -> true
        }
}
