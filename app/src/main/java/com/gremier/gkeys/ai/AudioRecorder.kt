package com.gremier.gkeys.ai

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): File {
        val file = File(context.cacheDir, "gkeys_audio_${System.currentTimeMillis()}.m4a")
        outputFile = file

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(128000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        return file
    }

    fun stopRecording(): File? {
        return try {
            recorder?.apply { stop(); release() }
            recorder = null
            outputFile
        } catch (e: Exception) {
            recorder = null
            null
        }
    }

    fun cancelRecording() {
        try { recorder?.apply { stop(); release() } } catch (e: Exception) { }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

    val isRecording: Boolean get() = recorder != null
}
