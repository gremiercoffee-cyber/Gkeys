package com.gremier.gkeys.ime.gesture

import kotlin.math.ln

/**
 * SHARK2-inspired gesture matcher: prune by start/end key collision, then rank
 * candidates using shape + location scores with dictionary frequency weighting.
 */
class GestureMatcher(
    private val dictionary: WordDictionary,
    private val geometry: KeyboardGeometry
) {
    companion object {
        private const val SHAPE_WEIGHT = 0.82f
        private const val LOCATION_WEIGHT = 0.18f
        private const val FREQ_WEIGHT = 0.35f
        private const val MIN_PATH_POINTS = 4
    }

    fun match(input: List<GesturePoint>, topN: Int = 3): List<GestureSuggestion> {
        if (input.size < 2 || !geometry.hasLayout()) return emptyList()

        val radius = geometry.averageKeySize * 1.15f
        val startKeys = geometry.keysNear(input.first().x, input.first().y, radius)
        val endKeys = geometry.keysNear(input.last().x, input.last().y, radius)

        var candidates = dictionary.candidates(startKeys, endKeys)
        if (candidates.isEmpty()) {
            candidates = dictionary.candidates(
                setOfNotNull(geometry.nearestKey(input.first().x, input.first().y)),
                setOfNotNull(geometry.nearestKey(input.last().x, input.last().y))
            )
        }
        if (candidates.isEmpty()) return emptyList()

        val locThreshold = geometry.averageKeySize * 0.85f
        val scored = ArrayList<GestureSuggestion>(candidates.size)

        for (entry in candidates) {
            val template = geometry.templateForWord(entry.word)
            if (template.size < 2) continue
            // Quick reject: template must have same first/last key roughly
            if (input.size >= MIN_PATH_POINTS) {
                val shape = GestureScorer.shapeScore(input, template)
                val location = GestureScorer.locationScore(input, template, locThreshold)
                val integration = SHAPE_WEIGHT * shape + LOCATION_WEIGHT * location
                val freqBonus = FREQ_WEIGHT * ln(1f + entry.rank.toFloat())
                scored.add(GestureSuggestion(entry.word, integration + freqBonus))
            }
        }

        return scored
            .sortedBy { it.score }
            .distinctBy { it.word }
            .take(topN)
    }
}
