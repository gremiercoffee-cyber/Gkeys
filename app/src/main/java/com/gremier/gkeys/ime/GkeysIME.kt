package com.gremier.gkeys.ime

import android.Manifest
import android.content.Context
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.os.*
import android.text.InputType
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
import com.gremier.gkeys.ai.LiveTranscribeController
import com.gremier.gkeys.clipboard.ClipboardItem
import com.gremier.gkeys.clipboard.GkeysClipboardManager
import com.gremier.gkeys.ime.bubble.VoiceBubbleController
import com.gremier.gkeys.ime.bubble.VoiceBubbleListener
import com.gremier.gkeys.ime.bubble.VoiceBubbleState
import com.gremier.gkeys.ime.emoji.EmojiCatalog
import com.gremier.gkeys.ime.emoji.EmojiUsageStore
import com.gremier.gkeys.ime.layout.KeyboardLayoutMetrics
import com.gremier.gkeys.ime.layout.KeyboardLayoutMetrics.Profile
import com.gremier.gkeys.ime.suggestions.DictionaryManager
import com.gremier.gkeys.ime.suggestions.SuggestionEngine
import com.gremier.gkeys.ime.suggestions.SuggestionStripController
import com.gremier.gkeys.ime.suggestions.SuggestionVisibilityController
import com.gremier.gkeys.ime.suggestions.UserWordsRepository
import com.gremier.gkeys.ime.touch.AdaptiveTouchIntelligence
import com.gremier.gkeys.ime.touch.TouchInputResolver
import com.gremier.gkeys.ime.touch.TouchPersonalization
import com.gremier.gkeys.settings.AppVersionTracker
import com.gremier.gkeys.settings.GkeysSettings
import com.gremier.gkeys.settings.OverlayPermissionHelper
import com.gremier.gkeys.settings.SecureApiKeyStore
import com.gremier.gkeys.settings.SettingsActivity
import com.gremier.gkeys.ui.GkeysTheme
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
    private var oneHandedWidthFraction = KeyboardLayoutMetrics.DEFAULT_ONE_HANDED_KEY_AREA_FRACTION
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
    private var deepgramKey = ""
    private var voiceBubbleModeActive = false
    /** When true the keyboard is hidden but the floating bubble stays on screen. */
    private var bubbleKeyboardCollapsed = false
    private var voiceBubbleEnabled = GkeysSettings.DEFAULT_VOICE_BUBBLE_ENABLED
    private var aiBarWandEnabled = GkeysSettings.DEFAULT_AI_BAR_FEATURE_ENABLED
    private var aiBarPolishButtonEnabled = GkeysSettings.DEFAULT_AI_BAR_FEATURE_ENABLED
    private var aiBarMicToolbarEnabled = GkeysSettings.DEFAULT_AI_BAR_FEATURE_ENABLED
    private var aiBarVoiceInputIncludesMic = true
    private var aiBarLiveTranscribeEnabled = GkeysSettings.DEFAULT_AI_BAR_FEATURE_ENABLED
    private var aiBarClearAllEnabled = GkeysSettings.DEFAULT_AI_BAR_FEATURE_ENABLED
    private var aiBarClipboardToolbarEnabled = GkeysSettings.DEFAULT_AI_BAR_FEATURE_ENABLED
    private var aiBarNumpadEnabled = GkeysSettings.DEFAULT_AI_BAR_FEATURE_ENABLED
    private var aiBarOrder = AiBarLayout.DEFAULT_ORDER
    private var darkTheme = true
    private var voiceBubbleController: VoiceBubbleController? = null
    private var activeInputConnection: InputConnection? = null

    private lateinit var aiManager: AiManager
    private lateinit var audioRecorder: AudioRecorder
    private var isRecording = false
    private var recordingForGhostwriter = false
    private var isVoiceOverlay = false
    private var isGhostwriterOverlay = false
    private var longPressTriggered = false
    private var pendingVoiceAction = VoiceAction.DEFAULT
    private var micGestureTracking = false
    private var suppressBubbleAutoStart = false
    /** Set when the user taps the field or swipes up to leave bubble mode for the keyboard. */
    private var userRequestedKeyboard = false
    /** Swallow the automatic show-keyboard request that arrives with initial field focus. */
    private var bubbleConsumeNextShowRequest = false
    private var bubbleShowRequestConsumeRunnable: Runnable? = null
    /** Cached so onShowInputRequested (which can't suspend) knows the bubble-first preference. */
    private var defaultToVoiceBubbleCached = false
    /** When a text field was just focused programmatically (navigation) — suppress auto-keyboard briefly. */
    private var lastBubbleFieldFocusMs = 0L
    /** True while the IME is bound to an editable field (cleared shortly after [onFinishInput]). */
    private var bubbleTextFieldFocused = false
    private var bubbleFieldHideRunnable: Runnable? = null
    /** Prevents requestHideSelf while restoring IME session to commit bubble dictation. */
    private var suspendBubbleCollapse = false
    /** IME session kept alive invisibly while bubble dictates with keyboard collapsed. */
    private var bubbleDictationSessionActive = false
    /** IME session kept alive invisibly while live transcribe runs without the keyboard. */
    private var liveSttSessionActive = false
    /** When true, live transcribe started with the keyboard visible — do not collapse it. */
    private var liveSttKeepKeyboardOpen = false
    private var pendingBubbleCommit: Pair<String, ((Boolean) -> Unit)?>? = null
    private var pendingSessionReadyAction: (() -> Unit)? = null
    private var pendingSessionReadyFailed: (() -> Unit)? = null
    private var liveSttActive = false
    private var liveSttConnecting = false
    private val liveTranscribe by lazy {
        LiveTranscribeController(
            scope = scope,
            getInputConnection = { liveInputConnection() },
            onMicAcquire = { beginMicCapture() },
            onMicRelease = { endMicCapture() },
            onStateChanged = { connecting, active ->
                liveSttConnecting = connecting
                liveSttActive = active
                updateLiveTranscribeButton()
            }
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var dictationStatusClearRunnable: Runnable? = null
    private var imeStatusBannerClearRunnable: Runnable? = null
    private var deleteRunnable: Runnable? = null
    private val longPressRunnable = Runnable { onMicLongPress() }

    private lateinit var vibrator: Vibrator
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var keyboardView: View
    private lateinit var keyboardContent: LinearLayout
    private lateinit var suggestionStrip: LinearLayout
    private lateinit var suggestionLeft: TextView
    private lateinit var suggestionCenter: TextView
    private lateinit var suggestionDismiss: ImageButton
    private lateinit var suggestionDividerLeft: View
    private lateinit var suggestionDividerRight: View
    private var suggestionStripController: SuggestionStripController? = null
    private var suggestionVisibilityController: SuggestionVisibilityController? = null
    private var suggestionBarVisible = false
    /** After autocorrect on space: original typed word -> corrected word (for undo chip). */
    private var postAutocorrectUndo: Pair<String, String>? = null
    private lateinit var userWordsRepository: UserWordsRepository
    private lateinit var aiStrip: LinearLayout
    private lateinit var aiBarShellPrimary: FrameLayout
    private lateinit var aiBarShimmerPrimary: View
    private lateinit var keyboardPanel: FrameLayout
    private lateinit var keyboardKeysHost: FrameLayout
    private lateinit var keyboardRows: KeyboardTouchLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvClipboard: TextView
    private lateinit var tvClipboardHint: TextView
    private lateinit var ivClipboardPreview: ImageView
    private lateinit var clipboardArea: View
    private lateinit var clipboardPreviewStrip: View
    private lateinit var btnClipboardUndo: ImageButton
    private lateinit var btnDeleteForward: ImageButton
    private lateinit var btnSelectAll: ImageButton
    private lateinit var btnClipboardClearAll: ImageButton
    private lateinit var btnMic: ImageView
    private lateinit var btnMicContainer: FrameLayout
    private lateinit var micGroup: View
    private lateinit var btnMicCancel: ImageButton
    private lateinit var micAiGlow: View
    private lateinit var micAiShimmer: View
    private lateinit var micAiSparkles: ImageView
    private lateinit var btnKeyboard: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnVoiceBubble: ImageButton
    private lateinit var btnWand: FrameLayout
    private lateinit var btnLiveTranscribe: ImageButton
    private lateinit var btnPolishLevel: TextView
    private lateinit var btnRawPolish: ImageButton
    private lateinit var voiceOverlay: View
    private lateinit var voiceStatus: TextView
    private lateinit var voiceTranslateHint: TextView
    private lateinit var ghostwriterOverlay: View
    private lateinit var ghostwriterStatus: TextView
    private lateinit var btnGhostwriterClose: ImageButton
    private lateinit var ghostwriterContent: View
    private var isPolishing = false
    private var clipboardManager: GkeysClipboardManager? = null
    private val fieldUndo = FieldUndoManager()
    private var isDeleteRepeating = false
    private var keyboardHeightPx = 0
    private var keyboardSizeRail: LinearLayout? = null
    private lateinit var touchPersonalization: TouchPersonalization
    private lateinit var adaptiveTouch: AdaptiveTouchIntelligence
    private lateinit var touchResolver: TouchInputResolver
    private val touchKeyViews = mutableListOf<Triple<View, String, Int>>()
    private var lastTypedChar: Char? = null
    private var currentWordPrefix = ""
    private var lastCompletedWord = ""
    private var awaitingTouchCorrection = false
    private var adaptiveTouchEnabled = GkeysSettings.DEFAULT_ADAPTIVE_TOUCH

    private var emojiPanelVisible = false
    private var emojiOpenedFromNumpad = false
    private var micIsProcessing = false
    private lateinit var micSessionGuard: MicSessionGuard
    private var micProcessingAnimator: AnimatorSet? = null
    private var micSparkleRotateAnimator: ObjectAnimator? = null
    private var micIdleSparkleAnimator: ObjectAnimator? = null
    private var micShimmerRotateAnimator: ObjectAnimator? = null
    private var micIconPulseAnimator: ObjectAnimator? = null

    companion object {
        private const val LONG_PRESS_MS = 380L
        /** Block keyboard auto-show briefly after navigation focuses a new text field. */
        private const val BUBBLE_AUTO_SHOW_BLOCK_MS = 300L
        /** Grace period before hiding the bubble after [onFinishInput] — absorbs field rebinds. */
        private const val BUBBLE_FIELD_HIDE_DELAY_MS = 120L
        /** [InputMethodManager] show-soft-input bit in [onShowInputRequested] reason. */
        private const val SHOW_SOFT_INPUT_REASON = 1
        private const val KEY_EMOJI_PANEL = "\uE000"
        private const val FIELD_TEXT_SCAN_LIMIT = 100_000

        private val letterLongPressAlts = mapOf(
            "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
            "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
        )

        private val punctuationLongPressAlts = mapOf(
            "," to KEY_EMOJI_PANEL,
            "?" to "!"
        )

        private fun longPressAltFor(label: String): String? {
            val lower = label.lowercase()
            return letterLongPressAlts[lower] ?: punctuationLongPressAlts[label]
        }
    }

    /** Fixed 5-column grid: digits always in columns 1–3 so 1/4/7, 2/5/8, 3/6/9 align. */
    private val numpadRows = listOf(
        listOf("-", "1", "2", "3", "."),
        listOf("+", "4", "5", "6", ","),
        listOf("*", "7", "8", "9", "/"),
        listOf("#", "(", "0", ")", "?"),
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
        listOf("?123","🌐",",","!","SPACE",".","?","↵")
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
        listOf("?123","🌐",",","!","SPACE",".","?","↵")
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
        listOf("ABC","🌐",",","!","SPACE",".","?","↵")
    )

    override fun onCreate() {
        super.onCreate()
        try {
            aiManager = AiManager(this)
            audioRecorder = AudioRecorder(this)
            micSessionGuard = MicSessionGuard(this)
            micSessionGuard.setOverlayScreenOnCallback { keepOn ->
                voiceBubbleController?.setKeepScreenOn(keepOn)
            }
            vibrator = initVibrator()
            voiceBubbleController?.destroy()
            voiceBubbleController = VoiceBubbleController(this, voiceBubbleListener)
            observePolishLevel()
            observeAiBarSettings()
            loadSettings()
        } catch (e: Throwable) {
            android.util.Log.e("GkeysIME", "onCreate failed", e)
        }
    }

    private fun observePolishLevel() {
        scope.launch {
            GkeysSettings.polishLevel(this@GkeysIME).collect { level ->
                polishLevel = level
                if (::btnPolishLevel.isInitialized) updatePolishLevelButton()
            }
        }
    }

    private fun observeAiBarSettings() {
        scope.launch {
            combine(
                combine(
                    GkeysSettings.aiBarWandEnabled(this@GkeysIME),
                    GkeysSettings.aiBarPolishButtonEnabled(this@GkeysIME),
                    GkeysSettings.aiBarMicToolbarEnabled(this@GkeysIME),
                    GkeysSettings.aiBarVoiceInputIncludesMic(this@GkeysIME),
                    GkeysSettings.aiBarLiveTranscribeEnabled(this@GkeysIME),
                ) { wand, polish, micToolbar, micMode, live ->
                    arrayOf(wand, polish, micToolbar, micMode, live)
                },
                GkeysSettings.voiceBubbleEnabled(this@GkeysIME),
                combine(
                    GkeysSettings.aiBarOrder(this@GkeysIME),
                    GkeysSettings.aiBarClearAllEnabled(this@GkeysIME),
                    GkeysSettings.aiBarClipboardToolbarEnabled(this@GkeysIME),
                    GkeysSettings.aiBarNumpadEnabled(this@GkeysIME),
                ) { order, clearAll, clipboard, numpad ->
                    arrayOf(order, clearAll, clipboard, numpad)
                },
            ) { toggles, bubble, layout ->
                aiBarWandEnabled = toggles[0]
                aiBarPolishButtonEnabled = toggles[1]
                aiBarMicToolbarEnabled = toggles[2]
                aiBarVoiceInputIncludesMic = toggles[3]
                aiBarLiveTranscribeEnabled = toggles[4]
                voiceBubbleEnabled = bubble
                @Suppress("UNCHECKED_CAST")
                aiBarOrder = layout[0] as List<String>
                aiBarClearAllEnabled = layout[1] as Boolean
                aiBarClipboardToolbarEnabled = layout[2] as Boolean
                aiBarNumpadEnabled = layout[3] as Boolean
            }.collect {
                try {
                    if (::aiStrip.isInitialized) {
                        applyAiBarLayout()
                    }
                    if (!voiceBubbleEnabled && voiceBubbleModeActive) {
                        exitVoiceBubbleMode()
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("GkeysIME", "observeAiBarSettings failed", e)
                }
            }
        }
    }

    private val voiceBubbleListener = object : VoiceBubbleListener {
        override fun onBubbleTap() = handleFloatingBubbleTap()
        override fun onBubbleSwipeUp() {
            userRequestedKeyboard = true
            suppressBubbleAutoStart = true
            expandKeyboardFromBubble()
            requestShowSelf(0)
        }
        override fun onBubbleTranslateHoldStart() = handleBubbleTranslateHoldStart()
        override fun onBubbleTranslateHoldEnd(cancelled: Boolean) =
            handleBubbleTranslateHoldEnd(cancelled)
        override fun onBubbleCancelRecording() = handleBubbleCancelRecording()
        override fun onVibrate() = hapticKeyTap()
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

    private fun refreshThemeCacheSync() {
        try {
            runBlocking(Dispatchers.IO) {
                darkTheme = GkeysSettings.isDarkTheme(this@GkeysIME)
            }
        } catch (e: Exception) {
            android.util.Log.w("GkeysIME", "refreshThemeCacheSync failed", e)
        }
    }

    private fun themedContext(): Context = GkeysTheme.wrap(this, darkTheme)

    private fun themeColor(@ColorRes colorRes: Int): Int = themedContext().getColor(colorRes)

    private fun themeDrawable(@androidx.annotation.DrawableRes drawableRes: Int) =
        themedContext().getDrawable(drawableRes)

    private fun applyKeyboardTheme() {
        if (!::keyboardView.isInitialized) return
        val bg = themeColor(R.color.gkeys_bg)
        keyboardView.setBackgroundColor(bg)
        if (::keyboardContent.isInitialized) keyboardContent.setBackgroundColor(bg)
        if (::keyboardPanel.isInitialized) keyboardPanel.setBackgroundColor(bg)
        if (::keyboardKeysHost.isInitialized) keyboardKeysHost.setBackgroundColor(bg)
        if (::aiBarShellPrimary.isInitialized) {
            aiBarShellPrimary.findViewById<View>(R.id.ai_bar_bg_primary)?.background =
                themeDrawable(R.drawable.ai_bar_shell_bg)
        }
        if (::suggestionStrip.isInitialized) {
            suggestionStrip.setBackgroundColor(themeColor(R.color.gkeys_suggestion_bar))
        }
        if (::tvStatus.isInitialized) tvStatus.setTextColor(themeColor(R.color.gkeys_text_secondary))
        if (::tvClipboard.isInitialized) tvClipboard.setTextColor(themeColor(R.color.gkeys_text_primary))
        if (::tvClipboardHint.isInitialized && tvClipboardHint.visibility == View.VISIBLE) {
            tvClipboardHint.setTextColor(themeColor(R.color.gkeys_text_secondary))
        }
        if (::btnPolishLevel.isInitialized) btnPolishLevel.setTextColor(themeColor(R.color.gkeys_text_primary))
        if (::clipboardArea.isInitialized) {
            clipboardArea.background = themeDrawable(R.drawable.ai_bar_icon_btn_bg)
        }
        if (::voiceOverlay.isInitialized) voiceOverlay.setBackgroundColor(themeColor(R.color.gkeys_overlay_scrim))
        if (::ghostwriterOverlay.isInitialized) {
            ghostwriterOverlay.setBackgroundColor(themeColor(R.color.gkeys_overlay_scrim))
        }
        clipboardManager?.updateTheme(darkTheme)
    }

    private fun buildInputView(): View {
        refreshThemeCacheSync()
        keyboardView = LayoutInflater.from(themedContext()).inflate(R.layout.keyboard_view, null)
        keyboardView.layoutDirection = View.LAYOUT_DIRECTION_LTR

        keyboardContent = keyboardView.findViewById(R.id.keyboard_content)
        suggestionStrip = keyboardView.findViewById(R.id.suggestion_strip)
        suggestionLeft = keyboardView.findViewById(R.id.suggestion_left)
        suggestionCenter = keyboardView.findViewById(R.id.suggestion_center)
        suggestionDismiss = keyboardView.findViewById(R.id.suggestion_dismiss)
        suggestionDividerLeft = keyboardView.findViewById(R.id.suggestion_divider_left)
        suggestionDividerRight = keyboardView.findViewById(R.id.suggestion_divider_right)
        suggestionStripController = SuggestionStripController(
            strip = suggestionStrip,
            leftView = suggestionLeft,
            centerView = suggestionCenter,
            dismissButton = suggestionDismiss,
            dividerLeft = suggestionDividerLeft,
            dividerRight = suggestionDividerRight,
            onSuggestionPicked = { word -> applySuggestion(word) },
            onDismiss = { dismissSuggestionBar() },
        )
        suggestionVisibilityController = SuggestionVisibilityController(idleTimeoutMs = 5000L) { visible ->
            suggestionBarVisible = visible
            if (!visible) postAutocorrectUndo = null
            refreshSuggestions()
        }
        userWordsRepository = UserWordsRepository(applicationContext)
        aiStrip = keyboardView.findViewById(R.id.ai_strip)
        aiBarShellPrimary = keyboardView.findViewById(R.id.ai_bar_shell_primary)
        aiBarShimmerPrimary = keyboardView.findViewById(R.id.ai_bar_shimmer_primary)
        keyboardPanel = keyboardView.findViewById(R.id.keyboard_panel)
        keyboardKeysHost = keyboardView.findViewById(R.id.keyboard_keys_host)
        keyboardRows = keyboardView.findViewById(R.id.keyboard_rows)
        tvStatus = keyboardView.findViewById(R.id.tv_status)
        tvClipboard = keyboardView.findViewById(R.id.tv_clipboard)
        tvClipboardHint = keyboardView.findViewById(R.id.tv_clipboard_hint)
        ivClipboardPreview = keyboardView.findViewById(R.id.iv_clipboard_preview)
        clipboardArea = keyboardView.findViewById(R.id.clipboard_area)
        clipboardPreviewStrip = keyboardView.findViewById(R.id.clipboard_preview_strip)
        btnClipboardUndo = keyboardView.findViewById(R.id.btn_clipboard_undo)
        btnDeleteForward = keyboardView.findViewById(R.id.btn_delete_forward)
        btnSelectAll = keyboardView.findViewById(R.id.btn_select_all)
        btnClipboardClearAll = keyboardView.findViewById(R.id.btn_clipboard_clear_all)
        btnMic = keyboardView.findViewById(R.id.btn_mic)
        btnMicContainer = keyboardView.findViewById(R.id.btn_mic_container)
        micGroup = keyboardView.findViewById(R.id.mic_group)
        btnMicCancel = keyboardView.findViewById(R.id.btn_mic_cancel)
        micAiGlow = keyboardView.findViewById(R.id.mic_ai_glow)
        micAiShimmer = keyboardView.findViewById(R.id.mic_ai_shimmer)
        micAiSparkles = keyboardView.findViewById(R.id.mic_ai_sparkles)
        btnKeyboard = keyboardView.findViewById(R.id.btn_keyboard)
        btnSettings = keyboardView.findViewById(R.id.btn_settings)
        btnVoiceBubble = keyboardView.findViewById(R.id.btn_voice_bubble)
        btnWand = keyboardView.findViewById(R.id.btn_wand)
        btnLiveTranscribe = keyboardView.findViewById(R.id.btn_live_transcribe)
        btnPolishLevel = keyboardView.findViewById(R.id.btn_polish_level)
        btnRawPolish = keyboardView.findViewById(R.id.btn_raw_polish)
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
        ghostwriterOverlay.setOnClickListener {
            if (isGhostwriterOverlay && isRecording && recordingForGhostwriter) {
                vibrate()
                stopGhostwriterAndProcess()
            }
        }
        ghostwriterContent.setOnClickListener {
            if (isGhostwriterOverlay && isRecording && recordingForGhostwriter) {
                vibrate()
                stopGhostwriterAndProcess()
            }
        }

        touchPersonalization = TouchPersonalization(this, scope)
        touchPersonalization.load()
        adaptiveTouch = AdaptiveTouchIntelligence(this, scope)
        adaptiveTouch.load()
        adaptiveTouch.setEnabled(adaptiveTouchEnabled)
        touchResolver = TouchInputResolver(touchPersonalization, adaptiveTouch)
        keyboardRows.touchResolver = touchResolver
        keyboardRows.onKeyTap = { key ->
            handleKey(key)
            hapticKeyTap()
        }
        keyboardRows.onBackspaceDown = { startDeleteRepeat(skipInitial = true) }
        keyboardRows.onBackspaceUp = { stopDeleteRepeat() }
        keyboardRows.keyLongPressAlts = letterLongPressAlts + punctuationLongPressAlts
        keyboardRows.onKeyLongPress = { alt ->
            when (alt) {
                KEY_EMOJI_PANEL -> openEmojiPanel()
                else -> handleKey(alt)
            }
            hapticKeyTap()
        }

        val overlayContainer = keyboardView.findViewById<FrameLayout>(R.id.clipboard_overlay_container)
        val toolbarSlot = keyboardView.findViewById<FrameLayout>(R.id.toolbar_slot)
        clipboardManager = GkeysClipboardManager(
            context = this,
            themeContext = themedContext(),
            overlayContainer = overlayContainer,
            toolbarSlotHost = toolbarSlot,
            previewTapTarget = clipboardPreviewStrip,
            previewView = tvClipboard,
            previewImage = ivClipboardPreview,
            previewHint = tvClipboardHint,
            onPasteItem = { item -> pasteClipboardItem(item) },
            onVibrate = { hapticKeyTap() },
            onPanelOpen = { keyboardKeysHost.visibility = View.GONE },
            onPanelClose = { keyboardKeysHost.visibility = View.VISIBLE },
            onTextPromptOpen = {
                overlayContainer.visibility = View.GONE
                hideAiBarsForOverlay()
                suggestionStrip.visibility = View.GONE
                keyboardKeysHost.visibility = View.VISIBLE
            },
            onTextPromptClose = {
                restoreAiBarPage()
                applyAiBarVisibility()
                if (clipboardManager?.isPanelOpen() == true) {
                    overlayContainer.visibility = View.VISIBLE
                    keyboardKeysHost.visibility = View.GONE
                } else {
                    keyboardKeysHost.visibility = View.VISIBLE
                }
                refreshSuggestions()
            },
            shouldPreservePreviewHint = { isRecording || micIsProcessing || liveSttActive || liveSttConnecting }
        )
        clipboardManager?.setupPreviewInteractions()
        btnClipboardUndo.setOnClickListener {
            undoFieldEdit()
        }
        btnDeleteForward.setOnClickListener {
            deleteForward()
        }
        btnSelectAll.setOnClickListener {
            selectAllFieldText()
        }
        btnClipboardClearAll.setOnClickListener {
            clearAllFieldText()
        }
        updateUndoButtonState()

        forceLayoutLtr(keyboardView)

        setupAiStrip()
        applyAiBarLayout()
        handler.post { applyAiBarLayout() }
        applyKeyboardTheme()
        applyUniversalShellHeight()
        scope.launch(Dispatchers.IO) {
            val lang = DictionaryManager.languageForKeyboard(isHebrew)
            DictionaryManager.ensureLoaded(applicationContext, lang)
            userWordsRepository.ensureCache(lang)
        }
        buildKeyboard()
        attachTouchTargetLayoutWatcher(keyboardRows)
        syncBubbleKeyboardWindow()
        return keyboardView
    }

    override fun onEvaluateInputViewShown(): Boolean {
        // Input view must be "shown" (invisibly) while dictation or collapsed-bubble live STT need a session.
        if (bubbleDictationSessionActive) return true
        if (liveSttSessionActive && shouldUseInvisibleLiveSttSession()) return true
        if (shouldCollapseKeyboardForBubble()) return false
        return super.onEvaluateInputViewShown()
    }

    override fun onComputeInsets(outInsets: Insets) {
        if (shouldCollapseKeyboardForBubble() ||
            (bubbleDictationSessionActive && bubbleKeyboardCollapsed) ||
            (liveSttSessionActive && shouldHideKeyboardForLiveStt())
        ) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets
            outInsets.touchableRegion.setEmpty()
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
            return
        }
        super.onComputeInsets(outInsets)
    }

    private fun shouldHideKeyboardForLiveStt(): Boolean =
        liveSttSessionActive && (
            shouldCollapseKeyboardForBubble() ||
                !::keyboardView.isInitialized ||
                keyboardView.visibility != View.VISIBLE
            )

    /** Live STT runs invisibly only when the bubble keyboard is already collapsed. */
    private fun shouldUseInvisibleLiveSttSession(): Boolean =
        voiceBubbleModeActive && bubbleKeyboardCollapsed

    /** When true, bubble collapse and keyboard hide must not run (keyboard stays up). */
    private fun liveSttBlocksKeyboardCollapse(): Boolean =
        liveSttKeepKeyboardOpen || liveSttActive || liveSttConnecting

    /** Decide at tap time whether the keyboard must remain visible. */
    private fun shouldLiveSttKeepKeyboardOpen(): Boolean {
        if (!voiceBubbleModeActive) return true
        if (!bubbleKeyboardCollapsed) return true
        return ::keyboardView.isInitialized &&
            keyboardView.visibility == View.VISIBLE &&
            isInputViewShown
    }

    private fun shouldCollapseKeyboardForBubble(): Boolean =
        voiceBubbleModeActive && bubbleKeyboardCollapsed &&
            voiceBubbleController?.isShowing == true &&
            !liveSttBlocksKeyboardCollapse()

    /** Invisible IME session for live transcribe — only when bubble keyboard is already collapsed. */
    private fun prepareLiveSttSession() {
        liveSttSessionActive = true
        suspendBubbleCollapse = true
        if (::keyboardView.isInitialized) {
            keyboardView.visibility = View.GONE
        }
    }

    private fun releaseLiveSttSession() {
        if (!liveSttSessionActive) return
        liveSttSessionActive = false
        if (!bubbleDictationSessionActive) {
            suspendBubbleCollapse = false
        }
        if (voiceBubbleModeActive && bubbleKeyboardCollapsed) {
            syncBubbleKeyboardWindow()
        } else if (::keyboardView.isInitialized && isInputViewShown && !shouldCollapseKeyboardForBubble()) {
            keyboardView.visibility = View.VISIBLE
        }
    }

    private fun syncLiveSttWindow() {
        if (!liveSttSessionActive) return
        if (::keyboardView.isInitialized) {
            keyboardView.visibility = View.GONE
        }
        syncBubbleKeyboardWindow()
    }

    /** Keep the IME input session alive (invisibly) so bubble dictation can commit text. */
    private fun prepareBubbleDictationSession() {
        if (!voiceBubbleModeActive || !bubbleKeyboardCollapsed) return
        bubbleDictationSessionActive = true
        suspendBubbleCollapse = true
        if (::keyboardView.isInitialized) {
            keyboardView.visibility = View.GONE
        }
    }

    /** Return to fully collapsed bubble after dictation finishes. */
    private fun releaseInputSessionAfterBubbleDictation() {
        if (!bubbleDictationSessionActive) return
        bubbleDictationSessionActive = false
        suspendBubbleCollapse = false
        pendingBubbleCommit = null
        pendingSessionReadyAction = null
        pendingSessionReadyFailed = null
        if (bubbleKeyboardCollapsed && voiceBubbleModeActive) {
            syncBubbleKeyboardWindow()
        }
    }

    /**
     * Waits until [currentInputConnection] is live after [requestShowSelf], then runs [onReady].
     * Required because [requestHideSelf] invalidates the connection when the keyboard is collapsed.
     */
    private fun runWhenBubbleSessionReady(onReady: () -> Unit, onFailed: (() -> Unit)? = null) {
        if (!voiceBubbleModeActive || !bubbleKeyboardCollapsed) {
            onReady()
            return
        }
        prepareBubbleDictationSession()

        fun attemptReady(attemptsLeft: Int) {
            activeInputConnection = currentInputConnection ?: activeInputConnection
            val live = currentInputConnection
            if (live != null) {
                activeInputConnection = live
                syncBubbleKeyboardWindow()
                onReady()
                return
            }
            if (attemptsLeft > 0) {
                handler.postDelayed({ attemptReady(attemptsLeft - 1) }, 80)
            } else {
                releaseInputSessionAfterBubbleDictation()
                showErrorToast("Tap a text field first")
                onFailed?.invoke()
            }
        }

        if (!isInputViewShown) {
            pendingSessionReadyAction = { attemptReady(25) }
            pendingSessionReadyFailed = onFailed
            requestShowSelf(0)
        } else {
            handler.post { attemptReady(25) }
        }
    }

    private fun completePendingBubbleCommit() {
        val pending = pendingBubbleCommit ?: return
        pendingBubbleCommit = null
        val (text, onComplete) = pending
        ensureInputConnectionForCommit(
            onReady = {
                handler.post {
                    val success = tryCommitToField(text.trim())
                    if (!success) {
                        showErrorToast("Couldn't insert text — tap the text field first")
                    }
                    onComplete?.invoke(success)
                    releaseInputSessionAfterBubbleDictation()
                }
            },
            onFailed = {
                showErrorToast("Couldn't insert text — tap the text field first")
                onComplete?.invoke(false)
                releaseInputSessionAfterBubbleDictation()
            }
        )
    }

    private fun commitTextAfterSessionRestore(text: String, onComplete: ((Boolean) -> Unit)?) {
        pendingBubbleCommit = text to onComplete
        runWhenBubbleSessionReady(
            onReady = { completePendingBubbleCommit() },
            onFailed = {
                pendingBubbleCommit = null
                onComplete?.invoke(false)
            }
        )
    }

    private fun syncBubbleKeyboardWindow() {
        if (!::keyboardView.isInitialized) return
        val collapse = shouldCollapseKeyboardForBubble()
        if (collapse) {
            keyboardView.visibility = View.GONE
            // The IME window must actually be dismissed (not just hidden) so the framework
            // knows the keyboard is down. Otherwise tapping the text field is a no-op because
            // Android thinks the keyboard is already showing and never re-requests it.
            if (isInputViewShown && !suspendBubbleCollapse) {
                requestHideSelf(0)
            }
        } else {
            keyboardView.visibility = View.VISIBLE
        }
        window?.window?.decorView?.requestLayout()
        window?.window?.decorView?.requestApplyInsets()
        updateFullscreenMode()
        if (voiceBubbleModeActive && bubbleTextFieldFocused && bubbleKeyboardCollapsed) {
            handler.post { showBubbleOverlayIfFieldActive() }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (voiceBubbleModeActive && bubbleTextFieldFocused && bubbleKeyboardCollapsed) {
            handler.post { showBubbleOverlayIfFieldActive() }
        }
    }

    override fun onShowInputRequested(reason: Int, showingForced: Boolean): Boolean {
        // Invisible IME session while bubble dictation or collapsed-bubble live transcribe runs.
        if (bubbleDictationSessionActive || (liveSttSessionActive && shouldUseInvisibleLiveSttSession())) {
            return true
        }
        if (voiceBubbleModeActive && bubbleKeyboardCollapsed) {
            val explicitShow = showingForced || (reason and SHOW_SOFT_INPUT_REASON != 0)
            if (bubbleConsumeNextShowRequest) {
                cancelBubbleShowRequestConsume()
                if (!explicitShow && !shouldOpenKeyboardForBubbleShowRequest(reason, showingForced)) {
                    return false
                }
            }
            if (!shouldOpenKeyboardForBubbleShowRequest(reason, showingForced)) {
                return false
            }
            userRequestedKeyboard = true
            expandKeyboardFromBubble()
            return true
        }
        return super.onShowInputRequested(reason, showingForced)
    }

    /**
     * Returns true when [onShowInputRequested] should reveal the full keyboard in bubble mode.
     * Navigation/programmatic focus is suppressed for [BUBBLE_AUTO_SHOW_BLOCK_MS] after a new field bind.
     */
    private fun shouldOpenKeyboardForBubbleShowRequest(reason: Int, showingForced: Boolean): Boolean {
        if (showingForced) return true
        if (reason and SHOW_SOFT_INPUT_REASON != 0) return true
        if (userRequestedKeyboard) return true
        // After requestHideSelf the input view is gone — any show request is a user tap or app edit field.
        if (!isInputViewShown && voiceBubbleModeActive && bubbleKeyboardCollapsed) return true
        val sinceFocus = System.currentTimeMillis() - lastBubbleFieldFocusMs
        return sinceFocus >= BUBBLE_AUTO_SHOW_BLOCK_MS
    }

    private fun markBubbleFieldFocused() {
        lastBubbleFieldFocusMs = System.currentTimeMillis()
    }

    private fun markBubbleTextFieldFocused() {
        cancelBubbleFieldHide()
        bubbleTextFieldFocused = true
    }

    private fun cancelBubbleFieldHide() {
        bubbleFieldHideRunnable?.let { handler.removeCallbacks(it) }
        bubbleFieldHideRunnable = null
    }

    /** Hide the bubble when no editable field is focused; defer briefly for chat → edit rebinds. */
    private fun scheduleHideBubbleWhenNoField() {
        cancelBubbleFieldHide()
        val hide = Runnable {
            bubbleFieldHideRunnable = null
            if (bubbleTextFieldFocused) return@Runnable
            if (currentInputConnection != null) return@Runnable
            if (isRecording || micIsProcessing || liveSttActive || liveSttConnecting || liveSttSessionActive) {
                return@Runnable
            }
            if (!voiceBubbleModeActive) return@Runnable
            hideBubbleOverlay(animate = false)
        }
        bubbleFieldHideRunnable = hide
        bubbleTextFieldFocused = false
        handler.postDelayed(hide, BUBBLE_FIELD_HIDE_DELAY_MS)
    }

    private fun hideBubbleForLostField() {
        cancelBubbleFieldHide()
        bubbleTextFieldFocused = false
        if (!voiceBubbleModeActive) return
        if (isRecording || micIsProcessing || liveSttActive || liveSttConnecting || liveSttSessionActive) {
            return
        }
        hideBubbleOverlay(animate = false)
    }

    private fun canShowBubbleOverlay(): Boolean {
        if (!voiceBubbleEnabled || !voiceBubbleModeActive) return false
        if (!bubbleTextFieldFocused) return false
        return currentInputConnection != null
    }

    private fun isEditableField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputType = info.inputType
        if (inputType == InputType.TYPE_NULL) return false
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }
    }

    private fun hideBubbleForFinishedInput() {
        cancelBubbleFieldHide()
        bubbleTextFieldFocused = false
        if (isRecording && !recordingForGhostwriter) {
            cancelRecording()
        }
        if (!isRecording && !micIsProcessing && !liveSttActive && !liveSttConnecting && !liveSttSessionActive) {
            hideBubbleOverlay(animate = false)
        }
    }

    /** Load persisted bubble mode before the first input view frame to avoid keyboard flash. */
    private fun refreshBubbleModeCacheSync() {
        try {
            runBlocking(Dispatchers.IO) {
                voiceBubbleEnabled = GkeysSettings.voiceBubbleEnabled(this@GkeysIME).first()
                defaultToVoiceBubbleCached = GkeysSettings.defaultToVoiceBubble(this@GkeysIME).first()
                val persistedActive = GkeysSettings.voiceBubbleModeActive(this@GkeysIME).first()
                if (voiceBubbleEnabled && persistedActive) {
                    voiceBubbleModeActive = true
                    if (!userRequestedKeyboard && !bubbleDictationSessionActive &&
                        !liveSttSessionActive && !isRecording && !micIsProcessing &&
                        !liveSttConnecting && !liveSttActive && !liveSttKeepKeyboardOpen
                    ) {
                        bubbleKeyboardCollapsed = true
                    }
                } else {
                    voiceBubbleModeActive = false
                    bubbleKeyboardCollapsed = false
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("GkeysIME", "refreshBubbleModeCacheSync failed", e)
        }
    }

    /**
     * Start microphone capture. Recording does not require a live [InputConnection];
     * text is committed after transcription (with session restore when the keyboard is collapsed).
     */
    private fun beginDictationRecording(onStarted: () -> Unit = {}): Boolean {
        if (micIsProcessing && !isRecording) {
            micIsProcessing = false
            stopMicProcessingAnimatorsOnly()
        }
        refreshApiKeys()
        if (!hasMicPermission()) {
            showErrorToast("Allow microphone for Gkeys")
            openAppForMicPermission()
            return false
        }
        if (openAiKey.isBlank()) {
            showErrorToast("Add OpenAI API key in Gkeys settings")
            openAppSettings()
            return false
        }
        stopLiveStt()
        if (shouldUseBubbleMicVisuals()) {
            prepareBubbleDictationSession()
            if (!isInputViewShown) {
                requestShowSelf(0)
            }
        }
        return try {
            audioRecorder.startRecording()
            isRecording = true
            recordingForGhostwriter = false
            beginMicCapture()
            showDictationStatus("Listening… tap mic to finish or ✕ to cancel")
            onStarted()
            if (::keyboardView.isInitialized && keyboardView.visibility == View.VISIBLE) {
                updateMicVisuals(recording = true)
            }
            true
        } catch (_: Exception) {
            showErrorToast("Microphone error — check permission in Gkeys app")
            false
        }
    }

    private fun prepareBubbleDictationForCommit() {
        if (needsSessionRestoreForCommit()) {
            prepareBubbleDictationSession()
        }
    }

    /** True only when the keyboard is hidden and text must be committed via an invisible IME session. */
    private fun needsSessionRestoreForCommit(): Boolean {
        if (!voiceBubbleEnabled || !voiceBubbleModeActive || !bubbleKeyboardCollapsed) return false
        if (!::keyboardView.isInitialized) return true
        return keyboardView.visibility != View.VISIBLE
    }

    /** Fresh connection from the framework — falls back to the last live connection. */
    private fun liveInputConnection(): InputConnection? {
        val live = currentInputConnection
        if (live != null) {
            activeInputConnection = live
            return live
        }
        return activeInputConnection
    }

    private fun activeIc(): InputConnection? {
        val ic = currentInputConnection ?: activeInputConnection
        if (ic != null) activeInputConnection = ic
        return ic
    }

    /** Wait for a live [InputConnection] before inserting dictated text. */
    private fun ensureInputConnectionForCommit(onReady: () -> Unit, onFailed: () -> Unit) {
        if (needsSessionRestoreForCommit()) {
            runWhenBubbleSessionReady(onReady = onReady, onFailed = onFailed)
            return
        }
        if (liveInputConnection() != null) {
            onReady()
            return
        }
        if (!isInputViewShown) {
            requestShowSelf(0)
        }
        fun attempt(remaining: Int) {
            if (liveInputConnection() != null) {
                onReady()
                return
            }
            if (remaining > 0) {
                handler.postDelayed({ attempt(remaining - 1) }, 80)
            } else {
                onFailed()
            }
        }
        handler.post { attempt(30) }
    }

    /** Bubble overlay shows mic state when keyboard is hidden; keyboard mic button when it is open. */
    private fun shouldUseBubbleMicVisuals(): Boolean =
        voiceBubbleModeActive && (bubbleKeyboardCollapsed || !isInputViewShown)

    private fun armBubbleShowRequestConsume() {
        bubbleConsumeNextShowRequest = true
        bubbleShowRequestConsumeRunnable?.let { handler.removeCallbacks(it) }
        val clear = Runnable {
            bubbleConsumeNextShowRequest = false
            bubbleShowRequestConsumeRunnable = null
        }
        bubbleShowRequestConsumeRunnable = clear
        handler.postDelayed(clear, 350)
    }

    private fun cancelBubbleShowRequestConsume() {
        bubbleShowRequestConsumeRunnable?.let { handler.removeCallbacks(it) }
        bubbleShowRequestConsumeRunnable = null
        bubbleConsumeNextShowRequest = false
    }

    private fun hideBubbleOverlay(animate: Boolean = true) {
        voiceBubbleController?.hide(animate = animate)
    }

    private fun showBubbleOverlayIfFieldActive() {
        if (!canShowBubbleOverlay() || isRecording || micIsProcessing) return
        if (voiceBubbleController?.isShowing == true) return
        voiceBubbleController?.show(fast = true)
    }

    /** Keep bubble visible and collapsed when the IME rebinds to a new field in bubble mode. */
    private fun prepareBubbleFieldBinding(restarting: Boolean) {
        if (!voiceBubbleEnabled || !voiceBubbleModeActive) return
        if (liveSttActive || liveSttConnecting || suppressBubbleAutoStart) return
        if (restarting && userRequestedKeyboard) return
        bubbleKeyboardCollapsed = true
        if (!restarting) {
            userRequestedKeyboard = false
        }
        markBubbleFieldFocused()
        tryActivateVoiceBubbleMode(showOverlay = true)
    }

    private suspend fun activateBubbleOnFieldFocus() {
        bubbleKeyboardCollapsed = true
        userRequestedKeyboard = false
        markBubbleFieldFocused()
        if (tryActivateVoiceBubbleMode(showOverlay = true)) {
            handler.post { syncBubbleKeyboardWindow() }
        } else if (GkeysSettings.voiceBubbleModeActive(this@GkeysIME).first()) {
            voiceBubbleModeActive = true
            handler.post { syncBubbleKeyboardWindow() }
        } else {
            voiceBubbleModeActive = false
            GkeysSettings.saveVoiceBubbleModeActive(this@GkeysIME, false)
            handler.post { syncBubbleKeyboardWindow() }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        refreshBubbleModeCacheSync()
        super.onStartInput(attribute, restarting)
        activeInputConnection = currentInputConnection
        if (isEditableField(attribute)) {
            markBubbleTextFieldFocused()
            prepareBubbleFieldBinding(restarting)
            if (voiceBubbleModeActive && bubbleKeyboardCollapsed) {
                handler.post { showBubbleOverlayIfFieldActive() }
            }
        } else {
            hideBubbleForLostField()
        }
        if (voiceBubbleModeActive && bubbleKeyboardCollapsed) {
            handler.post { syncBubbleKeyboardWindow() }
        }
        try {
            refreshApiKeys()
            scope.launch {
                voiceBubbleEnabled = GkeysSettings.voiceBubbleEnabled(this@GkeysIME).first()
                updateVoiceBubbleButtonVisibility()
                if (!voiceBubbleEnabled) {
                    if (voiceBubbleModeActive) {
                        exitVoiceBubbleMode()
                    }
                    return@launch
                }

                val persistedActive = GkeysSettings.voiceBubbleModeActive(this@GkeysIME).first()
                defaultToVoiceBubbleCached = GkeysSettings.defaultToVoiceBubble(this@GkeysIME).first()

                // Live transcribe is running — keep keyboard hidden only when bubble is collapsed.
                if (liveTranscribe.isRunning) {
                    if (!liveSttKeepKeyboardOpen && shouldUseInvisibleLiveSttSession()) {
                        prepareLiveSttSession()
                    }
                    cancelBubbleShowRequestConsume()
                    handler.post {
                        if (!liveSttKeepKeyboardOpen && shouldUseInvisibleLiveSttSession()) {
                            syncLiveSttWindow()
                            showBubbleOverlayIfFieldActive()
                        }
                    }
                    return@launch
                }

                // Bubble swipe-up explicitly requests the full keyboard.
                if (suppressBubbleAutoStart) {
                    suppressBubbleAutoStart = false
                    cancelBubbleShowRequestConsume()
                    syncBubbleKeyboardWindow()
                    return@launch
                }

                // Field re-bound while still focused (e.g. WhatsApp edit message).
                if (restarting) {
                    cancelBubbleShowRequestConsume()
                    if (voiceBubbleModeActive) {
                        if (userRequestedKeyboard) {
                            handler.post { expandKeyboardFromBubble() }
                        } else {
                            handler.post { syncBubbleKeyboardWindow() }
                        }
                    } else {
                        syncBubbleKeyboardWindow()
                    }
                    return@launch
                }

                // New field while bubble mode is on — show bubble, keep keyboard collapsed.
                if (bubbleTextFieldFocused && (persistedActive || voiceBubbleModeActive)) {
                    activateBubbleOnFieldFocus()
                    return@launch
                }

                // First-time entry when "default to bubble" setting is enabled.
                if (bubbleTextFieldFocused && defaultToVoiceBubbleCached) {
                    activateBubbleOnFieldFocus()
                    return@launch
                }

                syncBubbleKeyboardWindow()
            }
        } catch (e: Throwable) {
            android.util.Log.e("GkeysIME", "onStartInput failed", e)
        }
    }

    override fun onFinishInput() {
        try {
            cancelBubbleShowRequestConsume()
            if (isRecording && !recordingForGhostwriter) {
                cancelRecording()
            }
            if (voiceBubbleModeActive && voiceBubbleEnabled) {
                scheduleHideBubbleWhenNoField()
            } else if (!isRecording && !micIsProcessing && !liveSttActive && !liveSttConnecting) {
                hideBubbleOverlay(animate = true)
            }
        } catch (e: Exception) {
            android.util.Log.e("GkeysIME", "onFinishInput failed", e)
        }
        // Keep the cached connection while bubble dictation or live transcribe is active.
        if (!isRecording && !micIsProcessing && !liveSttActive && !liveSttConnecting && !liveSttSessionActive) {
            activeInputConnection = null
        }
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        refreshBubbleModeCacheSync()
        val collapseForBubble = voiceBubbleModeActive && bubbleKeyboardCollapsed &&
            !bubbleDictationSessionActive && !liveSttSessionActive && !restarting &&
            !userRequestedKeyboard && !liveSttKeepKeyboardOpen &&
            !isRecording && !micIsProcessing && !liveSttActive && !liveSttConnecting
        if (collapseForBubble) {
            armBubbleShowRequestConsume()
            if (::keyboardView.isInitialized) {
                keyboardView.visibility = View.GONE
            }
        } else {
            if (::keyboardView.isInitialized) {
                keyboardView.visibility = View.VISIBLE
            }
        }
        if (liveSttSessionActive && shouldUseInvisibleLiveSttSession() && ::keyboardView.isInitialized) {
            keyboardView.visibility = View.GONE
        }
        super.onStartInputView(info, restarting)
        window?.window?.let { micSessionGuard.setImeWindow(it) }
        activeInputConnection = currentInputConnection
        try {
            val sessionPending = pendingSessionReadyAction
            if (sessionPending != null) {
                pendingSessionReadyAction = null
                pendingSessionReadyFailed = null
                syncBubbleKeyboardWindow()
                handler.post { sessionPending() }
                refreshApiKeys()
                return
            }
            if (pendingBubbleCommit != null) {
                syncBubbleKeyboardWindow()
                completePendingBubbleCommit()
                refreshApiKeys()
                return
            }
            syncBubbleKeyboardWindow()
            if (AppVersionTracker.noteCurrentVersion(this)) {
                showErrorToast("Gkeys updated — pick Gkeys again in your keyboard switcher")
            }
            refreshApiKeys()
            if (!(bubbleDictationSessionActive && bubbleKeyboardCollapsed) &&
                !liveTranscribe.isRunning && !liveSttKeepKeyboardOpen
            ) {
                loadSettings()
            }
            if (!restarting) {
                fieldUndo.clear()
                if (::btnClipboardUndo.isInitialized) updateUndoButtonState()
            }
            clipboardManager?.startListening()
            showBubbleOverlayIfFieldActive()
            liveTranscribe.flushPendingPartial()
            handler.post { syncWordPrefixAndRefreshSuggestions() }
        } catch (e: Throwable) {
            android.util.Log.e("GkeysIME", "onStartInputView failed", e)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        try {
            hideSuggestionBar()
            refreshBubbleModeCacheSync()
            if (voiceBubbleModeActive) {
                if (liveSttBlocksKeyboardCollapse()) {
                    bubbleKeyboardCollapsed = false
                } else if (bubbleKeyboardCollapsed || bubbleDictationSessionActive ||
                    (liveSttSessionActive && shouldUseInvisibleLiveSttSession())
                ) {
                    bubbleKeyboardCollapsed = true
                } else if (userRequestedKeyboard) {
                    bubbleKeyboardCollapsed = false
                }
                hideVoiceOverlay()
                hideGhostwriterOverlay()
                clipboardManager?.stopListening()
                clipboardManager?.hidePanel()
                if (finishingInput) {
                    hideBubbleForFinishedInput()
                } else {
                    showBubbleOverlayIfFieldActive()
                }
                super.onFinishInputView(finishingInput)
                return
            }
            if (isRecording && !recordingForGhostwriter) {
                cancelRecording()
            }
            if (!micIsProcessing && !liveSttActive && !liveSttConnecting && !liveSttSessionActive) {
                cancelGhostwriter()
                stopLiveStt()
                stopMicProcessingAnimation()
            }
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
                deepgramKey = GkeysSettings.deepgramKey(this@GkeysIME).first()
                keyRepeatMs = GkeysSettings.keyRepeatSpeed(this@GkeysIME).first()
                deleteSpeedMs = GkeysSettings.deleteSpeed(this@GkeysIME).first()
                vibrationEnabled = GkeysSettings.vibrationEnabled(this@GkeysIME).first()
                vibrationStrength = GkeysSettings.vibrationStrength(this@GkeysIME).first()
                voiceTranslateFrom = GkeysSettings.voiceTranslateFrom(this@GkeysIME).first()
                voiceTranslateTo = GkeysSettings.voiceTranslateTo(this@GkeysIME).first()
                isHebrew = GkeysSettings.defaultLanguage(this@GkeysIME).first() == "he"
                oneHandedMode = GkeysSettings.oneHandedMode(this@GkeysIME).first()
                rightHandedMode = GkeysSettings.rightHandedMode(this@GkeysIME).first()
                keySizePreset = GkeysSettings.keySizePreset(this@GkeysIME).first()
                universalKeyboardHeightDp = GkeysSettings.keyboardHeightDp(this@GkeysIME).first()
                oneHandedWidthFraction = GkeysSettings.oneHandedWidthFraction(this@GkeysIME).first()
                voiceBubbleEnabled = GkeysSettings.voiceBubbleEnabled(this@GkeysIME).first()
                defaultToVoiceBubbleCached = GkeysSettings.defaultToVoiceBubble(this@GkeysIME).first()
                voiceBubbleModeActive = GkeysSettings.voiceBubbleModeActive(this@GkeysIME).first()
                if (voiceBubbleModeActive && !userRequestedKeyboard &&
                    !liveTranscribe.isRunning && !liveSttKeepKeyboardOpen
                ) {
                    bubbleKeyboardCollapsed = true
                }
                if (!voiceBubbleEnabled && voiceBubbleModeActive) {
                    exitVoiceBubbleMode()
                } else if (voiceBubbleModeActive && voiceBubbleController?.isShowing != true &&
                    bubbleTextFieldFocused
                ) {
                    handler.post { tryActivateVoiceBubbleMode(showOverlay = true) }
                }
                updateVoiceBubbleButtonVisibility()
                aiBarWandEnabled = GkeysSettings.aiBarWandEnabled(this@GkeysIME).first()
                aiBarPolishButtonEnabled = GkeysSettings.aiBarPolishButtonEnabled(this@GkeysIME).first()
                aiBarMicToolbarEnabled = GkeysSettings.aiBarMicToolbarEnabled(this@GkeysIME).first()
                aiBarVoiceInputIncludesMic = GkeysSettings.aiBarVoiceInputIncludesMic(this@GkeysIME).first()
                aiBarLiveTranscribeEnabled = GkeysSettings.aiBarLiveTranscribeEnabled(this@GkeysIME).first()
                aiBarOrder = GkeysSettings.aiBarOrder(this@GkeysIME).first()
                aiBarClearAllEnabled = GkeysSettings.aiBarClearAllEnabled(this@GkeysIME).first()
                aiBarClipboardToolbarEnabled = GkeysSettings.aiBarClipboardToolbarEnabled(this@GkeysIME).first()
                aiBarNumpadEnabled = GkeysSettings.aiBarNumpadEnabled(this@GkeysIME).first()
                val themeMode = GkeysSettings.themeMode(this@GkeysIME).first()
                val newDarkTheme = GkeysSettings.isDarkThemeMode(themeMode)
                val themeChanged = newDarkTheme != darkTheme
                darkTheme = newDarkTheme
                applyAiBarLayout()
                if (::keyboardView.isInitialized) {
                    handler.post { applyAiBarLayout() }
                }
                adaptiveTouchEnabled = GkeysSettings.adaptiveTouchEnabled(this@GkeysIME).first()
                if (::adaptiveTouch.isInitialized) {
                    adaptiveTouch.setEnabled(adaptiveTouchEnabled)
                }
                layoutProfile = KeyboardLayoutMetrics.profile(keySizePreset, rightHandedMode)
                keyboardHeightPx = 0
                if (::touchResolver.isInitialized) {
                    touchResolver.rightHandedMode = isLayoutRightBiased()
                }
                if (::keyboardView.isInitialized) {
                    if (themeChanged) applyKeyboardTheme()
                    applyUniversalShellHeight()
                    applyOneHandedMode()
                    updatePolishLevelButton()
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

        btnMicCancel.setOnClickListener {
            hapticKeyTap()
            cancelAiBarRecording()
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
            hapticKeyTap()
            openAppSettings()
        }

        btnVoiceBubble.setOnClickListener {
            vibrate()
            if (voiceBubbleModeActive && voiceBubbleController?.isShowing == true) {
                exitVoiceBubbleMode()
            } else {
                enterVoiceBubbleMode(fromKeyboard = true)
            }
        }

        btnWand.setOnClickListener {
            vibrate()
            handleGhostwriterTap()
        }
        btnWand.isClickable = true
        btnWand.isFocusable = true

        btnLiveTranscribe.setOnClickListener {
            hapticKeyTap()
            handleLiveTranscribeTap()
        }
        btnLiveTranscribe.isClickable = true
        btnLiveTranscribe.isFocusable = true
        updateLiveTranscribeButton()

        btnPolishLevel.setOnClickListener { cyclePolishLevel() }
        btnRawPolish.setOnClickListener { polishFieldTextManual() }
        updatePolishLevelButton()
        updateNumpadButton()
        updateVoiceBubbleButtonVisibility()
        AiBarShimmer.attach(aiBarShimmerPrimary)
        startMicIdleSparkleAnimation()
    }

    private fun cyclePolishLevel() {
        setPolishLevel(GkeysSettings.nextPolishLevel(polishLevel))
    }

    private fun setPolishLevel(level: String) {
        if (polishLevel == level) return
        polishLevel = level
        updatePolishLevelButton()
        vibrate()
        toastStatus("${GkeysSettings.polishLevelLabel(polishLevel)} dictation")
        handler.postDelayed({ toastStatus("") }, 1000)
        scope.launch {
            try {
                GkeysSettings.savePolishLevel(this@GkeysIME, level)
            } catch (e: Exception) {
                android.util.Log.e("GkeysIME", "savePolishLevel failed", e)
            }
        }
    }

    private fun updatePolishLevelButton() {
        if (!::btnPolishLevel.isInitialized) return
        btnPolishLevel.text = GkeysSettings.polishLevelLetter(polishLevel)
        btnPolishLevel.contentDescription =
            "${GkeysSettings.polishLevelLabel(polishLevel)} dictation — tap to change polish mode"
        if (::btnRawPolish.isInitialized) {
            val showRawPolish = polishLevel == GkeysSettings.POLISH_RAW && aiBarPolishButtonEnabled
            btnRawPolish.visibility = if (showRawPolish) View.VISIBLE else View.GONE
        }
    }

    private fun polishFieldTextManual() {
        if (isPolishing) return
        refreshApiKeys()
        if (openAiKey.isBlank()) {
            showErrorToast("Add OpenAI API key in Gkeys settings")
            openAppSettings()
            return
        }
        val ic = currentInputConnection ?: return
        val target = InputTextHelper.extractForPolishManual(ic, FIELD_TEXT_SCAN_LIMIT)
        if (target == null || target.text.isBlank()) {
            showErrorToast("No text to polish")
            return
        }
        val polishAs = if (polishLevel == GkeysSettings.POLISH_RAW) {
            GkeysSettings.POLISH_NATURAL
        } else {
            polishLevel
        }
        isPolishing = true
        toastStatus("Polishing…")
        scope.launch {
            val result = aiManager.polishText(target.text, openAiKey, polishAs)
            toastStatus("")
            isPolishing = false
            if (result.isSuccess) {
                val polished = result.getOrThrow()
                val finalized = aiManager.finalizeWithUserInstructions(polished, openAiKey).getOrElse { polished }
                InputTextHelper.replaceText(ic, target, finalized)
                vibrate()
            } else {
                showErrorToast("Polish failed — text unchanged")
            }
        }
    }

    /** Tap wand: open ghostwriter, record, tap again to write into the field. */
    private fun handleGhostwriterTap() {
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

        if (isGhostwriterOverlay) {
            if (isRecording && recordingForGhostwriter) {
                stopGhostwriterAndProcess()
            } else {
                cancelGhostwriter()
            }
            return
        }

        stopLiveStt()
        if (isVoiceOverlay) {
            hideVoiceOverlay()
        }
        releaseAudioRecorderForGhostwriter()

        vibrate(12)
        showGhostwriterOverlay()
        startGhostwriterRecording()
    }

    private fun releaseAudioRecorderForGhostwriter() {
        try {
            if (isRecording || audioRecorder.isRecording) {
                audioRecorder.cancelRecording()
                endMicCapture()
            }
        } catch (e: Exception) {
            android.util.Log.w("GkeysIME", "releaseAudioRecorderForGhostwriter failed", e)
        }
        isRecording = false
        recordingForGhostwriter = false
        updateMicVisuals(recording = false)
    }

    private fun beginMicCapture() {
        if (::micSessionGuard.isInitialized) {
            micSessionGuard.acquire()
        }
    }

    private fun endMicCapture() {
        if (::micSessionGuard.isInitialized) {
            micSessionGuard.release()
        }
    }

    /** Tap live-transcribe button: toggle Deepgram streaming into the text field. */
    private fun handleLiveTranscribeTap() {
        if (isGhostwriterOverlay) {
            showErrorToast("Close ghostwriter first")
            return
        }
        vibrate()
        refreshApiKeys()
        if (!hasMicPermission()) {
            showLiveSttStatus("Allow microphone for Gkeys", autoClearMs = 5000L)
            openAppForMicPermission()
            return
        }
        if (deepgramKey.isBlank()) {
            showLiveSttStatus("Add Deepgram API key in Gkeys settings", autoClearMs = 6000L)
            openAppSettings()
            return
        }
        if (liveTranscribe.isRunning) {
            stopLiveStt()
            showLiveSttStatus("Live transcribe stopped")
            handler.postDelayed({ clearLiveSttStatus() }, 1500L)
            return
        }
        if (isRecording) {
            cancelRecording()
        }
        releaseAudioRecorderForGhostwriter()
        endMicCapture()

        liveSttKeepKeyboardOpen = shouldLiveSttKeepKeyboardOpen()

        fun beginStreaming() {
            liveTranscribe.start(
                apiKey = deepgramKey,
                languageCode = sttLanguageCode(),
                onStatus = { showLiveSttStatus(it) },
                onError = { message, autoClearMs ->
                    showLiveSttStatus(message, autoClearMs = autoClearMs)
                    liveSttKeepKeyboardOpen = false
                    releaseLiveSttSession()
                }
            )
        }

        fun launchLiveStt() {
            if (liveSttKeepKeyboardOpen) {
                if (liveInputConnection() == null) {
                    showLiveSttStatus("Tap a text field first", autoClearMs = 5000L)
                    return
                }
                beginStreaming()
                return
            }
            ensureInputForLiveStt {
                prepareLiveSttSession()
                syncLiveSttWindow()
                showBubbleOverlayIfFieldActive()
                beginStreaming()
            }
        }

        // Brief pause so dictation/ghostwriter can fully release the mic hardware.
        handler.postDelayed({ launchLiveStt() }, 150L)
    }

    /** Wait for input connection — only used when bubble keyboard is collapsed. */
    private fun ensureInputForLiveStt(onReady: () -> Unit) {
        if (liveInputConnection() != null) {
            onReady()
            return
        }
        runWhenLiveSttSessionReady(
            onReady = onReady,
            onFailed = {
                liveSttKeepKeyboardOpen = false
                releaseLiveSttSession()
                showLiveSttStatus("Tap a text field first", autoClearMs = 5000L)
            }
        )
    }

    private fun runWhenLiveSttSessionReady(onReady: () -> Unit, onFailed: (() -> Unit)? = null) {
        if (!voiceBubbleModeActive || !bubbleKeyboardCollapsed) {
            onReady()
            return
        }
        fun attemptReady(attemptsLeft: Int) {
            activeInputConnection = currentInputConnection ?: activeInputConnection
            val live = currentInputConnection
            if (live != null) {
                activeInputConnection = live
                onReady()
                return
            }
            if (attemptsLeft > 0) {
                handler.postDelayed({ attemptReady(attemptsLeft - 1) }, 80)
            } else {
                releaseLiveSttSession()
                showErrorToast("Tap a text field first")
                onFailed?.invoke()
            }
        }
        if (!isInputViewShown) {
            pendingSessionReadyAction = { attemptReady(25) }
            pendingSessionReadyFailed = onFailed
            requestShowSelf(0)
        } else {
            handler.post { attemptReady(25) }
        }
    }

    private fun updateLiveTranscribeButton() {
        if (!::btnLiveTranscribe.isInitialized) return
        val active = liveTranscribe.isRunning
        btnLiveTranscribe.setBackgroundResource(
            if (active) R.drawable.ai_mic_bg_active else R.drawable.btn_circle_bg
        )
        btnLiveTranscribe.alpha = if (active) 1f else 0.92f
    }

    private fun sttLanguageCode(): String = when {
        isHebrew -> "he-IL"
        else -> "en-US"
    }

    private fun stopLiveStt(clearStatus: Boolean = true) {
        if (!liveTranscribe.isRunning && !liveSttSessionActive) return
        if (liveTranscribe.isRunning) {
            liveTranscribe.stop()
        }
        liveSttKeepKeyboardOpen = false
        releaseLiveSttSession()
        updateLiveTranscribeButton()
        if (clearStatus && !isRecording && !micIsProcessing) {
            handler.postDelayed({ clearLiveSttStatus() }, 300L)
        }
    }

    private fun showLiveSttStatus(message: String, autoClearMs: Long = 0L) {
        showImeStatusBanner(message, autoClearMs)
    }

    /** Full-width status line below the AI bar (live transcribe, errors). */
    private fun showImeStatusBanner(message: String, autoClearMs: Long = 0L) {
        imeStatusBannerClearRunnable?.let { handler.removeCallbacks(it) }
        imeStatusBannerClearRunnable = null
        if (::tvStatus.isInitialized) {
            tvStatus.text = message
            tvStatus.setTextColor(themeColor(R.color.gkeys_accent))
            tvStatus.visibility = View.VISIBLE
        }
        if (autoClearMs > 0L) {
            showErrorToast(message)
            val clear = Runnable { hideImeStatusBanner() }
            imeStatusBannerClearRunnable = clear
            handler.postDelayed(clear, autoClearMs)
        }
    }

    private fun hideImeStatusBanner() {
        imeStatusBannerClearRunnable?.let { handler.removeCallbacks(it) }
        imeStatusBannerClearRunnable = null
        if (!::tvStatus.isInitialized) return
        tvStatus.visibility = View.GONE
        tvStatus.text = ""
    }

    private fun clearLiveSttStatus() {
        if (liveSttActive || liveSttConnecting) return
        hideImeStatusBanner()
        clearDictationStatus()
    }

    private fun isInputViewReady(): Boolean = ::keyboardView.isInitialized

    private fun showGhostwriterOverlay() {
        if (!isInputViewReady()) return
        isGhostwriterOverlay = true
        keyboardKeysHost.visibility = View.GONE
        ghostwriterOverlay.visibility = View.VISIBLE
        ghostwriterOverlay.bringToFront()
        ghostwriterOverlay.requestLayout()
        ghostwriterStatus.text = "Listening… tap wand or screen when done"
    }

    private fun hideGhostwriterOverlay() {
        isGhostwriterOverlay = false
        if (!::ghostwriterOverlay.isInitialized) return
        ghostwriterOverlay.visibility = View.GONE
        if (keyboardVisible && ::keyboardKeysHost.isInitialized &&
            clipboardManager?.isPanelOpen() != true && !isVoiceOverlay
        ) {
            keyboardKeysHost.visibility = View.VISIBLE
        }
    }

    private fun startGhostwriterRecording() {
        refreshApiKeys()
        if (openAiKey.isBlank() || !hasMicPermission()) {
            hideGhostwriterOverlay()
            return
        }
        if (audioRecorder.isRecording) {
            releaseAudioRecorderForGhostwriter()
        }
        try {
            audioRecorder.startRecording()
            isRecording = true
            recordingForGhostwriter = true
            beginMicCapture()
            if (::ghostwriterStatus.isInitialized) {
                ghostwriterStatus.text = "Listening… tap wand or screen when done"
            }
        } catch (e: Exception) {
            android.util.Log.e("GkeysIME", "startGhostwriterRecording failed", e)
            hideGhostwriterOverlay()
            showErrorToast("Microphone error — check permission in Gkeys app")
        }
    }

    private fun cancelGhostwriter() {
        if (isRecording && recordingForGhostwriter) {
            audioRecorder.cancelRecording()
            isRecording = false
            recordingForGhostwriter = false
            endMicCapture()
        }
        hideGhostwriterOverlay()
        if (::ghostwriterStatus.isInitialized) {
            ghostwriterStatus.text = "Tap when done speaking"
        }
    }

    private fun stopGhostwriterAndProcess() {
        if (!isRecording || !recordingForGhostwriter) return
        val file = audioRecorder.stopRecording()
        isRecording = false
        recordingForGhostwriter = false
        endMicCapture()
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
            if (written.isSuccess) {
                val text = written.getOrThrow()
                val finalized = aiManager.finalizeWithUserInstructions(text, openAiKey).getOrElse { text }
                activeIc()?.commitText(finalized, 1) ?: currentInputConnection?.commitText(finalized, 1)
                vibrate()
            } else {
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
        vibrate()
        if (isRecording) {
            stopRecordingAndProcess(VoiceAction.DEFAULT)
            return
        }
        if (beginDictationRecording()) {
            updateMicVisuals(recording = true)
        }
    }

    private fun refreshApiKeys() {
        try {
            openAiKey = SecureApiKeyStore.getOpenAiKey(this)
            anthropicKey = SecureApiKeyStore.getAnthropicKey(this)
            deepgramKey = SecureApiKeyStore.getDeepgramKey(this)
            if (deepgramKey.isBlank()) {
                runBlocking(Dispatchers.IO) {
                    deepgramKey = GkeysSettings.deepgramKey(this@GkeysIME).first()
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("GkeysIME", "refreshApiKeys failed", e)
            openAiKey = ""
            anthropicKey = ""
            deepgramKey = ""
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
            putExtra(SettingsActivity.EXTRA_SHOW_OVERLAY_RESTRICTED_HELP, true)
        })
    }

    private fun enterVoiceBubbleMode(@Suppress("UNUSED_PARAMETER") fromKeyboard: Boolean) {
        if (!voiceBubbleEnabled) {
            showErrorToast("Voice bubble is off — enable it in Gkeys settings")
            return
        }
        userRequestedKeyboard = false
        bubbleKeyboardCollapsed = true
        scope.launch { GkeysSettings.saveVoiceBubbleModeActive(this@GkeysIME, true) }
        val hasField = currentInputConnection != null
        if (hasField) {
            markBubbleTextFieldFocused()
            armBubbleShowRequestConsume()
            if (!tryActivateVoiceBubbleMode(showOverlay = true)) {
                showErrorToast("Allow display over other apps for Voice Bubble")
                openAppForOverlayPermission()
                return
            }
        } else {
            voiceBubbleModeActive = true
            if (voiceBubbleController?.canDrawOverlay() != true) {
                voiceBubbleModeActive = false
                scope.launch { GkeysSettings.saveVoiceBubbleModeActive(this@GkeysIME, false) }
                showErrorToast("Allow display over other apps for Voice Bubble")
                openAppForOverlayPermission()
                return
            }
            hideBubbleOverlay(animate = false)
            syncBubbleKeyboardWindow()
        }
        applyAiBarVisibility()
    }

    /** @return true if bubble mode is active (overlay shown when [showOverlay] is true). */
    private fun tryActivateVoiceBubbleMode(showOverlay: Boolean = true): Boolean {
        val controller = voiceBubbleController ?: return false
        if (!controller.canDrawOverlay()) return false
        voiceBubbleModeActive = true
        if (showOverlay) {
            if (!canShowBubbleOverlay()) {
                syncBubbleKeyboardWindow()
                return true
            }
            if (!controller.isShowing) {
                controller.show()
            }
            if (!controller.isShowing) {
                voiceBubbleModeActive = false
                bubbleKeyboardCollapsed = false
                syncBubbleKeyboardWindow()
                return false
            }
        } else {
            controller.hide(animate = false)
        }
        syncBubbleKeyboardWindow()
        applyAiBarVisibility()
        return true
    }

    /** Expand keyboard while keeping the floating bubble visible. */
    private fun expandKeyboardFromBubble() {
        if (!voiceBubbleModeActive) return
        cancelBubbleShowRequestConsume()
        userRequestedKeyboard = true
        bubbleKeyboardCollapsed = false
        keyboardVisible = true
        if (::keyboardView.isInitialized) {
            keyboardView.visibility = View.VISIBLE
            keyboardContent.visibility = View.VISIBLE
            if (clipboardManager?.isPanelOpen() != true && !isVoiceOverlay && !isGhostwriterOverlay) {
                keyboardKeysHost.visibility = View.VISIBLE
            }
        }
        showBubbleOverlayIfFieldActive()
        if (!isInputViewShown) {
            requestShowSelf(0)
        } else {
            window?.window?.decorView?.requestLayout()
        }
        syncBubbleKeyboardWindow()
    }

    /** Collapse keyboard back to bubble-only (bubble stays on screen). */
    private fun collapseKeyboardForBubble() {
        if (!voiceBubbleModeActive) return
        userRequestedKeyboard = false
        bubbleKeyboardCollapsed = true
        syncBubbleKeyboardWindow()
    }

    /** Floating bubble tap: start/stop dictation; collapse full keyboard first if it was open. */
    private fun handleFloatingBubbleTap() {
        if (isRecording) {
            voiceBubbleController?.setState(VoiceBubbleState.PROCESSING)
            stopRecordingAndProcess(VoiceAction.DEFAULT)
            return
        }
        if (voiceBubbleModeActive && !bubbleKeyboardCollapsed) {
            collapseKeyboardForBubble()
            handler.postDelayed({ handleBubbleMicTap() }, 120)
            return
        }
        handleBubbleMicTap()
    }

    /** Turn off bubble mode entirely and return to the normal keyboard. */
    private fun exitVoiceBubbleMode() {
        cancelBubbleShowRequestConsume()
        cancelBubbleFieldHide()
        bubbleTextFieldFocused = false
        if (!voiceBubbleModeActive && voiceBubbleController?.isShowing != true) {
            bubbleKeyboardCollapsed = false
            syncBubbleKeyboardWindow()
            return
        }
        voiceBubbleModeActive = false
        bubbleKeyboardCollapsed = false
        scope.launch { GkeysSettings.saveVoiceBubbleModeActive(this@GkeysIME, false) }
        stopLiveStt()
        cancelGhostwriter()
        if (isRecording && !recordingForGhostwriter) {
            cancelRecording()
        }
        handler.post { voiceBubbleController?.hide(animate = false) }
        if (::keyboardView.isInitialized) {
            keyboardView.visibility = View.VISIBLE
        }
        if (!isInputViewShown) {
            requestShowSelf(0)
        }
        syncBubbleKeyboardWindow()
        applyAiBarVisibility()
    }

    private fun updateVoiceBubbleButtonVisibility() {
        if (!::btnVoiceBubble.isInitialized) return
        btnVoiceBubble.visibility = if (voiceBubbleEnabled) View.VISIBLE else View.GONE
    }

    private fun restoreAiBarPage() {
        if (::aiBarShellPrimary.isInitialized) aiBarShellPrimary.visibility = View.VISIBLE
    }

    private fun hideAiBarsForOverlay() {
        if (::aiBarShellPrimary.isInitialized) aiBarShellPrimary.visibility = View.GONE
    }

    private fun applyAiBarLayout() {
        if (!::aiStrip.isInitialized) return
        try {
            val views = aiBarOrder.mapNotNull { aiBarViewForId(it) }
            aiStrip.removeAllViews()
            for (view in views) {
                aiStrip.addView(view, aiBarItemLayoutParams(view))
            }
            recoverOrphanedToolbarViews(views.toSet())
            applyAiBarVisibility()
        } catch (e: Throwable) {
            android.util.Log.e("GkeysIME", "applyAiBarLayout failed", e)
        }
    }

    private fun recoverOrphanedToolbarViews(assigned: Set<View>) {
        for (view in allKnownToolbarViews()) {
            if (view in assigned || view.parent != null) continue
            aiStrip.addView(view, aiBarItemLayoutParams(view))
            android.util.Log.w("GkeysIME", "Recovered orphaned toolbar view id=${view.id}")
        }
    }

    private fun allKnownToolbarViews(): List<View> = buildList {
        for (id in AiBarLayout.ALL_ITEMS) {
            aiBarViewForId(id)?.let { add(it) }
        }
    }.distinctBy { it.id }

    private fun aiBarViewForId(id: String): View? = when (id) {
        AiBarLayout.WAND -> if (::btnWand.isInitialized) btnWand else null
        AiBarLayout.DELETE_FORWARD -> if (::btnDeleteForward.isInitialized) btnDeleteForward else null
        AiBarLayout.POLISH -> if (::btnPolishLevel.isInitialized) btnPolishLevel else null
        AiBarLayout.RAW_POLISH -> if (::btnRawPolish.isInitialized) btnRawPolish else null
        AiBarLayout.CLEAR_ALL -> if (::btnClipboardClearAll.isInitialized) btnClipboardClearAll else null
        AiBarLayout.CLIPBOARD -> if (::clipboardArea.isInitialized) clipboardArea else null
        AiBarLayout.LIVE -> if (::btnLiveTranscribe.isInitialized) btnLiveTranscribe else null
        AiBarLayout.MIC -> if (::micGroup.isInitialized) micGroup else null
        AiBarLayout.NUMPAD -> if (::btnKeyboard.isInitialized) btnKeyboard else null
        AiBarLayout.SETTINGS -> if (::btnSettings.isInitialized) btnSettings else null
        AiBarLayout.UNDO -> if (::btnClipboardUndo.isInitialized) btnClipboardUndo else null
        AiBarLayout.SELECT_ALL -> if (::btnSelectAll.isInitialized) btnSelectAll else null
        AiBarLayout.BUBBLE -> if (::btnVoiceBubble.isInitialized) btnVoiceBubble else null
        else -> null
    }

    private fun aiBarItemLayoutParams(view: View): LinearLayout.LayoutParams {
        val iconSize = dp(AiBarLayout.ICON_SIZE_DP)
        val previous = view.layoutParams as? LinearLayout.LayoutParams
        val width = when (view.id) {
            R.id.clipboard_area -> dp(AiBarLayout.CLIPBOARD_WIDTH_DP)
            R.id.mic_group -> LinearLayout.LayoutParams.WRAP_CONTENT
            else -> iconSize
        }
        val height = if (view.id == R.id.mic_group) iconSize else iconSize
        return LinearLayout.LayoutParams(width, height).apply {
            marginStart = previous?.marginStart ?: if (aiStrip.childCount == 0) 0 else dp(5)
            marginEnd = previous?.marginEnd ?: 0
            topMargin = previous?.topMargin ?: 0
            bottomMargin = previous?.bottomMargin ?: 0
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
    }

    private fun applyAiBarVisibility() {
        if (!::btnWand.isInitialized) return
        btnWand.visibility = if (aiBarWandEnabled) View.VISIBLE else View.GONE
        btnPolishLevel.visibility = if (aiBarPolishButtonEnabled) View.VISIBLE else View.GONE
        if (::btnRawPolish.isInitialized) {
            btnRawPolish.visibility =
                if (aiBarPolishButtonEnabled && polishLevel == GkeysSettings.POLISH_RAW) View.VISIBLE
                else View.GONE
        }
        if (::btnClipboardClearAll.isInitialized) {
            btnClipboardClearAll.visibility = if (aiBarClearAllEnabled) View.VISIBLE else View.GONE
        }
        if (::clipboardArea.isInitialized) {
            clipboardArea.visibility = if (aiBarClipboardToolbarEnabled) View.VISIBLE else View.GONE
        }
        if (::btnKeyboard.isInitialized) {
            btnKeyboard.visibility = if (aiBarNumpadEnabled) View.VISIBLE else View.GONE
        }
        if (::micGroup.isInitialized) {
            val showMic = aiBarVoiceInputIncludesMic &&
                aiBarMicToolbarEnabled &&
                !voiceBubbleModeActive
            micGroup.visibility = if (showMic) View.VISIBLE else View.GONE
        }
        btnLiveTranscribe.visibility = if (aiBarLiveTranscribeEnabled) View.VISIBLE else View.GONE
        if (!aiBarVoiceInputIncludesMic && isRecording && !recordingForGhostwriter && !voiceBubbleModeActive) {
            cancelRecording()
        }
        if (aiBarVoiceInputIncludesMic &&
            !aiBarMicToolbarEnabled &&
            isRecording &&
            !recordingForGhostwriter &&
            !voiceBubbleModeActive
        ) {
            cancelRecording()
        }
        if (!aiBarLiveTranscribeEnabled && (liveSttActive || liveSttConnecting)) {
            stopLiveStt()
        }
        updateVoiceBubbleButtonVisibility()
    }

    private fun handleBubbleMicTap() {
        if (bubbleTranslateHoldActive) return
        if (isRecording) {
            voiceBubbleController?.setState(VoiceBubbleState.PROCESSING)
            stopRecordingAndProcess(VoiceAction.DEFAULT)
            return
        }
        val started = beginDictationRecording {
            voiceBubbleController?.setState(VoiceBubbleState.RECORDING)
        }
        if (!started) {
            voiceBubbleController?.setState(VoiceBubbleState.IDLE)
        }
    }

    private fun handleBubbleCancelRecording() {
        if (!isRecording || recordingForGhostwriter) return
        bubbleTranslateHoldActive = false
        cancelRecording()
    }

    private var bubbleTranslateHoldActive = false

    private fun handleBubbleTranslateHoldStart() {
        if (isRecording) {
            cancelRecording()
        }
        bubbleTranslateHoldActive = true
        pendingVoiceAction = VoiceAction.TRANSLATE
        vibrate(12)
        val started = beginDictationRecording {
            voiceBubbleController?.setState(VoiceBubbleState.RECORDING)
        }
        if (!started) {
            bubbleTranslateHoldActive = false
            voiceBubbleController?.setState(VoiceBubbleState.IDLE)
        }
    }

    private fun handleBubbleTranslateHoldEnd(cancelled: Boolean) {
        if (cancelled) {
            bubbleTranslateHoldActive = false
            cancelRecording()
            voiceBubbleController?.setState(VoiceBubbleState.IDLE)
            return
        }
        bubbleTranslateHoldActive = false
        if (!isRecording) {
            showErrorToast("Hold longer to translate")
            voiceBubbleController?.setState(VoiceBubbleState.IDLE)
            return
        }
        voiceBubbleController?.setState(VoiceBubbleState.PROCESSING)
        stopRecordingAndProcess(VoiceAction.TRANSLATE)
    }

    private fun commitTranscriptionResult(text: String, onComplete: (Boolean) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onComplete(false)
            return
        }

        ensureInputConnectionForCommit(
            onReady = {
                handler.post {
                    if (tryCommitToField(trimmed)) {
                        onComplete(true)
                    } else {
                        android.util.Log.w("GkeysIME", "commitTranscriptionResult: commit failed")
                        showErrorToast("Couldn't insert text — tap the text field first")
                        onComplete(false)
                    }
                }
            },
            onFailed = {
                showErrorToast("Couldn't insert text — tap the text field first")
                onComplete(false)
            }
        )
    }

    private fun commitToActiveField(
        text: String,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        commitTranscriptionResult(text) { success -> onComplete?.invoke(success) }
        return false
    }

    private fun tryCommitToField(trimmed: String): Boolean {
        val ic = liveInputConnection() ?: return false
        return try {
            ic.finishComposingText()
            ic.commitText("$trimmed ", 1)
            true
        } catch (e: Exception) {
            android.util.Log.w("GkeysIME", "tryCommitToField failed", e)
            false
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
        btnKeyboard.setImageDrawable(
            if (isNumpad) themeDrawable(R.drawable.ic_keyboard_grid) else createDigitsGridIcon()
        )
        btnKeyboard.contentDescription = if (isNumpad) "Show letters" else "Number pad"
    }

    /** Toolbar icon: 3×3 grid of digits 1–9 (readable at small size). */
    private fun createDigitsGridIcon(): Drawable {
        val size = dp(24)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9CA3AF")
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * 0.27f
        }
        val baselineAdjust = -(paint.descent() + paint.ascent()) / 2f
        val cellW = size / 3f
        val cellH = size / 3f
        for (i in 0 until 9) {
            val x = (i % 3) * cellW + cellW / 2f
            val y = (i / 3) * cellH + cellH / 2f + baselineAdjust
            canvas.drawText((i + 1).toString(), x, y, paint)
        }
        return BitmapDrawable(resources, bitmap).apply {
            setBounds(0, 0, size, size)
        }
    }

    private fun showVoiceOverlay() {
        if (!isInputViewReady()) return
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
        if (!::voiceOverlay.isInitialized) return
        voiceOverlay.visibility = View.GONE
        if (keyboardVisible && ::keyboardKeysHost.isInitialized &&
            clipboardManager?.isPanelOpen() != true && !isGhostwriterOverlay
        ) {
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

    /** Base height from settings × key-size preset → physical key area height. */
    private fun effectiveKeyboardHeightDp(): Int =
        KeyboardLayoutMetrics.effectiveKeyboardHeightDp(universalKeyboardHeightDp, keySizePreset)

    private fun applyKeyboardSizeFromSettings() {
        keyboardHeightPx = 0
        applyUniversalShellHeight()
        if (::keyboardView.isInitialized) {
            buildKeyboard()
            applyOneHandedMode()
        }
    }

    private fun adjustKeyboardHeight(deltaDp: Int) {
        universalKeyboardHeightDp = KeyboardLayoutMetrics.clampKeyboardHeightDp(
            universalKeyboardHeightDp + deltaDp
        )
        scope.launch { GkeysSettings.saveKeyboardHeightDp(this@GkeysIME, universalKeyboardHeightDp) }
        applyKeyboardSizeFromSettings()
    }

    private fun adjustOneHandedWidth(delta: Float) {
        oneHandedWidthFraction = (oneHandedWidthFraction + delta).coerceIn(
            KeyboardLayoutMetrics.MIN_ONE_HANDED_KEY_AREA_FRACTION,
            KeyboardLayoutMetrics.MAX_ONE_HANDED_KEY_AREA_FRACTION
        )
        scope.launch { GkeysSettings.saveOneHandedWidthFraction(this@GkeysIME, oneHandedWidthFraction) }
        applyOneHandedMode()
        refreshTouchTargetsAfterLayout()
    }

    /** Locks toolbar + key area to one fixed height for every mode and overlay. */
    private fun applyUniversalShellHeight(retryAfterLayout: Boolean = true) {
        if (!::keyboardPanel.isInitialized) return
        val keysPx = dp(effectiveKeyboardHeightDp())
        val dividerPx = dp(KeyboardLayoutMetrics.SHELL_DIVIDER_DP)
        val bottomPadPx = dp(KeyboardLayoutMetrics.SHELL_BOTTOM_PADDING_DP)

        val toolbarSlot = keyboardView.findViewById<View>(R.id.toolbar_slot)
        var toolbarPx = toolbarSlot?.let { slot ->
            when {
                slot.height > 0 -> slot.height
                slot.measuredHeight > 0 -> slot.measuredHeight
                else -> 0
            }
        } ?: 0
        if (toolbarPx <= 0) {
            toolbarPx = dp(KeyboardLayoutMetrics.AI_STRIP_HEIGHT_DP)
        }
        val shellPx = toolbarPx + dividerPx + keysPx + bottomPadPx

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

        if (retryAfterLayout && toolbarSlot != null && toolbarSlot.height == 0) {
            toolbarSlot.post { applyUniversalShellHeight(retryAfterLayout = false) }
        }
    }

    private fun cancelAiBarRecording() {
        if (!isRecording || recordingForGhostwriter) return
        hideVoiceOverlay()
        clearDictationStatus()
        cancelRecording()
    }

    private fun updateMicCancelVisibility() {
        if (!::btnMicCancel.isInitialized) return
        val toolbarVisible = ::aiBarShellPrimary.isInitialized &&
            aiBarShellPrimary.visibility == View.VISIBLE
        val show = isRecording && !recordingForGhostwriter && toolbarVisible
        btnMicCancel.visibility = if (show) View.VISIBLE else View.GONE
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
        if (::micAiSparkles.isInitialized) {
            micAiSparkles.visibility = if (recording) View.GONE else View.VISIBLE
            micAiSparkles.setImageResource(R.drawable.mic_sparkle_pair_gold)
            micAiSparkles.rotation = 0f
        }
        updateMicCancelVisibility()
        stopMicIdleSparkleAnimation()
    }

    private fun startMicIdleSparkleAnimation() {
        if (micIsProcessing || isRecording || !::micAiSparkles.isInitialized) return
        micAiSparkles.visibility = View.VISIBLE
        micAiSparkles.setImageResource(R.drawable.mic_sparkle_pair_gold)
        micAiSparkles.alpha = 0.95f
        micAiSparkles.rotation = 0f
    }

    private fun stopMicIdleSparkleAnimation() {
        micIdleSparkleAnimator?.cancel()
        micIdleSparkleAnimator = null
    }

    private fun startMicProcessingAnimation() {
        if (micIsProcessing) return
        micIsProcessing = true
        updateMicCancelVisibility()
        stopMicIdleSparkleAnimation()
        val keyboardVisible = ::keyboardView.isInitialized && keyboardView.visibility == View.VISIBLE
        if (shouldUseBubbleMicVisuals()) {
            voiceBubbleController?.setState(VoiceBubbleState.PROCESSING)
        }
        if (!keyboardVisible) return
        stopMicProcessingAnimatorsOnly()

        micAiGlow.visibility = View.VISIBLE
        micAiShimmer.visibility = View.VISIBLE
        micAiSparkles.visibility = View.GONE
        micAiGlow.alpha = 0.4f
        micAiShimmer.alpha = 0.55f
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
        if (::micAiSparkles.isInitialized && !micIsProcessing) {
            micAiSparkles.setImageResource(R.drawable.mic_sparkle_pair_gold)
            micAiSparkles.visibility = if (isRecording) View.GONE else View.VISIBLE
        }
    }

    private fun stopMicProcessingAnimation() {
        if (!micIsProcessing) {
            if (shouldUseBubbleMicVisuals()) {
                voiceBubbleController?.setState(VoiceBubbleState.IDLE)
            }
            releaseInputSessionAfterBubbleDictation()
            return
        }
        micIsProcessing = false
        stopMicProcessingAnimatorsOnly()
        updateMicCancelVisibility()
        if (shouldUseBubbleMicVisuals()) {
            voiceBubbleController?.setState(VoiceBubbleState.IDLE)
        }
        releaseInputSessionAfterBubbleDictation()
        if (::keyboardView.isInitialized && keyboardView.visibility == View.VISIBLE) {
            micAiGlow.visibility = View.GONE
            micAiShimmer.visibility = View.GONE
            micAiSparkles.rotation = 0f
            micAiShimmer.rotation = 0f
            btnMic.alpha = 1f
            btnMicContainer.scaleX = 1f
            btnMicContainer.scaleY = 1f
            updateMicVisuals(recording = isRecording)
            startMicIdleSparkleAnimation()
        }
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

        when {
            oneHandedMode == GkeysSettings.ONE_HANDED_RIGHT -> {
                rowsParams.width = (screenWidth * oneHandedWidthFraction).toInt()
                rowsParams.gravity = Gravity.END or Gravity.BOTTOM
            }
            oneHandedMode == GkeysSettings.ONE_HANDED_LEFT -> {
                rowsParams.width = (screenWidth * oneHandedWidthFraction).toInt()
                rowsParams.gravity = Gravity.START or Gravity.BOTTOM
            }
            (keyboardRows.tag == "numpad") && isOneHandedActive() -> {
                rowsParams.width = (screenWidth * oneHandedWidthFraction).toInt()
                rowsParams.gravity = when (oneHandedMode) {
                    GkeysSettings.ONE_HANDED_LEFT -> Gravity.START or Gravity.BOTTOM
                    else -> Gravity.END or Gravity.BOTTOM
                }
            }
            else -> {
                rowsParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                rowsParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            }
        }
        rowsParams.height = rowsHeight
        keyboardRows.layoutParams = rowsParams
        keyboardRows.requestLayout()
        updateKeyboardSizeRail()
        refreshTouchTargetsAfterLayout()
    }

    private fun updateKeyboardSizeRail() {
        keyboardSizeRail?.let { keyboardKeysHost.removeView(it) }
        keyboardSizeRail = null
        if (!::keyboardKeysHost.isInitialized || !isOneHandedActive()) return

        val onLeftSide = oneHandedMode == GkeysSettings.ONE_HANDED_RIGHT
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                dp(52),
                FrameLayout.LayoutParams.MATCH_PARENT,
                if (onLeftSide) Gravity.START or Gravity.CENTER_VERTICAL
                else Gravity.END or Gravity.CENTER_VERTICAL
            )
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        rail.addView(createKeyboardResizeButton("＋", "Taller keyboard") { adjustKeyboardHeight(10) })
        rail.addView(createKeyboardResizeButton("－", "Shorter keyboard") { adjustKeyboardHeight(-10) })
        rail.addView(spacerView(dp(8)))
        rail.addView(
            createKeyboardResizeButton(
                if (onLeftSide) "▷" else "◁",
                "Wider one-handed keyboard"
            ) { adjustOneHandedWidth(0.04f) }
        )
        rail.addView(
            createKeyboardResizeButton(
                if (onLeftSide) "◁" else "▷",
                "Narrower one-handed keyboard"
            ) { adjustOneHandedWidth(-0.04f) }
        )

        keyboardKeysHost.addView(rail)
        keyboardSizeRail = rail
    }

    private fun spacerView(heightPx: Int): View =
        Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )
        }

    private fun createKeyboardResizeButton(label: String, contentDescription: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(themeColor(R.color.gkeys_text_secondary))
            setBackgroundResource(R.drawable.btn_circle_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { setMargins(0, dp(3), 0, dp(3)) }
            this.contentDescription = contentDescription
            isClickable = true
            isFocusable = true
            setOnClickListener {
                vibrate()
                onClick()
            }
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
                dp(KeyboardLayoutMetrics.KEY_TILE_MARGIN_DP),
                container.paddingTop,
                dp(KeyboardLayoutMetrics.KEY_TILE_MARGIN_DP),
                container.paddingBottom
            )
            container.translationX = 0f
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
                        oneHandedActive && layoutLeft -> Gravity.START
                        oneHandedActive && layoutRight -> Gravity.END
                        else -> Gravity.CENTER_HORIZONTAL
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
        applyUniversalShellHeight()
        refreshSuggestions()
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
                    KeyboardLayoutMetrics.numpadColumnWeight(colIndex, "", rowIndex, totalRows)
                } else {
                    KeyboardLayoutMetrics.numpadColumnWeight(colIndex, label, rowIndex, totalRows)
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
                            useDirectTouchHandlers = false
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

        val columns = KeyboardLayoutMetrics.EMOJI_COLUMNS
        val recent = EmojiUsageStore.recentlyUsed(this)

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

        grid.addView(buildEmojiCategoryHeader("Recently used"))
        recent.chunked(columns).forEach { rowEmojis ->
            grid.addView(buildEmojiRow(rowEmojis, profile, columns))
        }

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
            setTextColor(themeColor(R.color.gkeys_text_muted))
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
        val margin = dp(KeyboardLayoutMetrics.KEY_TILE_MARGIN_DP)
        val cell = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            tag = emoji
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(margin, margin, margin, margin)
            }
        }
        val tile = TextView(this).apply {
            text = emoji
            textSize = KeyboardLayoutMetrics.EMOJI_TEXT_SP * profile.textScale
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = themeDrawable(R.drawable.key_tile_ripple)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        cell.addView(tile)
        attachKeyTouchHandler(cell, emoji)
        return cell
    }

    private fun applyStableKeyboardHeight(container: View) {
        val target = dp(effectiveKeyboardHeightDp())
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

    private fun addLongPressIndicator(cell: FrameLayout, label: String) {
        val alt = longPressAltFor(label) ?: return
        val indicatorColor = themeColor(R.color.gkeys_text_secondary)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(0, dp(2), dp(4), 0)
        }

        when (alt) {
            KEY_EMOJI_PANEL -> {
                val iconSize = dp(9)
                cell.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_longpress_emoji)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.TOP or Gravity.END).apply {
                        setMargins(0, dp(3), dp(4), 0)
                    }
                })
            }
            else -> {
                cell.addView(TextView(this).apply {
                    text = alt
                    textSize = 8f
                    setTextColor(indicatorColor)
                    includeFontPadding = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = params
                })
            }
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
        useDirectTouchHandlers: Boolean = false
    ): View {
        val isSpace = label == "SPACE"
        val isSpecial = label in listOf("⇧", "⌫", "↵", "?123", "ABC", "🌐", "NUMPAD_BACK")
        val displayLabel = displayLabelFor(label)
        val margin = dp(KeyboardLayoutMetrics.KEY_TILE_MARGIN_DP)
        val weight = when {
            isNumpadMode || KeyboardLayoutMetrics.isBottomRowSpecialRow(rowIndex, totalRows) ->
                KeyboardLayoutMetrics.rowKeyWeight(label, rowIndex, totalRows, profile.rightHanded, isNumpadMode)
            label == "⌫" || label == "⇧" ->
                KeyboardLayoutMetrics.standardRowKeyWeight(label)
            else -> columnWeight
        }
        val textScale = profile.textScale

        val cell = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            tag = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(margin, margin, margin, margin)
            }
        }

        val bgDrawable = if (isSpecial || (isNumpadMode && label in listOf("ABC", "⌫", "↵", "NUMPAD_BACK"))) {
            themeDrawable(R.drawable.key_tile_special_ripple)
        } else {
            themeDrawable(R.drawable.key_tile_ripple)
        }

        if (label == "⌫") {
            val iconPad = dp(6)
            val backIcon = ImageView(this).apply {
                setImageDrawable(themeDrawable(R.drawable.ic_back_arrow))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = bgDrawable
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
                setTextColor(themeColor(R.color.gkeys_text_primary))
                background = bgDrawable
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            cell.addView(keyTile)
        }

        addLongPressIndicator(cell, label)

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
        when {
            label == "⌫" -> attachBackspaceRepeatHandler(cell)
            useDirectHandlers && label == "," -> attachKeyTouchHandler(cell, label) {
                toggleEmojiPanel()
                hapticKeyTap()
            }
            useDirectHandlers && label == "?" -> attachKeyTouchHandler(cell, label) {
                handleKey("!")
                hapticKeyTap()
            }
            else -> attachKeyTouchHandler(cell, label)
        }
    }

    /** Fires character commit on ACTION_DOWN for instant response; long-press keys wait for hold or UP. */
    private fun attachKeyTouchHandler(cell: View, label: String, onLongPress: (() -> Unit)? = null) {
        var longPressFired = false
        var firedOnDown = false
        val longPressRunnable = Runnable {
            longPressFired = true
            onLongPress?.invoke()
        }
        cell.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressFired = false
                    firedOnDown = false
                    cell.removeCallbacks(longPressRunnable)
                    if (onLongPress != null) {
                        cell.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    } else {
                        handleKey(label)
                        hapticKeyTap()
                        firedOnDown = true
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cell.removeCallbacks(longPressRunnable)
                    if (!longPressFired && onLongPress != null) {
                        handleKey(label)
                        hapticKeyTap()
                    } else if (!longPressFired && !firedOnDown) {
                        handleKey(label)
                        hapticKeyTap()
                    }
                    longPressFired = false
                    firedOnDown = false
                    true
                }
                else -> true
            }
        }
    }

    /** Hold-to-repeat delete for layouts without central touch handling (Hebrew, symbols, numpad). */
    private fun attachBackspaceRepeatHandler(cell: View) {
        var deleteRepeatStarted = false
        val startRepeatRunnable = Runnable {
            deleteRepeatStarted = true
            startDeleteRepeat(skipInitial = true)
        }
        cell.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    deleteRepeatStarted = false
                    cell.removeCallbacks(startRepeatRunnable)
                    handleKey("⌫")
                    hapticKeyTap()
                    cell.postDelayed(startRepeatRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cell.removeCallbacks(startRepeatRunnable)
                    if (deleteRepeatStarted) {
                        stopDeleteRepeat()
                    }
                    deleteRepeatStarted = false
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

    private fun suggestionsSupported(): Boolean =
        !isSymbols && !isNumpad && !emojiPanelVisible &&
            ::keyboardView.isInitialized && keyboardView.visibility == View.VISIBLE

    private fun activeSuggestionLanguage(): DictionaryManager.Language =
        DictionaryManager.languageForKeyboard(isHebrew)

    private fun isWordCharacter(c: Char): Boolean = c.isLetter() || c == '\''

    private fun normalizeWordChar(fragment: String): String =
        if (isHebrew) fragment else fragment.lowercase()

    private fun normalizeCompletedWord(word: String): String =
        if (isHebrew) word else word.lowercase()

    private fun userWordsForSuggestions(): Map<String, Int> =
        if (::userWordsRepository.isInitialized) {
            userWordsRepository.words(activeSuggestionLanguage())
        } else {
            emptyMap()
        }

    /** Read the partial word at the cursor — avoids stale prefix from earlier keystrokes. */
    private fun syncWordPrefixFromField(ic: InputConnection? = currentInputConnection) {
        val conn = ic ?: return
        currentWordPrefix = normalizeWordChar(InputTextHelper.wordBeforeCursor(conn, isHebrew))
        if (::adaptiveTouch.isInitialized) {
            adaptiveTouch.setWordPrefix(currentWordPrefix)
        }
    }

    private fun syncWordPrefixAndRefreshSuggestions(ic: InputConnection? = currentInputConnection) {
        syncWordPrefixFromField(ic)
        if (currentWordPrefix.isEmpty()) {
            if (postAutocorrectUndo == null) hideSuggestionBar()
        } else if (suggestionsSupported()) {
            onSuggestionTypingKey()
        }
        refreshSuggestions()
    }

    private fun learnCompletedWord(word: String) {
        val w = word.trim()
        if (w.length < 2 || !::userWordsRepository.isInitialized) return
        scope.launch {
            userWordsRepository.recordWord(activeSuggestionLanguage(), w)
        }
    }

    private fun refreshSuggestions() {
        val controller = suggestionStripController ?: return
        val undo = postAutocorrectUndo
        val showTyping = suggestionsSupported() && suggestionBarVisible && currentWordPrefix.isNotEmpty()
        val showUndo = undo != null && suggestionBarVisible
        if (!showTyping && !showUndo) {
            controller.setActive(false)
            controller.clear()
            restoreAiBarPage()
            return
        }
        hideAiBarsForOverlay()
        val model = if (showUndo && undo != null) {
            SuggestionEngine.buildPostAutocorrectUndo(undo.first, undo.second)
        } else {
            SuggestionEngine.build(
                this,
                activeSuggestionLanguage(),
                currentWordPrefix,
                userWordsForSuggestions(),
            )
        }
        controller.setActive(true)
        controller.render(
            model,
            primaryColor = themeColor(R.color.gkeys_text_primary),
            secondaryColor = themeColor(R.color.gkeys_text_secondary),
        )
    }

    private fun onSuggestionTypingKey() {
        if (!suggestionsSupported()) return
        postAutocorrectUndo = null
        suggestionVisibilityController?.onTypingKey()
    }

    private fun hideSuggestionBar() {
        postAutocorrectUndo = null
        suggestionVisibilityController?.hideImmediately()
    }

    private fun dismissSuggestionBar() {
        hapticKeyTap()
        hideSuggestionBar()
    }

    private fun showPostAutocorrectUndo(original: String, corrected: String) {
        postAutocorrectUndo = original to corrected
        suggestionVisibilityController?.extendVisible()
    }

    private fun revertAutocorrect() {
        val undo = postAutocorrectUndo ?: return
        val ic = currentInputConnection ?: return
        val (original, corrected) = undo
        hapticKeyTap()
        ic.deleteSurroundingText(corrected.length + 1, 0)
        ic.commitText("$original ", 1)
        fieldUndo.recordInsert("$original ")
        lastCompletedWord = normalizeCompletedWord(original)
        postAutocorrectUndo = null
        hideSuggestionBar()
        updateUndoButtonState()
    }

    private fun preloadSuggestionLanguage() {
        scope.launch(Dispatchers.IO) {
            val lang = activeSuggestionLanguage()
            DictionaryManager.ensureLoaded(applicationContext, lang)
            userWordsRepository.ensureCache(lang)
        }
    }

    private fun applySuggestion(word: String) {
        val pick = word.trim()
        if (pick.isEmpty()) return

        val undo = postAutocorrectUndo
        if (undo != null) {
            if (pick == undo.first) {
                revertAutocorrect()
            } else {
                postAutocorrectUndo = null
                dismissSuggestionBar()
            }
            return
        }

        val ic = currentInputConnection ?: return
        hapticKeyTap()
        val replaceLen = currentWordPrefix.length
        if (replaceLen > 0) {
            ic.deleteSurroundingText(replaceLen, 0)
        }
        ic.commitText("$pick ", 1)
        fieldUndo.recordInsert("$pick ")
        lastCompletedWord = normalizeCompletedWord(pick)
        currentWordPrefix = ""
        learnCompletedWord(pick)
        if (::adaptiveTouch.isInitialized) {
            adaptiveTouch.setWordPrefix("")
            if (pick.length >= 2) {
                adaptiveTouch.recordWordCompleted(normalizeCompletedWord(pick))
            }
        }
        updateTouchContext(" ")
        updateUndoButtonState()
        hideSuggestionBar()
        refreshSuggestions()
    }

    private fun handleSpaceKey(ic: InputConnection) {
        syncWordPrefixFromField(ic)
        val prefix = currentWordPrefix
        val lang = activeSuggestionLanguage()
        val corrected = if (suggestionsSupported() && prefix.length >= 2) {
            SuggestionEngine.autocorrectOnSpace(this, lang, prefix, userWordsForSuggestions())
        } else {
            null
        }

        if (corrected != null && corrected != prefix) {
            if (prefix.isNotEmpty()) {
                ic.deleteSurroundingText(prefix.length, 0)
            }
            ic.commitText("$corrected ", 1)
            fieldUndo.recordInsert("$corrected ")
            lastCompletedWord = normalizeCompletedWord(corrected)
            learnCompletedWord(corrected)
            updateTouchContext(" ")
            completeCurrentWord()
            updateUndoButtonState()
            showPostAutocorrectUndo(prefix, corrected)
            refreshSuggestions()
            return
        }

        ic.commitText(" ", 1)
        fieldUndo.recordInsert(" ")
        if (prefix.isNotEmpty()) {
            lastCompletedWord = normalizeCompletedWord(prefix)
            learnCompletedWord(prefix)
            if (::adaptiveTouch.isInitialized && prefix.length >= 2) {
                adaptiveTouch.recordWordCompleted(normalizeCompletedWord(prefix))
            }
        }
        updateTouchContext(" ")
        completeCurrentWord()
        updateUndoButtonState()
        hideSuggestionBar()
        refreshSuggestions()
    }

    private fun handleTextPromptKey(key: String) {
        val mgr = clipboardManager ?: return
        when (key) {
            "⌫" -> mgr.deleteTextPromptChar()
            "SPACE" -> mgr.insertTextPromptText(" ")
            "↵" -> { /* single-line prompt — ignore enter */ }
            "⇧" -> {
                if (isShifted && !capsLock) capsLock = true
                else if (capsLock) { capsLock = false; isShifted = false }
                else isShifted = true
                handler.post { refreshLetterCaseOnKeys() }
            }
            "?123" -> { hideSuggestionBar(); isSymbols = true; isNumpad = false; emojiPanelVisible = false; buildKeyboard() }
            "ABC" -> { hideSuggestionBar(); isSymbols = false; isNumpad = false; emojiPanelVisible = false; buildKeyboard() }
            "NUMPAD_BACK" -> { closeEmojiPanel() }
            "🌐" -> {
                isHebrew = !isHebrew
                isSymbols = false
                isNumpad = false
                emojiPanelVisible = false
                hideSuggestionBar()
                buildKeyboard()
            }
            else -> {
                val toInsert = if (isShifted && !isHebrew && key.length == 1 && key[0].isLetter()) {
                    key.uppercase()
                } else {
                    key
                }
                mgr.insertTextPromptText(toInsert)
                if (isShifted && !capsLock && toInsert.length == 1 && toInsert[0].isLetter()) {
                    isShifted = false
                    handler.post { refreshLetterCaseOnKeys() }
                }
            }
        }
    }

    private fun handleKeyInternal(key: String) {
        if (clipboardManager?.isTextPromptActive() == true) {
            handleTextPromptKey(key)
            return
        }
        val ic = currentInputConnection ?: return
        when (key) {
            "⌫" -> {
                deleteOneCharWithUndo(ic)
                if (::touchResolver.isInitialized && touchResolver.enabled) {
                    touchResolver.recordBackspaceOnRecentTap()
                }
                awaitingTouchCorrection = true
                syncWordPrefixAndRefreshSuggestions(ic)
                updateUndoButtonState()
            }
            "↵" -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                completeCurrentWord()
                awaitingTouchCorrection = false
                syncWordPrefixFromField(ic)
                hideSuggestionBar()
                refreshSuggestions()
            }
            "⇧" -> {
                if (isShifted && !capsLock) capsLock = true
                else if (capsLock) { capsLock = false; isShifted = false }
                else isShifted = true
                handler.post { refreshLetterCaseOnKeys() }
            }
            "?123" -> { hideSuggestionBar(); isSymbols = true; isNumpad = false; emojiPanelVisible = false; buildKeyboard(); refreshSuggestions() }
            "ABC" -> { hideSuggestionBar(); isSymbols = false; isNumpad = false; emojiPanelVisible = false; buildKeyboard(); refreshSuggestions() }
            "NUMPAD_BACK" -> { closeEmojiPanel() }
            "🌐" -> {
                isHebrew = !isHebrew
                isSymbols = false
                isNumpad = false
                emojiPanelVisible = false
                hideSuggestionBar()
                buildKeyboard()
                preloadSuggestionLanguage()
                refreshSuggestions()
            }
            else -> {
                if (key == "SPACE") {
                    handleSpaceKey(ic)
                    return
                }
                val toInsert = if (isShifted && !isHebrew && key.length == 1 && key[0].isLetter()) key.uppercase()
                else key

                ic.commitText(toInsert, 1)
                fieldUndo.recordInsert(toInsert)
                updateTouchContext(toInsert)
                updateUndoButtonState()

                val needsShiftRefresh = isShifted && !capsLock &&
                    toInsert.length == 1 && toInsert[0].isLetter()
                if (needsShiftRefresh) {
                    isShifted = false
                    handler.post { refreshLetterCaseOnKeys() }
                }

                val correctionPending = awaitingTouchCorrection &&
                    key.length == 1 && key[0].isLetter()
                awaitingTouchCorrection = false
                if (correctionPending && ::touchResolver.isInitialized) {
                    touchResolver.recordCorrection(key)
                }
                if (EmojiCatalog.isEmoji(toInsert)) {
                    scope.launch { EmojiUsageStore.record(this@GkeysIME, toInsert) }
                }
                syncWordPrefixAndRefreshSuggestions(ic)
            }
        }
    }

    private fun completeCurrentWord() {
        val word = currentWordPrefix
        if (word.isNotEmpty()) {
            lastCompletedWord = normalizeCompletedWord(word)
        }
        currentWordPrefix = ""
        if (::adaptiveTouch.isInitialized) {
            adaptiveTouch.setWordPrefix("")
            if (word.length >= 2) {
                handler.post { adaptiveTouch.recordWordCompleted(normalizeCompletedWord(word)) }
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

    private fun startDeleteRepeat(skipInitial: Boolean = false) {
        if (isDeleteRepeating) return
        deleteRunnable?.let { handler.removeCallbacks(it) }
        isDeleteRepeating = true
        if (!skipInitial) {
            if (clipboardManager?.isTextPromptActive() == true) {
                clipboardManager?.deleteTextPromptChar()
            } else {
                val ic = currentInputConnection
                if (ic != null) {
                    deleteOneCharWithUndo(ic)
                    updateUndoButtonState()
                }
            }
        }
        deleteRunnable = object : Runnable {
            override fun run() {
                if (!isDeleteRepeating) return
                if (clipboardManager?.isTextPromptActive() == true) {
                    clipboardManager?.deleteTextPromptChar()
                } else {
                    val ic = currentInputConnection ?: return
                    deleteOneCharWithUndo(ic)
                    syncWordPrefixFromField(ic)
                    updateUndoButtonState()
                }
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
            beginMicCapture()
        } catch (_: Exception) {
            showErrorToast("Microphone error — check permission in Gkeys app")
        }
    }

    private fun cancelRecording() {
        handler.removeCallbacks(longPressRunnable)
        if (isRecording && !recordingForGhostwriter) {
            audioRecorder.cancelRecording()
            isRecording = false
            endMicCapture()
        }
        updateMicVisuals(recording = false)
        updateMicCancelVisibility()
        if (voiceBubbleModeActive) {
            voiceBubbleController?.setState(VoiceBubbleState.IDLE)
        }
        bubbleTranslateHoldActive = false
        toastStatus("")
        releaseInputSessionAfterBubbleDictation()
    }

    private fun stopRecordingAndProcess(action: VoiceAction) {
        if (!isRecording || recordingForGhostwriter) return
        val durationMs = audioRecorder.lastRecordingDurationMs()
        val file = audioRecorder.stopRecording()
        isRecording = false
        endMicCapture()
        updateMicCancelVisibility()
        if (file == null) {
            updateMicVisuals(recording = false)
            voiceBubbleController?.setState(VoiceBubbleState.IDLE)
            toastStatus("⚠ Recording failed")
            releaseInputSessionAfterBubbleDictation()
            return
        }

        val effectiveAction = action
        refreshApiKeys()
        if (openAiKey.isBlank()) {
            updateMicVisuals(recording = false)
            val message = "Add OpenAI API key in Gkeys settings"
            showDictationStatus(message, autoClearMs = 6000L)
            showErrorToast(message)
            return
        }
        startMicProcessingAnimation()
        showDictationStatus("Transcribing…")

        handler.post {
            scope.launch {
            if (effectiveAction == VoiceAction.TRANSLATE) {
                voiceTranslateFrom = GkeysSettings.voiceTranslateFrom(this@GkeysIME).first()
                voiceTranslateTo = GkeysSettings.voiceTranslateTo(this@GkeysIME).first()
            }
            val transcribeLang = if (effectiveAction == VoiceAction.TRANSLATE) voiceTranslateFrom else null
            val transcriptResult = aiManager.transcribe(file, openAiKey, durationMs, transcribeLang)
            file.delete()
            transcriptResult.onFailure { error ->
                val message = userFriendlyDictationError(error)
                showDictationStatus(message, autoClearMs = 6000L)
                stopMicProcessingAnimation()
                return@launch
            }
            val transcript = transcriptResult.getOrNull().orEmpty()
            if (transcript.isBlank()) {
                showDictationStatus("Nothing heard — try again", autoClearMs = 6000L)
                stopMicProcessingAnimation()
                return@launch
            }

            val activePolishLevel = GkeysSettings.polishLevel(this@GkeysIME).first()
            polishLevel = activePolishLevel
            if (::btnPolishLevel.isInitialized) updatePolishLevelButton()

            if (effectiveAction == VoiceAction.DEFAULT &&
                activePolishLevel == GkeysSettings.POLISH_RAW
            ) {
                showDictationStatus("Inserting…")
                val rawText = transcript.trim()
                commitTranscriptionResult(rawText) { success ->
                    stopMicProcessingAnimation()
                    if (success) {
                        vibrate()
                        clearDictationStatus()
                    } else {
                        showDictationStatus("Couldn't insert text — tap the field first", autoClearMs = 5000L)
                    }
                }
                return@launch
            }

            showDictationStatus("Polishing…")

            val finalText = when (effectiveAction) {
                VoiceAction.TRANSLATE ->
                    aiManager.translateText(transcript, voiceTranslateFrom, voiceTranslateTo, openAiKey)
                else ->
                    aiManager.polishText(transcript, openAiKey, activePolishLevel)
            }

            if (finalText.isSuccess) {
                val polished = finalText.getOrThrow().trim().ifEmpty { transcript.trim() }
                val textToCommit = aiManager.finalizeWithUserInstructions(polished, openAiKey)
                    .getOrElse { polished }
                commitTranscriptionResult(textToCommit) { success ->
                    stopMicProcessingAnimation()
                    if (success) {
                        vibrate()
                        clearDictationStatus()
                    } else {
                        showDictationStatus("Couldn't insert text — tap the field first", autoClearMs = 5000L)
                    }
                }
            } else {
                val fallback = aiManager.finalizeWithUserInstructions(transcript.trim(), openAiKey)
                    .getOrElse { transcript.trim() }
                commitTranscriptionResult(fallback) { success ->
                    stopMicProcessingAnimation()
                    if (success) {
                        vibrate()
                        showDictationStatus("Polish failed — inserted raw text", autoClearMs = 3000L)
                    } else {
                        showDictationStatus("Polish failed — couldn't insert text", autoClearMs = 5000L)
                        showErrorToast("Polish failed — text unchanged")
                    }
                }
            }
            }
        }
    }

    private fun polishFieldText() = polishFieldTextManual()

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
            fieldUndo.recordInsert(item.text)
            syncWordPrefixAndRefreshSuggestions(ic)
            updateUndoButtonState()
        }
    }

    private fun deleteOneCharWithUndo(ic: InputConnection) {
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            fieldUndo.recordDelete(selected.toString())
            ic.commitText("", 1)
        } else {
            val deleted = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            if (deleted.isNotEmpty()) {
                fieldUndo.recordDelete(deleted)
            }
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun deleteForward() {
        val ic = currentInputConnection ?: return
        hapticKeyTap()
        ic.deleteSurroundingText(0, 1)
    }

    private fun undoFieldEdit() {
        val ic = currentInputConnection ?: return
        if (!fieldUndo.undo(ic)) return
        awaitingTouchCorrection = false
        hapticKeyTap()
        syncWordPrefixAndRefreshSuggestions(ic)
        updateUndoButtonState()
    }

    private fun selectAllFieldText() {
        val ic = currentInputConnection ?: return
        hapticKeyTap()
        if (ic.performContextMenuAction(android.R.id.selectAll)) return
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, meta, 0, 0)
        )
        ic.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, meta, 0, 0)
        )
    }

    private fun clearAllFieldText() {
        val ic = currentInputConnection ?: return
        val beforeLen = ic.getTextBeforeCursor(FIELD_TEXT_SCAN_LIMIT, 0)?.length ?: 0
        val afterLen = ic.getTextAfterCursor(FIELD_TEXT_SCAN_LIMIT, 0)?.length ?: 0
        if (beforeLen == 0 && afterLen == 0) return
        val before = ic.getTextBeforeCursor(beforeLen, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(afterLen, 0)?.toString().orEmpty()
        val full = before + after
        fieldUndo.recordClear(full)
        ic.deleteSurroundingText(beforeLen, afterLen)
        currentWordPrefix = ""
        hideSuggestionBar()
        if (::adaptiveTouch.isInitialized) {
            adaptiveTouch.setWordPrefix("")
        }
        awaitingTouchCorrection = false
        hapticKeyTap()
        updateUndoButtonState()
    }

    private fun updateUndoButtonState() {
        if (!::btnClipboardUndo.isInitialized) return
        val enabled = fieldUndo.canUndo()
        btnClipboardUndo.isEnabled = enabled
        btnClipboardUndo.alpha = if (enabled) 1f else 0.35f
    }

    private fun showErrorToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun userFriendlyDictationError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw == "Recording too short" ->
                "Recording too short — speak longer, then tap mic again"
            raw == "Nothing heard" -> "Nothing heard — try again"
            raw.contains("permission", ignoreCase = true) ->
                "Network blocked — update Gkeys (needs internet permission)"
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("Failed to connect", ignoreCase = true) ||
                raw.contains("Network is unreachable", ignoreCase = true) ->
                "No internet — check your connection"
            raw.startsWith("Transcription failed (401") ->
                "Invalid OpenAI API key — check Gkeys settings"
            raw.startsWith("Transcription failed (403") ->
                "OpenAI access denied — check your API key billing"
            raw.startsWith("Transcription failed") -> raw
            raw.isNotBlank() -> raw
            else -> "Transcription failed"
        }
    }

    private fun showDictationStatus(message: String, autoClearMs: Long = 0L) {
        dictationStatusClearRunnable?.let { handler.removeCallbacks(it) }
        dictationStatusClearRunnable = null
        if (isVoiceOverlay && ::voiceStatus.isInitialized) {
            voiceStatus.text = message
            return
        }
        if (!::tvClipboardHint.isInitialized) return
        tvClipboard.visibility = View.GONE
        ivClipboardPreview.visibility = View.GONE
        tvClipboardHint.visibility = View.VISIBLE
        tvClipboardHint.setTextColor(themeColor(R.color.gkeys_accent))
        tvClipboardHint.text = message
        if (autoClearMs > 0L) {
            showErrorToast(message)
            val clear = Runnable { clearDictationStatus() }
            dictationStatusClearRunnable = clear
            handler.postDelayed(clear, autoClearMs)
        }
    }

    private fun clearDictationStatus() {
        dictationStatusClearRunnable?.let { handler.removeCallbacks(it) }
        dictationStatusClearRunnable = null
        if (isRecording || micIsProcessing || liveSttActive || liveSttConnecting) return
        if (!::tvClipboardHint.isInitialized) return
        tvClipboardHint.setTextColor(themeColor(R.color.gkeys_text_secondary))
        toastStatus("")
    }

    private fun toastStatus(msg: String) {
        if (isVoiceOverlay && ::voiceStatus.isInitialized) {
            voiceStatus.text = if (msg.isBlank()) "Listening…" else msg
            return
        }
        if (!::tvClipboardHint.isInitialized) return
        if (msg.isBlank()) {
            if (!isRecording && !micIsProcessing && !liveSttActive && !liveSttConnecting) {
                tvClipboardHint.setTextColor(themeColor(R.color.gkeys_text_secondary))
                clipboardManager?.refreshPreview()
            }
        } else {
            tvClipboard.visibility = View.GONE
            ivClipboardPreview.visibility = View.GONE
            tvClipboardHint.visibility = View.VISIBLE
            tvClipboardHint.setTextColor(themeColor(R.color.gkeys_text_secondary))
            tvClipboardHint.text = msg
        }
    }

    private fun hapticKeyTap() {
        if (!vibrationEnabled || vibrationStrength <= 0) return
        emitKeyTapHaptic()
    }

    private fun emitKeyTapHaptic() {
        if (!::vibrator.isInitialized) return
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    val amplitude = (vibrationStrength * 2.55f).toInt().coerceIn(1, 255)
                    vibrator.vibrate(VibrationEffect.createOneShot(1, amplitude))
                }
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(1)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("GkeysIME", "hapticKeyTap failed", e)
        }
    }

    private fun vibrate(ms: Long = 8) {
        if (!vibrationEnabled || vibrationStrength <= 0) return
        handler.post { emitVibrate(ms) }
    }

    private fun emitVibrate(ms: Long) {
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
        cancelBubbleFieldHide()
        voiceBubbleController?.destroy()
        if (::micSessionGuard.isInitialized) {
            micSessionGuard.forceRelease()
        }
        super.onDestroy()
        clipboardManager?.destroy()
        scope.cancel()
        stopDeleteRepeat()
        handler.removeCallbacks(longPressRunnable)
        stopLiveStt()
        audioRecorder.cancelRecording()
    }
}
