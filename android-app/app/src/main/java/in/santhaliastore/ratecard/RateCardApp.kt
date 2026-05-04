package `in`.santhaliastore.ratecard

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import `in`.santhaliastore.ratecard.di.AppContainer

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
 *
 * No disk reads, no network, no Room init on the main thread. Cold
 * start on a 2GB-RAM device should comfortably fit under 1 second.
 */
class RateCardApp : Application(), Configuration.Provider {

    /** App-scoped DI container. Built lazily on first access. */
    val container: AppContainer by lazy { AppContainer(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
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
}
