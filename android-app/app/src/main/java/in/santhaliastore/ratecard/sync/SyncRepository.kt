package `in`.santhaliastore.ratecard.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.data.repo.CrashRepository
import `in`.santhaliastore.ratecard.data.repo.ItemRepository
import `in`.santhaliastore.ratecard.data.repo.PurchaseRepository
import `in`.santhaliastore.ratecard.util.AppResult
import `in`.santhaliastore.ratecard.util.appResultOf
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Orchestrator that knows how to:
 *   1) Test connectivity to the Apps Script web app.
 *   2) Push pending local changes in batches.
 *   3) Schedule a [SyncWorker] for retry with exponential backoff.
 *
 * UI calls [enqueueIfPending] or [requestImmediateSync] to nudge the
 * worker; the worker then uses [pushAllPending] to do the heavy lift.
 */
class SyncRepository(
    private val context: Context,
    private val itemRepo: ItemRepository,
    private val purchaseRepo: PurchaseRepository,
    private val settings: SettingsRepository,
    private val crashRepo: CrashRepository,
    private val apiFactory: () -> AppsScriptApi
) {

    companion object {
        const val UNIQUE_WORK_NAME = "sync_pending"
        // The server caps a single bulkSync request at 200 changes.
        const val MAX_BATCH_SIZE = 200
        // Apps Script `logCrashes` action's per-call cap. Keep in sync
        // with the matching constant in apps-script/Code.gs.
        const val MAX_CRASHES_PER_CALL = 50
    }

    /* --------------------------- public API ---------------------------- */

    /**
     * Schedule a one-shot sync if there is anything pending. Safe to
     * call from any place that mutates the DB. Cheap to call repeatedly.
     */
    fun enqueueIfPending() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP, // don't pile up duplicates
            request
        )
    }

    /**
     * Like [enqueueIfPending] but force-replaces any queued work so the
     * "Sync now" button feels responsive even if a previous sync is
     * stuck waiting for connectivity.
     */
    fun requestImmediateSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Synchronous "Sync now" — bypasses WorkManager so the Settings
     * screen can render the outcome immediately.
     *
     * Marks every row pending, then pushes inline on whatever coroutine
     * dispatcher the caller provided. The result is mirrored into
     * [SettingsRepository] (`lastSyncedAt` / `lastSyncError`) so the UI
     * stays consistent whether the sync ran here or via the worker.
     *
     * Returns the count of rows the server confirmed as processed
     * (zero is fine — it just means nothing was pending).
     */
    suspend fun runFullSyncNow(): AppResult<Int> {
        // Mark first, then push. If push fails the rows stay pending
        // and the worker's next run will retry them.
        itemRepo.markAllPendingSync()
        purchaseRepo.markAllPendingSync()
        val outcome = pushAllPending()

        // Drain any pending crash reports as a separate step. We
        // explicitly do NOT fold the result into [outcome]: a crash
        // upload failure should not undo a successful items/entries
        // sync (or vice versa) — they're independent payloads with
        // independent retry semantics, and conflating them would
        // confuse the user-facing "Sync now" success/failure surface.
        runCatching { pushPendingCrashes() }

        when (outcome) {
            is AppResult.Ok -> {
                settings.setLastSyncedNow()
            }
            is AppResult.Err -> {
                settings.setLastSyncError(outcome.message)
            }
        }
        return outcome
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

    /**
     * Push every pending local change to the server in batches of
     * up to [MAX_BATCH_SIZE]. On success, clears `pendingSync` for
     * the rows the server confirmed.
     *
     * Surface errors via [AppResult.Err] so the worker can decide to
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
     *     so the worker / Settings UI can log it.
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
