package `in`.santhaliastore.ratecard.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for purchase entries. The "history" view sorts by date desc so the
 * most recent purchase floats to the top of the item detail screen.
 */
@Dao
interface PurchaseEntryDao {

    /* ----------------------------- writes ----------------------------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PurchaseEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<PurchaseEntryEntity>)

    @Update
    suspend fun update(entry: PurchaseEntryEntity)

    @Query("UPDATE purchase_entries SET deleted = 1, pendingSync = 1, updatedAt = :updatedAt WHERE entryId = :entryId")
    suspend fun softDelete(entryId: String, updatedAt: String)

    @Query("UPDATE purchase_entries SET pendingSync = 0 WHERE entryId IN (:ids)")
    suspend fun clearPendingSync(ids: List<String>)

    /**
     * Mark every row pending. Used by the manual "Sync now" path so the
     * Google Sheet ends up with the full purchase history. Includes
     * soft-deleted rows so unsynced tombstones still propagate.
     */
    @Query("UPDATE purchase_entries SET pendingSync = 1")
    suspend fun markAllPending()

    /* ----------------------------- reads ------------------------------ */

    @Query("SELECT * FROM purchase_entries WHERE entryId = :entryId LIMIT 1")
    suspend fun findById(entryId: String): PurchaseEntryEntity?

    @Query(
        """
        SELECT * FROM purchase_entries
        WHERE itemCode = :itemCode AND deleted = 0
        ORDER BY date DESC, updatedAt DESC
        """
    )
    fun observeForItem(itemCode: String): Flow<List<PurchaseEntryEntity>>

    @Query(
        """
        SELECT * FROM purchase_entries
        WHERE itemCode = :itemCode AND deleted = 0
        ORDER BY date DESC, updatedAt DESC LIMIT 1
        """
    )
    suspend fun latestForItem(itemCode: String): PurchaseEntryEntity?

    @Query("SELECT * FROM purchase_entries WHERE pendingSync = 1")
    suspend fun pendingSync(): List<PurchaseEntryEntity>

    @Query("SELECT COUNT(*) FROM purchase_entries WHERE pendingSync = 1")
    fun observePendingCount(): Flow<Int>
}
