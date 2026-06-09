package com.gremier.gkeys.ai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * Captures 16 kHz mono PCM16 from the microphone for live speech-to-text streaming.
 */
class PcmAudioRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    val isRecording: Boolean get() = captureJob?.isActive == true

    fun start(scope: CoroutineScope, onChunk: (ByteArray) -> Unit) {
        stop()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            throw IllegalStateException("Microphone format not supported on this device")
        }
        val bufferSize = minBuffer.coerceAtLeast(SAMPLE_RATE / 10 * 2)
        var lastError: Exception? = null

        for (source in AUDIO_SOURCES) {
            try {
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    bufferSize * 2
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    lastError = IllegalStateException("Microphone not initialized")
                    continue
                }
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    record.release()
                    lastError = IllegalStateException("Microphone failed to start")
                    continue
                }
                audioRecord = record
                captureJob = scope.launch(Dispatchers.IO) {
                    val buffer = ByteArray(bufferSize)
                    while (isActive &&
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
                return
            } catch (e: Exception) {
                lastError = e as? Exception ?: Exception(e)
                try {
                    audioRecord?.release()
                } catch (_: Exception) {
                }
                audioRecord = null
            }
        }
        throw lastError ?: IllegalStateException("Microphone not available")
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
