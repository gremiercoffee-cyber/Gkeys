package com.gremier.gkeys.ime.slm

import android.content.Context
import android.util.Log
import com.gremier.gkeys.settings.AiPredictionSettings
import java.io.File
import java.security.MessageDigest

class LocalSlmManager(
    context: Context,
    private val backend: LocalSlmBackend = sharedBackend,
) {
    private val tag = "LocalSlmManager"
    private val appContext = context.applicationContext

    val modelFile: File
        get() = File(File(appContext.filesDir, "models"), LocalModelConfig.FILE_NAME)

    fun status(): LocalSlmStatus {
        val file = modelFile
        val exists = file.exists()
        val verified = exists && verifyModel()
        return LocalSlmStatus(
            installed = exists,
            verified = verified,
            loaded = backend.isLoaded(),
            modelName = LocalModelConfig.MODEL_NAME,
            modelVersion = LocalModelConfig.MODEL_VERSION,
            fileSizeBytes = if (exists) file.length() else 0L,
            message = when {
                !exists -> "Model not installed"
                !verified && LocalModelConfig.checksumConfigured() -> "Model failed checksum verification"
                !verified -> "Model file is empty"
                backend.isLoaded() -> "Model loaded"
                else -> "Model installed"
            },
        )
    }

    fun verifyModel(): Boolean {
        return verifyFile(modelFile)
    }

    fun verifyImportedFile(file: File): Boolean {
        return verifyFile(file)
    }

    private fun verifyFile(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) {
            Log.i(tag, "checksum_result missing_or_empty path=${file.absolutePath}")
            return false
        }
        if (!LocalModelConfig.checksumConfigured()) {
            Log.i(tag, "checksum_result skipped_not_configured path=${file.absolutePath} size=${file.length()}")
            return true
        }
        val actual = sha256(file)
        val matches = actual.equals(LocalModelConfig.SHA256, ignoreCase = true)
        Log.i(tag, "checksum_result configured matches=$matches path=${file.absolutePath} size=${file.length()}")
        return matches
    }

    suspend fun ensureLoaded(): Boolean {
        if (!verifyModel()) {
            AiPredictionSettings.saveEnabled(appContext, false)
            backend.unload()
            return false
        }
        if (backend.isLoaded()) return true
        return try {
            backend.load(modelFile)
        } catch (e: Throwable) {
            android.util.Log.e("LocalSlmManager", "Local model load failed", e)
            AiPredictionSettings.saveEnabled(appContext, false)
            false
        }
    }

    suspend fun removeModel() {
        backend.unload()
        modelFile.delete()
        AiPredictionSettings.saveEnabled(appContext, false)
    }

    fun backend(): LocalSlmBackend = backend

    companion object {
        private val sharedBackend: LocalSlmBackend = MockLocalSlmBackend()

        fun modelDir(context: Context): File = File(context.applicationContext.filesDir, "models")

        fun sha256(file: File): String {
            if (!file.exists()) return ""
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

data class LocalSlmStatus(
    val installed: Boolean,
    val verified: Boolean,
    val loaded: Boolean,
    val modelName: String,
    val modelVersion: String,
    val fileSizeBytes: Long,
    val message: String,
)
