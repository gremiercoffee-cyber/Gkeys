package com.gremier.gkeys.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.RadioGroup
import android.widget.TextView
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
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REQUEST_MIC_PERMISSION = "request_mic_permission"
    }

    private lateinit var etOpenAiKey: TextInputEditText
    private lateinit var etAnthropicKey: TextInputEditText
    private lateinit var sliderKeyRepeat: Slider
    private lateinit var sliderDeleteSpeed: Slider
    private lateinit var switchVibration: SwitchMaterial
    private lateinit var sliderVibration: Slider
    private lateinit var switchAutoPolish: SwitchMaterial
    private lateinit var switchDefaultLang: SwitchMaterial
    private lateinit var switchRightHanded: SwitchMaterial
    private lateinit var sliderKeySize: Slider
    private lateinit var tvKeySizeLabel: TextView
    private lateinit var tvAppVersion: TextView
    private lateinit var btnMicPermission: MaterialButton
    private lateinit var radioOneHanded: RadioGroup
    private lateinit var btnSave: MaterialButton
    private lateinit var btnEnableKeyboard: MaterialButton
    private lateinit var cardCrash: com.google.android.material.card.MaterialCardView
    private lateinit var tvCrashLog: TextView
    private lateinit var btnCopyCrash: MaterialButton
    private lateinit var btnClearCrash: MaterialButton

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindViews()
        loadSettings()
        setupListeners()
        checkKeyboardEnabled()
        updateMicPermissionButton()
        tvAppVersion.text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        setupCrashCardListeners()
        refreshCrashCard()
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false)) {
            requestMicPermissionIfNeeded()
        }
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

    override fun onResume() {
        super.onResume()
        updateMicPermissionButton()
        checkKeyboardEnabled()
        refreshCrashCard()
    }

    private fun bindViews() {
        etOpenAiKey = findViewById(R.id.et_openai_key)
        etAnthropicKey = findViewById(R.id.et_anthropic_key)
        sliderKeyRepeat = findViewById(R.id.slider_key_repeat)
        sliderDeleteSpeed = findViewById(R.id.slider_delete_speed)
        switchVibration = findViewById(R.id.switch_vibration)
        sliderVibration = findViewById(R.id.slider_vibration)
        switchAutoPolish = findViewById(R.id.switch_auto_polish)
        switchDefaultLang = findViewById(R.id.switch_default_lang)
        switchRightHanded = findViewById(R.id.switch_right_handed)
        sliderKeySize = findViewById(R.id.slider_key_size)
        tvKeySizeLabel = findViewById(R.id.tv_key_size_label)
        tvAppVersion = findViewById(R.id.tv_app_version)
        btnMicPermission = findViewById(R.id.btn_mic_permission)
        radioOneHanded = findViewById(R.id.radio_one_handed)
        btnSave = findViewById(R.id.btn_save)
        btnEnableKeyboard = findViewById(R.id.btn_enable_keyboard)
        cardCrash = findViewById(R.id.card_crash)
        tvCrashLog = findViewById(R.id.tv_crash_log)
        btnCopyCrash = findViewById(R.id.btn_copy_crash)
        btnClearCrash = findViewById(R.id.btn_clear_crash)
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
            sliderKeyRepeat.value = clampToSlider(sliderKeyRepeat, GkeysSettings.keyRepeatSpeed(this@SettingsActivity).first().toFloat())
            sliderDeleteSpeed.value = clampToSlider(sliderDeleteSpeed, GkeysSettings.deleteSpeed(this@SettingsActivity).first().toFloat())
            switchVibration.isChecked = GkeysSettings.vibrationEnabled(this@SettingsActivity).first()
            sliderVibration.value = clampToSlider(sliderVibration, GkeysSettings.vibrationStrength(this@SettingsActivity).first().toFloat())
            sliderVibration.isEnabled = switchVibration.isChecked
            switchAutoPolish.isChecked = GkeysSettings.autoPolishEnabled(this@SettingsActivity).first()
            switchDefaultLang.isChecked = GkeysSettings.defaultLanguage(this@SettingsActivity).first() == "he"
            switchRightHanded.isChecked = GkeysSettings.rightHandedMode(this@SettingsActivity).first()
            sliderKeySize.value = clampToSlider(sliderKeySize, presetToSlider(GkeysSettings.keySizePreset(this@SettingsActivity).first()))
            updateKeySizeLabel(sliderKeySize.value.toInt())
            when (GkeysSettings.oneHandedMode(this@SettingsActivity).first()) {
                GkeysSettings.ONE_HANDED_LEFT -> radioOneHanded.check(R.id.radio_one_hand_left)
                GkeysSettings.ONE_HANDED_RIGHT -> radioOneHanded.check(R.id.radio_one_hand_right)
                else -> radioOneHanded.check(R.id.radio_one_hand_off)
            }
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
        switchVibration.setOnCheckedChangeListener { _, checked ->
            sliderVibration.isEnabled = checked
        }
        switchRightHanded.setOnCheckedChangeListener { _, checked ->
            if (checked && sliderKeySize.value < 2f) {
                sliderKeySize.value = 2f
                updateKeySizeLabel(2)
            }
        }
        sliderKeySize.addOnChangeListener { _, value, _ ->
            updateKeySizeLabel(value.toInt())
        }
        btnMicPermission.setOnClickListener { requestMicPermissionIfNeeded() }
        btnSave.setOnClickListener {
            lifecycleScope.launch {
                GkeysSettings.saveOpenAiKey(this@SettingsActivity, etOpenAiKey.text.toString().trim())
                GkeysSettings.saveAnthropicKey(this@SettingsActivity, etAnthropicKey.text.toString().trim())
                GkeysSettings.saveKeyRepeatSpeed(this@SettingsActivity, sliderKeyRepeat.value.toInt())
                GkeysSettings.saveDeleteSpeed(this@SettingsActivity, sliderDeleteSpeed.value.toInt())
                GkeysSettings.saveVibration(this@SettingsActivity, switchVibration.isChecked)
                GkeysSettings.saveVibrationStrength(this@SettingsActivity, sliderVibration.value.toInt())
                GkeysSettings.saveAutoPolish(this@SettingsActivity, switchAutoPolish.isChecked)
                GkeysSettings.saveDefaultLanguage(this@SettingsActivity,
                    if (switchDefaultLang.isChecked) "he" else "en")
                GkeysSettings.saveRightHandedMode(this@SettingsActivity, switchRightHanded.isChecked)
                GkeysSettings.saveKeySizePreset(this@SettingsActivity, sliderToPreset(sliderKeySize.value.toInt()))
                val oneHanded = when (radioOneHanded.checkedRadioButtonId) {
                    R.id.radio_one_hand_left -> GkeysSettings.ONE_HANDED_LEFT
                    R.id.radio_one_hand_right -> GkeysSettings.ONE_HANDED_RIGHT
                    else -> GkeysSettings.ONE_HANDED_OFF
                }
                GkeysSettings.saveOneHandedMode(this@SettingsActivity, oneHanded)
                AppVersionTracker.noteCurrentVersion(this@SettingsActivity)
                btnSave.text = "Saved ✓"
                Toast.makeText(
                    this@SettingsActivity,
                    "Saved. Switch to another keyboard and back to Gkeys to apply updates.",
                    Toast.LENGTH_LONG
                ).show()
                btnSave.postDelayed({ btnSave.text = "Save Settings" }, 2000)
            }
        }
        btnEnableKeyboard.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
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

    private fun checkKeyboardEnabled() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val isEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        btnEnableKeyboard.text = if (isEnabled) "✓ Gkeys is enabled" else "Enable Gkeys Keyboard"
    }
}
