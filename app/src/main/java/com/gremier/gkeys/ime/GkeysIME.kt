package com.gremier.gkeys.ime

import android.Manifest
import android.content.Context
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.*
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.inputmethod.EditorInfo
import android.widget.*
import android.widget.Toast
import com.gremier.gkeys.R
import com.gremier.gkeys.ai.AiManager
import com.gremier.gkeys.ai.AudioRecorder
import com.gremier.gkeys.ai.GoogleSpeechStreamingClient
import com.gremier.gkeys.clipboard.ClipboardItem
import com.gremier.gkeys.clipboard.GkeysClipboardManager
import com.gremier.gkeys.ime.bubble.VoiceBubbleController
import com.gremier.gkeys.ime.bubble.VoiceBubbleListener
import com.gremier.gkeys.ime.bubble.VoiceBubbleState
import com.gremier.gkeys.ime.emoji.EmojiCatalog
import com.gremier.gkeys.ime.layout.KeyboardLayoutMetrics
import com.gremier.gkeys.ime.layout.KeyboardLayoutMetrics.Profile
import com.gremier.gkeys.ime.touch.AdaptiveTouchIntelligence
import com.gremier.gkeys.ime.touch.TouchInputResolver
import com.gremier.gkeys.ime.touch.TouchPersonalization
import com.gremier.gkeys.settings.AppVersionTracker
import com.gremier.gkeys.settings.GkeysSettings
import com.gremier.gkeys.settings.SecureApiKeyStore
import com.gremier.gkeys.settings.SettingsActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class GkeysIME : InputMethodService() {

    private enum class VoiceAction { DEFAULT, TRANSLATE }

    private var isHebrew = false
    private var isSymbols = false
    private var isNumpad = false
    private var isShifted = false
    private var capsLock = false
    private var oneHandedMode = GkeysSettings.ONE_HANDED_OFF
    private var rightHandedMode = false
    private var keySizePreset = GkeysSettings.KEY_SIZE_DEFAULT
    private var universalKeyboardHeightDp = GkeysSettings.DEFAULT_KEYBOARD_HEIGHT_DP
    private var layoutProfile: Profile = KeyboardLayoutMetrics.profile(
        GkeysSettings.KEY_SIZE_DEFAULT, false
    )
    private var keyboardVisible = true

    private var keyRepeatMs = GkeysSettings.DEFAULT_KEY_REPEAT_MS
    private var deleteSpeedMs = GkeysSettings.DEFAULT_DELETE_SPEED_MS
    private var vibrationEnabled = true
    private var vibrationStrength = GkeysSettings.DEFAULT_VIBRATION_STRENGTH
    private var polishLevel = GkeysSettings.DEFAULT_POLISH_LEVEL
    private var voiceTranslateFrom = GkeysSettings.DEFAULT_VOICE_TRANSLATE_FROM
    private var voiceTranslateTo = GkeysSettings.DEFAULT_VOICE_TRANSLATE_TO
    private var openAiKey = ""
    private var anthropicKey = ""
    private var googleSttKey = ""
    private var voiceBubbleModeActive = false
    private var voiceBubbleController: VoiceBubbleController? = null

    private lateinit var aiManager: AiManager
    private lateinit var audioRecorder: AudioRecorder
    private var isRecording = false
    private var recordingForGhostwriter = false
    private var isVoiceOverlay = false
    private var isGhostwriterOverlay = false
    private var longPressTriggered = false
    private var pendingVoiceAction = VoiceAction.DEFAULT
    private var micGestureTracking = false
    private var wandGestureTracking = false
    private var wandLongPressTriggered = false
    private var liveSttActive = false
    private var liveSttPartialLen = 0
    private var googleSpeechClient: GoogleSpeechStreamingClient? = null

    private val handler = Handler(Looper.getMainLooper())
    private var deleteRunnable: Runnable? = null
    private val longPressRunnable = Runnable { onMicLongPress() }
    private val wandLongPressRunnable = Runnable { onWandLongPress() }

    private lateinit var vibrator: Vibrator
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var keyboardView: View
    private lateinit var keyboardContent: LinearLayout
    private lateinit var keyboardPanel: FrameLayout
    private lateinit var keyboardKeysHost: FrameLayout
    private lateinit var keyboardRows: KeyboardTouchLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvClipboard: TextView
    private lateinit var tvClipboardHint: TextView
    private lateinit var ivClipboardPreview: ImageView
    private lateinit var clipboardArea: View
    private lateinit var btnMic: ImageView
    private lateinit var btnMicContainer: FrameLayout
    private lateinit var micAiGlow: View
    private lateinit var micAiShimmer: View
    private lateinit var micAiSparkles: ImageView
    private lateinit var btnKeyboard: ImageButton
    private lateinit var btnVoiceBubble: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnWand: ImageButton
    private lateinit var btnPolishFormal: TextView
    private lateinit var btnPolishNatural: TextView
    private lateinit var btnPolishRaw: TextView
    private lateinit var voiceOverlay: View
    private lateinit var voiceStatus: TextView
    private lateinit var voiceTranslateHint: TextView
    private lateinit var ghostwriterOverlay: View
    private lateinit var ghostwriterStatus: TextView
    private lateinit var btnGhostwriterClose: ImageButton
    private lateinit var ghostwriterContent: View
    private var isPolishing = false
    private var clipboardManager: GkeysClipboardManager? = null
    private var isDeleteRepeating = false
    private var keyboardHeightPx = 0
    private lateinit var touchPersonalization: TouchPersonalization
    private lateinit var adaptiveTouch: AdaptiveTouchIntelligence
    private lateinit var touchResolver: TouchInputResolver
    private val touchKeyViews = mutableListOf<Triple<View, String, Int>>()
    private var lastTypedChar: Char? = null
    private var currentWordPrefix = ""
    private var awaitingTouchCorrection = false
    private var adaptiveTouchEnabled = GkeysSettings.DEFAULT_ADAPTIVE_TOUCH

    private var emojiPanelVisible = false
    private var emojiOpenedFromNumpad = false
    private var micIsProcessing = false
    private var micProcessingAnimator: AnimatorSet? = null
    private var micSparkleRotateAnimator: ObjectAnimator? = null
    private var micShimmerRotateAnimator: ObjectAnimator? = null
    private var micIconPulseAnimator: ObjectAnimator? = null

    companion object {
        private const val LONG_PRESS_MS = 380L
        private const val KEY_EMOJI_PANEL = "\uE000"

        private val letterLongPressAlts = mapOf(
            "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
            "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
        )

        private val punctuationLongPressAlts = mapOf(
            "," to KEY_EMOJI_PANEL,
            "?" to "!"
        )
    }

    /** Fixed 5-column grid: digits always in columns 1–3 so 1/4/7, 2/5/8, 3/6/9 align. */
    private val numpadRows = listOf(
        listOf("-", "1", "2", "3", "."),
        listOf("+", "4", "5", "6", ","),
        listOf("*", "7", "8", "9", "/"),
        listOf("#", "(", ")", "0", "?"),
        listOf("ABC", "SPACE", "⌫", ".", "↵")
    )

    private fun isNumpadDigit(label: String): Boolean =
        label.length == 1 && label[0].isDigit()

    private fun openEmojiPanel() {
        if (!emojiPanelVisible) {
            emojiOpenedFromNumpad = isNumpad
        }
        emojiPanelVisible = true
        buildKeyboard()
    }

    private fun closeEmojiPanel() {
        emojiPanelVisible = false
        buildKeyboard()
    }

    private fun toggleEmojiPanel() {
        if (emojiPanelVisible) closeEmojiPanel() else openEmojiPanel()
    }

    private val enRows = listOf(
        listOf("q","w","e","r","t","y","u","i","o","p"),
        listOf("a","s","d","f","g","h","j","k","l"),
        listOf("⇧","z","x","c","v","b","n","m","⌫"),
        listOf("?123","🌐",",","SPACE",".","?","↵")
    )

    override fun onConfigureWindow(window: android.view.Window, isFullscreen: Boolean, isExtract: Boolean) {
        super.onConfigureWindow(window, isFullscreen, isExtract)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    private fun forceLayoutLtr(view: View) {
        view.layoutDirection = View.LAYOUT_DIRECTION_LTR
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                forceLayoutLtr(view.getChildAt(i))
            }
        }
    }

    /** Gboard visual order: left edge of keyboard → right edge (see reference screenshot). */
    private val heRowsGboard = listOf(
        listOf("'","-","ק","ר","א","ט","ו","ן","ם","פ"),
        listOf("ש","ד","ג","כ","ע","י","ח","ל","ך","ף"),
        listOf("ז","ס","ב","ה","נ","מ","צ","ת","ץ","⌫"),
        listOf("?123","🌐",",","SPACE",".","?","↵")
    )

    private fun orderKeysForDisplay(keys: List<String>): List<String> {
        if (!isHebrew || isSymbols) return keys
        // If the system still mirrors rows (Hebrew locale), reverse to match Gboard visually.
        return if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            keys.reversed()
        } else {
            keys
        }
    }

    private val symRows = listOf(
        listOf("1","2","3","4","5","6","7","8","9","0"),
        listOf("!","@","#","$","%","^","&","*","(",")"),
        listOf("-","_","=","+","[","]","{","}","\\"),
        listOf(";","'","\"","<",">","?","/","⌫"),
        listOf("ABC","🌐",",","SPACE",".","?","↵")
    )

    override fun onCreate() {
        super.onCreate()
        try {
            aiManager = AiManager(this)
            audioRecorder = AudioRecorder(this)
            vibrator = initVibrator()
            voiceBubbleController = VoiceBubbleController(this, voiceBubbleListener)
            loadSettings()
        } catch (e: Throwable) {
            android.util.Log.e("GkeysIME", "onCreate failed", e)
        }
    }

    private val voiceBubbleListener = object : VoiceBubbleListener {
        override fun onBubbleTap() = handleBubbleMicTap()
        override fun onBubbleSwipeUp() = exitVoiceBubbleMode(showKeyboard = true)
        override fun onShowKeyboardRequested() = exitVoiceBubbleMode(showKeyboard = true)
        override fun onVibrate() = vibrate()
    }

    private fun initVibrator(): Vibrator {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        } catch (e: Exception) {
            android.util.Log.e("GkeysIME", "Vibrator init failed", e)
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onCreateInputView(): View {
        return try {
            buildInputView()
        } catch (e: Throwable) {
            android.util.Log.e("GkeysIME", "onCreateInputView failed", e)
            com.gremier.gkeys.diag.CrashLogger.record(this, e)
            // Fallback: never return null / crash the IME host.
            FrameLayout(this)
        }
    }

    private fun buildInputView(): View {
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView.layoutDirection = View.LAYOUT_DIRECTION_LTR

        keyboardContent = keyboardView.findViewById(R.id.keyboard_content)
        keyboardPanel = keyboardView.findViewById(R.id.keyboard_panel)
        keyboardKeysHost = keyboardView.findViewById(R.id.keyboard_keys_host)
        keyboardRows = keyboardView.findViewById(R.id.keyboard_rows)
        tvStatus = keyboardView.findViewById(R.id.tv_status)
        tvClipboard = keyboardView.findViewById(R.id.tv_clipboard)
        tvClipboardHint = keyboardView.findViewById(R.id.tv_clipboard_hint)
        ivClipboardPreview = keyboardView.findViewById(R.id.iv_clipboard_preview)
        clipboardArea = keyboardView.findViewById(R.id.clipboard_area)
        btnMic = keyboardView.findViewById(R.id.btn_mic)
        btnMicContainer = keyboardView.findViewById(R.id.btn_mic_container)
        micAiGlow = keyboardView.findViewById(R.id.mic_ai_glow)
        micAiShimmer = keyboardView.findViewById(R.id.mic_ai_shimmer)
        micAiSparkles = keyboardView.findViewById(R.id.mic_ai_sparkles)
        btnKeyboard = keyboardView.findViewById(R.id.btn_keyboard)
        btnVoiceBubble = keyboardView.findViewById(R.id.btn_voice_bubble)
        btnSettings = keyboardView.findViewById(R.id.btn_settings)
        btnWand = keyboardView.findViewById(R.id.btn_wand)
        btnPolishFormal = keyboardView.findViewById(R.id.btn_polish_formal)
        btnPolishNatural = keyboardView.findViewById(R.id.btn_polish_natural)
        btnPolishRaw = keyboardView.findViewById(R.id.btn_polish_raw)
        voiceOverlay = keyboardView.findViewById(R.id.voice_overlay)
        voiceStatus = keyboardView.findViewById(R.id.voice_status)
        voiceTranslateHint = keyboardView.findViewById(R.id.voice_translate_hint)
        ghostwriterOverlay = keyboardView.findViewById(R.id.ghostwriter_overlay)
        ghostwriterStatus = keyboardView.findViewById(R.id.ghostwriter_status)
        btnGhostwriterClose = keyboardView.findViewById(R.id.btn_ghostwriter_close)
        ghostwriterContent = keyboardView.findViewById(R.id.ghostwriter_content)

        btnGhostwriterClose.setOnClickListener {
            vibrate()
            cancelGhostwriter()
        }
        ghostwriterContent.setOnClickListener {
            if (isGhostwriterOverlay && isRecording && recordingForGhostwriter) {
                stopGhostwriterAndProcess()
            }
        }

        touchPersonalization = TouchPersonalization(this, scope)
        touchPersonalization.load()
        adaptiveTouch = AdaptiveTouchIntelligence(this, scope)
        adaptiveTouch.load()
        touchResolver = TouchInputResolver(touchPersonalization, adaptiveTouch)
        keyboardRows.touchResolver = touchResolver
        keyboardRows.onKeyTap = { key ->
            vibrate()
            handleKey(key)
        }
        keyboardRows.onBackspaceDown = { startDeleteRepeat() }
        keyboardRows.onBackspaceUp = { stopDeleteRepeat() }
        keyboardRows.keyLongPressAlts = letterLongPressAlts + punctuationLongPressAlts
        keyboardRows.onKeyLongPress = { alt ->
            vibrate()
            when (alt) {
                KEY_EMOJI_PANEL -> openEmojiPanel()
                else -> handleKey(alt)
            }
        }

        val overlayContainer = keyboardView.findViewById<FrameLayout>(R.id.clipboard_overlay_container)
        clipboardManager = GkeysClipboardManager(
            context = this,
            overlayContainer = overlayContainer,
            previewContainer = clipboardArea,
            previewView = tvClipboard,
            previewImage = ivClipboardPreview,
            previewHint = tvClipboardHint,
            onPasteItem = { item -> pasteClipboardItem(item) },
            onVibrate = { vibrate() },
            onPanelOpen = { keyboardKeysHost.visibility = View.GONE },
            onPanelClose = { keyboardKeysHost.visibility = View.VISIBLE }
        )
        clipboardManager?.setupPreviewInteractions()

        forceLayoutLtr(keyboardView)

        setupAiStrip()
        applyUniversalShellHeight()
        buildKeyboard()
        attachTouchTargetLayoutWatcher(keyboardRows)
        return keyboardView
    }

    override fun onEvaluateInputViewShown(): Boolean = !voiceBubbleModeActive

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        try {
            refreshApiKeys()
            scope.launch {
                voiceBubbleModeActive = GkeysSettings.voiceBubbleModeActive(this@GkeysIME).first()
                if (voiceBubbleModeActive) {
                    if (voiceBubbleController?.canDrawOverlay() == true) {
                        voiceBubbleController?.show()
                    } else {
                        voiceBubbleModeActive = false
                        GkeysSettings.saveVoiceBubbleModeActive(this@GkeysIME, false)
                        requestShowSelf(0)
                    }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("GkeysIME", "onStartInput failed", e)
        }
    }

    override fun onFinishInput() {
        try {
            if (!isRecording && !micIsProcessing) {
                voiceBubbleController?.hide(animate = true)
            }
        } catch (e: Exception) {
            android.util.Log.e("GkeysIME", "onFinishInput failed", e)
        }
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        if (voiceBubbleModeActive) {
            try {
                refreshApiKeys()
                loadSettings()
                clipboardManager?.startListening()
                voiceBubbleController?.show()
                requestHideSelf(0)
            } catch (e: Throwable) {
                android.util.Log.e("GkeysIME", "onStartInputView bubble failed", e)
            }
            return
        }
        super.onStartInputView(info, restarting)
        try {
            if (AppVersionTracker.noteCurrentVersion(this)) {
                showErrorToast("Gkeys updated — pick Gkeys again in your keyboard switcher")
            }
            refreshApiKeys()
            loadSettings()
            clipboardManager?.startListening()
        } catch (e: Throwable) {
            android.util.Log.e("GkeysIME", "onStartInputView failed", e)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        try {
            if (voiceBubbleModeActive) {
                hideVoiceOverlay()
                hideGhostwriterOverlay()
                clipboardManager?.stopListening()
                clipboardManager?.hidePanel()
                if (!isRecording && !micIsProcessing) {
                    voiceBubbleController?.show()
                }
                super.onFinishInputView(finishingInput)
                return
            }
            cancelRecording()
            cancelGhostwriter()
            stopLiveStt()
            stopMicProcessingAnimation()
            hideVoiceOverlay()
            hideGhostwriterOverlay()
            voiceBubbleController?.hide(animate = false)
            clipboardManager?.stopListening()
            clipboardManager?.hidePanel()
        } catch (e: Exception) {
            android.util.Log.e("GkeysIME", "onFinishInputView failed", e)
        }
        super.onFinishInputView(finishingInput)
    }

    private fun loadSettings() {
        scope.launch {
            try {
                openAiKey = GkeysSettings.openAiKey(this@GkeysIME).first()
                anthropicKey = GkeysSettings.anthropicKey(this@GkeysIME).first()
                keyRepeatMs = GkeysSettings.keyRepeatSpeed(this@GkeysIME).first()
                deleteSpeedMs = GkeysSettings.deleteSpeed(this@GkeysIME).first()
                vibrationEnabled = GkeysSettings.vibrationEnabled(this@GkeysIME).first()
                vibrationStrength = GkeysSettings.vibrationStrength(this@GkeysIME).first()
                polishLevel = GkeysSettings.polishLevel(this@GkeysIME).first()
                voiceTranslateFrom = GkeysSettings.voiceTranslateFrom(this@GkeysIME).first()
                voiceTranslateTo = GkeysSettings.voiceTranslateTo(this@GkeysIME).first()
                isHebrew = GkeysSettings.defaultLanguage(this@GkeysIME).first() == "he"
                oneHandedMode = GkeysSettings.oneHandedMode(this@GkeysIME).first()
                rightHandedMode = GkeysSettings.rightHandedMode(this@GkeysIME).first()
                keySizePreset = GkeysSettings.keySizePreset(this@GkeysIME).first()
                universalKeyboardHeightDp = GkeysSettings.keyboardHeightDp(this@GkeysIME).first()
                voiceBubbleModeActive = GkeysSettings.voiceBubbleModeActive(this@GkeysIME).first()
                adaptiveTouchEnabled = GkeysSettings.adaptiveTouchEnabled(this@GkeysIME).first()
                adaptiveTouch.setEnabled(adaptiveTouchEnabled)
                layoutProfile = KeyboardLayoutMetrics.profile(keySizePreset, rightHandedMode)
                keyboardHeightPx = 0
                if (::touchResolver.isInitialized) {
                    touchResolver.rightHandedMode = isLayoutRightBiased()
                }
                if (::keyboardView.isInitialized) {
                    applyUniversalShellHeight()
                    applyOneHandedMode()
                    updatePolishLevelToggle()
                    buildKeyboard()
                }
            } catch (e: Throwable) {
                android.util.Log.e("GkeysIME", "loadSettings failed", e)
                com.gremier.gkeys.diag.CrashLogger.record(this@GkeysIME, e)
            }
        }
    }

    private fun setupAiStrip() {
        btnMic.isClickable = false
        btnMic.isFocusable = false
        btnMicContainer.isClickable = true
        btnMicContainer.isFocusable = true

        btnMicContainer.setOnTouchListener { _, event ->
            try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        micGestureTracking = true
                        longPressTriggered = false
                        pendingVoiceAction = VoiceAction.DEFAULT
                        handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        finishMicGesture()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        finishMicGesture(cancelled = true)
                        true
                    }
                    else -> true
                }
            } catch (e: Exception) {
                android.util.Log.e("GkeysIME", "mic touch failed", e)
                true
            }
        }

        btnKeyboard.setOnClickListener {
            vibrate()
            if (isVoiceOverlay) {
                cancelRecording()
                hideVoiceOverlay()
            }
            if (isGhostwriterOverlay) {
                cancelGhostwriter()
            }
            if (liveSttActive) {
                stopLiveStt()
            }
            if (emojiPanelVisible) {
                emojiPanelVisible = false
            } else {
                isNumpad = !isNumpad
                if (isNumpad) {
                    isSymbols = false
                }
            }
            buildKeyboard()
            updateNumpadButton()
        }

        btnSettings.setOnClickListener {
            vibrate()
            openAppSettings()
        }

        btnVoiceBubble.setOnClickListener {
            vibrate()
            enterVoiceBubbleMode(fromKeyboard = true)
        }

        btnWand.setOnTouchListener { _, event ->
            try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        wandGestureTracking = true
                        wandLongPressTriggered = false
                        handler.postDelayed(wandLongPressRunnable, LONG_PRESS_MS)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        finishWandGesture()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        finishWandGesture(cancelled = true)
                        true
                    }
                    else -> true
                }
            } catch (e: Exception) {
                android.util.Log.e("GkeysIME", "wand touch failed", e)
                true
            }
        }
        btnPolishFormal.setOnClickListener { setPolishLevel(GkeysSettings.POLISH_FORMAL) }
        btnPolishNatural.setOnClickListener { setPolishLevel(GkeysSettings.POLISH_NATURAL) }
        btnPolishRaw.setOnClickListener { setPolishLevel(GkeysSettings.POLISH_RAW) }
        updatePolishLevelToggle()
        updateNumpadButton()
    }

    private fun setPolishLevel(level: String) {
        if (polishLevel == level) return
        polishLevel = level
        scope.launch { GkeysSettings.savePolishLevel(this@GkeysIME, polishLevel) }
        updatePolishLevelToggle()
        vibrate()
        toastStatus("${GkeysSettings.polishLevelLabel(polishLevel)} dictation")
        handler.postDelayed({ toastStatus("") }, 1000)
    }

    private fun updatePolishLevelToggle() {
        if (!::btnPolishFormal.isInitialized) return
        val activeColor = 0xFFFFFFFF.toInt()
        val inactiveColor = 0xFF9CA3AF.toInt()
        val segments = listOf(
            btnPolishFormal to GkeysSettings.POLISH_FORMAL,
            btnPolishNatural to GkeysSettings.POLISH_NATURAL,
            btnPolishRaw to GkeysSettings.POLISH_RAW
        )
        for ((view, level) in segments) {
            val selected = polishLevel == level
            view.setTextColor(if (selected) activeColor else inactiveColor)
            view.setBackgroundResource(
                if (selected) R.drawable.polish_toggle_segment_active else 0
            )
        }
    }

    private fun finishWandGesture(cancelled: Boolean = false) {
        handler.removeCallbacks(wandLongPressRunnable)
        wandGestureTracking = false
        if (wandLongPressTriggered) {
            wandLongPressTriggered = false
        } else if (!cancelled) {
            handleWandTap()
        }
    }

    /** Short tap: toggle Google live speech-to-text into the text field. */
    private fun handleWandTap() {
        if (isGhostwriterOverlay) return
        refreshApiKeys()
        vibrate()
        if (!hasMicPermission()) {
            showErrorToast("Allow microphone for Gkeys")
            openAppForMicPermission()
            return
        }
        if (googleSttKey.isBlank()) {
            showErrorToast("Add Google Speech-to-Text API key in Gkeys settings")
            openAppSettings()
            return
        }
        if (liveSttActive) {
            stopLiveStt()
        } else {
            startLiveStt()
        }
    }

    /** Long press: open AI ghostwriter overlay (stays open until closed). */
    private fun onWandLongPress() {
        refreshApiKeys()
        if (!hasMicPermission()) {
            showErrorToast("Allow microphone for Gkeys")
            openAppForMicPermission()
            return
        }
        if (openAiKey.isBlank()) {
            showErrorToast("Add OpenAI API key in Gkeys settings")
            openAppSettings()
            return
        }
        stopLiveStt()
        wandLongPressTriggered = true
        vibrate(12)
        if (isGhostwriterOverlay) return
        showGhostwriterOverlay()
        startGhostwriterRecording()
    }

    private fun sttLanguageCode(): String = when {
        isHebrew -> "he-IL"
        else -> "en-US"
    }

    private fun startLiveStt() {
        if (liveSttActive) return
        liveSttPartialLen = 0
        googleSpeechClient = GoogleSpeechStreamingClient(
            apiKey = googleSttKey,
            languageCode = sttLanguageCode(),
            onPartial = { text -> updateLiveSttPartial(text) },
            onFinal = { text -> commitLiveSttFinal(text) },
            onError = { error ->
                android.util.Log.e("GkeysIME", "Live STT failed", error)
                showErrorToast("Live dictation failed")
                stopLiveStt()
            }
        )
        googleSpeechClient?.start(scope)
        liveSttActive = true
        toastStatus("Live dictation… tap wand to stop")
    }

    private fun updateLiveSttPartial(text: String) {
        val ic = currentInputConnection ?: return
        if (liveSttPartialLen > 0) {
            ic.deleteSurroundingText(liveSttPartialLen, 0)
        }
        if (text.isNotEmpty()) {
            ic.commitText(text, 1)
            liveSttPartialLen = text.length
        } else {
            liveSttPartialLen = 0
        }
    }

    private fun commitLiveSttFinal(text: String) {
        val ic = currentInputConnection ?: return
        if (liveSttPartialLen > 0) {
            ic.deleteSurroundingText(liveSttPartialLen, 0)
        }
        liveSttPartialLen = 0
        if (text.isNotEmpty()) {
            ic.commitText("$text ", 1)
        }
    }

    private fun stopLiveStt() {
        if (!liveSttActive && googleSpeechClient == null) return
        googleSpeechClient?.stop()
        googleSpeechClient = null
        liveSttActive = false
        liveSttPartialLen = 0
        toastStatus("")
    }

    private fun showGhostwriterOverlay() {
        isGhostwriterOverlay = true
        keyboardKeysHost.visibility = View.GONE
        ghostwriterOverlay.visibility = View.VISIBLE
        ghostwriterStatus.text = "Tap when done speaking"
    }

    private fun hideGhostwriterOverlay() {
        isGhostwriterOverlay = false
        ghostwriterOverlay.visibility = View.GONE
        if (keyboardVisible && clipboardManager?.isPanelOpen() != true && !isVoiceOverlay) {
            keyboardKeysHost.visibility = View.VISIBLE
        }
    }

    private fun startGhostwriterRecording() {
        refreshApiKeys()
        if (openAiKey.isBlank() || !hasMicPermission()) return
        try {
            audioRecorder.startRecording()
            isRecording = true
            recordingForGhostwriter = true
            ghostwriterStatus.text = "Listening… tap when done"
        } catch (_: Exception) {
            hideGhostwriterOverlay()
            showErrorToast("Microphone error — check permission in Gkeys app")
        }
    }

    private fun cancelGhostwriter() {
        if (isRecording && recordingForGhostwriter) {
            audioRecorder.cancelRecording()
            isRecording = false
            recordingForGhostwriter = false
        }
        hideGhostwriterOverlay()
        ghostwriterStatus.text = "Tap when done speaking"
    }

    private fun stopGhostwriterAndProcess() {
        if (!isRecording || !recordingForGhostwriter) return
        val file = audioRecorder.stopRecording()
        isRecording = false
        recordingForGhostwriter = false
        if (file == null) {
            hideGhostwriterOverlay()
            showErrorToast("Recording failed")
            return
        }

        ghostwriterStatus.text = "Writing…"
        scope.launch {
            val durationMs = audioRecorder.lastRecordingDurationMs()
            val transcriptResult = aiManager.transcribe(file, openAiKey, durationMs)
            file.delete()
            transcriptResult.onFailure { error ->
                hideGhostwriterOverlay()
                showErrorToast(
                    if (error.message == "Nothing heard" || error.message == "Recording too short") {
                        "Nothing heard"
                    } else {
                        "Transcription failed"
                    }
                )
                return@launch
            }
            val prompt = transcriptResult.getOrNull().orEmpty()
            if (prompt.isBlank()) {
                hideGhostwriterOverlay()
                showErrorToast("Nothing heard")
                return@launch
            }

            ghostwriterStatus.text = "Writing…"
            val written = aiManager.ghostwrite(prompt, openAiKey)
            hideGhostwriterOverlay()
            written.onSuccess { text ->
                currentInputConnection?.commitText(text, 1)
                vibrate()
            }.onFailure {
                showErrorToast("Ghostwriter failed — try again")
            }
        }
    }

    private fun finishMicGesture(cancelled: Boolean = false) {
        handler.removeCallbacks(longPressRunnable)
        micGestureTracking = false
        if (longPressTriggered) {
            hideVoiceOverlay()
            if (isRecording && !cancelled) {
                stopRecordingAndProcess(pendingVoiceAction)
            } else if (cancelled && isRecording) {
                cancelRecording()
            }
            longPressTriggered = false
        } else if (!cancelled) {
            handleMicTap()
        }
    }

    private fun handleMicTap() {
        if (isGhostwriterOverlay) return
        refreshApiKeys()
        vibrate()
        if (!hasMicPermission()) {
            showErrorToast("Allow microphone for Gkeys")
            openAppForMicPermission()
            return
        }
        if (openAiKey.isBlank()) {
            showErrorToast("Add OpenAI API key in Gkeys settings")
            openAppSettings()
            return
        }
        if (isRecording) {
            stopRecordingAndProcess(VoiceAction.DEFAULT)
        } else {
            startRecording()
            updateMicVisuals(recording = true)
            toastStatus("Listening… tap mic to stop")
        }
    }

    private fun refreshApiKeys() {
        try {
            openAiKey = SecureApiKeyStore.getOpenAiKey(this)
            anthropicKey = SecureApiKeyStore.getAnthropicKey(this)
            googleSttKey = SecureApiKeyStore.getGoogleSttKey(this)
        } catch (e: Throwable) {
            android.util.Log.e("GkeysIME", "refreshApiKeys failed", e)
            openAiKey = ""
            anthropicKey = ""
            googleSttKey = ""
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun openAppForOverlayPermission() {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SettingsActivity.EXTRA_REQUEST_OVERLAY_PERMISSION, true)
        })
    }

    private fun enterVoiceBubbleMode(fromKeyboard: Boolean) {
        val controller = voiceBubbleController ?: return
        if (!controller.canDrawOverlay()) {
            showErrorToast("Allow display over other apps for Voice Bubble")
            openAppForOverlayPermission()
            return
        }
        voiceBubbleModeActive = true
        scope.launch { GkeysSettings.saveVoiceBubbleModeActive(this@GkeysIME, true) }
        controller.show()
        if (fromKeyboard) {
            requestHideSelf(0)
        }
    }

    private fun exitVoiceBubbleMode(showKeyboard: Boolean) {
        voiceBubbleModeActive = false
        scope.launch { GkeysSettings.saveVoiceBubbleModeActive(this@GkeysIME, false) }
        stopLiveStt()
        cancelGhostwriter()
        if (isRecording && !recordingForGhostwriter) {
            cancelRecording()
        }
        if (showKeyboard) {
            voiceBubbleController?.animateExpandHandoff(
                onMidpoint = { },
                onEnd = { requestShowSelf(0) }
            ) ?: requestShowSelf(0)
        } else {
            voiceBubbleController?.hide()
        }
    }

    private fun handleBubbleMicTap() {
        refreshApiKeys()
        if (!hasMicPermission()) {
            showErrorToast("Allow microphone for Gkeys")
            openAppForMicPermission()
            return
        }
        if (openAiKey.isBlank()) {
            showErrorToast("Add OpenAI API key in Gkeys settings")
            openAppSettings()
            return
        }
        if (isRecording) {
            voiceBubbleController?.setState(VoiceBubbleState.PROCESSING)
            stopRecordingAndProcess(VoiceAction.DEFAULT)
        } else {
            try {
                audioRecorder.startRecording()
                isRecording = true
                recordingForGhostwriter = false
                voiceBubbleController?.setState(VoiceBubbleState.RECORDING)
            } catch (_: Exception) {
                showErrorToast("Microphone error — check permission in Gkeys app")
                voiceBubbleController?.setState(VoiceBubbleState.IDLE)
            }
        }
    }

    private fun openAppForMicPermission() {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SettingsActivity.EXTRA_REQUEST_MIC_PERMISSION, true)
        })
    }

    private fun onMicLongPress() {
        if (isGhostwriterOverlay) return
        refreshApiKeys()
        if (!hasMicPermission()) {
            showErrorToast("Allow microphone for Gkeys")
            openAppForMicPermission()
            return
        }
        if (openAiKey.isBlank()) {
            showErrorToast("Add OpenAI API key in Gkeys settings")
            openAppSettings()
            return
        }
        longPressTriggered = true
        pendingVoiceAction = VoiceAction.TRANSLATE
        vibrate(12)
        showVoiceOverlay()
        if (!isRecording) {
            startRecording()
        }
        updateMicVisuals(recording = true)
    }

    private fun updateNumpadButton() {
        btnKeyboard.setImageResource(
            if (isNumpad) R.drawable.ic_keyboard_grid else R.drawable.ic_dialpad
        )
        btnKeyboard.contentDescription = if (isNumpad) "Show letters" else "Number pad"
    }

    private fun showVoiceOverlay() {
        isVoiceOverlay = true
        keyboardKeysHost.visibility = View.GONE
        voiceOverlay.visibility = View.VISIBLE
        val fromName = GkeysSettings.languageDisplayName(voiceTranslateFrom)
        val toName = GkeysSettings.languageDisplayName(voiceTranslateTo)
        voiceStatus.text = "Speak in $fromName…"
        voiceTranslateHint.text = "Release to translate → $toName"
    }

    private fun hideVoiceOverlay() {
        isVoiceOverlay = false
        voiceOverlay.visibility = View.GONE
        if (keyboardVisible && clipboardManager?.isPanelOpen() != true && !isGhostwriterOverlay) {
            keyboardKeysHost.visibility = View.VISIBLE
        }
        updateMicVisuals(recording = isRecording)
    }

    private fun showKeyboardPanel() {
        keyboardVisible = true
        if (clipboardManager?.isPanelOpen() != true && !isVoiceOverlay && !isGhostwriterOverlay) {
            keyboardKeysHost.visibility = View.VISIBLE
        }
    }

    /** Locks toolbar + key area to one fixed height for every mode and overlay. */
    private fun applyUniversalShellHeight() {
        if (!::keyboardPanel.isInitialized) return
        val keysPx = dp(universalKeyboardHeightDp)
        val shellPx = dp(KeyboardLayoutMetrics.shellHeightDp(universalKeyboardHeightDp))

        keyboardPanel.layoutParams = keyboardPanel.layoutParams.apply { height = keysPx }
        keyboardKeysHost.layoutParams = keyboardKeysHost.layoutParams.apply { height = keysPx }
        keyboardRows.layoutParams = keyboardRows.layoutParams.apply { height = keysPx }

        keyboardContent.layoutParams = keyboardContent.layoutParams.apply {
            height = shellPx
        }
        keyboardContent.minimumHeight = shellPx

        val overlayContainer = keyboardView.findViewById<FrameLayout>(R.id.clipboard_overlay_container)
        overlayContainer?.layoutParams = overlayContainer.layoutParams.apply { height = keysPx }
        voiceOverlay.layoutParams = voiceOverlay.layoutParams.apply { height = keysPx }
        ghostwriterOverlay.layoutParams = ghostwriterOverlay.layoutParams.apply { height = keysPx }
    }

    private fun updateMicVisuals(recording: Boolean) {
        if (micIsProcessing) return
        btnMic.setImageResource(R.drawable.ic_mic_white)
        btnMic.alpha = 1f
        btnMicContainer.scaleX = 1f
        btnMicContainer.scaleY = 1f
        btnMicContainer.setBackgroundResource(
            if (recording) R.drawable.ai_mic_bg_active else R.drawable.ai_mic_bg
        )
    }

    private fun startMicProcessingAnimation() {
        if (micIsProcessing) return
        micIsProcessing = true
        if (voiceBubbleModeActive || !isInputViewShown) {
            voiceBubbleController?.setState(VoiceBubbleState.PROCESSING)
            return
        }
        stopMicProcessingAnimatorsOnly()

        micAiGlow.visibility = View.VISIBLE
        micAiShimmer.visibility = View.VISIBLE
        micAiSparkles.visibility = View.VISIBLE
        micAiGlow.alpha = 0.4f
        micAiShimmer.alpha = 0.55f
        micAiSparkles.alpha = 0.85f
        btnMicContainer.setBackgroundResource(R.drawable.ai_mic_bg_processing)

        val glowPulse = ObjectAnimator.ofFloat(micAiGlow, View.ALPHA, 0.25f, 1f).apply {
            duration = 750
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val containerPulse = ObjectAnimator.ofPropertyValuesHolder(
            btnMicContainer,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)
        ).apply {
            duration = 750
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        micProcessingAnimator = AnimatorSet().apply {
            playTogether(glowPulse, containerPulse)
            start()
        }

        micSparkleRotateAnimator = ObjectAnimator.ofFloat(micAiSparkles, View.ROTATION, 0f, 360f).apply {
            duration = 2200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        micShimmerRotateAnimator = ObjectAnimator.ofFloat(micAiShimmer, View.ROTATION, 0f, 360f).apply {
            duration = 1400
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        micIconPulseAnimator = ObjectAnimator.ofFloat(btnMic, View.ALPHA, 0.65f, 1f).apply {
            duration = 600
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopMicProcessingAnimatorsOnly() {
        micProcessingAnimator?.cancel()
        micSparkleRotateAnimator?.cancel()
        micShimmerRotateAnimator?.cancel()
        micIconPulseAnimator?.cancel()
        micProcessingAnimator = null
        micSparkleRotateAnimator = null
        micShimmerRotateAnimator = null
        micIconPulseAnimator = null
    }

    private fun stopMicProcessingAnimation() {
        if (!micIsProcessing) {
            if (voiceBubbleModeActive) {
                voiceBubbleController?.setState(VoiceBubbleState.IDLE)
            }
            return
        }
        micIsProcessing = false
        stopMicProcessingAnimatorsOnly()
        if (voiceBubbleModeActive || !isInputViewShown) {
            voiceBubbleController?.setState(VoiceBubbleState.IDLE)
            return
        }
        micAiGlow.visibility = View.GONE
        micAiShimmer.visibility = View.GONE
        micAiSparkles.visibility = View.GONE
        micAiSparkles.rotation = 0f
        micAiShimmer.rotation = 0f
        btnMic.alpha = 1f
        btnMicContainer.scaleX = 1f
        btnMicContainer.scaleY = 1f
        updateMicVisuals(recording = isRecording)
    }

    private fun applyOneHandedMode() {
        val contentParams = keyboardContent.layoutParams as FrameLayout.LayoutParams
        contentParams.width = FrameLayout.LayoutParams.MATCH_PARENT
        contentParams.gravity = Gravity.BOTTOM
        keyboardContent.layoutParams = contentParams

        val screenWidth = resources.displayMetrics.widthPixels
        val rowsHeight = keyboardRows.layoutParams.height
        val rowsParams = (keyboardRows.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, rowsHeight)

        when (oneHandedMode) {
            GkeysSettings.ONE_HANDED_RIGHT -> {
                rowsParams.width = (screenWidth * KeyboardLayoutMetrics.ONE_HANDED_KEY_AREA_FRACTION).toInt()
                rowsParams.gravity = Gravity.END or Gravity.BOTTOM
            }
            GkeysSettings.ONE_HANDED_LEFT -> {
                rowsParams.width = (screenWidth * KeyboardLayoutMetrics.ONE_HANDED_KEY_AREA_FRACTION).toInt()
                rowsParams.gravity = Gravity.START or Gravity.BOTTOM
            }
            else -> {
                rowsParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                rowsParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            }
        }
        rowsParams.height = rowsHeight
        keyboardRows.layoutParams = rowsParams
        keyboardRows.requestLayout()
        refreshTouchTargetsAfterLayout()
    }

    private fun isOneHandedActive(): Boolean =
        oneHandedMode != GkeysSettings.ONE_HANDED_OFF

    private fun isLayoutRightBiased(): Boolean =
        rightHandedMode || oneHandedMode == GkeysSettings.ONE_HANDED_RIGHT

    private fun isLayoutLeftBiased(): Boolean =
        oneHandedMode == GkeysSettings.ONE_HANDED_LEFT

    /**
     * Re-measures key hit zones after the keyboard reflows (one-handed resize,
     * right-handed layout, settings changes). Must run after layout completes.
     */
    private fun refreshTouchTargetsAfterLayout(container: KeyboardTouchLayout? = null) {
        if (!::touchResolver.isInitialized || !touchResolver.enabled) return
        val targetContainer = container
            ?: keyboardView.findViewById<KeyboardTouchLayout>(R.id.keyboard_rows)
            ?: return
        if (touchKeyViews.isEmpty()) return

        targetContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    targetContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (touchKeyViews.isEmpty() || !touchResolver.enabled) return
                    touchResolver.rightHandedMode = isLayoutRightBiased()
                    touchResolver.refreshFromViews(targetContainer, touchKeyViews)
                }
            }
        )
        targetContainer.requestLayout()
    }

    private fun attachTouchTargetLayoutWatcher(container: KeyboardTouchLayout) {
        container.addOnLayoutChangeListener { _, _, _, _, _, oldLeft, oldTop, oldRight, oldBottom ->
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if (oldW <= 0 || oldH <= 0) return@addOnLayoutChangeListener
            if (touchKeyViews.isEmpty() || !touchResolver.enabled) return@addOnLayoutChangeListener
            container.removeCallbacks(touchTargetRefreshRunnable)
            container.postDelayed(touchTargetRefreshRunnable, 16)
        }
    }

    private val touchTargetRefreshRunnable = Runnable {
        if (!::keyboardView.isInitialized || !::touchResolver.isInitialized) return@Runnable
        val container = keyboardView.findViewById<KeyboardTouchLayout>(R.id.keyboard_rows) ?: return@Runnable
        if (touchKeyViews.isEmpty() || !touchResolver.enabled) return@Runnable
        touchResolver.rightHandedMode = isLayoutRightBiased()
        touchResolver.refreshFromViews(container, touchKeyViews)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buildKeyboard() {
        try {
            buildKeyboardInternal()
        } catch (e: Throwable) {
            android.util.Log.e("GkeysIME", "buildKeyboard failed", e)
            com.gremier.gkeys.diag.CrashLogger.record(this, e)
        }
    }

    private fun buildKeyboardInternal() {
        if (!::keyboardView.isInitialized || !::touchResolver.isInitialized) return
        val container = keyboardView.findViewById<KeyboardTouchLayout>(R.id.keyboard_rows) ?: return
        container.removeAllViews()
        container.layoutDirection = View.LAYOUT_DIRECTION_LTR
        touchKeyViews.clear()
        touchResolver.clearTargets()

        val profile = layoutProfile.copy(rightHanded = isLayoutRightBiased())
        val layoutRight = isLayoutRightBiased()
        val layoutLeft = isLayoutLeftBiased()
        val oneHandedActive = isOneHandedActive()
        if (oneHandedActive) {
            container.setPadding(dp(4), container.paddingTop, dp(4), container.paddingBottom)
            container.translationX = 0f
        } else {
            container.setPadding(
                dp(
                    if (layoutLeft) KeyboardLayoutMetrics.keyboardPaddingEndDp(true)
                    else KeyboardLayoutMetrics.keyboardPaddingStartDp(layoutRight)
                ),
                container.paddingTop,
                dp(
                    if (layoutLeft) KeyboardLayoutMetrics.keyboardPaddingStartDp(true)
                    else KeyboardLayoutMetrics.keyboardPaddingEndDp(layoutRight)
                ),
                container.paddingBottom
            )
            container.translationX = when {
                layoutLeft -> -dp(KeyboardLayoutMetrics.keyboardShiftRightDp(true)).toFloat()
                layoutRight -> dp(KeyboardLayoutMetrics.keyboardShiftRightDp(true)).toFloat()
                else -> 0f
            }
        }

        val rows = when {
            isNumpad && !isHebrew -> numpadRows
            isSymbols -> symRows
            isHebrew -> heRowsGboard
            else -> enRows
        }
        applyStableKeyboardHeight(container)
        val emojiMode = emojiPanelVisible && !isHebrew
        val touchCorrectionEnabled = !isHebrew && !isSymbols && !isNumpad && !emojiMode
        touchResolver.enabled = touchCorrectionEnabled
        touchResolver.rightHandedMode = layoutRight
        touchResolver.setPreviousChar(lastTypedChar)
        if (::adaptiveTouch.isInitialized) {
            adaptiveTouch.setWordPrefix(currentWordPrefix)
        }

        val numpadMode = isNumpad && !isHebrew
        container.tag = when {
            emojiMode -> "emoji"
            numpadMode -> "numpad"
            oneHandedActive -> "onehanded"
            else -> null
        }

        if (emojiMode) {
            buildEmojiPanel(container, profile)
        } else if (numpadMode) {
            buildNumpadGrid(
                container = container,
                profile = profile,
                oneHandedActive = oneHandedActive,
                layoutLeft = layoutLeft,
                layoutRight = layoutRight
            )
        } else {
            val totalRows = rows.size
            rows.forEachIndexed { rowIndex, keys ->
                val orderedKeys = orderKeysForDisplay(keys)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_LTR
                    gravity = when {
                        layoutLeft -> Gravity.START
                        layoutRight -> Gravity.END
                        else -> Gravity.CENTER
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                }
                orderedKeys.forEach { key ->
                    row.addView(
                        buildKey(
                            label = key,
                            touchCorrectionEnabled = touchCorrectionEnabled,
                            rowIndex = rowIndex,
                            totalRows = totalRows,
                            profile = profile,
                            isNumpadMode = numpadMode,
                            useDirectTouchHandlers = false
                        )
                    )
                }
                container.addView(row)
            }
        }
        forceLayoutLtr(container)
        refreshTouchTargetsAfterLayout(container)
        updateNumpadButton()
        if (::keyboardRows.isInitialized) {
            applyOneHandedMode()
        }
    }

    /** Builds a compact 5-column numpad grid with equal column widths. */
    private fun buildNumpadGrid(
        container: KeyboardTouchLayout,
        profile: Profile,
        oneHandedActive: Boolean,
        layoutLeft: Boolean,
        layoutRight: Boolean
    ) {
        val totalRows = numpadRows.size
        numpadRows.forEachIndexed { rowIndex, keys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                gravity = when {
                    oneHandedActive && layoutLeft -> Gravity.START
                    oneHandedActive && layoutRight -> Gravity.END
                    else -> Gravity.CENTER_HORIZONTAL
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            for (colIndex in 0 until KeyboardLayoutMetrics.NUMPAD_COLUMN_COUNT) {
                val label = keys.getOrNull(colIndex).orEmpty()
                val weight = if (label.isEmpty()) {
                    1f
                } else {
                    KeyboardLayoutMetrics.rowKeyWeight(
                        label, rowIndex, totalRows, profile.rightHanded, isNumpadMode = true
                    )
                }
                row.addView(
                    if (label.isEmpty()) {
                        buildNumpadSpacer(weight)
                    } else {
                        buildKey(
                            label = label,
                            touchCorrectionEnabled = false,
                            rowIndex = rowIndex,
                            totalRows = totalRows,
                            profile = profile,
                            isNumpadMode = true,
                            columnWeight = weight,
                            tightSpacing = true
                        )
                    }
                )
            }
            container.addView(row)
        }
    }

    private fun buildNumpadSpacer(weight: Float): View {
        return Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        }
    }

    /** Fixed-height scrollable emoji grid — same shell height as QWERTY. */
    private fun buildEmojiPanel(container: KeyboardTouchLayout, profile: Profile) {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(KeyboardLayoutMetrics.EMOJI_HEADER_ROW_DP)
            )
        }
        topBar.addView(
            buildKey(
                label = "NUMPAD_BACK",
                touchCorrectionEnabled = false,
                rowIndex = 0,
                totalRows = 1,
                profile = profile,
                isNumpadMode = true,
                useDirectTouchHandlers = true
            )
        )
        shell.addView(topBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = true
        }

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val columns = KeyboardLayoutMetrics.EMOJI_COLUMNS
        for (category in EmojiCatalog.categories()) {
            grid.addView(buildEmojiCategoryHeader(category.name))
            category.emojis.chunked(columns).forEach { rowEmojis ->
                grid.addView(buildEmojiRow(rowEmojis, profile, columns))
            }
        }

        scroll.addView(grid)
        shell.addView(scroll)
        container.addView(shell)
    }

    private fun buildEmojiCategoryHeader(name: String): TextView =
        TextView(this).apply {
            text = name
            textSize = 10f
            setTextColor(0xFF6B7280.toInt())
            setPadding(dp(6), dp(3), dp(6), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(KeyboardLayoutMetrics.EMOJI_CATEGORY_HEADER_DP)
            )
        }

    private fun buildEmojiRow(emojis: List<String>, profile: Profile, columns: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(KeyboardLayoutMetrics.EMOJI_ROW_HEIGHT_DP)
            )
            emojis.forEach { emoji -> addView(buildEmojiKey(emoji, profile)) }
            repeat(columns - emojis.size) {
                addView(
                    Space(this@GkeysIME).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    }
                )
            }
        }

    private fun buildEmojiKey(emoji: String, profile: Profile): View {
        val gap = KeyboardLayoutMetrics.TILE_GAP_DP
        val cell = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            tag = emoji
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(gap / 2), dp(gap / 2), dp(gap / 2), dp(gap / 2))
            }
            setOnClickListener {
                vibrate()
                handleKey(emoji)
            }
        }
        val tile = TextView(this).apply {
            text = emoji
            textSize = KeyboardLayoutMetrics.EMOJI_TEXT_SP * profile.textScale
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundResource(R.drawable.key_tile_ripple)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        cell.addView(tile)
        return cell
    }

    private fun applyStableKeyboardHeight(container: View) {
        val target = dp(universalKeyboardHeightDp)
        if (keyboardHeightPx == target && container.layoutParams.height == target) return
        keyboardHeightPx = target
        container.layoutParams = container.layoutParams.apply { height = target }
    }

    private fun displayLabelFor(label: String): String {
        val isSpace = label == "SPACE"
        val isSpecial = label in listOf("⇧", "⌫", "↵", "?123", "ABC", "🌐", "NUMPAD_BACK")
        return when {
            label == "NUMPAD_BACK" -> if (emojiOpenedFromNumpad || isNumpad) "123" else "ABC"
            isSpace && isHebrew -> "עברית"
            isSpace -> "space"
            isShifted && !isHebrew && !isSpecial && label.length == 1 && label[0].isLetter() ->
                label.uppercase()
            else -> label
        }
    }

    private fun refreshLetterCaseOnKeys() {
        val container = keyboardView.findViewById<KeyboardTouchLayout>(R.id.keyboard_rows) ?: return
        for (rowIndex in 0 until container.childCount) {
            val row = container.getChildAt(rowIndex) as? ViewGroup ?: continue
            for (keyIndex in 0 until row.childCount) {
                val cell = row.getChildAt(keyIndex)
                val label = cell.tag as? String ?: continue
                if (label == "⌫") continue
                val keyTile = (cell as? ViewGroup)?.getChildAt(0) as? TextView ?: continue
                keyTile.text = displayLabelFor(label)
            }
        }
    }

    private fun buildKey(
        label: String,
        touchCorrectionEnabled: Boolean,
        rowIndex: Int,
        totalRows: Int,
        profile: Profile,
        isNumpadMode: Boolean = false,
        columnWeight: Float = 1f,
        tightSpacing: Boolean = false,
        useDirectTouchHandlers: Boolean = false
    ): View {
        val isSpace = label == "SPACE"
        val isSpecial = label in listOf("⇧", "⌫", "↵", "?123", "ABC", "🌐", "NUMPAD_BACK")
        val displayLabel = displayLabelFor(label)
        val gap = when {
            tightSpacing -> KeyboardLayoutMetrics.NUMPAD_TILE_GAP_DP
            isOneHandedActive() -> KeyboardLayoutMetrics.ONE_HANDED_KEY_GAP_DP
            else -> profile.keyGapDp
        }
        val weight = if (isNumpadMode || KeyboardLayoutMetrics.isBottomRowSpecialRow(rowIndex, totalRows)) {
            KeyboardLayoutMetrics.rowKeyWeight(label, rowIndex, totalRows, profile.rightHanded, isNumpadMode)
        } else {
            columnWeight
        }
        val textScale = profile.textScale

        val cell = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            tag = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dp(gap / 2), dp(gap / 2), dp(gap / 2), dp(gap / 2))
            }
        }

        val bgRes = if (isSpecial || (isNumpadMode && label in listOf("ABC", "⌫", "↵", "NUMPAD_BACK"))) {
            R.drawable.key_tile_special_ripple
        } else {
            R.drawable.key_tile_ripple
        }

        if (label == "⌫") {
            val iconPad = dp(8)
            val backIcon = ImageView(this).apply {
                setImageResource(R.drawable.ic_back_arrow)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setBackgroundResource(bgRes)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setPadding(iconPad, iconPad, iconPad, iconPad)
                contentDescription = "Backspace"
            }
            cell.addView(backIcon)
        } else {
            val keyTile = TextView(this).apply {
                text = displayLabel
                val baseTextSize = when {
                    isSpace -> 9f
                    label == "🌐" -> 14f
                    isNumpadMode && label in listOf("ABC", "↵", "NUMPAD_BACK") -> 13f
                    isSpecial -> 13f
                    isNumpadMode && isNumpadDigit(label) -> 19f
                    isNumpadMode -> 16f
                    else -> 14f
                }
                textSize = baseTextSize * textScale
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                setTextColor(0xFFE8EAF0.toInt())
                setBackgroundResource(bgRes)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            cell.addView(keyTile)
        }

        if (touchCorrectionEnabled && !useDirectTouchHandlers) {
            touchKeyViews.add(Triple(cell, label, rowIndex))
            cell.isClickable = false
            cell.isFocusable = false
        } else {
            attachNumpadKeyHandler(cell, label, isNumpadMode || useDirectTouchHandlers)
        }

        return cell
    }

    private fun attachNumpadKeyHandler(cell: View, label: String, useDirectHandlers: Boolean) {
        if (!useDirectHandlers) {
            cell.setOnClickListener {
                vibrate()
                handleKey(label)
            }
            return
        }
        when (label) {
            "," -> attachNumpadLongPressHandler(cell, label) {
                vibrate()
                toggleEmojiPanel()
            }
            "?" -> attachNumpadLongPressHandler(cell, label) {
                vibrate()
                handleKey("!")
            }
            "⌫" -> {
                cell.setOnLongClickListener { startDeleteRepeat(); true }
                cell.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDeleteRepeat()
                    }
                    false
                }
                cell.setOnClickListener {
                    vibrate()
                    handleKey(label)
                }
            }
            else -> {
                cell.setOnClickListener {
                    vibrate()
                    handleKey(label)
                }
            }
        }
    }

    private fun attachNumpadLongPressHandler(
        cell: View,
        label: String,
        onLongPress: () -> Unit
    ) {
        var longPressFired = false
        val runnable = Runnable {
            longPressFired = true
            onLongPress()
        }
        cell.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressFired = false
                    cell.postDelayed(runnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cell.removeCallbacks(runnable)
                    if (!longPressFired) {
                        vibrate()
                        handleKey(label)
                    }
                    longPressFired = false
                    true
                }
                else -> true
            }
        }
    }

    private fun handleKey(key: String) {
        try {
            handleKeyInternal(key)
        } catch (e: Exception) {
            android.util.Log.e("GkeysIME", "handleKey failed for '$key'", e)
        }
    }

    private fun handleKeyInternal(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "⌫" -> {
                awaitingTouchCorrection = true
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) ic.commitText("", 1)
                else ic.deleteSurroundingText(1, 0)
                trimWordPrefixAfterBackspace()
            }
            "↵" -> {
                completeCurrentWord()
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                awaitingTouchCorrection = false
            }
            "⇧" -> {
                if (isShifted && !capsLock) capsLock = true
                else if (capsLock) { capsLock = false; isShifted = false }
                else isShifted = true
                refreshLetterCaseOnKeys()
            }
            "?123" -> { isSymbols = true; isNumpad = false; emojiPanelVisible = false; buildKeyboard() }
            "ABC" -> { isSymbols = false; isNumpad = false; emojiPanelVisible = false; buildKeyboard() }
            "NUMPAD_BACK" -> { closeEmojiPanel() }
            "🌐" -> { isHebrew = !isHebrew; isSymbols = false; isNumpad = false; emojiPanelVisible = false; buildKeyboard() }
            else -> {
                val toInsert = if (key == "SPACE") " "
                else if (isShifted && !isHebrew && key.length == 1 && key[0].isLetter()) key.uppercase()
                else key

                if (awaitingTouchCorrection && key.length == 1 && key[0].isLetter()) {
                    if (::touchResolver.isInitialized) {
                        touchResolver.recordCorrection(key)
                    }
                    awaitingTouchCorrection = false
                } else {
                    awaitingTouchCorrection = false
                }

                if (toInsert == " ") {
                    completeCurrentWord()
                    currentWordPrefix = ""
                } else if (toInsert.length == 1 && toInsert[0].isLetter()) {
                    currentWordPrefix = (currentWordPrefix + toInsert.lowercase()).take(24)
                } else {
                    currentWordPrefix = ""
                }
                if (::adaptiveTouch.isInitialized) {
                    adaptiveTouch.setWordPrefix(currentWordPrefix)
                }

                ic.commitText(toInsert, 1)
                updateTouchContext(toInsert)
                if (isShifted && !capsLock && toInsert.length == 1 && toInsert[0].isLetter()) {
                    isShifted = false
                    refreshLetterCaseOnKeys()
                }
            }
        }
    }

    private fun completeCurrentWord() {
        if (currentWordPrefix.length >= 2 && ::adaptiveTouch.isInitialized) {
            adaptiveTouch.recordWordCompleted(currentWordPrefix)
        }
        currentWordPrefix = ""
        if (::adaptiveTouch.isInitialized) {
            adaptiveTouch.setWordPrefix("")
        }
    }

    private fun trimWordPrefixAfterBackspace() {
        if (currentWordPrefix.isNotEmpty()) {
            currentWordPrefix = currentWordPrefix.dropLast(1)
            if (::adaptiveTouch.isInitialized) {
                adaptiveTouch.setWordPrefix(currentWordPrefix)
            }
        }
    }

    private fun updateTouchContext(inserted: String) {
        lastTypedChar = when {
            inserted == " " || inserted.endsWith(" ") -> ' '
            inserted.length == 1 && inserted[0].isLetter() -> inserted.lowercase()[0]
            else -> lastTypedChar
        }
        if (::touchResolver.isInitialized) {
            touchResolver.setPreviousChar(lastTypedChar)
        }
    }

    private fun startDeleteRepeat() {
        if (isDeleteRepeating) return
        deleteRunnable?.let { handler.removeCallbacks(it) }
        isDeleteRepeating = true
        currentInputConnection?.deleteSurroundingText(1, 0)
        deleteRunnable = object : Runnable {
            override fun run() {
                if (!isDeleteRepeating) return
                currentInputConnection?.deleteSurroundingText(1, 0)
                vibrate(6)
                handler.postDelayed(this, deleteSpeedMs.toLong())
            }
        }
        handler.postDelayed(deleteRunnable!!, deleteSpeedMs.toLong())
    }

    private fun stopDeleteRepeat() {
        isDeleteRepeating = false
        deleteRunnable?.let { handler.removeCallbacks(it) }
        deleteRunnable = null
    }

    private fun startRecording() {
        refreshApiKeys()
        if (openAiKey.isBlank()) {
            showErrorToast("Add OpenAI API key in Gkeys settings")
            return
        }
        if (!hasMicPermission()) {
            showErrorToast("Allow microphone for Gkeys")
            openAppForMicPermission()
            return
        }
        try {
            audioRecorder.startRecording()
            isRecording = true
            recordingForGhostwriter = false
        } catch (_: Exception) {
            showErrorToast("Microphone error — check permission in Gkeys app")
        }
    }

    private fun cancelRecording() {
        handler.removeCallbacks(longPressRunnable)
        if (isRecording && !recordingForGhostwriter) {
            audioRecorder.cancelRecording()
            isRecording = false
        }
        updateMicVisuals(recording = false)
        if (voiceBubbleModeActive) {
            voiceBubbleController?.setState(VoiceBubbleState.IDLE)
        }
        toastStatus("")
    }

    private fun stopRecordingAndProcess(action: VoiceAction) {
        if (!isRecording || recordingForGhostwriter) return
        val file = audioRecorder.stopRecording()
        isRecording = false
        if (file == null) {
            updateMicVisuals(recording = false)
            voiceBubbleController?.setState(VoiceBubbleState.IDLE)
            toastStatus("⚠ Recording failed")
            return
        }

        val effectiveAction = action
        startMicProcessingAnimation()

        scope.launch {
            val durationMs = audioRecorder.lastRecordingDurationMs()
            val transcribeLang = if (effectiveAction == VoiceAction.TRANSLATE) voiceTranslateFrom else null
            val transcriptResult = aiManager.transcribe(file, openAiKey, durationMs, transcribeLang)
            file.delete()
            transcriptResult.onFailure { error ->
                stopMicProcessingAnimation()
                showErrorToast(
                    if (error.message == "Nothing heard" || error.message == "Recording too short") {
                        "Nothing heard"
                    } else {
                        "Transcription failed"
                    }
                )
                return@launch
            }
            val transcript = transcriptResult.getOrNull().orEmpty()
            if (transcript.isBlank()) {
                stopMicProcessingAnimation()
                showErrorToast("Nothing heard")
                return@launch
            }

            val finalText = when (effectiveAction) {
                VoiceAction.TRANSLATE ->
                    aiManager.translateText(transcript, voiceTranslateFrom, voiceTranslateTo, openAiKey)
                else ->
                    aiManager.polishText(transcript, openAiKey, polishLevel)
            }

            finalText.onSuccess { polished ->
                currentInputConnection?.commitText(polished, 1)
                stopMicProcessingAnimation()
            }.onFailure {
                stopMicProcessingAnimation()
                showErrorToast(
                    if (effectiveAction == VoiceAction.TRANSLATE) "Translation failed — text unchanged"
                    else "Polish failed — text unchanged"
                )
            }
        }
    }

    private fun polishFieldText() {
        if (isPolishing) return
        refreshApiKeys()
        if (openAiKey.isBlank()) {
            showErrorToast("Add OpenAI API key in Gkeys settings")
            openAppSettings()
            return
        }
        val ic = currentInputConnection ?: return
        val target = InputTextHelper.extractForPolish(ic)
        if (target == null || target.text.isBlank()) {
            showErrorToast("No text to polish")
            return
        }

        isPolishing = true
        toastStatus("Polishing…")
        scope.launch {
            val result = aiManager.polishText(target.text, openAiKey, polishLevel)
            toastStatus("")
            isPolishing = false

            result.onSuccess { polished ->
                InputTextHelper.replaceText(ic, target, polished)
                vibrate()
            }.onFailure {
                showErrorToast("Polish failed — text unchanged")
            }
        }
    }

    private fun pasteClipboardItem(item: ClipboardItem) {
        val ic = currentInputConnection ?: return
        if (item.isImage && !item.imageUri.isNullOrBlank()) {
            try {
                val uri = Uri.parse(item.imageUri)
                val mime = contentResolver.getType(uri) ?: "image/png"
                val desc = ClipDescription("image", arrayOf(mime))
                val info = InputContentInfo(uri, desc, null)
                val supported = ic.commitContent(
                    info,
                    InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null
                )
                if (!supported) {
                    showErrorToast("This app does not accept images")
                }
            } catch (e: Exception) {
                android.util.Log.e("GkeysIME", "paste image failed", e)
                showErrorToast("Could not paste image")
            }
        } else {
            ic.commitText(item.text, 1)
        }
    }

    private fun showErrorToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toastStatus(msg: String) {
        if (isVoiceOverlay) {
            voiceStatus.text = if (msg.isBlank()) "Listening…" else msg
            return
        }
        if (msg.isBlank()) {
            clipboardManager?.refreshPreview()
        } else {
            tvClipboard.visibility = View.GONE
            ivClipboardPreview.visibility = View.GONE
            tvClipboardHint.visibility = View.VISIBLE
            tvClipboardHint.text = msg
        }
    }

    private fun vibrate(ms: Long = 8) {
        if (!vibrationEnabled || vibrationStrength <= 0) return
        if (!::vibrator.isInitialized) return
        try {
            val amplitude = (vibrationStrength * 2.55f).toInt().coerceIn(1, 255)
            val duration = (ms * (vibrationStrength / 50f)).toLong().coerceIn(1, 25)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } catch (e: Exception) {
            android.util.Log.w("GkeysIME", "vibrate failed", e)
        }
    }

    override fun onDestroy() {
        voiceBubbleController?.destroy()
        super.onDestroy()
        clipboardManager?.destroy()
        scope.cancel()
        stopDeleteRepeat()
        handler.removeCallbacks(longPressRunnable)
        audioRecorder.cancelRecording()
    }
}
