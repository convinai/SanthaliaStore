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
     * Paged list of items with their most recent non-deleted entry.
     *
     * Sort: pinned by `name COLLATE NOCASE` so the list reads
     * alphabetically regardless of casing.
     */
    @Transaction
    @Query(
        """
        SELECT
            i.code AS code,
            i.name AS name,
            i.unit AS unit,
            (SELECT pe.pricePerUnit FROM purchase_entries pe
             WHERE pe.itemCode = i.code AND pe.deleted = 0
             ORDER BY pe.date DESC, pe.updatedAt DESC LIMIT 1) AS lastPrice,
            (SELECT pe.date FROM purchase_entries pe
             WHERE pe.itemCode = i.code AND pe.deleted = 0
             ORDER BY pe.date DESC, pe.updatedAt DESC LIMIT 1) AS lastDate,
            i.pendingSync AS pendingSync
        FROM items i
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
     */
    @Transaction
    @Query(
        """
        SELECT
            i.code AS code,
            i.name AS name,
            i.unit AS unit,
            (SELECT pe.pricePerUnit FROM purchase_entries pe
             WHERE pe.itemCode = i.code AND pe.deleted = 0
             ORDER BY pe.date DESC, pe.updatedAt DESC LIMIT 1) AS lastPrice,
            (SELECT pe.date FROM purchase_entries pe
             WHERE pe.itemCode = i.code AND pe.deleted = 0
             ORDER BY pe.date DESC, pe.updatedAt DESC LIMIT 1) AS lastDate,
            i.pendingSync AS pendingSync
        FROM items i
        WHERE i.deleted = 0
          AND i.code IN (SELECT code FROM items_fts WHERE items_fts MATCH :query)
        ORDER BY i.name COLLATE NOCASE ASC
        """
    )
    fun searchItemsWithLastEntry(query: String): PagingSource<Int, ItemWithLastEntry>
}
