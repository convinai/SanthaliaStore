package `in`.santhaliastore.ratecard.data.db.entity

import androidx.compose.runtime.Immutable

/**
 * Projection used by the home screen list. Combines an item with its
 * single most recent (non-deleted) purchase entry — enough to render
 * the row without a second query per item.
 *
 * Built by a JOIN inside [ItemDao.observeItemsWithLastEntry] / the
 * paged search. Marked `@Immutable` so Compose can skip rows that
 * have not changed.
 */
@Immutable
data class ItemWithLastEntry(
    val code: String,
    val name: String,
    val unit: String?,
    val lastPrice: Double?,
    val lastDate: String?,
    val pendingSync: Boolean
)
