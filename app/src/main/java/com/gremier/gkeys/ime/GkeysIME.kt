package com.gremier.gkeys.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import android.widget.Toast
import com.gremier.gkeys.R
import com.gremier.gkeys.ai.AiManager
import com.gremier.gkeys.ai.AudioRecorder
import com.gremier.gkeys.clipboard.GkeysClipboardManager
import com.gremier.gkeys.ime.layout.KeyboardLayoutMetrics
import com.gremier.gkeys.ime.layout.KeyboardLayoutMetrics.Profile
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

    private enum class VoiceAction { DEFAULT, TRANSLATE, POLISH, DEEP_POLISH, RAW }

    private var isHebrew = false
    private var isSymbols = false
    private var isNumpad = false
    private var isShifted = false
    private var capsLock = false
    private var oneHandedMode = GkeysSettings.ONE_HANDED_OFF
    private var rightHandedMode = false
    private var keySizePreset = GkeysSettings.KEY_SIZE_DEFAULT
    private var layoutProfile: Profile = KeyboardLayoutMetrics.profile(
        GkeysSettings.KEY_SIZE_DEFAULT, false
    )
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
    private var micGestureTracking = false

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
    private var isPolishing = false
    private var clipboardManager: GkeysClipboardManager? = null
    private var isDeleteRepeating = false
    private var keyboardHeightPx = 0
    private lateinit var touchPersonalization: TouchPersonalization
    private lateinit var touchResolver: TouchInputResolver
    private val touchKeyViews = mutableListOf<Triple<View, String, Int>>()
    private var lastTypedChar: Char? = null

    private val voiceActionViews = mutableMapOf<VoiceAction, TextView>()

    companion object {
        private const val LONG_PRESS_MS = 380L

        private val letterLongPressAlts = mapOf(
            "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
            "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0"
        )
    }

    private val enRows = listOf(
        listOf("q","w","e","r","t","y","u","i","o","p"),
        listOf("a","s","d","f","g","h","j","k","l"),
        listOf("⇧","z","x","c","v","b","n","m","⌫"),
        listOf("?123","🌐",",","SPACE",".","↵")
    )

    private val numpadRows = listOf(
        listOf("1","2","3"),
        listOf("4","5","6"),
        listOf("7","8","9"),
        listOf("ABC","0","⌫")
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
        listOf("?123","🌐",",","SPACE",".","↵")
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

        val keyboardRows = keyboardView.findViewById<KeyboardTouchLayout>(R.id.keyboard_rows)
            ?: throw IllegalStateException("keyboard_rows missing from keyboard_view")

        touchPersonalization = TouchPersonalization(this, scope)
        touchPersonalization.load()
        touchResolver = TouchInputResolver(touchPersonalization)
        keyboardRows.touchResolver = touchResolver
        keyboardRows.onKeyTap = { key ->
            vibrate()
            handleKey(key)
        }
        keyboardRows.onBackspaceDown = { startDeleteRepeat() }
        keyboardRows.onBackspaceUp = { stopDeleteRepeat() }
        keyboardRows.keyLongPressAlts = letterLongPressAlts
        keyboardRows.onKeyLongPress = { alt ->
            vibrate()
            handleKey(alt)
        }

        val overlayContainer = keyboardView.findViewById<FrameLayout>(R.id.clipboard_overlay_container)
        clipboardManager = GkeysClipboardManager(
            context = this,
            overlayContainer = overlayContainer,
            previewView = tvClipboard,
            onPaste = { text -> currentInputConnection?.commitText(text, 1) },
            onVibrate = { vibrate() },
            onPanelOpen = { keyboardContent.visibility = View.GONE },
            onPanelClose = { keyboardContent.visibility = View.VISIBLE }
        )

        forceLayoutLtr(keyboardView)

        setupAiStrip()
        buildKeyboard()
        keyboardView.findViewById<KeyboardTouchLayout>(R.id.keyboard_rows)?.let {
            attachTouchTargetLayoutWatcher(it)
        }
        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        try {
            if (AppVersionTracker.noteCurrentVersion(this)) {
                showErrorToast("Gkeys updated — pick Gkeys again in your keyboard switcher")
            }
            refreshApiKeys()
            loadSettings()
            clipboardManager?.startListening()
        } catch (e: Exception) {
            android.util.Log.e("GkeysIME", "onStartInputView failed", e)
        }
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
            try {
                openAiKey = GkeysSettings.openAiKey(this@GkeysIME).first()
                anthropicKey = GkeysSettings.anthropicKey(this@GkeysIME).first()
                keyRepeatMs = GkeysSettings.keyRepeatSpeed(this@GkeysIME).first()
                deleteSpeedMs = GkeysSettings.deleteSpeed(this@GkeysIME).first()
                vibrationEnabled = GkeysSettings.vibrationEnabled(this@GkeysIME).first()
                vibrationStrength = GkeysSettings.vibrationStrength(this@GkeysIME).first()
                autoPolishEnabled = GkeysSettings.autoPolishEnabled(this@GkeysIME).first()
                isHebrew = GkeysSettings.defaultLanguage(this@GkeysIME).first() == "he"
                oneHandedMode = GkeysSettings.oneHandedMode(this@GkeysIME).first()
                rightHandedMode = GkeysSettings.rightHandedMode(this@GkeysIME).first()
                keySizePreset = GkeysSettings.keySizePreset(this@GkeysIME).first()
                layoutProfile = KeyboardLayoutMetrics.profile(keySizePreset, rightHandedMode)
                keyboardHeightPx = 0
                if (::touchResolver.isInitialized) {
                    touchResolver.rightHandedMode = isLayoutRightBiased()
                }
                if (::keyboardView.isInitialized) {
                    applyOneHandedMode()
                    updateOneHandButton()
                    buildKeyboard()
                }
            } catch (e: Exception) {
                android.util.Log.e("GkeysIME", "loadSettings failed", e)
            }
        }
    }

    private fun setupAiStrip() {
        btnMic.isClickable = false
        btnMic.isFocusable = false
        btnMicContainer.isClickable = true
        btnMicContainer.isFocusable = true

        btnMicContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    micGestureTracking = true
                    longPressTriggered = false
                    pendingVoiceAction = VoiceAction.DEFAULT
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longPressTriggered || isVoiceOverlay) {
                        highlightVoiceAction(event.rawX, event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    finishMicGesture()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (longPressTriggered && isVoiceOverlay) {
                        keyboardView.setOnTouchListener(rootMicTouchListener)
                    } else {
                        finishMicGesture(cancelled = true)
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
            isNumpad = !isNumpad
            if (isNumpad) {
                isSymbols = false
            }
            buildKeyboard()
            updateNumpadButton()
        }

        val openClipboard = {
            clipboardManager?.showPanel()
            vibrate()
        }
        tvClipboard.setOnClickListener { openClipboard() }
        clipboardArea.setOnClickListener { openClipboard() }
        btnOneHand.setOnClickListener { cycleOneHandedMode() }
        updateNumpadButton()
    }

    private val rootMicTouchListener = View.OnTouchListener { _, event ->
        if (!micGestureTracking) return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                highlightVoiceAction(event.rawX, event.rawY)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishMicGesture()
                true
            }
            else -> true
        }
    }

    private fun finishMicGesture(cancelled: Boolean = false) {
        handler.removeCallbacks(longPressRunnable)
        keyboardView.setOnTouchListener(null)
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
        } catch (e: Exception) {
            android.util.Log.e("GkeysIME", "refreshApiKeys failed", e)
            openAiKey = ""
            anthropicKey = ""
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

    private fun openAppForMicPermission() {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SettingsActivity.EXTRA_REQUEST_MIC_PERMISSION, true)
        })
    }

    private fun onMicLongPress() {
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
        pendingVoiceAction = VoiceAction.DEFAULT
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
        btnMic.setImageResource(R.drawable.ic_mic_white)
        btnMicContainer.setBackgroundResource(
            if (recording) R.drawable.ai_mic_bg_active else R.drawable.ai_mic_bg
        )
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
        buildKeyboard()
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
        params.width = FrameLayout.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.CENTER_HORIZONTAL
        keyboardContent.layoutParams = params
        keyboardContent.requestLayout()
        refreshTouchTargetsAfterLayout()
    }

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
        val container = keyboardView.findViewById<KeyboardTouchLayout>(R.id.keyboard_rows) ?: return
        container.removeAllViews()
        container.layoutDirection = View.LAYOUT_DIRECTION_LTR
        touchKeyViews.clear()
        touchResolver.clearTargets()

        val profile = layoutProfile.copy(rightHanded = isLayoutRightBiased())
        val layoutRight = isLayoutRightBiased()
        val layoutLeft = isLayoutLeftBiased()
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

        val rows = when {
            isNumpad && !isHebrew -> numpadRows
            isSymbols -> symRows
            isHebrew -> heRowsGboard
            else -> enRows
        }
        applyStableKeyboardHeight(container, rows.size)
        val touchCorrectionEnabled = !isHebrew && !isSymbols && !isNumpad
        touchResolver.enabled = touchCorrectionEnabled
        touchResolver.rightHandedMode = layoutRight
        touchResolver.setPreviousChar(lastTypedChar)

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
                        profile = profile
                    )
                )
            }
            container.addView(row)
        }
        forceLayoutLtr(container)
        refreshTouchTargetsAfterLayout(container)
        updateNumpadButton()
    }

    private fun applyStableKeyboardHeight(container: View, rowCount: Int) {
        val target = dp(KeyboardLayoutMetrics.heightForRowCount(layoutProfile, rowCount))
        if (keyboardHeightPx == target && container.layoutParams.height == target) return
        keyboardHeightPx = target
        container.layoutParams = container.layoutParams.apply { height = target }
    }

    private fun displayLabelFor(label: String): String {
        val isSpace = label == "SPACE"
        val isSpecial = label in listOf("⇧", "⌫", "↵", "?123", "ABC", "🌐")
        return when {
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
                val pebble = (cell as? ViewGroup)?.getChildAt(0) as? TextView ?: continue
                pebble.text = displayLabelFor(label)
            }
        }
    }

    private fun buildKey(
        label: String,
        touchCorrectionEnabled: Boolean,
        rowIndex: Int,
        totalRows: Int,
        profile: Profile
    ): View {
        val isSpace = label == "SPACE"
        val isSpecial = label in listOf("⇧", "⌫", "↵", "?123", "ABC", "🌐")
        val displayLabel = displayLabelFor(label)

        val baseWeight = if (KeyboardLayoutMetrics.isBottomRowSpecialRow(rowIndex, totalRows)) {
            KeyboardLayoutMetrics.bottomRowWeight(label, profile.rightHanded)
        } else {
            when {
                isSpace -> 4.9f
                label == "⌫" -> 1.85f
                label in listOf("↵", "⇧") -> 1.5f
                else -> 1f
            }
        }
        val weight = baseWeight * KeyboardLayoutMetrics.weightMultiplier(label, profile.rightHanded)

        val gap = profile.keyGapDp
        val cell = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            tag = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dp(gap / 2), dp(1), dp(gap / 2), dp(1))
            }
        }

        val (pebbleW, pebbleH, bgRes) = when {
            isSpace -> Triple(
                if (isHebrew) dp(150) else dp(profile.spaceWidthDp),
                dp(profile.spaceHeightDp),
                R.drawable.key_pebble_space
            )
            isSpecial -> Triple(dp(profile.specialDp), dp(profile.specialDp), R.drawable.key_pebble_special_ripple)
            else -> Triple(dp(profile.pebbleWidthDp), dp(profile.pebbleHeightDp), R.drawable.key_pebble_ripple)
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

        if (touchCorrectionEnabled) {
            touchKeyViews.add(Triple(cell, label, rowIndex))
            cell.isClickable = false
            cell.isFocusable = false
            pebble.isClickable = false
        } else {
            cell.setOnClickListener {
                vibrate()
                handleKey(label)
            }
            if (label == "⌫") {
                cell.setOnLongClickListener { startDeleteRepeat(); true }
                cell.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDeleteRepeat()
                    }
                    false
                }
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
                refreshLetterCaseOnKeys()
            }
            "?123" -> { isSymbols = true; isNumpad = false; buildKeyboard() }
            "ABC" -> { isSymbols = false; isNumpad = false; buildKeyboard() }
            "🌐" -> { isHebrew = !isHebrew; isSymbols = false; isNumpad = false; buildKeyboard() }
            else -> {
                val toInsert = if (key == "SPACE") " "
                else if (isShifted && !isHebrew && key.length == 1 && key[0].isLetter()) key.uppercase()
                else key
                ic.commitText(toInsert, 1)
                updateTouchContext(toInsert)
                if (isShifted && !capsLock && toInsert.length == 1 && toInsert[0].isLetter()) {
                    isShifted = false
                    refreshLetterCaseOnKeys()
                }
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
        } catch (_: Exception) {
            showErrorToast("Microphone error — check permission in Gkeys app")
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
            VoiceAction.DEFAULT -> VoiceAction.POLISH
            VoiceAction.RAW -> VoiceAction.RAW
            else -> action
        }

        scope.launch {
            toastStatus(
                when (effectiveAction) {
                    VoiceAction.TRANSLATE -> "Transcribing…"
                    VoiceAction.DEEP_POLISH -> "Transcribing…"
                    VoiceAction.POLISH -> "Transcribing…"
                    else -> "Transcribing…"
                }
            )
            val durationMs = audioRecorder.lastRecordingDurationMs()
            val transcriptResult = aiManager.transcribe(file, openAiKey, durationMs)
            file.delete()
            transcriptResult.onFailure { error ->
                toastStatus("")
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
                toastStatus("")
                showErrorToast("Nothing heard")
                return@launch
            }

            val finalText = when (effectiveAction) {
                VoiceAction.TRANSLATE ->
                    aiManager.polishAndTranslateToHebrew(transcript, openAiKey)
                VoiceAction.POLISH ->
                    aiManager.polishText(transcript, openAiKey)
                VoiceAction.DEEP_POLISH -> {
                    if (anthropicKey.isBlank()) {
                        toastStatus("")
                        showErrorToast("Add Anthropic key for deep polish")
                        return@launch
                    }
                    aiManager.deepPolish(transcript, anthropicKey)
                }
                else -> Result.success(transcript)
            }

            finalText.onSuccess { polished ->
                currentInputConnection?.commitText(polished, 1)
                toastStatus("")
            }.onFailure {
                showErrorToast("Polish failed — text unchanged")
                toastStatus("")
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
        toastStatus("Transcribing…")
        scope.launch {
            val result = aiManager.polishText(target.text, openAiKey)
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
