package com.gremier.gkeys.ime.slm

import android.content.Context
import android.util.Log
import com.gremier.gkeys.settings.AiPredictionSettings
import java.io.File
import java.security.MessageDigest

class LocalSlmManager(
    context: Context,
    backendFactory: ((LocalModelFormat) -> LocalSlmBackend)? = null,
) {
    private val tag = "LocalSlmManager"
    private val appContext = context.applicationContext
    private val backendFactory: (LocalModelFormat) -> LocalSlmBackend =
        backendFactory ?: { defaultBackendFor(appContext, it) }

    val modelFile: File
        get() = installedModelFile() ?: recommendedModelFile

    val recommendedModelFile: File
        get() = File(modelDir(appContext), LocalModelConfig.FILE_NAME)

    val importedTaskModelFile: File
        get() = File(modelDir(appContext), LocalModelConfig.IMPORTED_TASK_FILE_NAME)

    fun status(): LocalSlmStatus {
        val file = modelFile
        val exists = file.exists()
        val verified = exists && verifyModel()
        val format = LocalModelConfig.formatFor(file.name)
        val backend = backendFactory(format)
        return LocalSlmStatus(
            installed = exists,
            verified = verified,
            loaded = backend.isLoaded(),
            modelName = if (format == LocalModelFormat.MEDIA_PIPE_TASK) "Imported local model" else LocalModelConfig.MODEL_NAME,
            modelVersion = if (format == LocalModelFormat.MEDIA_PIPE_TASK) format.displayName else LocalModelConfig.MODEL_VERSION,
            modelFormat = format,
            runtimeAvailable = backend.runtimeAvailable,
            fileSizeBytes = if (exists) file.length() else 0L,
            message = when {
                !exists -> "Model not installed"
                !verified && LocalModelConfig.checksumConfigured() -> "Model failed checksum verification"
                !verified -> "Model file is empty"
                !backend.runtimeAvailable -> "${format.displayName} model installed, runtime missing"
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
            backendFactory(LocalModelFormat.GGUF).unload()
            backendFactory(LocalModelFormat.MEDIA_PIPE_TASK).unload()
            return false
        }
        val file = modelFile
        val backend = backendFactory(LocalModelConfig.formatFor(file.name))
        if (backend.isLoaded()) return true
        return try {
            backend.load(file)
        } catch (e: Throwable) {
            android.util.Log.e("LocalSlmManager", "Local model load failed", e)
            AiPredictionSettings.saveEnabled(appContext, false)
            false
        }
    }

    suspend fun removeModel() {
        backendFactory(LocalModelFormat.GGUF).unload()
        backendFactory(LocalModelFormat.MEDIA_PIPE_TASK).unload()
        recommendedModelFile.delete()
        importedTaskModelFile.delete()
        AiPredictionSettings.saveEnabled(appContext, false)
    }

    fun backend(): LocalSlmBackend = backendFactory(LocalModelConfig.formatFor(modelFile.name))

    private fun installedModelFile(): File? =
        listOf(importedTaskModelFile, recommendedModelFile).firstOrNull { it.exists() }

    companion object {
        private val sharedGgufBackend: LocalSlmBackend = MockLocalSlmBackend()
        @Volatile
        private var sharedTaskBackend: LocalSlmBackend? = null

        private fun defaultBackendFor(context: Context, format: LocalModelFormat): LocalSlmBackend = when (format) {
            LocalModelFormat.GGUF -> sharedGgufBackend
            LocalModelFormat.MEDIA_PIPE_TASK -> sharedTaskBackend ?: synchronized(this) {
                sharedTaskBackend ?: MediaPipeTaskSlmBackend(context.applicationContext).also {
                    sharedTaskBackend = it
                }
            }
        }

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
    val modelFormat: LocalModelFormat,
    val runtimeAvailable: Boolean,
    val fileSizeBytes: Long,
    val message: String,
)
