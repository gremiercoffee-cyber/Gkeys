package com.gremier.gkeys.ime.slm

object LocalModelConfig {
    const val MODEL_ID = "qwen2.5-0.5b-instruct-q4_k_m"
    const val MODEL_NAME = "Qwen2.5-0.5B-Instruct"
    const val MODEL_VERSION = "Q4_K_M GGUF"
    const val FILE_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"

    // Keep model metadata in this one object so a known-good mirror/checksum can be swapped safely.
    const val DOWNLOAD_URL =
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val SHA256 = "CHANGE_ME_TO_RECOMMENDED_MODEL_SHA256"
    const val EXPECTED_BYTES = 398L * 1024L * 1024L
    const val MIN_FREE_BYTES_AFTER_DOWNLOAD = 250L * 1024L * 1024L
    const val RERANK_TIMEOUT_MS = 180L
}
