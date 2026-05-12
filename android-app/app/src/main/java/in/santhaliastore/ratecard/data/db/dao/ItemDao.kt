package `in`.santhaliastore.ratecard.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.ItemWithLastEntry
import kotlinx.coroutines.flow.Flow

/**
 * DAO for items + the FTS-backed search.
 *
 * The list-with-last-entry query uses a correlated subquery to fetch
 * just the latest non-deleted entry per item. SQLite handles this
 * efficiently because both `itemCode` and `date` are indexed.
 */
@Dao
interface ItemDao {

    /* ----------------------------- writes ----------------------------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ItemEntity>)

    @Update
    suspend fun update(item: ItemEntity)

    @Query("UPDATE items SET deleted = 1, pendingSync = 1, updatedAt = :updatedAt WHERE code = :code")
    suspend fun softDelete(code: String, updatedAt: String)

    @Query("UPDATE items SET pendingSync = 0 WHERE code IN (:codes)")
    suspend fun clearPendingSync(codes: List<String>)

    /**
     * Mark every row pending. Used by the manual "Sync now" path so the
     * Google Sheet ends up with a full snapshot of the rate card,
     * including rows that were synced previously and would otherwise
     * be skipped by the incremental worker.
     *
     * Includes soft-deleted rows so a previously-deleted item that
     * never made it to the server still propagates its tombstone.
     */
    @Query("UPDATE items SET pendingSync = 1")
    suspend fun markAllPending()

    /**
     * Wipe every row from the items table.
     *
     * Used by the destructive "Reset local data" recovery action in
     * Settings — the user is acknowledging that the phone's view has
     * drifted from the sheet's view (e.g. they manually cleared the
     * sheet) and wants a clean re-pull. Room keeps `items_fts` in sync
     * automatically because the FTS entity declares `contentEntity =
     * ItemEntity::class`, so we don't need a separate FTS purge.
     *
     * Caller is responsible for clearing dependent rows BEFORE this
     * runs (the FK on `purchase_entries.itemCode` is NO_ACTION, so
     * dangling entries would otherwise stay alive but orphaned).
     */
    @Query("DELETE FROM items")
    suspend fun deleteAll()

    /* ----------------------------- reads ------------------------------ */

    @Query("SELECT * FROM items WHERE code = :code LIMIT 1")
    suspend fun findByCode(code: String): ItemEntity?

    @Query("SELECT * FROM items WHERE code = :code LIMIT 1")
    fun observeByCode(code: String): Flow<ItemEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE code = :code AND deleted = 0)")
    suspend fun existsActive(code: String): Boolean

    @Query("SELECT COUNT(*) FROM items WHERE deleted = 0")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM items WHERE pendingSync = 1")
    suspend fun pendingSync(): List<ItemEntity>

    /**
     * Reactive count of rows still waiting to be synced. Surfaced in
     * Settings so the user can see at a glance how many items the next
     * sync will push.
     */
    @Query("SELECT COUNT(*) FROM items WHERE pendingSync = 1")
    fun observePendingCount(): Flow<Int>

    /**
     * Paged list of items with their most recent non-deleted entry.
     *
     * Sort: pinned by `name COLLATE NOCASE` so the list reads
     * alphabetically regardless of casing.
     *
     * "Most recent entry" is picked in TWO steps so the projected row
     * is atomic — i.e. price and date always come from the same entry:
     *
     *   1. Inner correlated subquery returns ONE `entryId` per item,
     *      ordered by `date DESC, updatedAt DESC, entryId DESC`.
     *      The `entryId DESC` is a deterministic tertiary tiebreaker:
     *      two entries with the same purchase date and the same
     *      `updatedAt` (e.g. created in the same millisecond by a
     *      batch upsert) used to leave the order undefined, which
     *      allowed two separate subqueries — one for price, one for
     *      date — to disagree on which row to pick. With a single
     *      lookup the question can't arise.
     *
     *   2. Outer `LEFT JOIN purchase_entries pe ON pe.entryId = (...)`
     *      pulls every projected column from that single row. LEFT
     *      JOIN keeps items with zero entries (their `lastPrice` /
     *      `lastDate` come back as NULL, which the UI renders as
     *      "Abhi rate nahi").
     */
    @Transaction
    @Query(
        """
        SELECT
            i.code AS code,
            i.name AS name,
            i.unit AS unit,
            pe.pricePerUnit AS lastPrice,
            pe.date AS lastDate,
            i.pendingSync AS pendingSync
        FROM items i
        LEFT JOIN purchase_entries pe ON pe.entryId = (
            SELECT pe2.entryId FROM purchase_entries pe2
            WHERE pe2.itemCode = i.code AND pe2.deleted = 0
            ORDER BY pe2.date DESC, pe2.updatedAt DESC, pe2.entryId DESC LIMIT 1
        )
        WHERE i.deleted = 0
        ORDER BY i.name COLLATE NOCASE ASC
        """
    )
    fun pagedItemsWithLastEntry(): PagingSource<Int, ItemWithLastEntry>

    /**
     * Same projection but filtered through FTS. Room's MATCH operator
     * lights up the FTS index on `items_fts` and then we join back to
     * the content table to apply soft-delete filters.
     *
     * The query is appended with `*` by the caller (see ItemRepository)
     * so partial-prefix searches work.
     *
     * "Most recent entry" pick mirrors [pagedItemsWithLastEntry] — same
     * two-step entryId lookup so price + date always come from one row.
     */
    @Transaction
    @Query(
        """
        SELECT
            i.code AS code,
            i.name AS name,
            i.unit AS unit,
            pe.pricePerUnit AS lastPrice,
            pe.date AS lastDate,
            i.pendingSync AS pendingSync
        FROM items i
        LEFT JOIN purchase_entries pe ON pe.entryId = (
            SELECT pe2.entryId FROM purchase_entries pe2
            WHERE pe2.itemCode = i.code AND pe2.deleted = 0
            ORDER BY pe2.date DESC, pe2.updatedAt DESC, pe2.entryId DESC LIMIT 1
        )
        WHERE i.deleted = 0
          AND i.code IN (SELECT code FROM items_fts WHERE items_fts MATCH :query)
        ORDER BY i.name COLLATE NOCASE ASC
        """
    )
    fun searchItemsWithLastEntry(query: String): PagingSource<Int, ItemWithLastEntry>
}
