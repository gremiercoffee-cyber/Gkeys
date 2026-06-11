package com.gremier.gkeys.ime.touch

import android.content.Context
import com.gremier.gkeys.ime.suggestions.DictionaryManager
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class SwipePoint(
    val x: Float,
    val y: Float,
    val timeMs: Long,
)

object SwipeTypingDecoder {

    fun decode(
        context: Context,
        language: DictionaryManager.Language,
        points: List<SwipePoint>,
        letterTargets: List<KeyHitTarget>,
        userWords: Map<String, Int>,
        previousWord: String?,
    ): String? {
        if (language != DictionaryManager.Language.EN) return null
        if (points.size < MIN_POINTS || letterTargets.isEmpty()) return null

        DictionaryManager.ensureLoaded(context, language)

        val sampled = simplify(points, averageKeyWidth(letterTargets) * SAMPLE_DISTANCE_KEY_FRACTION)
        if (sampled.size < MIN_POINTS) return null

        val observed = sampled
            .mapNotNull { nearestLetter(it.x, it.y, letterTargets)?.char }
            .joinToString("")
            .collapseRepeats()
        if (observed.length < MIN_OBSERVED_KEYS) return null

        val first = observed.first()
        val last = observed.lastOrNull()
        val minLength = max(2, observed.length - 3)
        val maxLength = min(MAX_WORD_LENGTH, observed.length + 7)

        val candidates = LinkedHashSet<String>()
        candidates.addAll(DictionaryManager.swipeCandidates(language, first, last, minLength, maxLength))
        candidates.addAll(DictionaryManager.swipeCandidates(language, first, null, minLength, maxLength, limit = 450))
        userWords.keys.asSequence()
            .map { it.lowercase() }
            .filter { it.length in minLength..maxLength && it.firstOrNull() == first }
            .filter { last == null || it.lastOrNull() == last }
            .forEach { candidates.add(it) }

        if (candidates.isEmpty()) return null

        val scale = averageKeyWidth(letterTargets).coerceAtLeast(1f)
        var bestWord: String? = null
        var bestScore = Double.POSITIVE_INFINITY
        val previous = previousWord?.lowercase().orEmpty()

        for (candidate in candidates) {
            if (candidate.length < 2 || candidate.any { !it.isLetter() }) continue
            val score = scoreCandidate(
                candidate = candidate,
                observed = observed,
                points = sampled,
                letterTargets = letterTargets,
                scale = scale,
                language = language,
                userFrequency = userWords[candidate] ?: 0,
                previousWord = previous,
            )
            if (score < bestScore) {
                bestScore = score
                bestWord = candidate
            }
        }

        return bestWord?.takeIf { bestScore <= ACCEPT_SCORE }
    }

    private fun scoreCandidate(
        candidate: String,
        observed: String,
        points: List<SwipePoint>,
        letterTargets: List<KeyHitTarget>,
        scale: Float,
        language: DictionaryManager.Language,
        userFrequency: Int,
        previousWord: String,
    ): Double {
        val skeleton = candidate.collapseRepeats()
        val sequenceCost = sequenceDistance(observed, skeleton) * SEQUENCE_COST
        val geometryCost = geometryCost(points, candidate, letterTargets, scale) * GEOMETRY_COST
        val endpointCost = endpointCost(observed, skeleton) * ENDPOINT_COST
        val lengthCost = kotlin.math.abs(observed.length - skeleton.length) * LENGTH_COST
        val frequencyBonus = DictionaryManager.frequencyScore(language, candidate) * FREQUENCY_BONUS
        val userBonus = min(userFrequency, 25) * USER_WORD_BONUS
        val contextBonus = contextBonus(previousWord, candidate)

        return sequenceCost + geometryCost + endpointCost + lengthCost -
            frequencyBonus - userBonus - contextBonus
    }

    private fun geometryCost(
        points: List<SwipePoint>,
        word: String,
        letterTargets: List<KeyHitTarget>,
        scale: Float,
    ): Double {
        val path = word.mapNotNull { ch ->
            letterTargets.firstOrNull { it.char == ch }?.let { it.centerX to it.centerY }
        }
        if (path.size < 2) return 99.0

        var sum = 0.0
        for (point in points) {
            val d = distanceToPolyline(point.x, point.y, path)
            sum += min(3.0, d / scale)
        }
        return sum / points.size.coerceAtLeast(1)
    }

    private fun endpointCost(observed: String, candidate: String): Double {
        if (observed.isEmpty() || candidate.isEmpty()) return 2.0
        var cost = 0.0
        if (observed.first() != candidate.first()) cost += 1.0
        if (observed.last() != candidate.last()) cost += 0.8
        return cost
    }

    private fun sequenceDistance(observed: String, candidate: String): Double {
        val n = observed.length
        val m = candidate.length
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 0..n) d[i][0] = i * 0.7
        for (j in 0..m) d[0][j] = j * 0.9

        for (i in 1..n) {
            for (j in 1..m) {
                val sub = if (observed[i - 1] == candidate[j - 1]) 0.0 else 1.0
                d[i][j] = minOf(
                    d[i - 1][j] + 0.7,
                    d[i][j - 1] + 0.9,
                    d[i - 1][j - 1] + sub,
                )
            }
        }
        return d[n][m] / max(n, m).coerceAtLeast(1)
    }

    private fun distanceToPolyline(x: Float, y: Float, path: List<Pair<Float, Float>>): Double {
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until path.lastIndex) {
            val a = path[i]
            val b = path[i + 1]
            best = min(best, distanceToSegment(x, y, a.first, a.second, b.first, b.second))
        }
        return best
    }

    private fun distanceToSegment(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Double {
        val vx = bx - ax
        val vy = by - ay
        val wx = px - ax
        val wy = py - ay
        val lenSq = vx * vx + vy * vy
        val t = if (lenSq <= 0f) 0f else ((wx * vx + wy * vy) / lenSq).coerceIn(0f, 1f)
        val cx = ax + t * vx
        val cy = ay + t * vy
        return hypot((px - cx).toDouble(), (py - cy).toDouble())
    }

    private fun simplify(points: List<SwipePoint>, minDistance: Float): List<SwipePoint> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<SwipePoint>()
        var last = points.first()
        out.add(last)
        for (point in points.drop(1)) {
            if (hypot(point.x - last.x, point.y - last.y) >= minDistance) {
                out.add(point)
                last = point
            }
        }
        val finalPoint = points.last()
        if (out.last() != finalPoint) out.add(finalPoint)
        return out
    }

    private fun nearestLetter(x: Float, y: Float, targets: List<KeyHitTarget>): KeyHitTarget? =
        targets.minByOrNull { hypot(x - it.centerX, y - it.centerY) }

    private fun averageKeyWidth(targets: List<KeyHitTarget>): Float =
        targets.map { it.width }.average().takeIf { !it.isNaN() }?.toFloat() ?: 48f

    private fun String.collapseRepeats(): String {
        if (isEmpty()) return this
        val out = StringBuilder()
        var last: Char? = null
        for (ch in this) {
            if (ch != last) out.append(ch)
            last = ch
        }
        return out.toString()
    }

    private fun contextBonus(previous: String, candidate: String): Double = when {
        previous == "thank" && candidate == "you" -> 18.0
        previous == "good" && candidate in setOf("morning", "night") -> 12.0
        previous == "going" && candidate == "to" -> 12.0
        previous == "want" && candidate == "to" -> 12.0
        else -> 0.0
    }

    private const val MIN_POINTS = 4
    private const val MIN_OBSERVED_KEYS = 2
    private const val MAX_WORD_LENGTH = 24
    private const val SAMPLE_DISTANCE_KEY_FRACTION = 0.18f
    private const val SEQUENCE_COST = 52.0
    private const val GEOMETRY_COST = 34.0
    private const val ENDPOINT_COST = 34.0
    private const val LENGTH_COST = 4.0
    private const val FREQUENCY_BONUS = 0.36
    private const val USER_WORD_BONUS = 1.8
    private const val ACCEPT_SCORE = 70.0
}
