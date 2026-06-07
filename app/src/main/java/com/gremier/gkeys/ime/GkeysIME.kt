package com.gremier.gkeys.ime

import android.content.Context
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

    private var isHebrew = false
    private var isSymbols = false
    private var isShifted = false
    private var capsLock = false

    private var keyRepeatMs = GkeysSettings.DEFAULT_KEY_REPEAT_MS
    private var deleteSpeedMs = GkeysSettings.DEFAULT_DELETE_SPEED_MS
    private var vibrationEnabled = true
    private var autoPolishEnabled = true
    private var openAiKey = ""
    private var anthropicKey = ""

    private lateinit var aiManager: AiManager
    private lateinit var audioRecorder: AudioRecorder
    private var isRecording = false
    private var swipeStartX = 0f
    private val SWIPE_THRESHOLD = 120f

    private val handler = Handler(Looper.getMainLooper())
    private var deleteRunnable: Runnable? = null

    private lateinit var vibrator: Vibrator
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var keyboardView: View
    private lateinit var tvStatus: TextView
    private lateinit var tvClipboard: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var btnPolish: ImageButton
    private var clipboardManager: GkeysClipboardManager? = null

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
        tvStatus = keyboardView.findViewById(R.id.tv_status)
        tvClipboard = keyboardView.findViewById(R.id.tv_clipboard)
        btnMic = keyboardView.findViewById(R.id.btn_mic)
        btnPolish = keyboardView.findViewById(R.id.btn_polish)

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
            autoPolishEnabled = GkeysSettings.autoPolishEnabled(this@GkeysIME).first()
            isHebrew = GkeysSettings.defaultLanguage(this@GkeysIME).first() == "he"
            if (::keyboardView.isInitialized) buildKeyboard()
        }
    }

    private fun setupAiStrip() {
        btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { swipeStartX = event.x; startRecording(); true }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - swipeStartX
                    stopRecordingAndProcess(translateToHebrew = deltaX > SWIPE_THRESHOLD)
                    true
                }
                MotionEvent.ACTION_CANCEL -> { audioRecorder.cancelRecording(); isRecording = false; setStatus(""); true }
                else -> false
            }
        }

        btnPolish.setOnClickListener {
            val ic = currentInputConnection ?: return@setOnClickListener
            val selected = ic.getSelectedText(0)?.toString()
            if (!selected.isNullOrBlank()) {
                deepPolishText(selected)
            } else {
                val before = ic.getTextBeforeCursor(300, 0)?.toString() ?: ""
                if (before.isNotBlank()) deepPolishText(before, replaceAll = true)
            }
        }

        tvClipboard.setOnClickListener {
            clipboardManager?.showPanel()
            vibrate()
        }
    }

    private fun buildKeyboard() {
        val container = keyboardView.findViewById<LinearLayout>(R.id.keyboard_rows) ?: return
        container.removeAllViews()
        val rows = when { isSymbols -> symRows; isHebrew -> heRows; else -> enRows }

        rows.forEach { keys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            keys.forEach { key -> row.addView(buildKey(key)) }
            container.addView(row)
        }
    }

    private fun buildKey(label: String): View {
        val isSpace = label == "SPACE"
        val isSpecial = label in listOf("⇧","⌫","↵","?123","ABC","🌐")
        val displayLabel = when {
            isSpace -> "space"
            isShifted && !isHebrew && label.length == 1 && label[0].isLetter() -> label.uppercase()
            else -> label
        }

        val btn = TextView(this).apply {
            text = displayLabel
            textSize = if (isSpace) 11f else 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(when { isSpecial -> 0xFF2A2A3E.toInt(); isSpace -> 0xFF3A3A4E.toInt(); else -> 0xFF1E1E30.toInt() })
            val weight = when { isSpace -> 4f; label in listOf("⌫","↵","⇧") -> 1.5f; else -> 1f }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply { setMargins(2,2,2,2) }
        }

        btn.setOnClickListener { vibrate(); handleKey(label) }

        if (label == "⌫") {
            btn.setOnLongClickListener { startDeleteRepeat(); true }
            btn.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) stopDeleteRepeat()
                false
            }
        }
        return btn
    }

    private fun handleKey(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "⌫" -> {
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    ic.commitText("", 1)
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }
            "↵" -> { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }
            "⇧" -> { if (isShifted && !capsLock) capsLock = true else if (capsLock) { capsLock = false; isShifted = false } else isShifted = true; buildKeyboard() }
            "?123" -> { isSymbols = true; buildKeyboard() }
            "ABC" -> { isSymbols = false; buildKeyboard() }
            "🌐" -> { isHebrew = !isHebrew; isSymbols = false; buildKeyboard() }
            else -> {
                val toInsert = if (key == "SPACE") " " else if (isShifted && !isHebrew && key.length == 1 && key[0].isLetter()) key.uppercase() else key
                ic.commitText(toInsert, 1)
                if (isShifted && !capsLock && toInsert.length == 1 && toInsert[0].isLetter()) { isShifted = false; buildKeyboard() }
            }
        }
    }

    private fun startDeleteRepeat() {
        deleteRunnable = object : Runnable {
            override fun run() { currentInputConnection?.deleteSurroundingText(1, 0); vibrate(20); handler.postDelayed(this, deleteSpeedMs.toLong()) }
        }
        handler.postDelayed(deleteRunnable!!, (deleteSpeedMs * 3).toLong())
    }

    private fun stopDeleteRepeat() { deleteRunnable?.let { handler.removeCallbacks(it) }; deleteRunnable = null }

    private fun startRecording() {
        if (openAiKey.isBlank()) { setStatus("⚠ Add OpenAI key in Gkeys settings"); return }
        try { audioRecorder.startRecording(); isRecording = true; setStatus("🔴 Recording… release to send"); btnMic.setImageResource(R.drawable.ic_mic_active) }
        catch (e: Exception) { setStatus("⚠ Microphone error") }
    }

    private fun stopRecordingAndProcess(translateToHebrew: Boolean) {
        if (!isRecording) return
        val file = audioRecorder.stopRecording()
        isRecording = false
        btnMic.setImageResource(R.drawable.ic_mic)
        if (file == null) { setStatus("⚠ Recording failed"); return }

        scope.launch {
            setStatus(if (translateToHebrew) "🔄 Transcribing + translating…" else "🔄 Transcribing…")
            val transcriptResult = aiManager.transcribe(file, openAiKey)
            file.delete()
            val transcript = transcriptResult.getOrElse { setStatus("⚠ Transcription failed"); return@launch }
            if (transcript.isBlank()) { setStatus("⚠ Nothing heard"); return@launch }

            val finalText = if (translateToHebrew) {
                setStatus("🔄 Translating…")
                aiManager.polishAndTranslateToHebrew(transcript, openAiKey).getOrElse { transcript }
            } else if (autoPolishEnabled) {
                setStatus("✨ Polishing…")
                aiManager.autoPolish(transcript, openAiKey).getOrElse { transcript }
            } else transcript

            currentInputConnection?.commitText(finalText, 1)
            setStatus("")
        }
    }

    private fun deepPolishText(text: String, replaceAll: Boolean = false) {
        if (anthropicKey.isBlank()) { setStatus("⚠ Add Anthropic key in Gkeys settings"); return }
        scope.launch {
            setStatus("✨ Deep polishing…")
            aiManager.deepPolish(text, anthropicKey).onSuccess { polished ->
                val ic = currentInputConnection ?: return@onSuccess
                if (replaceAll) { ic.deleteSurroundingText(text.length, 0) }
                ic.commitText(polished, 1)
                setStatus("")
            }.onFailure { setStatus("⚠ Polish failed") }
        }
    }

    private fun setStatus(msg: String) {
        if (!::tvStatus.isInitialized) return
        tvStatus.text = msg
        tvStatus.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
    }

    private fun vibrate(ms: Long = 30) {
        if (!vibrationEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator.vibrate(ms)
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.destroy()
        scope.cancel()
        stopDeleteRepeat()
        audioRecorder.cancelRecording()
    }
}
