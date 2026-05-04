package `in`.santhaliastore.ratecard.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.util.AppResult

/**
 * Background worker that pushes pending local changes to the server.
 *
 * Behaviour:
 *   - On success: stamp `lastSyncedAt`, clear `lastSyncError`, return
 *     [Result.success].
 *   - On a recoverable error (network blip, transient 5xx) and we
 *     haven't burnt through [MAX_ATTEMPTS]: write the error message
 *     to `lastSyncError` so the user can see *something* happened, and
 *     return [Result.retry] so WorkManager retries with backoff.
 *   - On a permanent error (URL not configured, 4xx, parse error) or
 *     we've hit [MAX_ATTEMPTS]: keep the error message visible and
 *     return [Result.failure] so the work stops bouncing in the
 *     queue forever.
 *
 * Whatever happens, the Settings screen reflects it via the DataStore
 * keys `lastSyncedAt` and `lastSyncError`.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as RateCardApp).container
        val syncRepo = container.syncRepo
        val settings = container.settingsRepo

        val outcome = try {
            syncRepo.pushAllPending()
        } catch (t: Throwable) {
            // pushAllPending wraps in AppResult, but defend against
            // anything escaping the wrapper (e.g. coroutine cancellation
            // races on slow networks).
            AppResult.Err(t.message ?: t::class.java.simpleName, t)
        }

        return when (outcome) {
            is AppResult.Ok -> {
                settings.setLastSyncedNow()
                Result.success()
            }
            is AppResult.Err -> {
                // Always surface the error so the UI shows it.
                settings.setLastSyncError(outcome.message)
                if (isPermanent(outcome) || runAttemptCount + 1 >= MAX_ATTEMPTS) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        }
    }

    /**
     * Heuristic: errors that won't get better by retrying. We can't
     * inspect HTTP status codes from here without leaking through the
     * AppResult abstraction, so we string-match the most common
     * non-transient messages.
     */
    private fun isPermanent(err: AppResult.Err): Boolean {
        val msg = err.message.lowercase()
        return msg.contains("sheet url not set") ||
                msg.contains("server says not ok") ||
                msg.contains("malformed") ||
                msg.contains("404") ||
                msg.contains("400") ||
                msg.contains("401") ||
                msg.contains("403")
    }

    companion object {
        /**
         * Cap on retries. WorkManager has no built-in attempt cap, so
         * a long-broken URL would otherwise produce an infinite series
         * of backed-off retries. Five tries with 30s exponential backoff
         * tops out at roughly 8 minutes — beyond that, give up and let
         * the user fix the URL.
         */
        const val MAX_ATTEMPTS = 5
    }
}
