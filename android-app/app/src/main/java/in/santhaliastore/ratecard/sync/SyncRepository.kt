package `in`.santhaliastore.ratecard.sync

import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.data.repo.CrashRepository
import `in`.santhaliastore.ratecard.data.repo.ItemRepository
import `in`.santhaliastore.ratecard.data.repo.PurchaseRepository
import `in`.santhaliastore.ratecard.util.AppResult
import `in`.santhaliastore.ratecard.util.appResultOf
import kotlinx.coroutines.flow.first

/**
 * Orchestrator that knows how to:
 *   1) Test connectivity to the Apps Script web app.
 *   2) Pull server changes via `pullChanges` and apply them atomically.
 *   3) Push pending local changes in batches.
 *
 * This app deliberately does NOT run background sync (battery cost on
 * a low-end phone). The user drives every sync via the Home refresh
 * button or Settings → "Sync now"; both paths call [runFullSyncNow]
 * which performs pull → apply → push in that order.
 *
 * Pull aborts before push on failure: pushing stale local rows on top
 * of a server we couldn't read from would clobber whatever newer
 * remote state we don't yet know about.
 */
class SyncRepository(
    private val itemRepo: ItemRepository,
    private val purchaseRepo: PurchaseRepository,
    private val settings: SettingsRepository,
    private val crashRepo: CrashRepository,
    private val pullApplier: PullApplier,
    private val apiFactory: () -> AppsScriptApi
) {

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
     * Order is pull → apply → push so the device sees server-side
     * changes BEFORE re-uploading its own pending edits. Reversing the
     * order risks overwriting a newer server row with a stale local
     * one when the local clock briefly drifted ahead.
     *
     * The result is mirrored into [SettingsRepository] (`lastSyncedAt`
     * / `lastSyncError`) so the UI stays consistent.
     */
    suspend fun runFullSyncNow(): AppResult<SyncOutcome> {
        val url = settings.sheetUrl.first()
        if (url.isBlank()) {
            val message = "Sheet URL not set"
            settings.setLastSyncError(message)
            return AppResult.Err(message)
        }

        // 1) Pull first. On failure we abort BEFORE any push so we never
        //    overwrite newer remote state with a stale local copy.
        val pullOutcome: PullOutcome = when (val pullResult = pullAndApply(url)) {
            is AppResult.Err -> {
                settings.setLastSyncError(pullResult.message)
                return pullResult
            }
            is AppResult.Ok -> pullResult.value
        }

        // 2) Push our pending changes. Anything that was just pulled
        //    is already `pendingSync = false`, so we won't echo it back.
        val pushedRows: Int = when (val pushResult = pushAllPending()) {
            is AppResult.Err -> {
                settings.setLastSyncError(pushResult.message)
                return pushResult
            }
            is AppResult.Ok -> pushResult.value
        }

        // 3) Best-effort crash drain. Already wrapped in pushAllPending
        //    when there were data rows; do it explicitly here for the
        //    "nothing to push" branch too. Failure is swallowed —
        //    crashes retry on the next sync.
        runCatching { pushPendingCrashes() }

        settings.setLastSyncedNow()
        return AppResult.Ok(
            SyncOutcome(
                pushedRows = pushedRows,
                pulledItems = pullOutcome.itemsApplied,
                pulledEntries = pullOutcome.entriesApplied
            )
        )
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
        if (items.isEmpty() && entries.isEmpty()) {
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

        // Build flat lists of pre-serialised payloads. We chunk them
        // together so that a single batch can carry any mix of changes.
        val itemUpsertDtos = pendingItemUpserts.map { it.toUpsert() }
        val itemDeleteDtos = pendingItemDeletes.map { it.toDelete() }
        val entryUpsertDtos = pendingEntryUpserts.map { it.toUpsert() }
        val entryDeleteDtos = pendingEntryDeletes.map { it.toDelete() }

        val totalChanges = itemUpsertDtos.size + itemDeleteDtos.size +
                entryUpsertDtos.size + entryDeleteDtos.size

        var processed = 0
        val syncedItemCodes = mutableListOf<String>()
        val syncedEntryIds = mutableListOf<String>()

        // We greedily fill batches in this order: item upserts, item
        // deletes, entry upserts, entry deletes. Within a batch we
        // never split a single bucket across calls — keeps server-side
        // error reporting cleaner.
        val itemUpsertIter = itemUpsertDtos.iterator()
        val itemDeleteIter = itemDeleteDtos.iterator()
        val entryUpsertIter = entryUpsertDtos.iterator()
        val entryDeleteIter = entryDeleteDtos.iterator()

        var remaining = totalChanges
        while (remaining > 0) {
            val itemsBatch = mutableListOf<UpsertItemPayload>()
            val deletedItemsBatch = mutableListOf<DeleteItemPayload>()
            val entriesBatch = mutableListOf<UpsertEntryPayload>()
            val deletedEntriesBatch = mutableListOf<DeleteEntryPayload>()

            var room = MAX_BATCH_SIZE

            while (room > 0 && itemUpsertIter.hasNext()) { itemsBatch += itemUpsertIter.next(); room-- }
            while (room > 0 && itemDeleteIter.hasNext()) { deletedItemsBatch += itemDeleteIter.next(); room-- }
            while (room > 0 && entryUpsertIter.hasNext()) { entriesBatch += entryUpsertIter.next(); room-- }
            while (room > 0 && entryDeleteIter.hasNext()) { deletedEntriesBatch += entryDeleteIter.next(); room-- }

            val payload = BulkSyncPayload(
                items = itemsBatch,
                entries = entriesBatch,
                deletedItems = deletedItemsBatch,
                deletedEntries = deletedEntriesBatch
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

            remaining -= itemsBatch.size + deletedItemsBatch.size +
                    entriesBatch.size + deletedEntriesBatch.size
        }

        if (syncedItemCodes.isNotEmpty()) itemRepo.clearPendingSync(syncedItemCodes)
        if (syncedEntryIds.isNotEmpty()) purchaseRepo.clearPendingSync(syncedEntryIds)

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
}

/**
 * Aggregate counts surfaced by [SyncRepository.runFullSyncNow] so the
 * UI can show "Push N, pulled M items + K entries" in a single
 * snackbar without poking at the repository again.
 */
data class SyncOutcome(
    val pushedRows: Int,
    val pulledItems: Int,
    val pulledEntries: Int
)
