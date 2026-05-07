package `in`.santhaliastore.ratecard

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import `in`.santhaliastore.ratecard.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Application entry point.
 *
 * onCreate stays deliberately minimal:
 *   - Holds a `lazy` AppContainer (no DB access yet).
 *   - Implements [Configuration.Provider] so WorkManager can lazy-init.
 *   - Installs a global uncaught-exception handler so we capture
 *     crashes to disk before the process dies. The handler defers all
 *     I/O to [in.santhaliastore.ratecard.data.repo.CrashRepository] —
 *     which keeps onCreate fast and the failure surface tiny.
 *   - Wires up activity-triggered auto-sync. Two triggers, both tied
 *     to the user actually using the app:
 *       1. App start / resume after [RESUME_AUTO_SYNC_THRESHOLD_MS]
 *          away — kicks off a silent push-then-pull so the home list
 *          reflects what other phones did while we were gone.
 *       2. Local save / delete — debounced fire-and-forget push so a
 *          burst of writes coalesces into one network round-trip.
 *     There is NO periodic / WorkManager / scheduled sync — when the
 *     app is closed, zero work happens. Battery profile is the same
 *     as a fully-manual app.
 *
 * No disk reads, no network, no Room init on the main thread. Cold
 * start on a 2GB-RAM device should comfortably fit under 1 second.
 */
class RateCardApp : Application(), Configuration.Provider {

    /** App-scoped DI container. Built lazily on first access. */
    val container: AppContainer by lazy { AppContainer(this) }

    /**
     * Long-lived scope for fire-and-forget background work that should
     * outlive any single Activity / ViewModel — currently the
     * debounced post-write auto-sync loop and the resume-trigger
     * coroutines fired by the [ProcessLifecycleOwner] observer.
     *
     * `SupervisorJob` so a single coroutine throwing doesn't tear down
     * the whole loop. `Dispatchers.Main.immediate` is fine here — the
     * actual network work happens inside [SyncRepository.runAutoSync],
     * which dispatches its own coroutines via Retrofit + suspend
     * functions; the launch here just kicks off the suspend call.
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    /**
     * Wall-clock millis of the last `ON_START` we observed. We compare
     * this on every subsequent `ON_START` to decide whether the gap
     * was long enough (≥ [RESUME_AUTO_SYNC_THRESHOLD_MS]) to warrant a
     * fresh sync. Lives in memory only — process death IS a cold start
     * and a cold start always crosses the threshold (the field starts
     * at 0L, so `now - 0 >= 5min` is always true the first time).
     *
     * Marked `@Volatile` purely as a defensive read — `ON_START` is
     * dispatched on the main thread by ProcessLifecycleOwner so there's
     * only ever one writer, but a stray observer running on a different
     * thread should still see a coherent value.
     */
    @Volatile
    private var lastForegroundAtMillis: Long = 0L

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        // Auto-sync wiring. Both calls are cheap:
        //   - startAutoSyncLoop just collects on a SharedFlow; nothing
        //     fires until a write coroutine emits.
        //   - The lifecycle observer registers but doesn't run until
        //     the first ON_START — which is also free.
        container.startAutoSyncLoop(appScope)
        installResumeAutoSyncObserver()
    }

    /**
     * Hook the application-wide [ProcessLifecycleOwner] so we can fire
     * a silent auto-sync when the user actually opens / re-opens the
     * app. The observer is added once, lives for the life of the
     * process, and is automatically GC-resilient (ProcessLifecycleOwner
     * is itself a process-singleton).
     *
     * Cold start: `lastForegroundAtMillis` starts at 0L, so the first
     * `ON_START` always crosses the threshold and triggers a sync.
     * Resume after a brief navigation away: gap is small, so we skip
     * (saves bandwidth on every quick app switch).
     * Resume after a long break: gap ≥ 5 min, sync runs.
     */
    private fun installResumeAutoSyncObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val now = System.currentTimeMillis()
                val previous = lastForegroundAtMillis
                lastForegroundAtMillis = now
                if (shouldAutoSyncOnResume(nowMillis = now, lastForegroundMillis = previous)) {
                    appScope.launch {
                        container.syncRepo.runAutoSync()
                    }
                }
            }
        })
    }

    /**
     * Install a process-wide uncaught-exception handler that:
     *
     *  1. Captures the current default handler FIRST so we can
     *     delegate to it after writing — Android's default handler is
     *     what shows the "App has stopped" dialog and tears the
     *     process down. Skipping it would leave the user staring at a
     *     frozen screen.
     *
     *  2. Writes a JSON-encoded [in.santhaliastore.ratecard.sync.CrashEvent]
     *     line synchronously. The process is dying — we cannot
     *     hand off to a coroutine because the dispatcher might not
     *     finish before SIGKILL.
     *
     *  3. Wraps everything in a try/catch. The crash handler must
     *     never itself throw on top of the crash we're trying to log.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Lazy-construct CrashRepository here, not at process
                // start, so a crash *during* DI doesn't make the
                // handler itself throw before the previous handler
                // gets a chance.
                container.crashRepo.recordCrashSync(thread, throwable)
            } catch (_: Throwable) {
                // Swallow — see kdoc. The main goal is "never make
                // the crash worse"; losing one crash report is the
                // less-bad outcome.
            }
            // Always delegate to the previous handler (usually
            // Android's default) so the standard crash dialog and
            // process termination still happen.
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
                // Same: never throw out of the handler.
            }
        }
    }

    companion object {
        /**
         * Minimum gap between two foreground events that justifies
         * firing an auto-sync. Five minutes is the same window
         * `MainActivity` uses for re-locking, picked so a brief task
         * switch (camera, WhatsApp lookup) doesn't trigger a network
         * round-trip — but coming back to the app after lunch does.
         */
        val RESUME_AUTO_SYNC_THRESHOLD_MS: Long = TimeUnit.MINUTES.toMillis(5)

        /**
         * Pure predicate so the resume-threshold rule is trivially
         * unit-testable without standing up a Lifecycle. Returns
         * `true` when the gap between [lastForegroundMillis] and
         * [nowMillis] is at least [RESUME_AUTO_SYNC_THRESHOLD_MS].
         *
         * `lastForegroundMillis == 0L` means "we've never seen a
         * foreground event before" — i.e. cold start. We always
         * sync on cold start, so the predicate must return true for
         * that case (and naturally does, since `now - 0 >= 5min`
         * for any plausible system clock).
         */
        fun shouldAutoSyncOnResume(
            nowMillis: Long,
            lastForegroundMillis: Long
        ): Boolean = (nowMillis - lastForegroundMillis) >= RESUME_AUTO_SYNC_THRESHOLD_MS
    }
}
