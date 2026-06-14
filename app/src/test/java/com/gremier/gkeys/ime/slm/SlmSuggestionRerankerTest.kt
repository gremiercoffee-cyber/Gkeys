package com.gremier.gkeys.ime.slm

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class SlmSuggestionRerankerTest {

    @Test
    fun mockBackendReranksExistingCandidatesOnly() = runBlocking {
        val backend = MockLocalSlmBackend()
        val temp = File.createTempFile("model", ".gguf").apply { writeText("ok") }
        try {
            backend.load(temp)
            val original = listOf("two", "to", "too")
            val ranked = SlmSuggestionReranker.rerankWithBackend(
                backend = backend,
                previousWords = listOf("going"),
                currentPartial = "t",
                candidates = original,
                timeoutMs = 100,
            )
            assertEquals("to", ranked.first())
            assertEquals(original.toSet(), ranked.toSet())
        } finally {
            temp.delete()
        }
    }

    @Test
    fun timeoutFallsBackToNormalSuggestions() = runBlocking {
        val backend = object : LocalSlmBackend {
            override fun load(modelFile: File) = true
            override fun unload() = Unit
            override fun isLoaded() = true
            override fun rerank(request: SlmRerankRequest): SlmRerankResult = runBlocking {
                delay(80)
                SlmRerankResult(request.candidates.reversed())
            }
        }
        val original = listOf("alpha", "beta", "gamma")
        assertEquals(
            original,
            SlmSuggestionReranker.rerankWithBackend(backend, emptyList(), "", original, timeoutMs = 10),
        )
    }

    @Test
    fun inferenceFailureFallsBackToNormalSuggestions() = runBlocking {
        val backend = object : LocalSlmBackend {
            override fun load(modelFile: File) = true
            override fun unload() = Unit
            override fun isLoaded() = true
            override fun rerank(request: SlmRerankRequest): SlmRerankResult {
                error("boom")
            }
        }
        val original = listOf("alpha", "beta")
        assertEquals(
            original,
            SlmSuggestionReranker.rerankWithBackend(backend, emptyList(), "", original, timeoutMs = 100),
        )
    }

    @Test
    fun checksumDetectsInstalledFileChanges() {
        val temp = File.createTempFile("checksum", ".gguf")
        try {
            temp.writeText("one")
            val first = LocalSlmManager.sha256(temp)
            temp.writeText("two")
            val second = LocalSlmManager.sha256(temp)
            assertNotEquals(first, second)
        } finally {
            temp.delete()
        }
    }
}
