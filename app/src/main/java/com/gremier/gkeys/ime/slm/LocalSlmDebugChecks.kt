package com.gremier.gkeys.ime.slm

import android.content.Context

object LocalSlmDebugChecks {
    fun statusLines(context: Context): List<String> {
        val status = LocalSlmManager(context).status()
        return listOf(
            "model_missing=${!status.installed}",
            "model_installed=${status.installed}",
            "model_verified=${status.verified}",
            "model_loaded=${status.loaded}",
            "status=${status.message}",
        )
    }

    fun downloadResultLabel(result: DownloadResult): String = when (result) {
        DownloadResult.Success -> "Model installed"
        DownloadResult.Canceled -> "Download canceled"
        DownloadResult.ChecksumFailed -> "Model failed checksum verification"
        DownloadResult.NotEnoughStorage -> "Not enough storage"
        is DownloadResult.Failed -> result.message
    }
}
