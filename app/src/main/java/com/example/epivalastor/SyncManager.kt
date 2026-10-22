package com.example.epivalastor

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Point d'entrée unique de la synchronisation (app ouverte ou synchro quotidienne en arrière-plan). */
object SyncManager {
    private val mutex = Mutex() // Une seule synchro à la fois

    /** @return true si tous les agendas ont été synchronisés. */
    suspend fun sync(context: Context): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { doSync(context) }
    }

    private suspend fun doSync(context: Context): Boolean {
        val app = context.applicationContext
        val repository = ScheduleRepository(AppDatabase.getDatabase(app), IcsManager())
        val result = repository.refreshSchedule()

        val prefs = app.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        if (result.anySuccess) {
            prefs.edit().putLong(AppSettings.KEY_LAST_SYNC, System.currentTimeMillis() / 1000).apply()
        }
        if (result.newChanges.isNotEmpty()) ChangeNotifier.notifyChanges(app, result.newChanges)
        CourseNotifier.evaluateAndSchedule(app)
        return result.allOk
    }

    fun lastSyncEpoch(context: Context): Long =
        context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(AppSettings.KEY_LAST_SYNC, 0L)
}

/** Synchro automatique une fois par jour, vers 6h du matin, dès qu'il y a du réseau. */
class DailySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ok = SyncManager.sync(applicationContext)
        return if (ok || runAttemptCount >= 3) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "daily_sync"
        private const val SYNC_HOUR = 6

        fun schedule(context: Context) {
            val now = ZonedDateTime.now()
            var next = now.withHour(SYNC_HOUR).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val initialDelay = Duration.between(now, next).toMinutes()

            val request = PeriodicWorkRequestBuilder<DailySyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelay, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            // KEEP : ne reprogramme pas si c'est déjà prévu (sinon l'heure dériverait à chaque ouverture)
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
