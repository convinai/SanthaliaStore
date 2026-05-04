package `in`.santhaliastore.ratecard.data.repo

import `in`.santhaliastore.ratecard.data.db.dao.PurchaseEntryDao
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity
import `in`.santhaliastore.ratecard.util.Time
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Repository for purchase entries.
 *
 * `entryId` is generated client-side as a UUID v4 — see the sync
 * contract — so multiple devices never collide on insert.
 */
class PurchaseRepository(
    private val dao: PurchaseEntryDao,
    private val notifyChange: () -> Unit
) {

    fun observeForItem(itemCode: String): Flow<List<PurchaseEntryEntity>> =
        dao.observeForItem(itemCode)

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    suspend fun findById(entryId: String): PurchaseEntryEntity? = dao.findById(entryId)

    /**
     * Insert (when entryId is null) or update an existing entry.
     * Marks the row as `pendingSync = true` so the worker re-syncs.
     */
    suspend fun save(
        entryId: String?,
        itemCode: String,
        date: String,
        pricePerUnit: Double,
        quantity: Double?,
        supplier: String?,
        notes: String?
    ): PurchaseEntryEntity {
        val row = PurchaseEntryEntity(
            entryId = entryId ?: UUID.randomUUID().toString(),
            itemCode = itemCode,
            date = date,
            pricePerUnit = pricePerUnit,
            quantity = quantity,
            supplier = supplier?.trim()?.takeIf { it.isNotEmpty() },
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = Time.nowIso(),
            deleted = false,
            pendingSync = true
        )
        dao.upsert(row)
        notifyChange()
        return row
    }

    suspend fun softDelete(entryId: String) {
        dao.softDelete(entryId, Time.nowIso())
        notifyChange()
    }

    suspend fun pendingSync(): List<PurchaseEntryEntity> = dao.pendingSync()

    suspend fun clearPendingSync(ids: List<String>) {
        if (ids.isEmpty()) return
        dao.clearPendingSync(ids)
    }
}
