package com.gremier.gkeys.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Window
import android.view.WindowManager

/**
 * Keeps the screen on and pauses other media while the microphone is actively capturing.
 */
class MicSessionGuard(private val context: Context) {

    companion object {
        private const val TAG = "MicSessionGuard"
        private const val WAKE_LOCK_TAG = "Gkeys:MicSession"
        /** Safety cap so a stuck session cannot drain the battery indefinitely. */
        private const val MAX_WAKE_MS = 30 * 60 * 1000L
    }

    private var holdCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var imeWindow: Window? = null
    private var overlayScreenOnCallback: ((Boolean) -> Unit)? = null

    fun setImeWindow(window: Window?) {
        imeWindow = window
        if (holdCount > 0) {
            applyImeScreenOnFlag(true)
        }
    }

    fun setOverlayScreenOnCallback(callback: ((Boolean) -> Unit)?) {
        overlayScreenOnCallback = callback
        if (holdCount > 0) {
            callback?.invoke(true)
        }
    }

    fun acquire() {
        holdCount++
        if (holdCount > 1) return
        acquireWakeLock()
        requestAudioFocus()
        applyImeScreenOnFlag(true)
        overlayScreenOnCallback?.invoke(true)
    }

    fun release() {
        if (holdCount <= 0) return
        holdCount--
        if (holdCount > 0) return
        releaseWakeLock()
        abandonAudioFocus()
        applyImeScreenOnFlag(false)
        overlayScreenOnCallback?.invoke(false)
    }

    fun forceRelease() {
        holdCount = 0
        releaseWakeLock()
        abandonAudioFocus()
        applyImeScreenOnFlag(false)
        overlayScreenOnCallback?.invoke(false)
    }

    private fun acquireWakeLock() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld == true) return
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_LOCK_TAG
            ).also { it.acquire(MAX_WAKE_MS) }
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock acquire failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock release failed", e)
        }
        wakeLock = null
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "Audio focus change: $focusChange")
    }

    private fun requestAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .setWillPauseWhenDucked(true)
                    .build()
                audioFocusRequest = request
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed", e)
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus abandon failed", e)
        }
        audioFocusRequest = null
    }

    private fun applyImeScreenOnFlag(on: Boolean) {
        try {
            val window = imeWindow ?: return
            if (on) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (e: Exception) {
            Log.w(TAG, "IME screen-on flag update failed", e)
        }
    }
}
