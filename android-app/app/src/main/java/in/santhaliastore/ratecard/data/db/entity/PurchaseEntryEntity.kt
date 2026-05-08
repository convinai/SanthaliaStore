package `in`.santhaliastore.ratecard.data.db.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A dated purchase entry against an item — the heart of the rate card.
 *
 * Schema notes:
 *   - `entryId` is a client-generated UUID v4 so multiple devices can
 *     create entries offline without collisions.
 *   - `date` is `YYYY-MM-DD` (no time-of-day) since the shop owner only
 *     cares about the day they bought stock.
 *   - Foreign key uses NO_ACTION so a soft-deleted item still keeps
 *     its history rows (we filter on the read side).
 */
@Entity(
    tableName = "purchase_entries",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["code"],
            childColumns = ["itemCode"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index("itemCode"),
        Index("date")
    ]
)
@Immutable
data class PurchaseEntryEntity(
    @PrimaryKey val entryId: String,
    val itemCode: String,
    val date: String,
    val pricePerUnit: Double,
    // Free-form text — kirana shop owners write quantities like "5 kg",
    // "1 packet", "aadha kilo" alongside plain numbers, so the column
    // is a String. Migration v1→v2 converts the legacy REAL values
    // (rendered as their plain-number string) in place.
    val quantity: String?,
    val supplier: String?,
    val notes: String?,
    val updatedAt: String,
    val deleted: Boolean = false,
    val pendingSync: Boolean = true
)
