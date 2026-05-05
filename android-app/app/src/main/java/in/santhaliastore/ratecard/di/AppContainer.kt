package `in`.santhaliastore.ratecard.di

import android.content.Context
import `in`.santhaliastore.ratecard.BuildConfig
import `in`.santhaliastore.ratecard.data.db.AppDatabase
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.data.repo.CrashRepository
import `in`.santhaliastore.ratecard.data.repo.ItemRepository
import `in`.santhaliastore.ratecard.data.repo.PurchaseRepository
import `in`.santhaliastore.ratecard.sync.AppsScriptApi
import `in`.santhaliastore.ratecard.sync.PullApplier
import `in`.santhaliastore.ratecard.sync.SyncRepository
import java.io.File

/**
 * Manual DI container — replaces Hilt for this small app.
 *
 * Each field is `lazy` so nothing is constructed until first use.
 * In particular, the Room database is not built until the first
 * DAO touch, which keeps Application.onCreate fast.
 *
 * `notifyChange` is a single shared lambda the repos call after a
 * successful write. It used to enqueue a background sync worker; the
 * app no longer does background sync (battery cost on cheap phones)
 * so this is now a no-op. Every push / pull is user-driven via
 * Settings → "Sync now" or the Home refresh button. The lambda is
 * kept on the call sites so we don't have to change every repo
 * mutator just because the policy flipped.
 */
class AppContainer(private val context: Context) {

    /** Single API instance — Retrofit is happy to be reused across calls. */
    val apiFactory: () -> AppsScriptApi = { sharedApi }

    private val sharedApi: AppsScriptApi by lazy { AppsScriptApi.create() }

    val database: AppDatabase by lazy { AppDatabase.build(context) }

    val settingsRepo: SettingsRepository by lazy { SettingsRepository(context) }

    private val notifyChange: () -> Unit = {
        // No automatic sync; the user controls every push/pull from the
        // Home refresh button or Settings → "Sync now". See SyncRepository.
    }

    val itemRepo: ItemRepository by lazy {
        // ItemRepository needs both DAOs and the database handle so the
        // atomic code-rename flow can run inside `withTransaction`.
        ItemRepository(
            dao = database.itemDao(),
            purchaseEntryDao = database.purchaseEntryDao(),
            database = database,
            notifyChange = notifyChange
        )
    }

    val purchaseRepo: PurchaseRepository by lazy {
        PurchaseRepository(database.purchaseEntryDao(), notifyChange)
    }

    /**
     * On-disk crash queue. Constructed eagerly enough to be available
     * from the uncaught-exception handler installed in
     * `RateCardApp.onCreate`, but lazy in the DI sense so unit tests
     * that don't touch crash logging never instantiate it.
     *
     * The file path is fixed to `<filesDir>/crashes.log`. App-private,
     * cleared on uninstall, not visible to other apps.
     */
    val crashRepo: CrashRepository by lazy {
        CrashRepository(
            crashFile = File(context.filesDir, CrashRepository.CRASH_FILE_NAME),
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE
        )
    }

    val pullApplier: PullApplier by lazy {
        PullApplier(
            database = database,
            itemDao = database.itemDao(),
            entryDao = database.purchaseEntryDao()
        )
    }

    val syncRepo: SyncRepository by lazy {
        SyncRepository(
            itemRepo = itemRepo,
            purchaseRepo = purchaseRepo,
            settings = settingsRepo,
            crashRepo = crashRepo,
            pullApplier = pullApplier,
            apiFactory = apiFactory
        )
    }
}
