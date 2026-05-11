package `in`.santhaliastore.ratecard.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import `in`.santhaliastore.ratecard.data.db.entity.BillEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for supplier bills. The list view sorts by `date DESC, updatedAt
 * DESC` so the most recent bill floats to the top — matching how the
 * owner thinks about "the bill I just saved".
 */
@Dao
interface BillDao {

    /* ----------------------------- writes ----------------------------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bill: BillEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bills: List<BillEntity>)

    @Update
    suspend fun update(bill: BillEntity)

    @Query("UPDATE bills SET deleted = 1, pendingSync = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, updatedAt: String)

    @Query("UPDATE bills SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun clearPendingSync(ids: List<String>)

    /**
     * Mark every row pending. Used by the manual "Sync now" path so the
     * Google Sheet ends up with the full bill history. Includes
     * soft-deleted rows so unsynced tombstones still propagate.
     */
    @Query("UPDATE bills SET pendingSync = 1")
    suspend fun markAllPending()

    /**
     * Wipe every row from the bills table.
     *
     * Used by the destructive "Reset local data" recovery action in
     * Settings. Bills have no foreign-key relationship with items or
     * entries so order is irrelevant — but we still want the wipe to
     * sit inside the same Room transaction as the items/entries wipe
     * so the user sees a single all-or-nothing reset.
     *
     * Cached image files on disk are cleaned up out-of-band by the
     * image cache manager (UI track) — wiping the DB row alone is
     * enough to make the bills disappear from the user's perspective.
     */
    @Query("DELETE FROM bills")
    suspend fun deleteAll()

    /* ----------------------------- reads ------------------------------ */

    @Query("SELECT * FROM bills WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BillEntity?

    @Query(
        """
        SELECT * FROM bills
        WHERE deleted = 0
        ORDER BY date DESC, updatedAt DESC
        """
    )
    fun observeAll(): Flow<List<BillEntity>>

    /**
     * Substring-match search over supplier + notes. The caller passes a
     * pre-formed LIKE pattern (e.g. `%reliance%`) so the % wildcards
     * stay out of the binding and we keep the DAO contract simple.
     *
     * SQL LIKE is sufficient here — bills are sparse (a few per week at
     * most) so even a full-table scan finishes in microseconds. No FTS
     * because the index cost wouldn't pay back at this volume.
     *
     * `pattern` is case-insensitive because Room maps LIKE to SQLite's
     * NOCASE-by-default behaviour for ASCII. Devanagari / non-ASCII
     * collation would need an explicit NOCASE collation; we accept the
     * limitation because every kirana supplier in the user's contact
     * list is named in Roman script in the app today.
     */
    @Query(
        """
        SELECT * FROM bills
        WHERE deleted = 0
          AND (
            (supplier IS NOT NULL AND supplier LIKE :pattern) OR
            (notes IS NOT NULL AND notes LIKE :pattern)
          )
        ORDER BY date DESC, updatedAt DESC
        """
    )
    fun observeMatching(pattern: String): Flow<List<BillEntity>>

    /**
     * Paged version of [observeAll]. Backs the Bills list screen. Page
     * size is configured smaller than the items list (see
     * BillRepository.PAGING_CONFIG) because each row renders a thumbnail
     * and we don't want to materialise 30 image loads at once on a 2 GB
     * phone.
     */
    @Query(
        """
        SELECT * FROM bills
        WHERE deleted = 0
        ORDER BY date DESC, updatedAt DESC
        """
    )
    fun pagedBills(): PagingSource<Int, BillEntity>

    @Query("SELECT * FROM bills WHERE pendingSync = 1")
    suspend fun pendingSync(): List<BillEntity>

    /**
     * Reactive count of rows still waiting to be synced. Surfaced in
     * Settings so the user can see at a glance how many bills the next
     * sync will push.
     */
    @Query("SELECT COUNT(*) FROM bills WHERE pendingSync = 1")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM bills WHERE deleted = 0")
    fun observeActiveCount(): Flow<Int>
}
