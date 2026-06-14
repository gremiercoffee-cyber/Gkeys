package com.gremier.gkeys.ime.touch

import android.content.Context
import android.util.Log
import com.gremier.gkeys.ime.suggestions.ContextualCandidateReranker
import com.gremier.gkeys.ime.suggestions.DictionaryManager
import kotlin.math.abs
import kotlin.math.atan2
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
        fallbackWords = mergedDictionaryWords(userWords, maxSystemWords)
        loadedWordCount = fallbackWords.size
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
    ): GestureDecode? =
        diagnose(points, userWords, previousWord, nextWord).decode

    @Synchronized
    fun diagnose(
        points: List<SwipePoint>,
        userWords: Map<String, Int>,
        previousWord: String,
        nextWord: String? = null,
        targetWord: String? = null,
    ): SwipeDiagnostics {
        val learningPathKey = pathKey(points)
        return fallbackDecode(points, userWords, learningPathKey, previousWord, nextWord, targetWord)
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

    private fun fallbackDecode(
        points: List<SwipePoint>,
        userWords: Map<String, Int>,
        pathKey: String,
        previousWord: String,
        nextWord: String?,
        targetWord: String?,
    ): SwipeDiagnostics {
        val target = targetWord?.lowercase()
        val targetsByChar = letters.mapNotNull { targetKey -> targetKey.char?.let { it to targetKey } }.toMap()
        val sampledPoints = simplifyPoints(points)
        val turnPoints = detectTurnPoints(sampledPoints)
        val observedPath = likelyKeySequence(sampledPoints, turnPoints)
        val detailedObservedPath = pathKey(sampledPoints)
        val diagnostics = SwipeDiagnostics(
            targetWord = targetWord,
            rawPathPointCount = points.size,
            simplifiedPathPointCount = sampledPoints.size,
            turnPoints = turnPoints,
            likelyKeySequence = observedPath,
            targetInDictionary = target?.let { allDictionaryWords(userWords).contains(it) } ?: false,
        )
        if (points.size < MIN_POINTS || letters.isEmpty() || targetsByChar.isEmpty() || detailedObservedPath.length < 2) {
            return diagnostics.copy(failureReason = "not_enough_geometry")
        }

        val candidates = LinkedHashSet<String>()
        val removal = LinkedHashMap<String, String>()
        fun add(word: String, source: String) {
            val normalized = word.lowercase()
            if (normalized.length in 2..FALLBACK_MAX_WORD_LENGTH) candidates.add(normalized)
            if (source == "seed") removal.remove(normalized)
        }
        fun reject(word: String, reason: String) {
            removal.putIfAbsent(word.lowercase(), reason)
        }

        contractionlessVariant(observedPath)?.let { add(it, "seed") }
        confusionVariants(observedPath).forEach { add(it, "seed") }
        userWords.keys.forEach { add(it, "user") }
        PROJECT_WORDS.forEach { add(it, "project") }
        val dictionary = mergedDictionaryWords(userWords, FALLBACK_WORD_LIMIT)
        val rawDictionaryCount = dictionary.size
        diagnostics.pruningStages += SwipePruningStage("seed_user_project", candidates.size)

        dictionary.forEach { word ->
            val normalized = word.lowercase()
            when {
                normalized.length !in 2..FALLBACK_MAX_WORD_LENGTH -> reject(normalized, "length")
                normalized.any { it.isLetter() && it !in targetsByChar } -> reject(normalized, "missing_key")
                isFallbackCandidate(normalized, sampledPoints, observedPath, detailedObservedPath, targetsByChar) -> add(normalized, "strict")
                else -> reject(normalized, "strict_shape_or_endpoint")
            }
        }
        diagnostics.pruningStages += SwipePruningStage("strict_geometry", candidates.size)

        if (candidates.size < MIN_FALLBACK_CANDIDATES || target != null && target !in candidates) {
            dictionary.forEach { word ->
                val normalized = word.lowercase()
                if (normalized !in candidates &&
                    isRelaxedFallbackCandidate(normalized, sampledPoints, observedPath, detailedObservedPath, targetsByChar)
                ) {
                    add(normalized, "relaxed")
                }
            }
        }
        diagnostics.pruningStages += SwipePruningStage("relaxed_geometry", candidates.size)

        val scored = candidates.mapNotNull { word ->
            scoreCandidate(word, sampledPoints, observedPath, detailedObservedPath, pathKey, targetsByChar, userWords)
        }
        diagnostics.pruningStages += SwipePruningStage("scored", scored.size)
        val ranked = ContextualCandidateReranker.rerank(
            scored.map { it.rerankCandidate },
            ContextualCandidateReranker.Context(
                previousWords = listOfNotNull(previousWord.takeIf { it.isNotBlank() }),
                nextWord = nextWord,
            ),
        ).take(MAX_FALLBACK_RESULTS)

        val scoreByWord = scored.associateBy { it.word }
        val final = ranked.map { rankedCandidate ->
            val local = scoreByWord.getValue(rankedCandidate.word)
            SwipeCandidateDiagnostic(
                word = rankedCandidate.word,
                geometryScore = local.geometryScore,
                frequencyScore = rankedCandidate.baseFrequencyScore,
                personalDictionaryScore = rankedCandidate.personalPreferenceScore,
                contextScore = rankedCandidate.contextLanguageScore + rankedCandidate.grammarCompatibilityScore,
                finalScore = rankedCandidate.finalScore,
            )
        }
        val best = final.firstOrNull()?.word
        val decode = best?.let {
            GestureDecode(
                word = it,
                pathKey = pathKey,
                candidates = final.map { candidate -> candidate.word },
                candidateScores = final.map { candidate -> candidate.finalScore },
            )
        }
        val targetGenerated = target?.let { it in candidates } ?: false
        val targetRank = target?.let { t -> final.indexOfFirst { it.word.equals(t, ignoreCase = true) } + 1 }
            ?.takeIf { it > 0 }
        val targetRemovedBy = target?.takeIf { it !in candidates }?.let { removal[it] ?: "not_in_candidate_pool" }
        val out = diagnostics.copy(
            rawDictionaryCandidateCount = rawDictionaryCount,
            finalTopCandidates = final,
            targetGeneratedAsRawCandidate = targetGenerated,
            targetRemovedBy = targetRemovedBy,
            targetRank = targetRank,
            decode = decode,
        )
        if (targetWord == null || targetRank == null || targetRank > 10) {
            Log.d("SwipeDiagnostics", out.summary())
        }
        return out
    }

    private fun scoreCandidate(
        word: String,
        points: List<SwipePoint>,
        observedPath: String,
        detailedObservedPath: String,
        pathKey: String,
        targetsByChar: Map<Char, KeyHitTarget>,
        userWords: Map<String, Int>,
    ): SwipeScoredCandidate? {
        val distance = gestureDistance(points, word, targetsByChar) ?: return null
        val wordPath = pathKeyForWord(word)
        val sequenceDistance = minOf(
            normalizedSequenceDistance(observedPath, wordPath),
            normalizedSequenceDistance(detailedObservedPath, wordPath),
        )
        val startDistance = keyDistance(points.first(), firstLetter(word), targetsByChar) ?: return null
        val endDistance = keyDistance(points.last(), lastLetter(word), targetsByChar) ?: return null
        val frequency = DictionaryManager.frequencyScore(DictionaryManager.Language.EN, word)
        val personal = userWords[word]?.coerceAtMost(80) ?: if (word in PROJECT_WORDS_LOWER) 35 else 0
        val learned = learningStore.score(pathKey, word)
        val lengthPenalty = abs(observedPath.length - wordPath.length).toDouble() /
            maxOf(observedPath.length, wordPath.length, 1)
        val repeatedLetterRelief = repeatedLetterRelief(word, observedPath)
        val cost =
            distance * 1.35 +
                sequenceDistance * 0.68 +
                startDistance * 0.34 +
                endDistance * 0.40 +
                lengthPenalty * 0.22 +
                extraLetterPenalty(observedPath, wordPath) -
                shortWordBoost(observedPath, word) -
                literalPathBoost(observedPath, wordPath, word, personal) -
                repeatedLetterRelief
        val swipeScore = (1.0 / (1.0 + cost.coerceAtLeast(0.0))).coerceIn(0.0, 1.0)
        return SwipeScoredCandidate(
            word = word,
            geometryScore = swipeScore,
            rerankCandidate = ContextualCandidateReranker.Candidate(
                word = word,
                swipeOrTouchScore = swipeScore,
                baseFrequencyScore = (frequency / 100.0).coerceIn(0.0, 1.0),
                personalPreferenceScore = (personal / 80.0).coerceIn(0.0, 1.0),
                correctionLearningScore = (learned / 1200.0).coerceIn(-1.0, 1.0),
            ),
        )
    }

    private fun pathKey(points: List<SwipePoint>): String {
        if (letters.isEmpty()) return ""
        val minDistance = (averageLetterWidth * 0.18f).coerceAtLeast(5f)
        val out = StringBuilder()
        var lastPoint: SwipePoint? = null
        var lastChar: Char? = null
        for (point in points) {
            val previousPoint = lastPoint
            if (previousPoint != null && hypot(point.x - previousPoint.x, point.y - previousPoint.y) < minDistance) {
                continue
            }
            val nearest = letters.minByOrNull { hypot(point.x - it.centerX, point.y - it.centerY) }?.char ?: continue
            if (nearest != lastChar) {
                out.append(nearest)
                lastChar = nearest
            }
            lastPoint = point
        }
        return out.toString().take(40)
    }

    private fun likelyKeySequence(points: List<SwipePoint>, turnPoints: List<SwipePoint>): String {
        if (points.isEmpty()) return ""
        val anchors = buildList {
            add(points.first())
            addAll(turnPoints)
            if (points.last() != points.first()) add(points.last())
        }.distinct()
        val coarse = pathKey(anchors)
        return if (coarse.length >= 2) coarse else pathKey(points)
    }

    private fun simplifyPoints(points: List<SwipePoint>): List<SwipePoint> {
        if (points.size <= 2) return points
        val minDistance = (averageLetterWidth * 0.14f).coerceAtLeast(4f)
        val out = ArrayList<SwipePoint>(points.size.coerceAtMost(MAX_FALLBACK_POINTS))
        var last: SwipePoint? = null
        for (point in points) {
            val previous = last
            if (previous == null || hypot(point.x - previous.x, point.y - previous.y) >= minDistance) {
                out.add(point)
                last = point
            }
        }
        if (out.lastOrNull() != points.lastOrNull()) out.add(points.last())
        detectTurnPoints(points).forEach { turn -> if (turn !in out) out.add(turn) }
        val ordered = out.distinct().sortedBy { it.timeMs }
        if (ordered.size <= MAX_FALLBACK_POINTS) return ordered
        val step = (ordered.size - 1).toFloat() / (MAX_FALLBACK_POINTS - 1)
        return List(MAX_FALLBACK_POINTS) { index ->
            ordered[(index * step).roundToInt().coerceIn(0, ordered.lastIndex)]
        }
    }

    private fun detectTurnPoints(points: List<SwipePoint>): List<SwipePoint> {
        if (points.size < 3) return emptyList()
        val turns = ArrayList<SwipePoint>()
        for (i in 1 until points.lastIndex) {
            val a = points[i - 1]
            val b = points[i]
            val c = points[i + 1]
            val angle1 = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            val angle2 = atan2((c.y - b.y).toDouble(), (c.x - b.x).toDouble())
            val delta = abs(angle2 - angle1).let { minOf(it, Math.PI * 2 - it) }
            if (delta >= TURN_ANGLE_RADIANS) turns.add(b)
        }
        return turns.take(12)
    }

    private fun mergedDictionaryWords(userWords: Map<String, Int>, maxSystemWords: Int): List<String> =
        LinkedHashSet<String>().apply {
            userWords.keys.mapTo(this) { it.lowercase() }
            PROJECT_WORDS.mapTo(this) { it.lowercase() }
            DictionaryManager.topWords(DictionaryManager.Language.EN, maxSystemWords).mapTo(this) { it.lowercase() }
        }.toList()

    private fun allDictionaryWords(userWords: Map<String, Int>): Set<String> =
        mergedDictionaryWords(userWords, FALLBACK_WORD_LIMIT).toSet()

    private fun dictionaryWords(): List<String> {
        if (fallbackWords.isEmpty()) {
            fallbackWords = mergedDictionaryWords(emptyMap(), FALLBACK_WORD_LIMIT)
        }
        return fallbackWords
    }

    private fun isFallbackCandidate(
        word: String,
        points: List<SwipePoint>,
        observedPath: String,
        detailedObservedPath: String,
        targetsByChar: Map<Char, KeyHitTarget>,
    ): Boolean {
        if (word.length !in 2..FALLBACK_MAX_WORD_LENGTH) return false
        if (word.any { it.isLetter() && it !in targetsByChar }) return false
        val wordPath = pathKeyForWord(word)
        val maxSequenceDistance = when {
            wordPath.length <= 3 -> 1
            wordPath.length <= 6 -> 3
            else -> 5
        }
        if (minOf(editDistance(observedPath, wordPath), editDistance(detailedObservedPath, wordPath)) > maxSequenceDistance) {
            return false
        }
        val startDistance = keyDistance(points.first(), firstLetter(word), targetsByChar) ?: return false
        val endDistance = keyDistance(points.last(), lastLetter(word), targetsByChar) ?: return false
        return startDistance <= FALLBACK_ENDPOINT_RADIUS &&
            endDistance <= FALLBACK_ENDPOINT_RADIUS &&
            abs(observedPath.length - wordPath.length) <= FALLBACK_LENGTH_SLOP
    }

    private fun isRelaxedFallbackCandidate(
        word: String,
        points: List<SwipePoint>,
        observedPath: String,
        detailedObservedPath: String,
        targetsByChar: Map<Char, KeyHitTarget>,
    ): Boolean {
        if (word.length !in 2..FALLBACK_MAX_WORD_LENGTH) return false
        if (word.any { it.isLetter() && it !in targetsByChar }) return false
        val wordPath = pathKeyForWord(word)
        val startDistance = keyDistance(points.first(), firstLetter(word), targetsByChar) ?: return false
        val endDistance = keyDistance(points.last(), lastLetter(word), targetsByChar) ?: return false
        val sequenceDistance = minOf(editDistance(observedPath, wordPath), editDistance(detailedObservedPath, wordPath))
        return (startDistance <= RELAXED_ENDPOINT_RADIUS && endDistance <= RELAXED_ENDPOINT_RADIUS) ||
            sequenceDistance <= RELAXED_SEQUENCE_DISTANCE
    }

    private fun pathKeyForWord(word: String): String {
        val out = StringBuilder()
        var last: Char? = null
        for (char in word.lowercase()) {
            if (!char.isLetter()) continue
            if (char != last) {
                out.append(char)
                last = char
            }
        }
        return out.toString()
    }

    private fun firstLetter(word: String): Char = word.first { it.isLetter() }.lowercaseChar()
    private fun lastLetter(word: String): Char = word.last { it.isLetter() }.lowercaseChar()

    private fun extraLetterPenalty(observedPath: String, wordPath: String): Double {
        val extra = (wordPath.length - observedPath.length).coerceAtLeast(0)
        if (extra == 0) return 0.0
        return when {
            observedPath.length <= 2 -> extra * 0.48
            observedPath.length <= 4 -> extra * 0.26
            else -> extra * 0.10
        }
    }

    private fun normalizedSequenceDistance(observedPath: String, wordPath: String): Double =
        editDistance(observedPath, wordPath).toDouble() / maxOf(observedPath.length, wordPath.length, 1)

    private fun repeatedLetterRelief(word: String, observedPath: String): Double =
        if (word.zipWithNext().any { it.first == it.second } && pathKeyForWord(word) == observedPath) 0.34 else 0.0

    private fun shortWordBoost(observedPath: String, word: String): Double {
        if (observedPath.length > 3 || word.length > 3) return 0.0
        val wordPath = pathKeyForWord(word)
        return if (observedPath == wordPath) 0.55 else 0.0
    }

    private fun literalPathBoost(observedPath: String, wordPath: String, word: String, personalFrequency: Int): Double {
        if (observedPath != wordPath) return 0.0
        var boost = 0.82
        if (word.length <= 5) boost += 0.22
        if (personalFrequency > 0) boost += 0.48
        return boost
    }

    private fun contractionlessVariant(path: String): String? = CONTRACTIONS[path]

    private fun confusionVariants(path: String): List<String> = when (path) {
        "that" -> listOf("that", "thats", "that's")
        "thats" -> listOf("thats", "that's", "that")
        "ther" -> listOf("there", "their", "they're")
        "there" -> listOf("there", "their", "they're")
        "were" -> listOf("were", "we're")
        "your" -> listOf("your", "you're")
        "its" -> listOf("its", "it's")
        else -> emptyList()
    }

    private fun gestureDistance(points: List<SwipePoint>, word: String, targetsByChar: Map<Char, KeyHitTarget>): Double? {
        val wordPoints = wordPathPoints(word, targetsByChar) ?: return null
        if (wordPoints.isEmpty()) return null
        val norm = averageLetterWidth.coerceAtLeast(1f)
        val gesture = resamplePoints(points, SHAPE_SAMPLE_COUNT)
        val ideal = resamplePath(wordPoints, SHAPE_SAMPLE_COUNT)
        if (gesture.size != ideal.size || gesture.isEmpty()) return null
        var total = 0.0
        for (index in gesture.indices) {
            total += hypot(gesture[index].first - ideal[index].first, gesture[index].second - ideal[index].second) / norm
        }
        return total / gesture.size
    }

    private fun wordPathPoints(word: String, targetsByChar: Map<Char, KeyHitTarget>): List<Pair<Float, Float>>? {
        val out = ArrayList<Pair<Float, Float>>(word.length)
        var last: Char? = null
        for (char in word.lowercase()) {
            if (!char.isLetter()) continue
            if (char == last) continue
            val target = targetsByChar[char] ?: return null
            out.add(target.centerX to target.centerY)
            last = char
        }
        return out
    }

    private fun keyDistance(point: SwipePoint, char: Char, targetsByChar: Map<Char, KeyHitTarget>): Double? {
        val target = targetsByChar[char.lowercaseChar()] ?: return null
        return (hypot(point.x - target.centerX, point.y - target.centerY) / averageLetterWidth.coerceAtLeast(1f)).toDouble()
    }

    private fun resamplePoints(points: List<SwipePoint>, count: Int): List<Pair<Float, Float>> =
        resamplePath(points.map { it.x to it.y }, count)

    private fun resamplePath(points: List<Pair<Float, Float>>, count: Int): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1 || count <= 1) return List(count.coerceAtLeast(1)) { points.first() }
        val distances = FloatArray(points.size)
        var total = 0f
        for (index in 1 until points.size) {
            total += hypot(points[index].first - points[index - 1].first, points[index].second - points[index - 1].second)
            distances[index] = total
        }
        if (total <= 0f) return List(count) { points.first() }
        return List(count) { sampleIndex ->
            val target = total * sampleIndex / (count - 1)
            var right = 1
            while (right < distances.size - 1 && distances[right] < target) right++
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
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
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

    private data class SwipeScoredCandidate(
        val word: String,
        val geometryScore: Double,
        val rerankCandidate: ContextualCandidateReranker.Candidate,
    )

    private companion object {
        private const val MIN_POINTS = 5
        private const val FALLBACK_WORD_LIMIT = 24_000
        private const val FALLBACK_MAX_WORD_LENGTH = 18
        private const val FALLBACK_LENGTH_SLOP = 6
        private const val FALLBACK_ENDPOINT_RADIUS = 2.4
        private const val RELAXED_ENDPOINT_RADIUS = 3.6
        private const val RELAXED_SEQUENCE_DISTANCE = 7
        private const val MAX_FALLBACK_POINTS = 56
        private const val MAX_FALLBACK_RESULTS = 20
        private const val MIN_FALLBACK_CANDIDATES = 24
        private const val SHAPE_SAMPLE_COUNT = 40
        private const val TURN_ANGLE_RADIANS = 0.72
        private val PROJECT_WORDS = listOf("Gremier", "Supabase", "Codex", "PayMe", "OpenAI", "Shabbos", "yeshiva", "coldbrew")
        private val PROJECT_WORDS_LOWER = PROJECT_WORDS.mapTo(HashSet()) { it.lowercase() }
        private val CONTRACTIONS = mapOf(
            "thats" to "that's",
            "dont" to "don't",
            "cant" to "can't",
            "wont" to "won't",
            "im" to "i'm",
            "youre" to "you're",
            "its" to "it's",
            "well" to "we'll",
            "theyre" to "they're",
            "were" to "we're",
        )
    }
}

data class GestureDecode(
    val word: String,
    val pathKey: String,
    val candidates: List<String>,
    val candidateScores: List<Double> = emptyList(),
)

data class SwipePruningStage(
    val name: String,
    val candidateCount: Int,
)

data class SwipeCandidateDiagnostic(
    val word: String,
    val geometryScore: Double,
    val frequencyScore: Double,
    val personalDictionaryScore: Double,
    val contextScore: Double,
    val finalScore: Double,
)

data class SwipeDiagnostics(
    val targetWord: String? = null,
    val rawPathPointCount: Int = 0,
    val simplifiedPathPointCount: Int = 0,
    val turnPoints: List<SwipePoint> = emptyList(),
    val likelyKeySequence: String = "",
    val rawDictionaryCandidateCount: Int = 0,
    val pruningStages: MutableList<SwipePruningStage> = mutableListOf(),
    val finalTopCandidates: List<SwipeCandidateDiagnostic> = emptyList(),
    val targetInDictionary: Boolean = false,
    val targetGeneratedAsRawCandidate: Boolean = false,
    val targetRemovedBy: String? = null,
    val targetRank: Int? = null,
    val failureReason: String? = null,
    val decode: GestureDecode? = null,
) {
    fun summary(): String = buildString {
        append(targetWord ?: "(no target)")
        append(": generated=").append(targetGeneratedAsRawCandidate)
        append(", rank=").append(targetRank ?: "missing")
        append(", raw=").append(rawPathPointCount)
        append(", simplified=").append(simplifiedPathPointCount)
        append(", turns=").append(turnPoints.size)
        append(", seq=").append(likelyKeySequence)
        append(", dict=").append(targetInDictionary)
        targetRemovedBy?.let { append(", removedBy=").append(it) }
        failureReason?.let { append(", failure=").append(it) }
        append(", top=").append(finalTopCandidates.take(20).joinToString { it.word })
    }
}
