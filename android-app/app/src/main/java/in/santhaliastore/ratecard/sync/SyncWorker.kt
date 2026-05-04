package `in`.santhaliastore.ratecard.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.util.AppResult

/**
 * Background worker that pushes pending local changes to the server.
 *
 * Failure mode is graceful: any thrown exception or AppResult.Err
 * returns Result.retry(), which lets WorkManager re-run with the
 * exponential backoff configured at enqueue time. We only return
 * Result.failure() for unrecoverable cases — practically, never.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as RateCardApp).container
        val syncRepo = container.syncRepo
        val settings = container.settingsRepo

        return when (val outcome = syncRepo.pushAllPending()) {
            is AppResult.Ok -> {
                settings.setLastSyncedNow()
                Result.success()
            }
            is AppResult.Err -> {
                settings.setLastSyncError(outcome.message)
                // Retry — WorkManager will apply the backoff configured
                // by SyncRepository when the work was enqueued.
                Result.retry()
            }
        }
    }
}
