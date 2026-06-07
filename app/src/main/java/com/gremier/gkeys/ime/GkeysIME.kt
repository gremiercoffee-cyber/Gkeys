package com.gremier.gkeys.ime

import android.content.Context
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.gremier.gkeys.R
import com.gremier.gkeys.ai.AiManager
import com.gremier.gkeys.ai.AudioRecorder
import com.gremier.gkeys.clipboard.GkeysClipboardManager
import com.gremier.gkeys.settings.GkeysSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class GkeysIME : InputMethodService() {

    private enum class VoiceAction { DEFAULT, TRANSLATE, POLISH, DEEP_POLISH, RAW }

    private var isHebrew = false
    private var isSymbols = false
    private var isShifted = false
    private var capsLock = false
    private var oneHandedMode = GkeysSettings.ONE_HANDED_OFF
    private var keyboardVisible = true

    private var keyRepeatMs = GkeysSettings.DEFAULT_KEY_REPEAT_MS
    private var deleteSpeedMs = GkeysSettings.DEFAULT_DELETE_SPEED_MS
    private var vibrationEnabled = true
    private var vibrationStrength = GkeysSettings.DEFAULT_VIBRATION_STRENGTH
    private var autoPolishEnabled = true
    private var openAiKey = ""
    private var anthropicKey = ""

    private lateinit var aiManager: AiManager
    private lateinit var audioRecorder: AudioRecorder
    private var isRecording = false
    private var isVoiceOverlay = false
    private var longPressTriggered = false
    private var pendingVoiceAction = VoiceAction.DEFAULT

    private val handler = Handler(Looper.getMainLooper())
    private var deleteRunnable: Runnable? = null
    private val longPressRunnable = Runnable { onMicLongPress() }

    private lateinit var vibrator: Vibrator
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var keyboardView: View
    private lateinit var keyboardContent: LinearLayout
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvClipboard: TextView
    private lateinit var clipboardArea: View
    private lateinit var btnMic: ImageView
    private lateinit var btnMicContainer: FrameLayout
    private lateinit var btnKeyboard: ImageButton
    private lateinit var btnOneHand: TextView
    private lateinit var voiceOverlay: View
    private lateinit var voiceStatus: TextView
    private var clipboardManager: GkeysClipboardManager? = null
    private lateinit var swipeTyper: SwipeTyper

    private val voiceActionViews = mutableMapOf<VoiceAction, TextView>()

    companion object {
        private const val LONG_PRESS_MS = 380L
        private const val PEBBLE_SIZE_DP = 42
        private const val PEBBLE_SPECIAL_DP = 46
        private const val PEBBLE_SPACE_W_DP = 128
        private const val PEBBLE_SPACE_H_DP = 38
    }

    private val enRows = listOf(
        listOf("1","2","3","4","5","6","7","8","9","0"),
        listOf("q","w","e","r","t","y","u","i","o","p"),
        listOf("a","s","d","f","g","h","j","k","l"),
        listOf("⇧","z","x","c","v","b","n","m","⌫"),
        listOf("?123","🌐",",","SPACE",".","↵")
    )

    private val heRows = listOf(
        listOf("פ","ם","ן","ו","ט","א","ר","ק","-","'"),
        listOf("ף","ך","ל","ח","י","ע","כ","ג","ד","ש"),
        listOf("⇧","ץ","ת","צ","מ","נ","ה","ב","ס","ז","⌫"),
        listOf("?123","🌐",",","SPACE",".","↵")
    )

    private val symRows = listOf(
        listOf("1","2","3","4","5","6","7","8","9","0"),
        listOf("!","@","#","$","%","^","&","*","(",")"),
        listOf("-","_","=","+","[","]","{","}","\\"),
        listOf(";","'","\"","<",">","?","/","⌫"),
        listOf("ABC","🌐",",","SPACE",".","↵")
    )

    override fun onCreate() {
        super.onCreate()
        aiManager = AiManager(this)
        audioRecorder = AudioRecorder(this)
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        loadSettings()
    }

    override fun onCreateInputView(): View {
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView.layoutDirection = View.LAYOUT_DIRECTION_LTR

        keyboardContent = keyboardView.findViewById(R.id.keyboard_content)
        keyboardPanel = keyboardView.findViewById(R.id.keyboard_panel)
        tvStatus = keyboardView.findViewById(R.id.tv_status)
        tvClipboard = keyboardView.findViewById(R.id.tv_clipboard)
        clipboardArea = keyboardView.findViewById(R.id.clipboard_area)
        btnMic = keyboardView.findViewById(R.id.btn_mic)
        btnMicContainer = keyboardView.findViewById(R.id.btn_mic_container)
        btnKeyboard = keyboardView.findViewById(R.id.btn_keyboard)
        btnOneHand = keyboardView.findViewById(R.id.btn_one_hand)
        voiceOverlay = keyboardView.findViewById(R.id.voice_overlay)
        voiceStatus = keyboardView.findViewById(R.id.voice_status)

        voiceActionViews[VoiceAction.TRANSLATE] = keyboardView.findViewById(R.id.action_translate)
        voiceActionViews[VoiceAction.POLISH] = keyboardView.findViewById(R.id.action_polish)
        voiceActionViews[VoiceAction.DEEP_POLISH] = keyboardView.findViewById(R.id.action_deep)
        voiceActionViews[VoiceAction.RAW] = keyboardView.findViewById(R.id.action_raw)

        val keyboardRows = keyboardView.findViewById<SwipeKeyboardLayout>(R.id.keyboard_rows)
        swipeTyper = SwipeTyper { word ->
            vibrate()
            val text = if (isShifted || capsLock) word.replaceFirstChar { it.uppercase() } else word
            currentInputConnection?.commitText("$text ", 1)
            if (isShifted && !capsLock) {
                isShifted = false
                buildKeyboard()
            }
        }
        keyboardRows.swipeTyper = swipeTyper

        val overlayContainer = keyboardView.findViewById<FrameLayout>(R.id.clipboard_overlay_container)
        clipboardManager = GkeysClipboardManager(
            context = this,
            overlayContainer = overlayContainer,
            previewView = tvClipboard,
            onPaste = { text -> currentInputConnection?.commitText(text, 1) },
            onVibrate = { vibrate() }
        )

        setupAiStrip()
        buildKeyboard()
        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadSettings()
        clipboardManager?.startListening()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelRecording()
        hideVoiceOverlay()
        clipboardManager?.stopListening()
        clipboardManager?.hidePanel()
        super.onFinishInputView(finishingInput)
    }

    private fun loadSettings() {
        scope.launch {
            openAiKey = GkeysSettings.openAiKey(this@GkeysIME).first()
            anthropicKey = GkeysSettings.anthropicKey(this@GkeysIME).first()
            keyRepeatMs = GkeysSettings.keyRepeatSpeed(this@GkeysIME).first()
            deleteSpeedMs = GkeysSettings.deleteSpeed(this@GkeysIME).first()
            vibrationEnabled = GkeysSettings.vibrationEnabled(this@GkeysIME).first()
            vibrationStrength = GkeysSettings.vibrationStrength(this@GkeysIME).first()
            autoPolishEnabled = GkeysSettings.autoPolishEnabled(this@GkeysIME).first()
            isHebrew = GkeysSettings.defaultLanguage(this@GkeysIME).first() == "he"
            oneHandedMode = GkeysSettings.oneHandedMode(this@GkeysIME).first()
            if (::keyboardView.isInitialized) {
                applyOneHandedMode()
                updateOneHandButton()
                buildKeyboard()
            }
        }
    }

    private fun setupAiStrip() {
        btnMicContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    pendingVoiceAction = VoiceAction.DEFAULT
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isVoiceOverlay) highlightVoiceAction(event.rawX, event.rawY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (longPressTriggered) {
                        hideVoiceOverlay()
                        if (isRecording) stopRecordingAndProcess(pendingVoiceAction)
                    } else {
                        handleMicTap()
                    }
                    true
                }
                else -> false
            }
        }

        btnKeyboard.setOnClickListener {
            vibrate()
            if (isVoiceOverlay) {
                cancelRecording()
                hideVoiceOverlay()
            }
            showKeyboardPanel()
        }

        val openClipboard = {
            clipboardManager?.showPanel()
            vibrate()
        }
        tvClipboard.setOnClickListener { openClipboard() }
        clipboardArea.setOnClickListener { openClipboard() }
        btnOneHand.setOnClickListener { cycleOneHandedMode() }
    }

    private fun handleMicTap() {
        vibrate()
        if (isRecording) {
            stopRecordingAndProcess(VoiceAction.DEFAULT)
        } else {
            startRecording()
            updateMicVisuals(recording = true)
            toastStatus("Listening… tap mic to stop")
        }
    }

    private fun onMicLongPress() {
        longPressTriggered = true
        vibrate(12)
        pendingVoiceAction = VoiceAction.DEFAULT
        showVoiceOverlay()
        if (!isRecording) {
            startRecording()
        }
        updateMicVisuals(recording = true)
    }

    private fun showVoiceOverlay() {
        isVoiceOverlay = true
        keyboardPanel.visibility = View.GONE
        voiceOverlay.visibility = View.VISIBLE
        voiceStatus.text = "Listening…"
        highlightVoiceAction(-1f, -1f)
    }

    private fun hideVoiceOverlay() {
        isVoiceOverlay = false
        voiceOverlay.visibility = View.GONE
        if (keyboardVisible) keyboardPanel.visibility = View.VISIBLE
        updateMicVisuals(recording = isRecording)
        clearVoiceActionHighlight()
    }

    private fun showKeyboardPanel() {
        keyboardVisible = true
        keyboardPanel.visibility = View.VISIBLE
    }

    private fun highlightVoiceAction(rawX: Float, rawY: Float) {
        var selected = VoiceAction.DEFAULT
        if (rawX >= 0) {
            for ((action, view) in voiceActionViews) {
                val rect = Rect()
                if (view.getGlobalVisibleRect(rect) && rect.contains(rawX.toInt(), rawY.toInt())) {
                    selected = action
                    break
                }
            }
        }
        pendingVoiceAction = selected
        for ((action, view) in voiceActionViews) {
            view.setBackgroundResource(
                if (action == selected && selected != VoiceAction.DEFAULT)
                    R.drawable.voice_action_pill_selected
                else
                    R.drawable.voice_action_pill
            )
            view.setTextColor(
                if (action == selected && selected != VoiceAction.DEFAULT) 0xFFFFFFFF.toInt()
                else 0xFFE5E7EB.toInt()
            )
        }
    }

    private fun clearVoiceActionHighlight() {
        for ((_, view) in voiceActionViews) {
            view.setBackgroundResource(R.drawable.voice_action_pill)
            view.setTextColor(0xFFE5E7EB.toInt())
        }
    }

    private fun updateMicVisuals(recording: Boolean) {
        if (recording) {
            btnMic.setImageResource(R.drawable.ic_ai_mic_active)
            btnMicContainer.setBackgroundResource(R.drawable.ai_mic_bg_active)
        } else {
            btnMic.setImageResource(R.drawable.ic_ai_mic)
            btnMicContainer.setBackgroundResource(R.drawable.ai_mic_bg)
        }
    }

    private fun cycleOneHandedMode() {
        oneHandedMode = when (oneHandedMode) {
            GkeysSettings.ONE_HANDED_OFF -> GkeysSettings.ONE_HANDED_RIGHT
            GkeysSettings.ONE_HANDED_RIGHT -> GkeysSettings.ONE_HANDED_LEFT
            else -> GkeysSettings.ONE_HANDED_OFF
        }
        scope.launch { GkeysSettings.saveOneHandedMode(this@GkeysIME, oneHandedMode) }
        applyOneHandedMode()
        updateOneHandButton()
        vibrate()
    }

    private fun updateOneHandButton() {
        btnOneHand.text = when (oneHandedMode) {
            GkeysSettings.ONE_HANDED_RIGHT -> "◧→"
            GkeysSettings.ONE_HANDED_LEFT -> "←◧"
            else -> "◧"
        }
        btnOneHand.setTextColor(
            if (oneHandedMode == GkeysSettings.ONE_HANDED_OFF) 0xFF6B7280.toInt()
            else 0xFF4A9EFF.toInt()
        )
    }

    private fun applyOneHandedMode() {
        val params = keyboardContent.layoutParams as FrameLayout.LayoutParams
        val displayWidth = resources.displayMetrics.widthPixels
        when (oneHandedMode) {
            GkeysSettings.ONE_HANDED_RIGHT -> {
                params.width = (displayWidth * 0.78f).toInt()
                params.gravity = Gravity.END
            }
            GkeysSettings.ONE_HANDED_LEFT -> {
                params.width = (displayWidth * 0.78f).toInt()
                params.gravity = Gravity.START
            }
            else -> {
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        keyboardContent.layoutParams = params
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buildKeyboard() {
        val container = keyboardView.findViewById<SwipeKeyboardLayout>(R.id.keyboard_rows) ?: return
        container.removeAllViews()
        container.layoutDirection = View.LAYOUT_DIRECTION_LTR
        swipeTyper.clearKeys()

        val rows = when { isSymbols -> symRows; isHebrew -> heRows; else -> enRows }
        val swipeEnabled = !isHebrew && !isSymbols
        swipeTyper.setEnabled(swipeEnabled)

        rows.forEach { keys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            keys.forEach { key -> row.addView(buildKey(key, swipeEnabled)) }
            container.addView(row)
        }
    }

    private fun buildKey(label: String, swipeEnabled: Boolean): View {
        val isSpace = label == "SPACE"
        val isSpecial = label in listOf("⇧", "⌫", "↵", "?123", "ABC", "🌐")
        val displayLabel = when {
            isSpace -> "space"
            isShifted && !isHebrew && label.length == 1 && label[0].isLetter() -> label.uppercase()
            else -> label
        }

        val weight = when {
            isSpace -> 4f
            label in listOf("⌫", "↵", "⇧") -> 1.5f
            else -> 1f
        }

        val cell = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        }

        val (pebbleW, pebbleH, bgRes) = when {
            isSpace -> Triple(dp(PEBBLE_SPACE_W_DP), dp(PEBBLE_SPACE_H_DP), R.drawable.key_pebble_space)
            isSpecial -> Triple(dp(PEBBLE_SPECIAL_DP), dp(PEBBLE_SPECIAL_DP), R.drawable.key_pebble_special_ripple)
            else -> Triple(dp(PEBBLE_SIZE_DP), dp(PEBBLE_SIZE_DP), R.drawable.key_pebble_ripple)
        }

        val pebble = TextView(this).apply {
            text = displayLabel
            textSize = when {
                isSpace -> 10f
                isSpecial -> 14f
                else -> 15f
            }
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            setTextColor(0xFFE8EAF0.toInt())
            setBackgroundResource(bgRes)
            layoutParams = FrameLayout.LayoutParams(pebbleW, pebbleH, Gravity.CENTER)
        }

        cell.addView(pebble)

        if (swipeEnabled) swipeTyper.registerKey(cell, label)

        cell.setOnClickListener {
            if (swipeTyper.shouldSuppressClick()) return@setOnClickListener
            vibrate()
            handleKey(label)
        }

        if (label == "⌫") {
            cell.setOnLongClickListener { startDeleteRepeat(); true }
            cell.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    stopDeleteRepeat()
                }
                false
            }
        }
        return cell
    }

    private fun handleKey(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "⌫" -> {
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) ic.commitText("", 1)
                else ic.deleteSurroundingText(1, 0)
            }
            "↵" -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            "⇧" -> {
                if (isShifted && !capsLock) capsLock = true
                else if (capsLock) { capsLock = false; isShifted = false }
                else isShifted = true
                buildKeyboard()
            }
            "?123" -> { isSymbols = true; buildKeyboard() }
            "ABC" -> { isSymbols = false; buildKeyboard() }
            "🌐" -> { isHebrew = !isHebrew; isSymbols = false; buildKeyboard() }
            else -> {
                val toInsert = if (key == "SPACE") " "
                else if (isShifted && !isHebrew && key.length == 1 && key[0].isLetter()) key.uppercase()
                else key
                ic.commitText(toInsert, 1)
                if (isShifted && !capsLock && toInsert.length == 1 && toInsert[0].isLetter()) {
                    isShifted = false
                    buildKeyboard()
                }
            }
        }
    }

    private fun startDeleteRepeat() {
        deleteRunnable = object : Runnable {
            override fun run() {
                currentInputConnection?.deleteSurroundingText(1, 0)
                vibrate(6)
                handler.postDelayed(this, deleteSpeedMs.toLong())
            }
        }
        handler.postDelayed(deleteRunnable!!, (deleteSpeedMs * 3).toLong())
    }

    private fun stopDeleteRepeat() {
        deleteRunnable?.let { handler.removeCallbacks(it) }
        deleteRunnable = null
    }

    private fun startRecording() {
        if (openAiKey.isBlank()) {
            toastStatus("⚠ Add OpenAI key in Gkeys settings")
            return
        }
        try {
            audioRecorder.startRecording()
            isRecording = true
        } catch (_: Exception) {
            toastStatus("⚠ Microphone error")
        }
    }

    private fun cancelRecording() {
        handler.removeCallbacks(longPressRunnable)
        if (isRecording) {
            audioRecorder.cancelRecording()
            isRecording = false
        }
        updateMicVisuals(recording = false)
        toastStatus("")
    }

    private fun stopRecordingAndProcess(action: VoiceAction) {
        if (!isRecording) return
        val file = audioRecorder.stopRecording()
        isRecording = false
        updateMicVisuals(recording = false)
        if (file == null) {
            toastStatus("⚠ Recording failed")
            return
        }

        val effectiveAction = when (action) {
            VoiceAction.DEFAULT -> if (autoPolishEnabled) VoiceAction.POLISH else VoiceAction.RAW
            else -> action
        }

        scope.launch {
            toastStatus(
                when (effectiveAction) {
                    VoiceAction.TRANSLATE -> "🔄 Transcribing + translating…"
                    VoiceAction.DEEP_POLISH -> "✨ Transcribing + deep polish…"
                    VoiceAction.POLISH -> "✨ Transcribing + polishing…"
                    else -> "🔄 Transcribing…"
                }
            )
            val transcriptResult = aiManager.transcribe(file, openAiKey)
            file.delete()
            val transcript = transcriptResult.getOrElse {
                toastStatus("⚠ Transcription failed")
                return@launch
            }
            if (transcript.isBlank()) {
                toastStatus("⚠ Nothing heard")
                return@launch
            }

            val finalText = when (effectiveAction) {
                VoiceAction.TRANSLATE -> {
                    toastStatus("🔄 Translating…")
                    aiManager.polishAndTranslateToHebrew(transcript, openAiKey).getOrElse { transcript }
                }
                VoiceAction.POLISH -> {
                    toastStatus("✨ Polishing…")
                    aiManager.autoPolish(transcript, openAiKey).getOrElse { transcript }
                }
                VoiceAction.DEEP_POLISH -> {
                    if (anthropicKey.isBlank()) {
                        toastStatus("⚠ Add Anthropic key for deep polish")
                        return@launch
                    }
                    toastStatus("✨ Deep polishing…")
                    aiManager.deepPolish(transcript, anthropicKey).getOrElse { transcript }
                }
                else -> transcript
            }

            currentInputConnection?.commitText(finalText, 1)
            toastStatus("")
        }
    }

    private fun toastStatus(msg: String) {
        if (isVoiceOverlay) {
            voiceStatus.text = if (msg.isBlank()) "Listening…" else msg
            return
        }
        if (msg.isBlank()) {
            clipboardManager?.refreshPreview()
        } else {
            tvClipboard.text = msg
        }
    }

    private fun vibrate(ms: Long = 8) {
        if (!vibrationEnabled || vibrationStrength <= 0) return
        val amplitude = (vibrationStrength * 2.55f).toInt().coerceIn(1, 255)
        val duration = (ms * (vibrationStrength / 50f)).toLong().coerceIn(1, 25)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.destroy()
        scope.cancel()
        stopDeleteRepeat()
        handler.removeCallbacks(longPressRunnable)
        audioRecorder.cancelRecording()
    }
}
