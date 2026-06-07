package com.gremier.gkeys.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gremier.gkeys.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

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
    private lateinit var radioOneHanded: RadioGroup
    private lateinit var btnSave: MaterialButton
    private lateinit var btnEnableKeyboard: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindViews()
        loadSettings()
        setupListeners()
        checkKeyboardEnabled()
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
        radioOneHanded = findViewById(R.id.radio_one_handed)
        btnSave = findViewById(R.id.btn_save)
        btnEnableKeyboard = findViewById(R.id.btn_enable_keyboard)
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            etOpenAiKey.setText(GkeysSettings.openAiKey(this@SettingsActivity).first())
            etAnthropicKey.setText(GkeysSettings.anthropicKey(this@SettingsActivity).first())
            sliderKeyRepeat.value = GkeysSettings.keyRepeatSpeed(this@SettingsActivity).first().toFloat()
            sliderDeleteSpeed.value = GkeysSettings.deleteSpeed(this@SettingsActivity).first().toFloat()
            switchVibration.isChecked = GkeysSettings.vibrationEnabled(this@SettingsActivity).first()
            sliderVibration.value = GkeysSettings.vibrationStrength(this@SettingsActivity).first().toFloat()
            sliderVibration.isEnabled = switchVibration.isChecked
            switchAutoPolish.isChecked = GkeysSettings.autoPolishEnabled(this@SettingsActivity).first()
            switchDefaultLang.isChecked = GkeysSettings.defaultLanguage(this@SettingsActivity).first() == "he"
            switchRightHanded.isChecked = GkeysSettings.rightHandedMode(this@SettingsActivity).first()
            sliderKeySize.value = presetToSlider(GkeysSettings.keySizePreset(this@SettingsActivity).first())
            updateKeySizeLabel(sliderKeySize.value.toInt())
            when (GkeysSettings.oneHandedMode(this@SettingsActivity).first()) {
                GkeysSettings.ONE_HANDED_LEFT -> radioOneHanded.check(R.id.radio_one_hand_left)
                GkeysSettings.ONE_HANDED_RIGHT -> radioOneHanded.check(R.id.radio_one_hand_right)
                else -> radioOneHanded.check(R.id.radio_one_hand_off)
            }
        }
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
                btnSave.text = "Saved ✓"
                btnSave.postDelayed({ btnSave.text = "Save Settings" }, 2000)
            }
        }
        btnEnableKeyboard.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
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
