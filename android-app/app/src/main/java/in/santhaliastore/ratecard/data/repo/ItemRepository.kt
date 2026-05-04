package `in`.santhaliastore.ratecard.data.repo

import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.Pager
import `in`.santhaliastore.ratecard.data.db.dao.ItemDao
import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.ItemWithLastEntry
import `in`.santhaliastore.ratecard.util.Time
import kotlinx.coroutines.flow.Flow

/**
 * Repository on top of [ItemDao]. Owns the small bits of business logic
 * that don't belong in the DAO (paging config, FTS query escaping,
 * timestamp generation, sync trigger).
 *
 * The repo intentionally has no Android dependencies so it stays easy
 * to unit-test.
 */
class ItemRepository(
    private val dao: ItemDao,
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

    suspend fun pendingSync(): List<ItemEntity> = dao.pendingSync()

    suspend fun clearPendingSync(codes: List<String>) {
        if (codes.isEmpty()) return
        dao.clearPendingSync(codes)
    }

    /**
     * Take a raw user query and convert it into a safe FTS4 expression.
     *
     * - Strip characters FTS4 treats specially (-, ", *, etc.) so a
     *   user typing "5kg" doesn't accidentally produce a phrase token.
     * - Split on whitespace, drop empties, append `*` to each token
     *   so partial typing matches.
     */
    private fun toFtsPrefixQuery(raw: String): String {
        val cleaned = raw
            .lowercase()
            .filter { it.isLetterOrDigit() || it == ' ' }
            .trim()
        if (cleaned.isEmpty()) return "\"\""
        return cleaned.split(Regex("\\s+")).joinToString(" ") { token ->
            "$token*"
        }
    }

    private companion object {
        // Page size tuned for low-end devices: we want enough rows to
        // fill the viewport on cheap 5" phones (~9 rows visible) but
        // not so many that scrolling causes a spike.
        val PAGING_CONFIG = PagingConfig(
            pageSize = 30,
            initialLoadSize = 60,
            prefetchDistance = 15,
            enablePlaceholders = false
        )
    }
}
