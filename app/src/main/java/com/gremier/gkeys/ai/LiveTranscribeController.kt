package com.gremier.gkeys.ai

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope

/**
 * Deepgram live speech-to-text. Streams partial and final text into the focused field.
 * Does not manage IME window visibility — the IME keeps the keyboard open when required.
 */
class LiveTranscribeController(
    private val scope: CoroutineScope,
    private val getInputConnection: () -> InputConnection?,
    private val onMicAcquire: () -> Unit,
    private val onMicRelease: () -> Unit,
    private val onStateChanged: (connecting: Boolean, active: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "LiveTranscribe"
        private const val CONNECT_TIMEOUT_MS = 18_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var client: DeepgramSpeechStreamingClient? = null
    private var partialLen = 0
    private var pendingPartial: String? = null
    private var connectTimeout: Runnable? = null

    var isConnecting = false
        private set
    var isActive = false
        private set

    val isRunning: Boolean get() = isConnecting || isActive

    fun start(
        apiKey: String,
        languageCode: String,
        onStatus: (String) -> Unit,
        onError: (String, Long) -> Unit,
    ) {
        stop(clearPartial = true)
        isConnecting = true
        onStateChanged(true, false)
        onStatus("Connecting live transcribe…")
        scheduleConnectTimeout(onError)

        try {
            client = DeepgramSpeechStreamingClient(
                apiKey = apiKey.trim(),
                languageCode = languageCode,
                onPartial = { updatePartial(it) },
                onFinal = { commitFinal(it) },
                onBeforeMicStart = onMicAcquire,
                onConnected = {
                    cancelConnectTimeout()
                    isConnecting = false
                    isActive = true
                    onStateChanged(false, true)
                    onStatus("Listening… tap icon to stop")
                },
                onError = { error ->
                    Log.e(TAG, "Live transcribe failed", error)
                    onError(friendlyError(error), 6000L)
                    stop(clearPartial = false)
                }
            )
            client?.start(scope)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            onError(friendlyError(e), 6000L)
            stop(clearPartial = true)
        }
    }

    fun stop(clearPartial: Boolean = true) {
        cancelConnectTimeout()
        client?.stop()
        client = null
        val wasRunning = isConnecting || isActive
        isConnecting = false
        isActive = false
        if (wasRunning) {
            onMicRelease()
        }
        if (clearPartial) {
            partialLen = 0
            pendingPartial = null
            try {
                getInputConnection()?.finishComposingText()
            } catch (_: Exception) {
            }
        }
        onStateChanged(false, false)
    }

    fun flushPendingPartial() {
        pendingPartial?.let { updatePartial(it) }
    }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("permission", ignoreCase = true) ||
                raw.contains("SecurityException", ignoreCase = true) ->
                "Allow mic for Gkeys in Settings"
            raw.contains("not initialized", ignoreCase = true) ||
                raw.contains("not available", ignoreCase = true) ||
                raw.contains("failed to start", ignoreCase = true) ||
                raw.contains("busy", ignoreCase = true) ->
                "Mic busy — close other apps using it, then retry"
            raw.contains("401") || raw.contains("Invalid Deepgram", ignoreCase = true) ->
                "Invalid Deepgram API key"
            raw.contains("402") || raw.contains("billing", ignoreCase = true) ->
                "Deepgram billing issue — check your account"
            raw.contains("timed out", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ->
                "Connection timed out — check internet"
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("Network", ignoreCase = true) ->
                "No internet — check your connection"
            raw.isNotBlank() -> raw
            else -> "Live transcribe failed — try again"
        }
    }

    private fun updatePartial(text: String) {
        val ic = getInputConnection()
        if (ic == null) {
            pendingPartial = text
            return
        }
        pendingPartial = null
        if (text.isEmpty()) return
        try {
            ic.beginBatchEdit()
            replacePartial(ic, text, trailingSpace = false)
            ic.endBatchEdit()
        } catch (e: Exception) {
            Log.e(TAG, "updatePartial failed", e)
        }
    }

    private fun commitFinal(text: String) {
        val ic = getInputConnection() ?: return
        try {
            ic.beginBatchEdit()
            if (text.isNotEmpty()) {
                replacePartial(ic, text.trim(), trailingSpace = true)
            } else if (partialLen > 0) {
                ic.deleteSurroundingText(partialLen, 0)
                partialLen = 0
            }
            ic.endBatchEdit()
        } catch (e: Exception) {
            Log.e(TAG, "commitFinal failed", e)
        }
    }

    private fun replacePartial(ic: InputConnection, text: String, trailingSpace: Boolean) {
        if (partialLen > 0) {
            ic.deleteSurroundingText(partialLen, 0)
            partialLen = 0
        }
        val chunk = if (trailingSpace) "$text " else text
        if (chunk.isNotEmpty()) {
            ic.commitText(chunk, 1)
            partialLen = chunk.length
        }
        if (trailingSpace) {
            partialLen = 0
        }
    }

    private fun scheduleConnectTimeout(onError: (String, Long) -> Unit) {
        cancelConnectTimeout()
        val timeout = Runnable {
            if (isConnecting && !isActive) {
                Log.w(TAG, "Connect timeout")
                onError("Connection timed out — check Deepgram key & internet", 6000L)
                stop(clearPartial = false)
            }
        }
        connectTimeout = timeout
        mainHandler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() {
        connectTimeout?.let { mainHandler.removeCallbacks(it) }
        connectTimeout = null
    }
}
