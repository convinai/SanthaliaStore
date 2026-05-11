package `in`.santhaliastore.ratecard.data.repo

import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.Pager
import `in`.santhaliastore.ratecard.data.db.dao.BillDao
import `in`.santhaliastore.ratecard.data.db.entity.BillEntity
import `in`.santhaliastore.ratecard.util.Time
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Repository for supplier bills.
 *
 * `id` is generated client-side as a UUID v4 — see the sync contract —
 * so multiple devices never collide on insert.
 *
 * The repository deliberately does NOT own the image cache: writing a
 * Drive ID + local path pair is the caller's responsibility. The
 * upload pipeline (UI track) writes the local copy first, kicks off the
 * upload, and calls [updateImageState] once Drive hands back a fileId.
 */
class BillRepository(
    private val dao: BillDao,
    private val notifyChange: () -> Unit
) {

    /** All non-deleted bills, newest-first, paged. */
    fun pagedBills(): Flow<PagingData<BillEntity>> =
        Pager(PAGING_CONFIG) { dao.pagedBills() }.flow

    fun observeAll(): Flow<List<BillEntity>> = dao.observeAll()

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    fun observeActiveCount(): Flow<Int> = dao.observeActiveCount()

    suspend fun findById(id: String): BillEntity? = dao.findById(id)

    /**
     * Insert (when id is null) or update an existing bill.
     * Marks the row as `pendingSync = true` so the next sync re-pushes it.
     *
     * `imageFileIds` / `localImagePaths` are passed through verbatim —
     * trim/normalisation happens at the call site (the form layer
     * already concatenates the CSV after each successful upload).
     */
    suspend fun save(
        id: String?,
        date: String,
        supplier: String?,
        totalAmount: Double?,
        notes: String?,
        imageFileIds: String,
        localImagePaths: String
    ): BillEntity {
        val row = BillEntity(
            id = id ?: UUID.randomUUID().toString(),
            date = date,
            supplier = supplier?.trim()?.takeIf { it.isNotEmpty() },
            totalAmount = totalAmount,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            imageFileIds = imageFileIds,
            localImagePaths = localImagePaths,
            updatedAt = Time.nowIso(),
            deleted = false,
            pendingSync = true
        )
        dao.upsert(row)
        notifyChange()
        return row
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, Time.nowIso())
        notifyChange()
    }

    /**
     * Update just the image-state columns after an out-of-band upload
     * (or download-into-cache) completes.
     *
     * Important: `imageFileIds` is part of the sync DTO, so when it
     * changes we DO need `pendingSync = true` so the new Drive IDs
     * propagate to the sheet and other devices. The "feels separate"
     * intuition — that an image upload is independent from the bill
     * row — doesn't hold once you remember the IDs themselves are
     * data. So this method behaves exactly like a tiny [save]:
     * bumps `updatedAt`, sets `pendingSync = true`, and pings the
     * auto-sync trigger.
     *
     * `localImagePaths` is local-only and won't be serialised, but
     * keeping it on the same write makes the in-memory row coherent
     * for any observer that's mid-collect.
     */
    suspend fun updateImageState(
        id: String,
        imageFileIds: String,
        localImagePaths: String
    ): BillEntity? {
        val existing = dao.findById(id) ?: return null
        val updated = existing.copy(
            imageFileIds = imageFileIds,
            localImagePaths = localImagePaths,
            updatedAt = Time.nowIso(),
            pendingSync = true
        )
        dao.upsert(updated)
        notifyChange()
        return updated
    }

    suspend fun pendingSync(): List<BillEntity> = dao.pendingSync()

    suspend fun clearPendingSync(ids: List<String>) {
        if (ids.isEmpty()) return
        dao.clearPendingSync(ids)
    }

    /**
     * Mark every bill row pending so the next sync pushes the entire
     * bill history to the server. Used by the manual "Sync now" path;
     * the worker handles the actual upload.
     */
    suspend fun markAllPendingSync() {
        dao.markAllPending()
        notifyChange()
    }

    /**
     * Wipe every bill row. Destructive — only callable from the
     * Settings → "Reset local data" recovery action, which wraps this
     * alongside the matching items / entries wipes inside a single
     * Room transaction.
     */
    suspend fun deleteAll() {
        dao.deleteAll()
    }

    companion object {
        // Smaller page size than the items list because each bill row
        // renders an image thumbnail. On a 2 GB phone, materialising
        // 30 image loads per page is enough to stutter scrolling; 20
        // keeps the work band comfortable while still filling a
        // typical 5" viewport (~6 rows visible) twice over.
        internal val PAGING_CONFIG = PagingConfig(
            pageSize = 20,
            initialLoadSize = 40,
            prefetchDistance = 10,
            enablePlaceholders = false
        )
    }
}
