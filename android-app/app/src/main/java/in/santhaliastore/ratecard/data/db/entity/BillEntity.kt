package `in`.santhaliastore.ratecard.data.db.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A supplier bill — a dated, photographed invoice for stock the shop
 * received. Sits alongside (not foreign-keyed to) [ItemEntity] /
 * [PurchaseEntryEntity]: a bill is a snapshot of paper, not a structured
 * breakdown, so we don't try to link it to specific items.
 *
 * Schema notes:
 *   - `id` is a client-generated UUID v4 so multiple devices can create
 *     bills offline without colliding on primary key.
 *   - `date` is `YYYY-MM-DD` (no time-of-day) — the owner cares about
 *     the day the bill was issued, not the minute. Indexed because the
 *     list view sorts by date desc.
 *   - `supplier`, `totalAmount`, `notes` are all nullable — many bills
 *     come in with just a photo and the rest gets filled in later, or
 *     never. The UI shows "—" for the missing bits.
 *   - `imageFileIds` is a CSV of Google Drive file IDs (one bill can
 *     have multiple pages photographed). Stored as a single TEXT column
 *     rather than a relational `bill_images` table because:
 *       (a) we never query images independently of their bill,
 *       (b) the ID set is tiny (typically 1-3 photos per bill),
 *       (c) it round-trips to the sheet as a single cell.
 *     Empty string == no images attached yet. NOT nullable — keeping
 *     the column non-null lets the DAO query it without a null guard
 *     and matches the wire contract exactly.
 *   - `localImagePaths` is a CSV of app-private absolute file paths
 *     pointing at the cached copies of the same images. Local-only:
 *     other devices have their own download caches at different paths,
 *     so syncing this column would actively break things. The CSV
 *     positions align with `imageFileIds` so the UI can pair "this
 *     Drive ID lives at this local path" by index. Deliberately
 *     excluded from every DTO — see SyncDtos.kt.
 *   - `deleted` flips to true instead of removing the row, so the
 *     server sync can replay the deletion to other devices.
 *   - `pendingSync` tells the next sync that this row carries unsent
 *     changes. Flips back to false once the server acks.
 */
@Entity(
    tableName = "bills",
    indices = [Index("date")]
)
@Immutable
data class BillEntity(
    @PrimaryKey val id: String,
    val date: String,
    val supplier: String?,
    val totalAmount: Double?,
    val notes: String?,
    // CSV of Drive file IDs; empty string == no images. See class kdoc
    // for why this isn't a relational `bill_images` table.
    val imageFileIds: String = "",
    // CSV of local app-private file paths mirroring imageFileIds by
    // index. LOCAL-ONLY — never serialised to the sync DTO because the
    // path is meaningless on another device.
    val localImagePaths: String = "",
    val updatedAt: String,
    val deleted: Boolean = false,
    val pendingSync: Boolean = true
)
