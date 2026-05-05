package `in`.santhaliastore.ratecard.sync

import androidx.room.withTransaction
import `in`.santhaliastore.ratecard.data.db.AppDatabase
import `in`.santhaliastore.ratecard.data.db.dao.ItemDao
import `in`.santhaliastore.ratecard.data.db.dao.PurchaseEntryDao
import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity

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
 * already exists by the time we insert the entry).
 */
class PullApplier(
    private val database: AppDatabase,
    private val itemDao: ItemDao,
    private val entryDao: PurchaseEntryDao
) {

    suspend fun apply(response: PullChangesResponse): PullOutcome {
        var itemsApplied = 0
        var entriesApplied = 0

        database.withTransaction {
            // 1) Items first so FK target exists before any entry insert.
            for (pulled in response.items) {
                val existing = itemDao.findByCode(pulled.code)
                if (shouldApplyItem(existing, pulled)) {
                    itemDao.upsert(
                        ItemEntity(
                            code = pulled.code,
                            name = pulled.name,
                            unit = pulled.unit,
                            updatedAt = pulled.updatedAt,
                            deleted = pulled.deleted,
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
                val existing = entryDao.findById(pulled.entryId)
                if (shouldApplyEntry(existing, pulled)) {
                    entryDao.upsert(
                        PurchaseEntryEntity(
                            entryId = pulled.entryId,
                            itemCode = pulled.itemCode,
                            date = pulled.date,
                            pricePerUnit = pulled.pricePerUnit,
                            quantity = pulled.quantity,
                            supplier = pulled.supplier,
                            notes = pulled.notes,
                            updatedAt = pulled.updatedAt,
                            deleted = pulled.deleted,
                            pendingSync = false
                        )
                    )
                    entriesApplied++
                }
            }
        }

        return PullOutcome(itemsApplied = itemsApplied, entriesApplied = entriesApplied)
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

/**
 * Counts of rows that actually changed local state. Skip-because-local-newer
 * rows are NOT counted — they're invisible to the user.
 */
data class PullOutcome(
    val itemsApplied: Int,
    val entriesApplied: Int
)
