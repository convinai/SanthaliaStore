package `in`.santhaliastore.ratecard.di

import android.content.Context
import `in`.santhaliastore.ratecard.BuildConfig
import `in`.santhaliastore.ratecard.data.db.AppDatabase
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.data.repo.BillRepository
import `in`.santhaliastore.ratecard.data.repo.CrashRepository
import `in`.santhaliastore.ratecard.data.repo.ItemRepository
import `in`.santhaliastore.ratecard.data.repo.PurchaseRepository
import `in`.santhaliastore.ratecard.sync.AppsScriptApi
import `in`.santhaliastore.ratecard.sync.BillImageUploader
import `in`.santhaliastore.ratecard.sync.PullApplier
import `in`.santhaliastore.ratecard.sync.SyncRepository
import `in`.santhaliastore.ratecard.util.BillImageCache
import `in`.santhaliastore.ratecard.util.BillImageCompressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual DI container — replaces Hilt for this small app.
 *
 * Each field is `lazy` so nothing is constructed until first use.
 * In particular, the Room database is not built until the first
 * DAO touch, which keeps Application.onCreate fast.
 *
 * `notifyChange` is a single shared lambda the repos call after a
 * successful write. Since we re-introduced activity-triggered
 * auto-sync, it now emits onto [autoSyncTrigger] — a SharedFlow whose
 * collector is debounced so a burst of writes (e.g. a quick "edit
 * + save" or a bulk import) collapses into one network round-trip.
 *
 * The collector itself runs inside [appScope] (passed in from
 * [in.santhaliastore.ratecard.RateCardApp]) so it survives Activity
 * lifecycle and ViewModel teardown — but it ONLY runs while the
 * process is alive. Nothing fires once the app is killed; battery
 * profile stays the same as the fully-manual app.
 */
class AppContainer(private val context: Context) {

    /** Single API instance — Retrofit is happy to be reused across calls. */
    val apiFactory: () -> AppsScriptApi = { sharedApi }

    private val sharedApi: AppsScriptApi by lazy { AppsScriptApi.create() }

    val database: AppDatabase by lazy { AppDatabase.build(context) }

    val settingsRepo: SettingsRepository by lazy { SettingsRepository(context) }

    /**
     * Hot signal: every successful local mutation pings this flow.
     * The collector started by [startAutoSyncLoop] debounces the
     * stream so rapid back-to-back writes coalesce into a single
     * sync request. `extraBufferCapacity = 1` means a single pending
     * emission is buffered (so a write firing while the collector
     * is mid-debounce isn't dropped); subsequent emissions while a
     * pending one already exists are simply dropped — which is
     * exactly what we want, since they'd debounce to the same
     * trailing-edge sync anyway.
     */
    private val autoSyncTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val notifyChange: () -> Unit = {
        // Fire-and-forget. The downstream collector debounces by 1.5 s
        // before invoking SyncRepository.runAutoSync, so a burst of
        // saves (bulk import, quick edit-edit-save) only fires one
        // sync. tryEmit can fail if the buffer is full — but because
        // the collector also debounces, dropping the duplicate is
        // safe: the existing pending emission still lands the user's
        // latest state on the next run.
        autoSyncTrigger.tryEmit(Unit)
    }

    /**
     * Wire up the post-write debouncer. Called once from
     * [in.santhaliastore.ratecard.RateCardApp.onCreate]. The supplied
     * [scope] should be the long-lived `appScope` so the collector
     * outlives any ViewModel.
     *
     * The 1.5 s window is intentional: long enough to coalesce a
     * "type-then-save" pair (which lands two writes within tens of
     * milliseconds) AND a quick "save then immediately fix a typo"
     * sequence; short enough that a normal user-driven save still
     * shows up on the sheet within ~2 s of tapping confirm.
     */
    @OptIn(FlowPreview::class)
    fun startAutoSyncLoop(scope: CoroutineScope) {
        scope.launch {
            autoSyncTrigger
                .debounce(AUTO_SYNC_DEBOUNCE_MS)
                .collect {
                    // runAutoSync handles its own URL-blank skip and
                    // single-flight mutex, so we don't need to guard
                    // anything here. runCatching is paranoia — the
                    // collector must never die because a single sync
                    // threw, otherwise no future write would trigger.
                    runCatching { syncRepo.runAutoSync() }
                }
        }
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

    val billRepo: BillRepository by lazy {
        BillRepository(database.billDao(), notifyChange)
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
            entryDao = database.purchaseEntryDao(),
            billDao = database.billDao()
        )
    }

    /**
     * Bill image pipeline. All three are lazy so the camera / Drive
     * machinery isn't constructed on launch — only when the user
     * first opens the Bills screen.
     *
     * [billImageCache] is also passed into [SyncRepository] so the
     * destructive "Reset local data" recovery flow can drop every
     * cached JPEG alongside the Room wipe — otherwise the on-disk
     * cache would orphan past its rows.
     */
    val billImageCache: BillImageCache by lazy { BillImageCache(context) }

    val billImageCompressor: BillImageCompressor by lazy {
        BillImageCompressor(context, billImageCache)
    }

    val billImageUploader: BillImageUploader by lazy {
        BillImageUploader(apiFactory = apiFactory, settings = settingsRepo)
    }

    val syncRepo: SyncRepository by lazy {
        SyncRepository(
            database = database,
            itemRepo = itemRepo,
            purchaseRepo = purchaseRepo,
            billRepo = billRepo,
            billImageCache = billImageCache,
            billImageUploader = billImageUploader,
            settings = settingsRepo,
            crashRepo = crashRepo,
            pullApplier = pullApplier,
            apiFactory = apiFactory
        )
    }

    companion object {
        /**
         * Debounce window for the post-write auto-sync. 1.5 s is the
         * trade-off between:
         *  - too short: bulk operations (e.g. soft-deleting an item
         *    cascading into many entry writes) fire multiple syncs.
         *  - too long: user taps Save, walks to the next phone to
         *    verify, and the data isn't there yet.
         * 1500 ms catches every realistic edit burst (a human typing
         * + saving lands inside a sub-second window) while still
         * feeling instant on the receiving phone.
         */
        const val AUTO_SYNC_DEBOUNCE_MS: Long = 1500L
    }
}
