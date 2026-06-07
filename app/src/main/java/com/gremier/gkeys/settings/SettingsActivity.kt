package com.gremier.gkeys.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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
    private lateinit var switchAutoPolish: SwitchMaterial
    private lateinit var switchDefaultLang: SwitchMaterial
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
        switchAutoPolish = findViewById(R.id.switch_auto_polish)
        switchDefaultLang = findViewById(R.id.switch_default_lang)
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
            switchAutoPolish.isChecked = GkeysSettings.autoPolishEnabled(this@SettingsActivity).first()
            switchDefaultLang.isChecked = GkeysSettings.defaultLanguage(this@SettingsActivity).first() == "he"
        }
    }

    private fun setupListeners() {
        btnSave.setOnClickListener {
            lifecycleScope.launch {
                GkeysSettings.saveOpenAiKey(this@SettingsActivity, etOpenAiKey.text.toString().trim())
                GkeysSettings.saveAnthropicKey(this@SettingsActivity, etAnthropicKey.text.toString().trim())
                GkeysSettings.saveKeyRepeatSpeed(this@SettingsActivity, sliderKeyRepeat.value.toInt())
                GkeysSettings.saveDeleteSpeed(this@SettingsActivity, sliderDeleteSpeed.value.toInt())
                GkeysSettings.saveVibration(this@SettingsActivity, switchVibration.isChecked)
                GkeysSettings.saveAutoPolish(this@SettingsActivity, switchAutoPolish.isChecked)
                GkeysSettings.saveDefaultLanguage(this@SettingsActivity,
                    if (switchDefaultLang.isChecked) "he" else "en")
                btnSave.text = "Saved ✓"
                btnSave.postDelayed({ btnSave.text = "Save Settings" }, 2000)
            }
        }
        btnEnableKeyboard.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    private fun checkKeyboardEnabled() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val isEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        btnEnableKeyboard.text = if (isEnabled) "✓ Gkeys is enabled" else "Enable Gkeys Keyboard"
    }
}
