package com.gremier.gkeys.ime.slm

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AiModelDownloader(context: Context) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val canceled = AtomicBoolean(false)

    fun cancel() {
        canceled.set(true)
    }

    suspend fun downloadRecommended(
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        canceled.set(false)
        val dir = LocalSlmManager.modelDir(appContext).apply { mkdirs() }
        if (!hasEnoughStorage(dir)) {
            return@withContext DownloadResult.NotEnoughStorage
        }
        val target = File(dir, LocalModelConfig.FILE_NAME)
        val tmp = File(dir, "${LocalModelConfig.FILE_NAME}.download")
        tmp.delete()
        onProgress(DownloadProgress(0, LocalModelConfig.EXPECTED_BYTES, "Downloading model..."))

        try {
            val request = Request.Builder().url(LocalModelConfig.DOWNLOAD_URL).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext DownloadResult.Failed("Download failed (${response.code})")
            val body = response.body ?: return@withContext DownloadResult.Failed("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: LocalModelConfig.EXPECTED_BYTES
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var readTotal = 0L
                    while (true) {
                        if (canceled.get()) {
                            tmp.delete()
                            return@withContext DownloadResult.Canceled
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        readTotal += read
                        onProgress(DownloadProgress(readTotal, total, "Downloading model..."))
                    }
                }
            }
            if (!verify(tmp)) {
                tmp.delete()
                return@withContext DownloadResult.ChecksumFailed
            }
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            onProgress(DownloadProgress(target.length(), target.length(), "Model installed"))
            DownloadResult.Success
        } catch (e: Exception) {
            tmp.delete()
            DownloadResult.Failed(e.message ?: "Download failed")
        }
    }

    private fun hasEnoughStorage(dir: File): Boolean {
        val stat = StatFs(dir.absolutePath)
        return stat.availableBytes >= LocalModelConfig.EXPECTED_BYTES + LocalModelConfig.MIN_FREE_BYTES_AFTER_DOWNLOAD
    }

    private fun verify(file: File): Boolean {
        val expected = LocalModelConfig.SHA256
        if (expected == "CHANGE_ME_TO_RECOMMENDED_MODEL_SHA256") {
            return false
        }
        return LocalSlmManager.sha256(file).equals(expected, ignoreCase = true)
    }
}

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val message: String,
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

sealed class DownloadResult {
    data object Success : DownloadResult()
    data object Canceled : DownloadResult()
    data object ChecksumFailed : DownloadResult()
    data object NotEnoughStorage : DownloadResult()
    data class Failed(val message: String) : DownloadResult()
}
