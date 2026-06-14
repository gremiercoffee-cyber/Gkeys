package com.gremier.gkeys.ime.slm

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AiModelDownloader(context: Context) {
    private val tag = "AiModelDownloader"
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
        if (!LocalModelConfig.hasRecommendedDownloadUrl()) {
            Log.e(tag, "failure reason=missing_model_url")
            return@withContext DownloadResult.Failed("No valid model URL configured")
        }
        val dir = LocalSlmManager.modelDir(appContext).apply { mkdirs() }
        if (!hasEnoughStorage(dir)) {
            Log.e(tag, "failure reason=not_enough_storage targetDir=${dir.absolutePath}")
            return@withContext DownloadResult.NotEnoughStorage
        }
        val target = File(dir, LocalModelConfig.FILE_NAME)
        val tmp = File(dir, "${LocalModelConfig.FILE_NAME}.download")
        tmp.delete()
        Log.i(tag, "download_started url=${LocalModelConfig.DOWNLOAD_URL}")
        Log.i(tag, "target_path path=${target.absolutePath}")
        onProgress(DownloadProgress(0, LocalModelConfig.EXPECTED_BYTES, "Downloading model..."))

        try {
            val request = Request.Builder().url(LocalModelConfig.DOWNLOAD_URL).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(tag, "failure reason=http_${response.code}")
                return@withContext DownloadResult.Failed("Download failed (${response.code})")
            }
            val body = response.body ?: run {
                Log.e(tag, "failure reason=empty_response")
                return@withContext DownloadResult.Failed("Empty download")
            }
            val total = body.contentLength().takeIf { it > 0 } ?: LocalModelConfig.EXPECTED_BYTES
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var readTotal = 0L
                    while (true) {
                        if (canceled.get()) {
                            tmp.delete()
                            Log.i(tag, "failure reason=canceled bytes_downloaded=$readTotal")
                            return@withContext DownloadResult.Canceled
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        readTotal += read
                        if (readTotal == total || readTotal % (8L * 1024L * 1024L) < read) {
                            Log.i(tag, "bytes_downloaded bytes=$readTotal total=$total")
                        }
                        onProgress(DownloadProgress(readTotal, total, "Downloading model..."))
                    }
                }
            }
            if (!verify(tmp)) {
                tmp.delete()
                Log.e(tag, "checksum_result failed target=${target.absolutePath}")
                return@withContext DownloadResult.ChecksumFailed
            }
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Log.i(tag, "file_exists_after_download exists=${target.exists()}")
            Log.i(tag, "file_size bytes=${target.length()}")
            onProgress(DownloadProgress(target.length(), target.length(), "Model installed"))
            DownloadResult.Success
        } catch (e: Exception) {
            tmp.delete()
            Log.e(tag, "failure reason=${e.message ?: e.javaClass.simpleName}", e)
            DownloadResult.Failed(e.message ?: "Download failed")
        }
    }

    private fun hasEnoughStorage(dir: File): Boolean {
        val stat = StatFs(dir.absolutePath)
        return stat.availableBytes >= LocalModelConfig.EXPECTED_BYTES + LocalModelConfig.MIN_FREE_BYTES_AFTER_DOWNLOAD
    }

    private fun verify(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) {
            Log.e(tag, "checksum_result missing_or_empty path=${file.absolutePath}")
            return false
        }
        if (!LocalModelConfig.checksumConfigured()) {
            Log.i(tag, "checksum_result skipped_not_configured path=${file.absolutePath} size=${file.length()}")
            return true
        }
        val ok = LocalSlmManager.sha256(file).equals(LocalModelConfig.SHA256, ignoreCase = true)
        Log.i(tag, "checksum_result configured matches=$ok path=${file.absolutePath} size=${file.length()}")
        return ok
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
