package com.gremier.gkeys.ai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

/**
 * Captures 16 kHz mono PCM16 from the microphone for live speech-to-text streaming.
 */
class PcmAudioRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    val isRecording: Boolean get() = captureJob?.isActive == true

    fun start(scope: CoroutineScope, onChunk: (ByteArray) -> Unit) {
        stop()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) throw IllegalStateException("AudioRecord unavailable")
        val bufferSize = minBuffer.coerceAtLeast(SAMPLE_RATE / 10 * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize * 2
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            throw IllegalStateException("Microphone not initialized")
        }
        audioRecord?.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            while (coroutineContext.isActive &&
                audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    onChunk(buffer.copyOf(read))
                } else if (read < 0) {
                    delay(10)
                }
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
    }
}
