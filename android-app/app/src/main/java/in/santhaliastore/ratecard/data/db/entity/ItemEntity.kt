package `in`.santhaliastore.ratecard.data.db.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single rate-card item.
 *
 * `code` is the user-defined short id (e.g. "ATA", "RICE5") and is also
 * the natural primary key — we don't bother with a synthetic id because
 * the owner already thinks in codes.
 *
 * Soft-delete columns:
 *   - `deleted` flips to true instead of removing the row, so the
 *     server sync can replay the deletion to other devices.
 *   - `pendingSync` tells the next manual sync that this row carries
 *     unsent changes. It flips back to false once the server acks.
 */
@Entity(
    tableName = "items",
    indices = [Index(value = ["code"], unique = true)]
)
@Immutable
data class ItemEntity(
    @PrimaryKey val code: String,
    val name: String,
    val unit: String?,
    val updatedAt: String,
    val deleted: Boolean = false,
    val pendingSync: Boolean = true
)
