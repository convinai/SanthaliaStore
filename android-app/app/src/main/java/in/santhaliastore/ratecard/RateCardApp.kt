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
        // Intentionally empty. Anything we add here runs on every
        // cold start, even if the user only opens the app for a moment.
    }
}
