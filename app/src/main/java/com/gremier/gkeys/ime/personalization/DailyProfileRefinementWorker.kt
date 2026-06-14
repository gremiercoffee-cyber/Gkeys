package com.gremier.gkeys.ime.personalization

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gremier.gkeys.settings.GkeysSettings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class DailyProfileRefinementWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!GkeysSettings.dailyAiLearningEnabled(applicationContext).first()) return Result.success()
        val openAiKey = GkeysSettings.openAiKey(applicationContext).first()
        if (openAiKey.isBlank()) return Result.retry()

        val current = PersonalLanguageProfileStore.load(applicationContext)
        val summary = TypingEventLogger(applicationContext).buildSummary(
            allowRawSamples = GkeysSettings.allowRawTextSamples(applicationContext).first(),
        )
        val proposed = LlmProfileRefinementClient()
            .refine(openAiKey, current, summary)
            .getOrElse { return Result.retry() }
        if (!ProfileMergeEngine.validate(proposed)) return Result.retry()
        val merged = ProfileMergeEngine.merge(current, proposed)
        PersonalLanguageProfileStore.saveVersioned(applicationContext, merged)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily_profile_refinement"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build()
            val request = PeriodicWorkRequestBuilder<DailyProfileRefinementWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
