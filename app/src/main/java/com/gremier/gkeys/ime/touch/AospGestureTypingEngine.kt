package com.gremier.gkeys.ime.touch

import android.content.Context
import com.android.inputmethod.keyboard.ProximityInfo
import com.android.inputmethod.latin.BinaryDictionary
import com.gremier.gkeys.ime.suggestions.DictionaryManager
import java.util.Locale
import kotlin.math.roundToInt

class AospGestureTypingEngine(
    private val context: Context,
    private val learningStore: SwipeLearningStore,
) : AutoCloseable {
    private var dictionary: BinaryDictionary? = null
    private var proximityInfo: ProximityInfo? = null
    private var letters: List<KeyHitTarget> = emptyList()
    private var averageLetterWidth = 48f
    private var loadedWordCount = 0
    private var targetSignature = ""

    @Synchronized
    fun ensureDictionary(
        userWords: Map<String, Int>,
        maxSystemWords: Int = 12_000,
    ) {
        if (dictionary != null && loadedWordCount > 0) return
        DictionaryManager.ensureLoaded(context, DictionaryManager.Language.EN)
        val dict = BinaryDictionary(Locale.US)
        var count = 0
        for (word in DictionaryManager.topWords(DictionaryManager.Language.EN, maxSystemWords)) {
            if (word.length in 2..48 && word.all { it.isLetter() || it == '\'' }) {
                val score = DictionaryManager.frequencyScore(DictionaryManager.Language.EN, word)
                val probability = (40 + score * 1.9).roundToInt().coerceIn(1, 255)
                if (dict.addWord(word, probability)) count++
            }
        }
        for ((word, frequency) in userWords) {
            val normalized = word.lowercase()
            if (normalized.length in 2..48 && normalized.all { it.isLetter() || it == '\'' }) {
                val probability = (120 + frequency.coerceAtMost(50) * 2).coerceIn(1, 255)
                if (dict.addWord(normalized, probability)) count++
            }
        }
        dictionary = dict
        loadedWordCount = count
    }

    @Synchronized
    fun updateGeometry(
        keyboardWidth: Int,
        keyboardHeight: Int,
        targets: List<KeyHitTarget>,
    ) {
        val letters = targets.filter { it.char != null }
        if (keyboardWidth <= 0 || keyboardHeight <= 0 || letters.isEmpty()) return
        this.letters = letters
        averageLetterWidth = letters.map { it.width }.average().toFloat().takeIf { !it.isNaN() } ?: 48f
        val signature = buildString {
            append(keyboardWidth).append('x').append(keyboardHeight)
            letters.forEach {
                append('|').append(it.char)
                    .append(':').append(it.centerX.roundToInt())
                    .append(',').append(it.centerY.roundToInt())
                    .append(',').append(it.width.roundToInt())
                    .append(',').append(it.height.roundToInt())
            }
        }
        if (signature == targetSignature && proximityInfo != null) return
        proximityInfo?.close()
        targetSignature = signature

        val mostCommonWidth = letters.map { it.width.roundToInt() }.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: letters.first().width.roundToInt()
        val mostCommonHeight = letters.map { it.height.roundToInt() }.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: letters.first().height.roundToInt()

        proximityInfo = ProximityInfo(
            keyboardWidth,
            keyboardHeight,
            GRID_WIDTH,
            GRID_HEIGHT,
            mostCommonWidth,
            mostCommonHeight,
            letters.map { (it.centerX - it.width / 2f).roundToInt() }.toIntArray(),
            letters.map { (it.centerY - it.height / 2f).roundToInt() }.toIntArray(),
            letters.map { it.width.roundToInt() }.toIntArray(),
            letters.map { it.height.roundToInt() }.toIntArray(),
            letters.map { it.char!!.code }.toIntArray(),
            letters.map { it.sweetSpotX }.toFloatArray(),
            letters.map { it.sweetSpotY }.toFloatArray(),
            letters.map { kotlin.math.hypot(it.width, it.height) * 0.18f }.toFloatArray(),
        )
    }

    @Synchronized
    fun decode(
        points: List<SwipePoint>,
        userWords: Map<String, Int>,
        previousWord: String,
    ): GestureDecode? {
        val dict = dictionary ?: return null
        val proximity = proximityInfo ?: return null
        if (points.size < MIN_POINTS) return null
        val pathKey = pathKey(points)

        val startTime = points.first().timeMs
        val x = points.map { it.x.roundToInt() }.toIntArray()
        val y = points.map { it.y.roundToInt() }.toIntArray()
        val times = points.map { (it.timeMs - startTime).toInt().coerceAtLeast(0) }.toIntArray()
        val pointerIds = IntArray(points.size)

        val suggestions = dict.getGestureSuggestions(
            proximity.getNativeProximityInfo(),
            x,
            y,
            times,
            pointerIds,
            previousWord,
        )
        if (suggestions.isEmpty()) return null

        val winner = suggestions
            .asSequence()
            .filter { it.word.length >= 2 }
            .maxByOrNull { suggestion ->
                val normalized = suggestion.word.lowercase()
                suggestion.score +
                    (userWords[normalized] ?: 0) * 300 +
                    DictionaryManager.frequencyScore(DictionaryManager.Language.EN, suggestion.word) * 25 +
                    learningStore.score(pathKey, normalized)
            }
            ?: return null
        return GestureDecode(
            word = winner.word,
            pathKey = pathKey,
            candidates = suggestions.map { it.word },
        )
    }

    fun recordAccepted(pathKey: String, word: String) {
        learningStore.recordAccepted(pathKey, word)
        dictionary?.addWord(word.lowercase(), 220)
    }

    fun recordRejected(pathKey: String, word: String) {
        learningStore.recordRejected(pathKey, word)
    }

    fun recordCorrection(pathKey: String, wrongWord: String, correctWord: String) {
        learningStore.recordCorrection(pathKey, wrongWord, correctWord)
        dictionary?.addWord(correctWord.lowercase(), 255)
    }

    private fun pathKey(points: List<SwipePoint>): String {
        if (letters.isEmpty()) return ""
        val minDistance = (averageLetterWidth * 0.28f).coerceAtLeast(8f)
        val out = StringBuilder()
        var lastPoint: SwipePoint? = null
        var lastChar: Char? = null
        for (point in points) {
            val previousPoint = lastPoint
            if (previousPoint != null &&
                kotlin.math.hypot(point.x - previousPoint.x, point.y - previousPoint.y) < minDistance
            ) {
                continue
            }
            val nearest = letters.minByOrNull {
                kotlin.math.hypot(point.x - it.centerX, point.y - it.centerY)
            }?.char ?: continue
            if (nearest != lastChar) {
                out.append(nearest)
                lastChar = nearest
            }
            lastPoint = point
        }
        return out.toString().take(32)
    }

    override fun close() {
        proximityInfo?.close()
        proximityInfo = null
        dictionary?.close()
        dictionary = null
    }

    private companion object {
        private const val GRID_WIDTH = 16
        private const val GRID_HEIGHT = 8
        private const val MIN_POINTS = 5
    }
}

data class GestureDecode(
    val word: String,
    val pathKey: String,
    val candidates: List<String>,
)
