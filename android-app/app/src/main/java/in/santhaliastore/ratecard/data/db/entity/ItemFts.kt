package `in`.santhaliastore.ratecard.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 virtual table over the `items` content table. Indexes the
 * `name` and `code` columns so a free-text search like "ata" or "ric"
 * can match either the human name or the short code.
 *
 * Room's `contentEntity` mode keeps the FTS table in sync with the
 * content table automatically — no triggers required on our side.
 */
@Fts4(contentEntity = ItemEntity::class)
@Entity(tableName = "items_fts")
data class ItemFts(
    val code: String,
    val name: String
)
