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
        onMicAcquire()
        onStatus("Connecting live transcribe…")
        scheduleConnectTimeout(onError)

        try {
            client = DeepgramSpeechStreamingClient(
                apiKey = apiKey.trim(),
                languageCode = languageCode,
                onPartial = { updatePartial(it) },
                onFinal = { commitFinal(it) },
                onConnected = {
                    cancelConnectTimeout()
                    isConnecting = false
                    isActive = true
                    onStateChanged(false, true)
                    onStatus("Listening… tap icon to stop")
                },
                onError = { error ->
                    Log.e(TAG, "Live transcribe failed", error)
                    onError(error.message ?: "Live transcribe failed", 6000L)
                    stop(clearPartial = false)
                }
            )
            client?.start(scope)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            onError("Microphone error — check permission in Gkeys app", 6000L)
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
                onError("Live transcribe timed out — check Deepgram key & internet", 6000L)
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
