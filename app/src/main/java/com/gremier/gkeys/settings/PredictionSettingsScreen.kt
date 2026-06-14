package com.gremier.gkeys.settings

import android.content.Context
import androidx.work.WorkManager
import com.gremier.gkeys.ime.personalization.DailyProfileRefinementWorker

object PredictionSettingsScreen {
    const val WORK_NAME = "daily_profile_refinement"

    fun onDailyAiLearningChanged(context: Context, enabled: Boolean) {
        if (enabled) {
            DailyProfileRefinementWorker.schedule(context)
        } else {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
