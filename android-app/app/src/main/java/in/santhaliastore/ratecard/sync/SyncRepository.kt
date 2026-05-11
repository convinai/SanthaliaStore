package `in`.santhaliastore.ratecard.sync

import androidx.room.withTransaction
import `in`.santhaliastore.ratecard.data.db.AppDatabase
import `in`.santhaliastore.ratecard.data.db.entity.BillEntity
import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.data.repo.BillRepository
import `in`.santhaliastore.ratecard.data.repo.CrashRepository
import `in`.santhaliastore.ratecard.data.repo.ItemRepository
import `in`.santhaliastore.ratecard.data.repo.PurchaseRepository
import `in`.santhaliastore.ratecard.util.AppResult
import `in`.santhaliastore.ratecard.util.BillImageCache
import `in`.santhaliastore.ratecard.util.appResultOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrator that knows how to:
 *   1) Test connectivity to the Apps Script web app.
 *   2) Pull server changes via `pullChanges` and apply them atomically.
 *   3) Push pending local changes in batches.
 *
 * The app does NOT run periodic background sync (no WorkManager,
 * no scheduled jobs — battery cost on a low-end phone). Every sync is
 * tied to user activity:
 *
 *   - Manual refresh / Settings → "Sync now": calls [runFullSyncNow]
 *     and surfaces the outcome via a snackbar.
 *   - Auto-sync on app start / resume after ≥ 5 min away: calls
 *     [runAutoSync] silently — no snackbar, errors land in
 *     `lastSyncError` for the user to inspect on Settings if they care.
 *   - Auto-sync after a local save / delete: a debounced fire-and-forget
 *     [runAutoSync] coalesces a burst of writes into a single push.
 *
 * Both entry points perform **push → pull** in that order. Why push-first:
 * the user's mental model is "my edits go up, then I read back what other
 * phones did". If push fails (no network, server down) we abort BEFORE
 * pulling — pulling now would inject server state on top of edits we never
 * managed to record on the server, which looks to the user like silent data
 * loss. Conflict resolution still works because Apps Script applies
 * last-write-wins by `updatedAt` regardless of which side acted first.
 *
 * **Single-flight**: [syncMutex] guards both [runFullSyncNow] and
 * [runAutoSync] so they can never run in parallel. A manual tap that
 * arrives while an auto-sync is in flight waits for the auto-sync to
 * finish (typically <1 s) and then runs immediately.
 *
 * **Single source of truth for "is anything syncing"**: [isSyncing]
 * flips to `true` for the duration of any sync (auto or manual) and
 * back to `false` on exit. Both Home and Settings observe this so a
 * single indicator covers both flavours of sync.
 */
class SyncRepository(
    private val database: AppDatabase,
    private val itemRepo: ItemRepository,
    private val purchaseRepo: PurchaseRepository,
    private val billRepo: BillRepository,
    private val billImageCache: BillImageCache,
    private val settings: SettingsRepository,
    private val crashRepo: CrashRepository,
    private val pullApplier: PullApplier,
    private val apiFactory: () -> AppsScriptApi
) {

    /**
     * Single-flight gate shared by [runFullSyncNow] and [runAutoSync].
     * `withLock` (manual) and `tryLock` (auto) means: a manual tap waits
     * for any in-flight sync to finish; an overlapping auto-sync request
     * is dropped silently. Either way no two syncs ever overlap.
     */
    private val syncMutex = Mutex()

    private val _isSyncing = MutableStateFlow(false)

    /**
     * `true` while any sync (auto or manual) is in progress. Drives
     * the Home refresh-button spinner AND the "Sync ho raha hai…"
     * line under the search bar / on Settings. Single source of truth
     * — both screens collect from this same flow.
     */
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    companion object {
        // The server caps a single bulkSync request at 200 changes.
        const val MAX_BATCH_SIZE = 200
        // Apps Script `logCrashes` action's per-call cap. Keep in sync
        // with the matching constant in apps-script/Code.gs.
        const val MAX_CRASHES_PER_CALL = 50

        /**
         * Legacy WorkManager unique-work tag. Kept as a constant so
         * any UI code still observing this tag through
         * `WorkManager.getWorkInfosForUniqueWorkFlow` keeps compiling
         * while the UI is migrated off it. Nothing enqueues this tag
         * any more — see the class kdoc on background sync removal.
         */
        const val UNIQUE_WORK_NAME = "sync_pending"
    }

    /* ------------------- legacy no-op shims --------------------------- */

    /**
     * Legacy entry point — used to enqueue a [androidx.work.WorkManager]
     * one-shot. Background sync was removed for battery reasons; the
     * call is preserved as a no-op so call sites that haven't been
     * migrated yet keep compiling. Safe to delete once every caller
     * is moved off it.
     */
    fun requestImmediateSync() {
        // intentionally empty
    }

    /* --------------------------- public API ---------------------------- */

    /**
     * Synchronous "Sync now" — bidirectional, manual-trigger only.
     *
     * Order is **push → pull** so the user's local edits go up before
     * we read back any server-side changes. If push fails we abort
     * BEFORE pulling: pulling now would inject server state on top of
     * edits the user thought had been saved, masking the failure.
     *
     * Conflict resolution is independent of order — Apps Script applies
     * last-write-wins by `updatedAt`, so whichever side has the newer
     * timestamp wins regardless of which side acted first.
     *
     * The result is mirrored into [SettingsRepository] (`lastSyncedAt`
     * / `lastSyncError`) so the UI stays consistent.
     *
     * Single-flight via [syncMutex]: if an auto-sync is already in
     * flight, the manual tap waits for it to finish (typically <1 s)
     * and then runs. Two manual taps in a row queue up the same way —
     * the second runs the moment the first completes. Both UI surfaces
     * already disable their refresh button while [isSyncing] is true,
     * so in practice the wait is invisible to the user.
     */
    suspend fun runFullSyncNow(): AppResult<SyncOutcome> = syncMutex.withLock {
        _isSyncing.value = true
        try {
            runFullSyncInternal()
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Internal push-then-pull body shared by [runFullSyncNow] and
     * [runAutoSync]. Assumes the caller already holds [syncMutex] and
     * has flipped [_isSyncing] to `true`.
     */
    private suspend fun runFullSyncInternal(): AppResult<SyncOutcome> {
        val url = settings.sheetUrl.first()
        if (url.isBlank()) {
            val message = "Sheet URL not set"
            settings.setLastSyncError(message)
            return AppResult.Err(message)
        }

        // 1) PUSH local pending changes first. If push fails, abort —
        //    pulling now would overwrite the user's unsaved edits with
        //    server state, hiding the failure.
        val pushedRows: Int = when (val pushResult = pushAllPending()) {
            is AppResult.Err -> {
                settings.setLastSyncError(pushResult.message)
                return pushResult
            }
            is AppResult.Ok -> pushResult.value
        }

        // 2) PULL server changes since the last cursor and apply
        //    atomically. Anything we just pushed is already `pendingSync
        //    = false` locally and the server's response is keyed on the
        //    cursor, so we won't echo our own push back.
        val pullOutcome: PullOutcome = when (val pullResult = pullAndApply(url)) {
            is AppResult.Err -> {
                settings.setLastSyncError(pullResult.message)
                return pullResult
            }
            is AppResult.Ok -> pullResult.value
        }

        // 3) Best-effort crash drain. `pushAllPending` already does this
        //    when there were data rows; we do it explicitly here for
        //    the "nothing to push" branch too. Failure is swallowed —
        //    crashes retry on the next sync.
        runCatching { pushPendingCrashes() }

        // 4) Stamp success — clear any prior error and update the
        //    last-synced-at timestamp the UI keys off.
        settings.setLastSyncError(null)
        settings.setLastSyncedNow()
        return AppResult.Ok(
            SyncOutcome(
                pushedRows = pushedRows,
                pulledItems = pullOutcome.itemsApplied,
                pulledEntries = pullOutcome.entriesApplied,
                pulledBills = pullOutcome.billsApplied
            )
        )
    }

    /**
     * Silent, activity-triggered auto-sync.
     *
     * Differs from [runFullSyncNow] in three ways:
     *   1. **No-URL is a silent skip** — no error is recorded, no log
     *      spam, nothing surfaces in `lastSyncError`. A fresh install
     *      with no Sheet URL configured shouldn't see a "Sheet URL not
     *      set" error every time it auto-syncs.
     *   2. **Single-flight via `tryLock`, not `withLock`** — if a sync
     *      is already in progress (auto or manual), the request is
     *      dropped on the floor instead of queueing. Multiple write
     *      events that fire while a push is in flight collapse into
     *      one; the trailing edge of the debouncer catches the latest
     *      state on the next run.
     *   3. **Errors are swallowed by the caller-facing return** —
     *      we still write `lastSyncError` so Settings → Sync details
     *      shows the failure, but nothing propagates back. The Home /
     *      Settings snackbar is reserved for manual sync only.
     *
     * On success, [SettingsRepository.setLastSyncedNow] still fires so
     * the "Last sync" line on Home / Settings updates naturally.
     */
    suspend fun runAutoSync() {
        val url = settings.sheetUrl.first()
        if (url.isBlank()) return // silent skip: no URL configured

        val locked = syncMutex.tryLock()
        if (!locked) return // single-flight: drop overlapping requests

        _isSyncing.value = true
        try {
            // Discard the AppResult — auto-sync never propagates errors
            // to its caller. lastSyncError is already populated by
            // runFullSyncInternal on failure (Settings UI surfaces it).
            runCatching { runFullSyncInternal() }
        } finally {
            _isSyncing.value = false
            syncMutex.unlock()
        }
    }

    /**
     * Destructive recovery path — wipes every local item / entry /
     * bill row, resets the pull cursor, then runs a full sync against
     * the sheet.
     *
     * Use case: the user manually cleared rows on the sheet (or some
     * other out-of-band edit) and expects the phone to mirror that.
     * The incremental cursor on its own can't propagate "row vanished
     * from the sheet without a tombstone" because the server only
     * emits change rows it can see — bypassing it via a full reset is
     * the only honest recovery.
     *
     * Behaviour:
     *   1. Inside a single Room transaction, delete every entry row,
     *      delete every item row, and clear the pull cursor.
     *      Order matters: entries first, items second, so the FK on
     *      `purchase_entries.itemCode -> items.code` is never violated
     *      mid-transaction. `items_fts` cleans up automatically because
     *      it's a contentEntity-backed FTS table.
     *   2. After the transaction commits, run [runFullSyncNow]. This
     *      is OUTSIDE the transaction because it does network I/O —
     *      Room's `withTransaction` is suspend-aware but holding the
     *      DB lock across an HTTP round-trip would block every other
     *      DB write for the duration of the network call.
     *   3. If the post-reset sync fails, the local DB stays cleared
     *      and `lastSyncError` is populated by [runFullSyncNow] — the
     *      user can retry by tapping refresh. We deliberately do not
     *      "roll back" the wipe: the wipe is the user's intent, and
     *      reverting it would just leave them in the same drift state
     *      they invoked reset to escape.
     *
     * Crashes are NOT cleared — the on-disk crash queue lives in
     * `<filesDir>/crashes.log` and rides its own retry path so any
     * pending crash uploads still happen on the next sync.
     */
    suspend fun resetLocalAndPullFresh(): AppResult<SyncOutcome> {
        // Step 1: nuke local state inside one transaction. We can't
        // wrap appResultOf around `withTransaction` and the network
        // call together because `runFullSyncNow` does its own error
        // handling and surfaces errors through AppResult — running
        // it inside an outer try/catch would double-stamp lastSyncError.
        val wipeResult: AppResult<Unit> = appResultOf {
            database.withTransaction {
                // Entries first — the FK target (items) must outlive
                // its dependents inside the transaction window.
                purchaseRepo.deleteAll()
                itemRepo.deleteAll()
                // Bills have no FK to items/entries so they can wipe
                // in any order — we tack them on after the FK pair so
                // the order-sensitive deletes stay grouped at the top.
                billRepo.deleteAll()
                // Empty cursor → server returns the full dataset on
                // the next pull. Inside the transaction so a crash
                // between the wipe and the cursor reset doesn't leave
                // us in a state where the cursor would skip rows.
                settings.setPullCursor("")
            }
            // Wipe also clears any stale "last sync error" — the user
            // is starting fresh and shouldn't see an old error string
            // alongside a brand-new sync result.
            settings.setLastSyncError(null)
            // Drop every cached bill image from app-private storage.
            // The Room wipe removed the bill rows that pointed at them,
            // so the JPEGs would otherwise sit on disk as orphans until
            // the next BillImageCache.cleanOrphans call. Outside the
            // Room transaction because file IO has its own failure
            // domain — a delete failure shouldn't roll back the DB wipe
            // the user explicitly asked for.
            billImageCache.clearAll()
        }
        if (wipeResult is AppResult.Err) {
            // Local wipe failed (rare — IO error inside Room). Surface
            // the error and stop; running the sync against a half-wiped
            // DB would just produce a confusing partial state.
            settings.setLastSyncError(wipeResult.message)
            return wipeResult
        }

        // Step 2: full sync. With the cursor empty and the local DB
        // empty, the push step is a no-op (nothing pending — we just
        // deleted everything) and the pull becomes a one-shot bootstrap
        // (server sends the entire dataset because the cursor is empty).
        return runFullSyncNow()
    }

    /**
     * Server health check. Returns Ok on `{ ok: true }` or Err on any
     * network / parse problem. Used by Settings -> "Test connection".
     */
    suspend fun ping(): AppResult<Unit> = appResultOf {
        val url = settings.sheetUrl.first()
        require(url.isNotBlank()) { "Sheet URL not set" }
        val body = AppsScriptApi.envelope("health", HealthPayload())
        val response = apiFactory().call(url, body)
        require(response.ok) { "Server says not ok" }
        Unit
    }

    /* --------------------------- pull ---------------------------------- */

    /**
     * Issue `pullChanges` against [url] using the persisted cursor,
     * apply the result atomically, and persist the new cursor.
     *
     * The cursor is updated ONLY after [PullApplier.apply] returns
     * successfully — if the apply throws we keep the old cursor and
     * retry the same window on the next sync. The alternative
     * (cursor-first) would silently drop server changes whenever a
     * write blew up mid-transaction.
     */
    private suspend fun pullAndApply(url: String): AppResult<PullOutcome> = appResultOf {
        val cursor = settings.pullCursor.first()
        val payload = PullChangesPayload(sinceCursor = cursor)
        val body = AppsScriptApi.envelope("pullChanges", payload)
        val response = apiFactory().pullChanges(url, body)
        require(response.ok) { "Server says not ok" }

        val outcome = pullApplier.apply(response)
        // Persist cursor LAST. Apply failure -> we never get here ->
        // cursor stays put and the same window retries next sync.
        settings.setPullCursor(response.cursor)
        outcome
    }

    /* --------------------------- push ---------------------------------- */

    /**
     * Push every pending local change to the server in batches of
     * up to [MAX_BATCH_SIZE]. On success, clears `pendingSync` for
     * the rows the server confirmed.
     *
     * Surface errors via [AppResult.Err] so callers can decide to
     * retry vs. give up.
     */
    suspend fun pushAllPending(): AppResult<Int> = appResultOf {
        val url = settings.sheetUrl.first()
        require(url.isNotBlank()) { "Sheet URL not set" }

        val items = itemRepo.pendingSync()
        val entries = purchaseRepo.pendingSync()
        val bills = billRepo.pendingSync()
        if (items.isEmpty() && entries.isEmpty() && bills.isEmpty()) {
            // Nothing data-shaped to push, but we may still have
            // unsent crashes — ride the existing connectivity check
            // and try to drain them. Failure is swallowed so the
            // "nothing pending" success path stays clean.
            runCatching { pushPendingCrashes() }
            return@appResultOf 0
        }

        val pendingItemUpserts = items.filter { !it.deleted }
        val pendingItemDeletes = items.filter { it.deleted }
        val pendingEntryUpserts = entries.filter { !it.deleted }
        val pendingEntryDeletes = entries.filter { it.deleted }
        val pendingBillUpserts = bills.filter { !it.deleted }
        val pendingBillDeletes = bills.filter { it.deleted }

        // Build flat lists of pre-serialised payloads. We chunk them
        // together so that a single batch can carry any mix of changes.
        val itemUpsertDtos = pendingItemUpserts.map { it.toUpsert() }
        val itemDeleteDtos = pendingItemDeletes.map { it.toDelete() }
        val entryUpsertDtos = pendingEntryUpserts.map { it.toUpsert() }
        val entryDeleteDtos = pendingEntryDeletes.map { it.toDelete() }
        val billUpsertDtos = pendingBillUpserts.map { it.toUpsert() }
        val billDeleteDtos = pendingBillDeletes.map { it.toDelete() }

        val totalChanges = itemUpsertDtos.size + itemDeleteDtos.size +
                entryUpsertDtos.size + entryDeleteDtos.size +
                billUpsertDtos.size + billDeleteDtos.size

        var processed = 0
        val syncedItemCodes = mutableListOf<String>()
        val syncedEntryIds = mutableListOf<String>()
        val syncedBillIds = mutableListOf<String>()

        // We greedily fill batches in this order: item upserts, item
        // deletes, entry upserts, entry deletes, bill upserts, bill
        // deletes. Within a batch we never split a single bucket
        // across calls — keeps server-side error reporting cleaner.
        val itemUpsertIter = itemUpsertDtos.iterator()
        val itemDeleteIter = itemDeleteDtos.iterator()
        val entryUpsertIter = entryUpsertDtos.iterator()
        val entryDeleteIter = entryDeleteDtos.iterator()
        val billUpsertIter = billUpsertDtos.iterator()
        val billDeleteIter = billDeleteDtos.iterator()

        var remaining = totalChanges
        while (remaining > 0) {
            val itemsBatch = mutableListOf<UpsertItemPayload>()
            val deletedItemsBatch = mutableListOf<DeleteItemPayload>()
            val entriesBatch = mutableListOf<UpsertEntryPayload>()
            val deletedEntriesBatch = mutableListOf<DeleteEntryPayload>()
            val billsBatch = mutableListOf<UpsertBillPayload>()
            val deletedBillsBatch = mutableListOf<DeleteBillPayload>()

            var room = MAX_BATCH_SIZE

            while (room > 0 && itemUpsertIter.hasNext()) { itemsBatch += itemUpsertIter.next(); room-- }
            while (room > 0 && itemDeleteIter.hasNext()) { deletedItemsBatch += itemDeleteIter.next(); room-- }
            while (room > 0 && entryUpsertIter.hasNext()) { entriesBatch += entryUpsertIter.next(); room-- }
            while (room > 0 && entryDeleteIter.hasNext()) { deletedEntriesBatch += entryDeleteIter.next(); room-- }
            while (room > 0 && billUpsertIter.hasNext()) { billsBatch += billUpsertIter.next(); room-- }
            while (room > 0 && billDeleteIter.hasNext()) { deletedBillsBatch += billDeleteIter.next(); room-- }

            val payload = BulkSyncPayload(
                items = itemsBatch,
                entries = entriesBatch,
                deletedItems = deletedItemsBatch,
                deletedEntries = deletedEntriesBatch,
                bills = billsBatch,
                deletedBills = deletedBillsBatch
            )

            val body = AppsScriptApi.envelope("bulkSync", payload)
            val response = apiFactory().call(url, body)
            if (!response.ok) {
                val msg = response.errors?.firstOrNull()?.message ?: "Server returned not ok"
                throw IllegalStateException(msg)
            }
            processed += response.processed

            // Flag everything we just sent as confirmed. This is
            // marginally optimistic — if the server reported partial
            // errors we'd still have flipped them all — but the server
            // returns ok=false on any error so we never reach here.
            syncedItemCodes += itemsBatch.map { it.code }
            syncedItemCodes += deletedItemsBatch.map { it.code }
            syncedEntryIds += entriesBatch.map { it.entryId }
            syncedEntryIds += deletedEntriesBatch.map { it.entryId }
            syncedBillIds += billsBatch.map { it.id }
            syncedBillIds += deletedBillsBatch.map { it.id }

            remaining -= itemsBatch.size + deletedItemsBatch.size +
                    entriesBatch.size + deletedEntriesBatch.size +
                    billsBatch.size + deletedBillsBatch.size
        }

        if (syncedItemCodes.isNotEmpty()) itemRepo.clearPendingSync(syncedItemCodes)
        if (syncedEntryIds.isNotEmpty()) purchaseRepo.clearPendingSync(syncedEntryIds)
        if (syncedBillIds.isNotEmpty()) billRepo.clearPendingSync(syncedBillIds)

        // Crashes ride along on the same successful-sync trigger but
        // are pushed as a separate request so a crash-upload failure
        // doesn't roll back the data sync we just completed. The
        // failure (if any) is swallowed — we'll retry on the next
        // sync, and the file stays on disk in the meantime.
        runCatching { pushPendingCrashes() }

        processed
    }

    /**
     * Drain `<filesDir>/crashes.log` to the Apps Script `Crashes` tab.
     *
     * Independent from item / entry sync:
     *   - Caps at [MAX_CRASHES_PER_CALL] events per request — the
     *     server enforces the same cap, but we mirror it here so we
     *     never even make the call with too many.
     *   - On HTTP 200 + `ok=true`, removes the uploaded crashIds from
     *     the local file. If the file still has more (rare) the next
     *     sync picks them up.
     *   - On any failure (no URL, network, server not-ok) we leave
     *     the file in place and surface the error via [AppResult.Err]
     *     so the caller can log it.
     *
     * The Apps Script side is idempotent on `crashId`, so a duplicate
     * upload (from a phone that thought it failed but the server
     * actually got the request) is a no-op on the sheet.
     */
    suspend fun pushPendingCrashes(): AppResult<Int> = appResultOf {
        val pending = crashRepo.pendingCrashes()
        if (pending.isEmpty()) return@appResultOf 0

        val url = settings.sheetUrl.first()
        require(url.isNotBlank()) { "Sheet URL not set" }

        val batch = pending.take(MAX_CRASHES_PER_CALL)
        val payload = LogCrashesPayload(crashes = batch)
        val body = AppsScriptApi.envelope("logCrashes", payload)
        val response = apiFactory().call(url, body)
        if (!response.ok) {
            val msg = response.errors?.firstOrNull()?.message ?: "Server returned not ok"
            throw IllegalStateException(msg)
        }

        crashRepo.clearUploaded(batch.map { it.crashId })
        batch.size
    }

    /* ----------------------- entity -> DTO mappers --------------------- */

    private fun ItemEntity.toUpsert() = UpsertItemPayload(
        code = code,
        name = name,
        unit = unit,
        updatedAt = updatedAt
    )

    private fun ItemEntity.toDelete() = DeleteItemPayload(
        code = code,
        updatedAt = updatedAt
    )

    private fun PurchaseEntryEntity.toUpsert() = UpsertEntryPayload(
        entryId = entryId,
        itemCode = itemCode,
        date = date,
        pricePerUnit = pricePerUnit,
        quantity = quantity,
        supplier = supplier,
        notes = notes,
        updatedAt = updatedAt
    )

    private fun PurchaseEntryEntity.toDelete() = DeleteEntryPayload(
        entryId = entryId,
        updatedAt = updatedAt
    )

    private fun BillEntity.toUpsert() = UpsertBillPayload(
        id = id,
        date = date,
        supplier = supplier,
        totalAmount = totalAmount,
        notes = notes,
        // imageFileIds is CSV-on-the-wire; the entity stores it as the
        // same shape so this is a pure pass-through. `localImagePaths`
        // is intentionally NOT included — it's per-device cache state.
        imageFileIds = imageFileIds,
        updatedAt = updatedAt
    )

    private fun BillEntity.toDelete() = DeleteBillPayload(
        id = id,
        updatedAt = updatedAt
    )
}

/**
 * Aggregate counts surfaced by [SyncRepository.runFullSyncNow] so the
 * UI can show "Push N, pulled M items + K entries + L bills" in a
 * single snackbar without poking at the repository again.
 *
 * `pulledBills` defaults to 0 so call sites that constructed this
 * before the bills feature shipped (tests in particular) keep
 * compiling without a code change.
 */
data class SyncOutcome(
    val pushedRows: Int,
    val pulledItems: Int,
    val pulledEntries: Int,
    val pulledBills: Int = 0
)
