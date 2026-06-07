package com.gremier.gkeys.ime.gesture

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Resamples a polyline to [count] evenly spaced points along its arc length.
 */
object GesturePathSampler {

    fun sample(points: List<GesturePoint>, count: Int): List<GesturePoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return List(count) { points[0] }
        if (count <= 1) return listOf(points.first())

        val cumulative = FloatArray(points.size)
        cumulative[0] = 0f
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + dist(points[i - 1], points[i])
        }
        val total = cumulative.last()
        if (total <= 0f) return List(count) { points.first() }

        val result = ArrayList<GesturePoint>(count)
        var seg = 0
        for (i in 0 until count) {
            val target = total * i / (count - 1).coerceAtLeast(1)
            while (seg + 1 < cumulative.size && cumulative[seg + 1] < target) seg++
            val segStart = cumulative[seg]
            val segEnd = if (seg + 1 < cumulative.size) cumulative[seg + 1] else segStart
            val t = if (segEnd > segStart) (target - segStart) / (segEnd - segStart) else 0f
            val a = points[seg]
            val b = if (seg + 1 < points.size) points[seg + 1] else a
            result.add(GesturePoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
        }
        return result
    }

    private fun dist(a: GesturePoint, b: GesturePoint) = hypot(b.x - a.x, b.y - a.y)
}

/**
 * SHARK2-style normalization: scale the longest bounding-box side to [targetSize]
 * and translate so the centroid sits at the origin.
 */
object PathNormalizer {

    fun normalize(points: List<GesturePoint>, targetSize: Float = 1f): List<GesturePoint> {
        if (points.isEmpty()) return emptyList()
        var minX = points[0].x
        var maxX = points[0].x
        var minY = points[0].y
        var maxY = points[0].y
        for (p in points) {
            minX = minOf(minX, p.x)
            maxX = maxOf(maxX, p.x)
            minY = minOf(minY, p.y)
            maxY = maxOf(maxY, p.y)
        }
        val width = maxX - minX
        val height = maxY - minY
        val longest = maxOf(width, height, 1f)
        val scale = targetSize / longest
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        return points.map { p ->
            GesturePoint((p.x - cx) * scale, (p.y - cy) * scale)
        }
    }

    /** Align [template] to [reference] bbox size and centroid (SHARK2 shape channel). */
    fun alignTemplateToReference(
        template: List<GesturePoint>,
        reference: List<GesturePoint>
    ): List<GesturePoint> {
        if (template.isEmpty() || reference.isEmpty()) return template

        fun bounds(pts: List<GesturePoint>): FloatArray {
            var minX = pts[0].x
            var maxX = pts[0].x
            var minY = pts[0].y
            var maxY = pts[0].y
            for (p in pts) {
                minX = minOf(minX, p.x)
                maxX = maxOf(maxX, p.x)
                minY = minOf(minY, p.y)
                maxY = maxOf(maxY, p.y)
            }
            return floatArrayOf(minX, minY, maxX, maxY)
        }

        val tb = bounds(template)
        val rb = bounds(reference)
        val tLongest = maxOf(tb[2] - tb[0], tb[3] - tb[1], 1f)
        val rLongest = maxOf(rb[2] - rb[0], rb[3] - rb[1], 1f)
        val scale = rLongest / tLongest
        val tcx = (tb[0] + tb[2]) / 2f
        val tcy = (tb[1] + tb[3]) / 2f
        val rcx = (rb[0] + rb[2]) / 2f
        val rcy = (rb[1] + rb[3]) / 2f

        return template.map { p ->
            GesturePoint(
                (p.x - tcx) * scale + rcx,
                (p.y - tcy) * scale + rcy
            )
        }
    }
}

object GestureScorer {

    private const val SAMPLE_COUNT = 32

    /** Lower is better. Compares gesture shape independent of absolute position. */
    fun shapeScore(input: List<GesturePoint>, template: List<GesturePoint>): Float {
        val sIn = GesturePathSampler.sample(input, SAMPLE_COUNT)
        val sTpl = GesturePathSampler.sample(template, SAMPLE_COUNT)
        val aligned = PathNormalizer.alignTemplateToReference(sTpl, sIn)
        var sum = 0f
        for (i in sIn.indices) {
            sum += hypot(aligned[i].x - sIn[i].x, aligned[i].y - sIn[i].y)
        }
        return sum / SAMPLE_COUNT
    }

    /** Lower is better. Penalises input samples that stray far from the template path. */
    fun locationScore(
        input: List<GesturePoint>,
        template: List<GesturePoint>,
        threshold: Float
    ): Float {
        if (template.size < 2) return 0f
        val sIn = GesturePathSampler.sample(input, SAMPLE_COUNT)
        val sTpl = GesturePathSampler.sample(template, SAMPLE_COUNT)
        var penalty = 0f
        for (p in sIn) {
            val d = minDistanceToPolyline(p, sTpl)
            if (d > threshold) penalty += d - threshold
        }
        return penalty / sIn.size
    }

    private fun minDistanceToPolyline(point: GesturePoint, polyline: List<GesturePoint>): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            best = minOf(best, distToSegment(point, polyline[i], polyline[i + 1]))
        }
        return best
    }

    private fun distToSegment(p: GesturePoint, a: GesturePoint, b: GesturePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 0f) return hypot(p.x - a.x, p.y - a.y)
        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
        t = t.coerceIn(0f, 1f)
        val px = a.x + t * dx
        val py = a.y + t * dy
        return hypot(p.x - px, p.y - py)
    }
}
