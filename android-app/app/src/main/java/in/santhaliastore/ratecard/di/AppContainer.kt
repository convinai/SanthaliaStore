package `in`.santhaliastore.ratecard.di

import android.content.Context
import `in`.santhaliastore.ratecard.data.db.AppDatabase
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.data.repo.ItemRepository
import `in`.santhaliastore.ratecard.data.repo.PurchaseRepository
import `in`.santhaliastore.ratecard.sync.AppsScriptApi
import `in`.santhaliastore.ratecard.sync.SyncRepository

/**
 * Manual DI container — replaces Hilt for this small app.
 *
 * Each field is `lazy` so nothing is constructed until first use.
 * In particular, the Room database is not built until the first
 * DAO touch, which keeps Application.onCreate fast.
 *
 * `notifyChange` is a single shared lambda the repos call after a
 * successful write. It nudges the SyncRepository to enqueue the
 * worker. We pass a closure (rather than calling syncRepo directly)
 * to break the dependency cycle: SyncRepository depends on the two
 * data repos.
 */
class AppContainer(private val context: Context) {

    /** Single API instance — Retrofit is happy to be reused across calls. */
    val apiFactory: () -> AppsScriptApi = { sharedApi }

    private val sharedApi: AppsScriptApi by lazy { AppsScriptApi.create() }

    val database: AppDatabase by lazy { AppDatabase.build(context) }

    val settingsRepo: SettingsRepository by lazy { SettingsRepository(context) }

    private val notifyChange: () -> Unit = {
        // Fire-and-forget: scheduling work never throws.
        try {
            syncRepo.enqueueIfPending()
        } catch (t: Throwable) {
            // Intentionally swallow — sync is best-effort.
        }
    }

    val itemRepo: ItemRepository by lazy {
        ItemRepository(database.itemDao(), notifyChange)
    }

    val purchaseRepo: PurchaseRepository by lazy {
        PurchaseRepository(database.purchaseEntryDao(), notifyChange)
    }

    val syncRepo: SyncRepository by lazy {
        SyncRepository(
            context = context,
            itemRepo = itemRepo,
            purchaseRepo = purchaseRepo,
            settings = settingsRepo,
            apiFactory = apiFactory
        )
    }
}
