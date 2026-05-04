package `in`.santhaliastore.ratecard.data.repo

import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.Pager
import androidx.room.withTransaction
import `in`.santhaliastore.ratecard.data.db.AppDatabase
import `in`.santhaliastore.ratecard.data.db.dao.ItemDao
import `in`.santhaliastore.ratecard.data.db.dao.PurchaseEntryDao
import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.ItemWithLastEntry
import `in`.santhaliastore.ratecard.util.Time
import kotlinx.coroutines.flow.Flow

/**
 * Repository on top of [ItemDao]. Owns the small bits of business logic
 * that don't belong in the DAO (paging config, FTS query escaping,
 * timestamp generation, sync trigger).
 *
 * Also reaches into [PurchaseEntryDao] for the atomic code-rename flow
 * — renaming an item's code must move the item row + repoint every
 * entry's `itemCode` in a single Room transaction, otherwise the
 * purchase history orphans under the soft-deleted old code and the UI
 * (which filters out soft-deletes) shows the renamed item as having no
 * history.
 */
class ItemRepository(
    private val dao: ItemDao,
    private val purchaseEntryDao: PurchaseEntryDao,
    private val database: AppDatabase,
    private val notifyChange: () -> Unit
) {

    /** All non-deleted items, alphabetic, paged. */
    fun pagedItems(): Flow<PagingData<ItemWithLastEntry>> =
        Pager(PAGING_CONFIG) { dao.pagedItemsWithLastEntry() }.flow

    /**
     * FTS-backed paged search. We normalise the query and append `*`
     * so prefix matches work ("ric" -> "ric*" -> finds "rice").
     */
    fun searchItems(rawQuery: String): Flow<PagingData<ItemWithLastEntry>> {
        val ftsQuery = toFtsPrefixQuery(rawQuery)
        return Pager(PAGING_CONFIG) { dao.searchItemsWithLastEntry(ftsQuery) }.flow
    }

    fun observeItem(code: String): Flow<ItemEntity?> = dao.observeByCode(code)

    fun observeActiveCount(): Flow<Int> = dao.observeActiveCount()

    /** Reactive count of items waiting on the next sync. */
    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    suspend fun findByCode(code: String): ItemEntity? = dao.findByCode(code)

    suspend fun existsActive(code: String): Boolean = dao.existsActive(code)

    /**
     * Insert or update. Always sets `pendingSync = true` and bumps
     * `updatedAt`. Returns the canonical row that was written.
     */
    suspend fun save(code: String, name: String, unit: String?): ItemEntity {
        val row = ItemEntity(
            code = code.trim(),
            name = name.trim(),
            unit = unit?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = Time.nowIso(),
            deleted = false,
            pendingSync = true
        )
        dao.upsert(row)
        notifyChange()
        return row
    }

    suspend fun softDelete(code: String) {
        dao.softDelete(code, Time.nowIso())
        notifyChange()
    }

    /**
     * Atomic rename: change an item's primary key from [oldCode] to
     * [newCode] while preserving all of its purchase history.
     *
     * The whole sequence runs inside a single Room transaction so an
     * interruption (process kill, OOM, IO failure) leaves the database
     * exactly as it was. Order matters because the FK on
     * `purchase_entries.itemCode -> items.code` must remain satisfied at
     * every step:
     *
     *   1. Insert the new item row first (FK target now exists).
     *   2. Repoint every active entry from `oldCode` to `newCode` and
     *      flag them `pendingSync = 1` so the cloud sheet catches up.
     *   3. Soft-delete the old item row last (still references nothing
     *      live, but keeps a tombstone for sync to propagate).
     *
     * Note on conflicts: if a soft-deleted row already exists at
     * [newCode], `dao.upsert` (REPLACE on conflict) overwrites it. That
     * is intentional — reusing a previously-deleted code revives the
     * row with the new content rather than rejecting the rename.
     * Active duplicates are blocked upstream in the ViewModel's
     * `existsActive` check.
     *
     * @return the freshly-written [ItemEntity] under the new code.
     */
    suspend fun renameCode(
        oldCode: String,
        newCode: String,
        name: String,
        unit: String?
    ): ItemEntity {
        val now = Time.nowIso()
        val trimmedNewCode = newCode.trim()
        val newRow = ItemEntity(
            code = trimmedNewCode,
            name = name.trim(),
            unit = unit?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = now,
            deleted = false,
            pendingSync = true
        )
        database.withTransaction {
            // 1. New item first so the FK target exists before we
            //    repoint entries onto it.
            dao.upsert(newRow)
            // 2. Move all live entries onto the new code; each touched
            //    row is flipped to pending so sync re-uploads them.
            purchaseEntryDao.repointItemCode(
                oldCode = oldCode,
                newCode = trimmedNewCode,
                updatedAt = now
            )
            // 3. Tombstone the old item row last. After this the old
            //    code has no live entries pointing at it.
            dao.softDelete(oldCode, now)
        }
        notifyChange()
        return newRow
    }

    suspend fun pendingSync(): List<ItemEntity> = dao.pendingSync()

    suspend fun clearPendingSync(codes: List<String>) {
        if (codes.isEmpty()) return
        dao.clearPendingSync(codes)
    }

    /**
     * Mark every item row as pending so the next sync pushes them all.
     * Called by the manual "Sync now" path; the sync worker runs the
     * actual upload on a network constraint.
     */
    suspend fun markAllPendingSync() {
        dao.markAllPending()
        notifyChange()
    }

    companion object {
        // Page size tuned for low-end devices: we want enough rows to
        // fill the viewport on cheap 5" phones (~9 rows visible) but
        // not so many that scrolling causes a spike.
        internal val PAGING_CONFIG = PagingConfig(
            pageSize = 30,
            initialLoadSize = 60,
            prefetchDistance = 15,
            enablePlaceholders = false
        )

        /**
         * Take a raw user query and convert it into a safe FTS4 expression.
         *
         * - Strip characters FTS4 treats specially (-, ", *, etc.) so a
         *   user typing "5kg" doesn't accidentally produce a phrase token.
         * - Split on whitespace, drop empties, append `*` to each token
         *   so partial typing matches.
         *
         * Lifted out of the instance so unit tests can exercise it
         * without touching Room.
         */
        fun toFtsPrefixQuery(raw: String): String {
            val cleaned = raw
                .lowercase()
                .filter { it.isLetterOrDigit() || it == ' ' }
                .trim()
            if (cleaned.isEmpty()) return "\"\""
            return cleaned.split(Regex("\\s+")).joinToString(" ") { token ->
                "$token*"
            }
        }
    }
}
