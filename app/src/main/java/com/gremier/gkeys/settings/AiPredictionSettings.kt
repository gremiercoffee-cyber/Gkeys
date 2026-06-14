package com.gremier.gkeys.settings

import android.content.Context
import com.gremier.gkeys.ime.slm.LocalSlmManager
import kotlinx.coroutines.flow.Flow

object AiPredictionSettings {
    fun enabled(context: Context): Flow<Boolean> = GkeysSettings.onDeviceAiPredictionsEnabled(context)

    suspend fun saveEnabled(context: Context, enabled: Boolean): Boolean {
        val status = LocalSlmManager(context).status()
        if (enabled && (!status.verified || !status.runtimeAvailable)) {
            GkeysSettings.saveOnDeviceAiPredictionsEnabled(context, false)
            return false
        }
        GkeysSettings.saveOnDeviceAiPredictionsEnabled(context, enabled)
        return enabled
    }
}
