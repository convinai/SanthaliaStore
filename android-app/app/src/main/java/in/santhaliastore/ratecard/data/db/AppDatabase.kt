package `in`.santhaliastore.ratecard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import `in`.santhaliastore.ratecard.data.db.dao.ItemDao
import `in`.santhaliastore.ratecard.data.db.dao.PurchaseEntryDao
import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.ItemFts
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity

/**
 * Single Room database for the whole app.
 *
 * Schema export is on so future migrations can diff against the
 * `app/schemas/` directory in source control.
 *
 * `fallbackToDestructiveMigrationOnDowngrade()` is intentional: a
 * downgrade indicates the user side-loaded an old build, in which
 * case nuking the local DB is a sensible recovery — the cloud sync
 * will refill it on next connect.
 *
 * We do NOT call `fallbackToDestructiveMigration()` for upgrades —
 * that would silently destroy the user's data on a forgotten
 * migration. We'd rather crash and ship a fix.
 */
@Database(
    entities = [
        ItemEntity::class,
        PurchaseEntryEntity::class,
        ItemFts::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao
    abstract fun purchaseEntryDao(): PurchaseEntryDao

    companion object {
        const val DB_NAME = "ratecard.db"

        /**
         * v1 → v2: `purchase_entries.quantity` changes from REAL? to TEXT?.
         *
         * SQLite has dynamic typing so existing rows technically continue
         * to work, but Room's schema validator pins the column type at
         * runtime. The portable path is the create-copy-rename dance:
         * build a sibling table with the new schema, copy rows across
         * (CAST coerces the legacy REAL into TEXT — a numeric `1.5`
         * becomes the string `"1.5"`), drop the old table, rename.
         *
         * We rebuild the FTS-adjacent indices afterward to match the
         * post-migration state declared on the entity.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS purchase_entries_new (
                        entryId TEXT NOT NULL PRIMARY KEY,
                        itemCode TEXT NOT NULL,
                        date TEXT NOT NULL,
                        pricePerUnit REAL NOT NULL,
                        quantity TEXT,
                        supplier TEXT,
                        notes TEXT,
                        updatedAt TEXT NOT NULL,
                        deleted INTEGER NOT NULL DEFAULT 0,
                        pendingSync INTEGER NOT NULL DEFAULT 1,
                        FOREIGN KEY(itemCode) REFERENCES items(code) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO purchase_entries_new (
                        entryId, itemCode, date, pricePerUnit, quantity,
                        supplier, notes, updatedAt, deleted, pendingSync
                    )
                    SELECT
                        entryId, itemCode, date, pricePerUnit,
                        CASE WHEN quantity IS NULL THEN NULL ELSE CAST(quantity AS TEXT) END,
                        supplier, notes, updatedAt, deleted, pendingSync
                    FROM purchase_entries
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE purchase_entries")
                db.execSQL("ALTER TABLE purchase_entries_new RENAME TO purchase_entries")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_entries_itemCode ON purchase_entries(itemCode)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_entries_date ON purchase_entries(date)")
                // Mark every row as pending so the next sync re-pushes the
                // newly-stringified quantity values to the sheet. The cost
                // is one extra full-push on the upgrade boundary; the gain
                // is that the spreadsheet column becomes consistent text
                // without the user having to do anything.
                db.execSQL("UPDATE purchase_entries SET pendingSync = 1")
            }
        }

        fun build(context: Context): AppDatabase = Room
            .databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
            // WAL is on by default — leaving the explicit setter out so we
            // pick up Room's recommended journal mode for the platform.
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }
}
