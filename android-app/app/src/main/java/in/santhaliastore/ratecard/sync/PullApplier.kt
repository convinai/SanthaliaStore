package `in`.santhaliastore.ratecard.sync

import androidx.room.withTransaction
import `in`.santhaliastore.ratecard.data.db.AppDatabase
import `in`.santhaliastore.ratecard.data.db.dao.BillDao
import `in`.santhaliastore.ratecard.data.db.dao.ItemDao
import `in`.santhaliastore.ratecard.data.db.dao.PurchaseEntryDao
import `in`.santhaliastore.ratecard.data.db.entity.BillEntity
import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity
import `in`.santhaliastore.ratecard.util.Time

/**
 * Applies a [PullChangesResponse] to Room atomically.
 *
 * The whole apply runs inside a single `database.withTransaction` so a
 * crash mid-write leaves the local DB exactly as it was — partial
 * application would leave orphan entries (FK on `entries.itemCode -> items.code`)
 * and a stale cursor would then skip the missing rows forever.
 *
 * Conflict policy is last-writer-wins keyed on the device-local
 * `updatedAt` ISO 8601 timestamp:
 *   - No local row     → insert the pulled row, `pendingSync = false`.
 *   - `pulled.updatedAt >= existing.updatedAt` → overwrite, `pendingSync = false`.
 *   - `pulled.updatedAt <  existing.updatedAt` → skip; the local row
 *     has a newer pending edit and the next push will resolve it.
 *
 * Soft-deletes ride along: a pulled row with `deleted = true` becomes
 * a tombstone locally if the timestamp wins, propagating the deletion.
 *
 * Order matters: we apply items BEFORE entries inside the same
 * transaction so that a freshly-pulled entry pointing at a freshly-pulled
 * item never hits an FK violation (the entry's `itemCode` parent
 * already exists by the time we insert the entry). Bills go last —
 * they have no FK relationship with items or entries so ordering is
 * cosmetic, but keeping them at the tail makes the per-domain counts
 * read naturally in the log: items, then entries, then bills.
 */
class PullApplier(
    private val database: AppDatabase,
    private val itemDao: ItemDao,
    private val entryDao: PurchaseEntryDao,
    private val billDao: BillDao
) {

    suspend fun apply(response: PullChangesResponse): PullOutcome {
        var itemsApplied = 0
        var entriesApplied = 0
        var billsApplied = 0
        // Count of pulled entries whose parent item is absent both in
        // the pulled batch and locally — see the FK pre-check in the
        // entries loop for why this happens and why we silently skip
        // rather than fail the whole transaction.
        var orphansSkipped = 0

        // Snapshot the apply-time clock so every row written in this
        // batch uses the SAME fallback `updatedAt` when normalisation
        // can't recover the original timestamp. Keeps the LWW story
        // stable within a single pull.
        val applyClock = Time.nowIso()

        database.withTransaction {
            // 1) Items first so FK target exists before any entry insert.
            for (pulled in response.items) {
                // Normalise BEFORE the conflict check. The corrupt
                // pre-v1.0.3 `"Wed May 06 2026 …"` shape starts with
                // `'W'`, which lex-sorts above any digit — so a raw
                // comparison would always declare the pulled row newer
                // and silently re-corrupt local data we just repaired.
                // We coerce to canonical ISO before the LWW check
                // (apply-time clock as the fallback when the original
                // can't be recovered) so the comparison is sound.
                val normalisedPulled = pulled.copy(
                    updatedAt = Time.normalizeIsoTimestamp(pulled.updatedAt) ?: applyClock
                )
                val existing = itemDao.findByCode(normalisedPulled.code)
                if (shouldApplyItem(existing, normalisedPulled)) {
                    itemDao.upsert(
                        ItemEntity(
                            code = normalisedPulled.code,
                            name = normalisedPulled.name,
                            unit = normalisedPulled.unit,
                            updatedAt = normalisedPulled.updatedAt,
                            deleted = normalisedPulled.deleted,
                            // Pulled rows are server-authoritative — nothing
                            // to push back. If we left this as `true` we'd
                            // immediately push it back next sync, wasting
                            // bandwidth and potentially racing the server's
                            // own newer copy.
                            pendingSync = false
                        )
                    )
                    itemsApplied++
                }
            }

            // 2) Entries second. The `findById` lookup is per-row but the
            // pull payload caps server-side, so we're not paying for an
            // unbounded fan-out.
            for (pulled in response.entries) {
                // FK pre-check. The Apps Script's `collectChangedRows_`
                // intentionally drops tombstone rows from the response,
                // but it drops them per-sheet — a soft-deleted item is
                // omitted from `items[]`, while entries still attached
                // to that item's `code` continue to appear in
                // `entries[]`. Inserting such an orphan into Room
                // throws SQLITE_CONSTRAINT_FOREIGNKEY (code 787) and
                // aborts the entire transaction, which on a fresh
                // install means NOTHING applies and every subsequent
                // sync re-fails the same way.
                //
                // Skipping orphans is the right user-facing outcome:
                // the item that owned this history is gone, so the
                // history has no home to render under. The skipped
                // count is rolled up in [PullOutcome.orphansSkipped]
                // so the sync summary can surface it — and so the
                // server-side cleanup ticket has something concrete
                // to count.
                val parent = itemDao.findByCode(pulled.itemCode)
                if (parent == null) {
                    orphansSkipped++
                    continue
                }

                // Same locale-dump defence as items. `date` is repaired
                // alongside `updatedAt` because it drives the home-row
                // `ORDER BY date DESC` pick — leaving even one
                // unrepaired `"Wed May …"` string in the column would
                // reintroduce the user's original bug. Fallback for
                // an unparseable date: keep the raw value. We can't
                // safely fabricate a calendar date the way we can a
                // timestamp; better to surface the weirdness than to
                // silently shift the purchase day. The v3→v4 migration
                // is the safety net for those edge cases (it runs on
                // upgrade, not on pull).
                val normalisedPulled = pulled.copy(
                    date = Time.normalizeLocalDate(pulled.date) ?: pulled.date,
                    updatedAt = Time.normalizeIsoTimestamp(pulled.updatedAt) ?: applyClock
                )
                val existing = entryDao.findById(normalisedPulled.entryId)
                if (shouldApplyEntry(existing, normalisedPulled)) {
                    entryDao.upsert(
                        PurchaseEntryEntity(
                            entryId = normalisedPulled.entryId,
                            itemCode = normalisedPulled.itemCode,
                            date = normalisedPulled.date,
                            pricePerUnit = normalisedPulled.pricePerUnit,
                            quantity = normalisedPulled.quantity,
                            supplier = normalisedPulled.supplier,
                            notes = normalisedPulled.notes,
                            updatedAt = normalisedPulled.updatedAt,
                            deleted = normalisedPulled.deleted,
                            pendingSync = false
                        )
                    )
                    entriesApplied++
                }
            }

            // 3) Bills last. No FK to satisfy — the table stands alone —
            //    so ordering is purely cosmetic. We preserve the local
            //    `localImagePaths` value on an upsert (server never sends
            //    it; overwriting with empty would orphan the on-disk
            //    cache) and only touch the sync-driven columns.
            for (pulled in response.bills) {
                val existing = billDao.findById(pulled.id)
                if (shouldApplyBill(existing, pulled)) {
                    billDao.upsert(
                        BillEntity(
                            id = pulled.id,
                            date = pulled.date,
                            supplier = pulled.supplier,
                            totalAmount = pulled.totalAmount,
                            notes = pulled.notes,
                            imageFileIds = pulled.imageFileIds,
                            // Preserve any existing local cache mapping.
                            // A pull never knows about local file paths,
                            // so blanking this would destroy the offline
                            // viewer's index into the image cache.
                            localImagePaths = existing?.localImagePaths.orEmpty(),
                            updatedAt = pulled.updatedAt,
                            deleted = pulled.deleted,
                            pendingSync = false
                        )
                    )
                    billsApplied++
                }
            }
        }

        return PullOutcome(
            itemsApplied = itemsApplied,
            entriesApplied = entriesApplied,
            billsApplied = billsApplied,
            orphansSkipped = orphansSkipped
        )
    }
}

/**
 * Last-writer-wins on `updatedAt`. Lives at the package top level so
 * JVM unit tests can drive the conflict matrix without standing up
 * Room or constructing [PullApplier].
 *
 * Strings compare correctly because we use a fixed-width ISO 8601
 * format with `Z` suffix everywhere (see
 * [in.santhaliastore.ratecard.util.Time]).
 *
 * Tie behaviour (`pulled.updatedAt == existing.updatedAt`): the pulled
 * row wins. The alternative — local wins on tie — would silently
 * suppress server-emitted soft-deletes in the rare case the server
 * timestamp matches the local one to the millisecond.
 */
internal fun shouldApplyItem(existing: ItemEntity?, pulled: PulledItem): Boolean {
    if (existing == null) return true
    return pulled.updatedAt >= existing.updatedAt
}

internal fun shouldApplyEntry(existing: PurchaseEntryEntity?, pulled: PulledEntry): Boolean {
    if (existing == null) return true
    return pulled.updatedAt >= existing.updatedAt
}

internal fun shouldApplyBill(existing: BillEntity?, pulled: PulledBill): Boolean {
    if (existing == null) return true
    return pulled.updatedAt >= existing.updatedAt
}

/**
 * Counts of rows that actually changed local state. Skip-because-local-newer
 * rows are NOT counted — they're invisible to the user.
 *
 * `orphansSkipped` is the count of pulled entries whose parent item
 * was missing locally and in the same batch — see PullApplier's FK
 * pre-check. Defaults to 0 so older call sites and tests that
 * pre-date the field still compile.
 */
data class PullOutcome(
    val itemsApplied: Int,
    val entriesApplied: Int,
    val billsApplied: Int = 0,
    val orphansSkipped: Int = 0
)
