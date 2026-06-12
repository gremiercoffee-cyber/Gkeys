package com.gremier.gkeys.ime.touch

import android.content.Context
import com.android.inputmethod.keyboard.ProximityInfo
import com.android.inputmethod.latin.BinaryDictionary
import com.gremier.gkeys.ime.suggestions.DictionaryManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
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
        if (points.size < MIN_POINTS) return null
        val pathKey = pathKey(points)
        val dict = dictionary
        val proximity = proximityInfo

        val suggestions = if (dict != null && proximity != null) {
            val startTime = points.first().timeMs
            val x = points.map { it.x.roundToInt() }.toIntArray()
            val y = points.map { it.y.roundToInt() }.toIntArray()
            val times = points.map { (it.timeMs - startTime).toInt().coerceAtLeast(0) }.toIntArray()
            val pointerIds = IntArray(points.size)

            dict.getGestureSuggestions(
                proximity.getNativeProximityInfo(),
                x,
                y,
                times,
                pointerIds,
                previousWord,
            )
        } else {
            emptyList()
        }

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
        if (winner != null) {
            return GestureDecode(
                word = winner.word,
                pathKey = pathKey,
                candidates = suggestions.map { it.word },
            )
        }

        return fallbackDecode(points, userWords, pathKey)
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

    private fun fallbackDecode(
        points: List<SwipePoint>,
        userWords: Map<String, Int>,
        pathKey: String,
    ): GestureDecode? {
        if (letters.isEmpty()) return null
        val targetsByChar = letters.mapNotNull { target ->
            target.char?.let { it to target }
        }.toMap()
        if (targetsByChar.isEmpty()) return null

        val sampledPoints = simplifyPoints(points)
        if (sampledPoints.size < MIN_POINTS) return null
        val firstChar = nearestChar(sampledPoints.first(), targetsByChar) ?: return null
        val lastChar = nearestChar(sampledPoints.last(), targetsByChar) ?: return null
        val candidates = LinkedHashSet<String>()
        userWords.keys.asSequence()
            .map { it.lowercase() }
            .filterTo(candidates) { isFallbackCandidate(it, firstChar, lastChar, targetsByChar) }
        DictionaryManager.topWords(DictionaryManager.Language.EN, FALLBACK_WORD_LIMIT)
            .asSequence()
            .filterTo(candidates) { isFallbackCandidate(it, firstChar, lastChar, targetsByChar) }

        val ranked = candidates.asSequence()
            .mapNotNull { word ->
                val distance = gestureDistance(sampledPoints, word, targetsByChar)
                    ?: return@mapNotNull null
                val frequency = DictionaryManager.frequencyScore(DictionaryManager.Language.EN, word)
                val personal = userWords[word]?.coerceAtMost(50) ?: 0
                val learned = learningStore.score(pathKey, word)
                val endPenalty = if (word.first() == firstChar && word.last() == lastChar) 0.0 else 0.65
                val score = -((distance + endPenalty) * 1000.0) +
                    frequency * 6.0 +
                    personal * 35.0 +
                    learned
                word to score
            }
            .sortedByDescending { it.second }
            .take(MAX_FALLBACK_RESULTS)
            .toList()
        val best = ranked.firstOrNull()?.first ?: return null
        return GestureDecode(
            word = best,
            pathKey = pathKey,
            candidates = ranked.map { it.first },
        )
    }

    private fun simplifyPoints(points: List<SwipePoint>): List<SwipePoint> {
        val minDistance = (averageLetterWidth * 0.22f).coerceAtLeast(7f)
        val out = ArrayList<SwipePoint>(points.size.coerceAtMost(MAX_FALLBACK_POINTS))
        var last: SwipePoint? = null
        for (point in points) {
            val previous = last
            if (previous == null || hypot(point.x - previous.x, point.y - previous.y) >= minDistance) {
                out.add(point)
                last = point
            }
        }
        if (out.lastOrNull() != points.lastOrNull()) {
            out.add(points.last())
        }
        if (out.size <= MAX_FALLBACK_POINTS) return out
        val step = (out.size - 1).toFloat() / (MAX_FALLBACK_POINTS - 1)
        return List(MAX_FALLBACK_POINTS) { index ->
            out[(index * step).roundToInt().coerceIn(0, out.lastIndex)]
        }
    }

    private fun nearestChar(
        point: SwipePoint,
        targetsByChar: Map<Char, KeyHitTarget>,
    ): Char? =
        targetsByChar.minByOrNull { (_, target) ->
            hypot(point.x - target.centerX, point.y - target.centerY)
        }?.key

    private fun isFallbackCandidate(
        word: String,
        firstChar: Char,
        lastChar: Char,
        targetsByChar: Map<Char, KeyHitTarget>,
    ): Boolean {
        if (word.length !in 2..FALLBACK_MAX_WORD_LENGTH) return false
        if (word.any { it !in targetsByChar }) return false
        if (word.first() != firstChar && word.last() != lastChar) return false
        return abs(word.length - pathKeyForWord(word).length) <= FALLBACK_LENGTH_SLOP
    }

    private fun pathKeyForWord(word: String): String {
        val out = StringBuilder()
        var last: Char? = null
        for (char in word) {
            if (char != last) {
                out.append(char)
                last = char
            }
        }
        return out.toString()
    }

    private fun gestureDistance(
        points: List<SwipePoint>,
        word: String,
        targetsByChar: Map<Char, KeyHitTarget>,
    ): Double? {
        val wordTargets = word.map { targetsByChar[it] ?: return null }
        if (wordTargets.isEmpty()) return null
        val norm = averageLetterWidth.coerceAtLeast(1f)
        val rows = points.size + 1
        val cols = wordTargets.size + 1
        val costs = DoubleArray(rows * cols) { Double.POSITIVE_INFINITY }
        fun idx(row: Int, col: Int) = row * cols + col
        costs[idx(0, 0)] = 0.0
        for (row in 1..points.size) {
            val point = points[row - 1]
            for (col in 1..wordTargets.size) {
                val target = wordTargets[col - 1]
                val distance = hypot(point.x - target.centerX, point.y - target.centerY) / norm
                val previous = minOf(
                    costs[idx(row - 1, col)],
                    costs[idx(row, col - 1)],
                    costs[idx(row - 1, col - 1)],
                )
                costs[idx(row, col)] = distance + previous
            }
        }
        val raw = costs[idx(points.size, wordTargets.size)]
        if (!raw.isFinite()) return null
        return raw / (points.size + wordTargets.size)
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
        private const val FALLBACK_WORD_LIMIT = 8_000
        private const val FALLBACK_MAX_WORD_LENGTH = 14
        private const val FALLBACK_LENGTH_SLOP = 4
        private const val MAX_FALLBACK_POINTS = 42
        private const val MAX_FALLBACK_RESULTS = 8
    }
}

data class GestureDecode(
    val word: String,
    val pathKey: String,
    val candidates: List<String>,
)
