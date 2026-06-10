package com.gremier.gkeys.ime.touch

import androidx.datastore.preferences.core.edit
import android.content.Context
import com.gremier.gkeys.settings.GkeysSettings
import com.gremier.gkeys.settings.gkeysDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Learns the user's habitual tap offset (finger obscures keys from below) using
 * an exponential moving average, persisted via DataStore.
 */
class TouchPersonalization(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var offsetX = 0f
    private var offsetY = 0f
    private var sampleCount = 0
    private var loaded = false

    companion object {
        private const val EMA_ALPHA = 0.18f
        private const val MAX_OFFSET_FRACTION = 0.35f
    }

    fun load() {
        if (loaded) return
        scope.launch {
            val prefs = context.applicationContext.gkeysDataStore.data.first()
            offsetX = prefs[GkeysSettings.TOUCH_OFFSET_X] ?: 0f
            offsetY = prefs[GkeysSettings.TOUCH_OFFSET_Y] ?: 0f
            sampleCount = prefs[GkeysSettings.TOUCH_OFFSET_SAMPLES] ?: 0
            loaded = true
        }
    }

    fun personalizedOffsetX(): Float = offsetX
    fun personalizedOffsetY(): Float = offsetY
    fun sampleCount(): Int = sampleCount

    /**
     * Records raw touch vs resolved key center to refine offset correction.
     */
    fun recordSample(touchX: Float, touchY: Float, target: KeyHitTarget, avgKeySize: Float) {
        val observedX = touchX - target.centerX
        val observedY = touchY - target.centerY
        val cap = avgKeySize * MAX_OFFSET_FRACTION
        val clampedX = observedX.coerceIn(-cap, cap)
        val clampedY = observedY.coerceIn(-cap, cap)

        if (sampleCount == 0) {
            offsetX = clampedX
            offsetY = clampedY
        } else {
            offsetX += EMA_ALPHA * (clampedX - offsetX)
            offsetY += EMA_ALPHA * (clampedY - offsetY)
        }
        sampleCount++

        scope.launch {
            context.applicationContext.gkeysDataStore.edit {
                it[GkeysSettings.TOUCH_OFFSET_X] = offsetX
                it[GkeysSettings.TOUCH_OFFSET_Y] = offsetY
                it[GkeysSettings.TOUCH_OFFSET_SAMPLES] = sampleCount
            }
        }
    }

    /** Blend learned offset with default upward / rightward correction. */
    fun applyPersonalizedCorrection(
        touchX: Float,
        touchY: Float,
        rowYShift: Float,
        blendFactor: Float,
        rightHanded: Boolean = false,
        keyWidth: Float = 48f
    ): Pair<Float, Float> {
        val blend = blendFactor.coerceIn(0f, 1f)
        val learnX = offsetX * blend
        val learnY = offsetY * blend
        val baseX = if (rightHanded) TouchPositionCorrection.xShiftForKey(keyWidth, true) else 0f
        return (touchX + baseX - learnX) to (touchY + rowYShift - learnY)
    }
}
