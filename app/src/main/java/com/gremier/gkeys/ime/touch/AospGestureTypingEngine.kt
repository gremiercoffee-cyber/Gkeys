package com.gremier.gkeys.ime.touch

import android.content.Context
import com.gremier.gkeys.ime.suggestions.ContextualCandidateReranker
import com.gremier.gkeys.ime.suggestions.DictionaryManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class AospGestureTypingEngine(
    private val context: Context,
    private val learningStore: SwipeLearningStore,
) : AutoCloseable {
    private var letters: List<KeyHitTarget> = emptyList()
    private var averageLetterWidth = 48f
    private var loadedWordCount = 0
    private var targetSignature = ""
    private var fallbackWords: List<String> = emptyList()

    @Synchronized
    fun ensureDictionary(
        userWords: Map<String, Int>,
        maxSystemWords: Int = 12_000,
    ) {
        if (loadedWordCount > 0) return
        DictionaryManager.ensureLoaded(context, DictionaryManager.Language.EN)
        fallbackWords = DictionaryManager.topWords(DictionaryManager.Language.EN, FALLBACK_WORD_LIMIT)
        loadedWordCount = fallbackWords.take(maxSystemWords).size + userWords.size
    }

    @Synchronized
    fun updateGeometry(
        keyboardWidth: Int,
        keyboardHeight: Int,
        targets: List<KeyHitTarget>,
    ) {
        val letters = targets.filter { it.char != null }
        if (keyboardWidth <= 0 || keyboardHeight <= 0 || letters.isEmpty()) return
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
        if (signature == targetSignature) return
        this.letters = letters
        averageLetterWidth = letters.map { it.width }.average().toFloat().takeIf { !it.isNaN() } ?: 48f
        targetSignature = signature
    }

    @Synchronized
    fun decode(
        points: List<SwipePoint>,
        userWords: Map<String, Int>,
        previousWord: String,
        nextWord: String? = null,
    ): GestureDecode? {
        if (points.size < MIN_POINTS) return null
        val pathKey = pathKey(points)
        return fallbackDecode(points, userWords, pathKey, previousWord, nextWord)
    }

    fun recordAccepted(pathKey: String, word: String) {
        learningStore.recordAccepted(pathKey, word)
    }

    fun recordRejected(pathKey: String, word: String) {
        learningStore.recordRejected(pathKey, word)
    }

    fun recordCorrection(pathKey: String, wrongWord: String, correctWord: String) {
        learningStore.recordCorrection(pathKey, wrongWord, correctWord)
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
        previousWord: String,
        nextWord: String?,
    ): GestureDecode? {
        if (letters.isEmpty()) return null
        val targetsByChar = letters.mapNotNull { target ->
            target.char?.let { it to target }
        }.toMap()
        if (targetsByChar.isEmpty()) return null

        val sampledPoints = simplifyPoints(points)
        if (sampledPoints.size < MIN_POINTS) return null
        val observedPath = pathKey(sampledPoints)
        if (observedPath.length < 2) return null
        val candidates = LinkedHashSet<String>()
        userWords.keys.asSequence()
            .map { it.lowercase() }
            .filterTo(candidates) { isFallbackCandidate(it, sampledPoints, observedPath, targetsByChar) }
        dictionaryWords()
            .asSequence()
            .filterTo(candidates) { isFallbackCandidate(it, sampledPoints, observedPath, targetsByChar) }
        if (candidates.size < MIN_FALLBACK_CANDIDATES) {
            dictionaryWords()
                .asSequence()
                .filterTo(candidates) { isRelaxedFallbackCandidate(it, sampledPoints, observedPath, targetsByChar) }
        }

        val rerankCandidates = candidates.asSequence()
            .mapNotNull { word ->
                val distance = gestureDistance(sampledPoints, word, targetsByChar)
                    ?: return@mapNotNull null
                val wordPath = pathKeyForWord(word)
                val sequenceDistance = editDistance(observedPath, wordPath).toDouble() /
                    maxOf(observedPath.length, wordPath.length, 1)
                val startDistance = keyDistance(sampledPoints.first(), word.first(), targetsByChar)
                    ?: return@mapNotNull null
                val endDistance = keyDistance(sampledPoints.last(), word.last(), targetsByChar)
                    ?: return@mapNotNull null
                val frequency = DictionaryManager.frequencyScore(DictionaryManager.Language.EN, word)
                val personal = userWords[word]?.coerceAtMost(50) ?: 0
                val learned = learningStore.score(pathKey, word)
                val lengthPenalty = abs(observedPath.length - wordPath.length).toDouble() /
                    maxOf(observedPath.length, wordPath.length, 1)
                val extraLetterPenalty = extraLetterPenalty(observedPath, wordPath)
                val shortWordBoost = shortWordBoost(observedPath, word)
                val cost =
                    distance * 1.25 +
                    sequenceDistance * 0.85 +
                    startDistance * 0.42 +
                    endDistance * 0.52 +
                    lengthPenalty * 0.35 +
                    extraLetterPenalty -
                    shortWordBoost
                val swipeScore = (1.0 / (1.0 + cost.coerceAtLeast(0.0))).coerceIn(0.0, 1.0)
                ContextualCandidateReranker.Candidate(
                    word = word,
                    swipeOrTouchScore = swipeScore,
                    baseFrequencyScore = (frequency / 100.0).coerceIn(0.0, 1.0),
                    personalPreferenceScore = (personal / 50.0).coerceIn(0.0, 1.0),
                    correctionLearningScore = (learned / 1200.0).coerceIn(-1.0, 1.0),
                )
            }
            .toList()
        val ranked = ContextualCandidateReranker.rerank(
            rerankCandidates,
            ContextualCandidateReranker.Context(
                previousWords = listOfNotNull(previousWord.takeIf { it.isNotBlank() }),
                nextWord = nextWord,
            ),
        )
            .take(MAX_FALLBACK_RESULTS)
        val best = ranked.firstOrNull()?.word ?: return null
        return GestureDecode(
            word = best,
            pathKey = pathKey,
            candidates = ranked.map { it.word },
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

    private fun dictionaryWords(): List<String> {
        if (fallbackWords.isEmpty()) {
            fallbackWords = DictionaryManager.topWords(DictionaryManager.Language.EN, FALLBACK_WORD_LIMIT)
        }
        return fallbackWords
    }

    private fun isFallbackCandidate(
        word: String,
        points: List<SwipePoint>,
        observedPath: String,
        targetsByChar: Map<Char, KeyHitTarget>,
    ): Boolean {
        if (word.length !in 2..FALLBACK_MAX_WORD_LENGTH) return false
        if (word.any { it !in targetsByChar }) return false
        val wordPath = pathKeyForWord(word)
        val maxSequenceDistance = when {
            wordPath.length <= 3 -> 1
            wordPath.length <= 6 -> 2
            else -> 3
        }
        if (editDistance(observedPath, wordPath) > maxSequenceDistance) return false
        val startDistance = keyDistance(points.first(), word.first(), targetsByChar) ?: return false
        val endDistance = keyDistance(points.last(), word.last(), targetsByChar) ?: return false
        return startDistance <= FALLBACK_ENDPOINT_RADIUS &&
            endDistance <= FALLBACK_ENDPOINT_RADIUS &&
            abs(observedPath.length - wordPath.length) <= FALLBACK_LENGTH_SLOP
    }

    private fun isRelaxedFallbackCandidate(
        word: String,
        points: List<SwipePoint>,
        observedPath: String,
        targetsByChar: Map<Char, KeyHitTarget>,
    ): Boolean {
        if (word.length !in 2..FALLBACK_MAX_WORD_LENGTH) return false
        if (word.any { it !in targetsByChar }) return false
        val wordPath = pathKeyForWord(word)
        val startDistance = keyDistance(points.first(), word.first(), targetsByChar) ?: return false
        val endDistance = keyDistance(points.last(), word.last(), targetsByChar) ?: return false
        val sequenceDistance = editDistance(observedPath, wordPath)
        return (startDistance <= RELAXED_ENDPOINT_RADIUS && endDistance <= RELAXED_ENDPOINT_RADIUS) ||
            sequenceDistance <= RELAXED_SEQUENCE_DISTANCE
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

    private fun extraLetterPenalty(observedPath: String, wordPath: String): Double {
        val extra = (wordPath.length - observedPath.length).coerceAtLeast(0)
        if (extra == 0) return 0.0
        return when {
            observedPath.length <= 2 -> extra * 0.75
            observedPath.length <= 4 -> extra * 0.45
            else -> extra * 0.18
        }
    }

    private fun shortWordBoost(observedPath: String, word: String): Double {
        if (observedPath.length > 3 || word.length > 3) return 0.0
        val wordPath = pathKeyForWord(word)
        return if (observedPath == wordPath) 0.65 else 0.0
    }

    private fun gestureDistance(
        points: List<SwipePoint>,
        word: String,
        targetsByChar: Map<Char, KeyHitTarget>,
    ): Double? {
        val wordPoints = wordPathPoints(word, targetsByChar) ?: return null
        if (wordPoints.isEmpty()) return null
        val norm = averageLetterWidth.coerceAtLeast(1f)
        val gesture = resamplePoints(points, SHAPE_SAMPLE_COUNT)
        val ideal = resamplePath(wordPoints, SHAPE_SAMPLE_COUNT)
        if (gesture.size != ideal.size || gesture.isEmpty()) return null
        var total = 0.0
        for (index in gesture.indices) {
            total += hypot(
                gesture[index].first - ideal[index].first,
                gesture[index].second - ideal[index].second,
            ) / norm
        }
        return total / gesture.size
    }

    private fun wordPathPoints(
        word: String,
        targetsByChar: Map<Char, KeyHitTarget>,
    ): List<Pair<Float, Float>>? {
        val out = ArrayList<Pair<Float, Float>>(word.length)
        var last: Char? = null
        for (char in word) {
            if (char == last) continue
            val target = targetsByChar[char] ?: return null
            out.add(target.centerX to target.centerY)
            last = char
        }
        return out
    }

    private fun keyDistance(
        point: SwipePoint,
        char: Char,
        targetsByChar: Map<Char, KeyHitTarget>,
    ): Double? {
        val target = targetsByChar[char] ?: return null
        return (hypot(point.x - target.centerX, point.y - target.centerY) /
            averageLetterWidth.coerceAtLeast(1f)).toDouble()
    }

    private fun resamplePoints(points: List<SwipePoint>, count: Int): List<Pair<Float, Float>> =
        resamplePath(points.map { it.x to it.y }, count)

    private fun resamplePath(points: List<Pair<Float, Float>>, count: Int): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1 || count <= 1) return List(count.coerceAtLeast(1)) { points.first() }
        val distances = FloatArray(points.size)
        var total = 0f
        for (index in 1 until points.size) {
            total += hypot(
                points[index].first - points[index - 1].first,
                points[index].second - points[index - 1].second,
            )
            distances[index] = total
        }
        if (total <= 0f) return List(count) { points.first() }
        return List(count) { sampleIndex ->
            val target = total * sampleIndex / (count - 1)
            var right = 1
            while (right < distances.size - 1 && distances[right] < target) {
                right++
            }
            val left = (right - 1).coerceAtLeast(0)
            val span = (distances[right] - distances[left]).coerceAtLeast(0.001f)
            val t = ((target - distances[left]) / span).coerceIn(0f, 1f)
            val a = points[left]
            val b = points[right]
            (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
        }
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost,
                )
            }
            for (j in curr.indices) prev[j] = curr[j]
        }
        return prev[b.length]
    }

    override fun close() {
        letters = emptyList()
        loadedWordCount = 0
        targetSignature = ""
        fallbackWords = emptyList()
    }

    private companion object {
        private const val MIN_POINTS = 5
        private const val FALLBACK_WORD_LIMIT = 18_000
        private const val FALLBACK_MAX_WORD_LENGTH = 14
        private const val FALLBACK_LENGTH_SLOP = 4
        private const val FALLBACK_ENDPOINT_RADIUS = 2.05
        private const val RELAXED_ENDPOINT_RADIUS = 3.25
        private const val RELAXED_SEQUENCE_DISTANCE = 5
        private const val MAX_FALLBACK_POINTS = 42
        private const val MAX_FALLBACK_RESULTS = 8
        private const val MIN_FALLBACK_CANDIDATES = 12
        private const val SHAPE_SAMPLE_COUNT = 32
    }
}

data class GestureDecode(
    val word: String,
    val pathKey: String,
    val candidates: List<String>,
)
