package `in`.santhaliastore.ratecard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import `in`.santhaliastore.ratecard.data.db.dao.BillDao
import `in`.santhaliastore.ratecard.data.db.dao.ItemDao
import `in`.santhaliastore.ratecard.data.db.dao.PurchaseEntryDao
import `in`.santhaliastore.ratecard.data.db.entity.BillEntity
import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.ItemFts
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity
import `in`.santhaliastore.ratecard.util.Time

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
        ItemFts::class,
        BillEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao
    abstract fun purchaseEntryDao(): PurchaseEntryDao
    abstract fun billDao(): BillDao

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

        /**
         * v2 → v3: introduce the `bills` table for supplier bills.
         *
         * New feature, brand-new table — no existing data to coerce or
         * copy. We just create the table + the date index Room would
         * have generated from the [BillEntity] declaration, so the
         * schema validator passes on first boot after the upgrade.
         *
         * Column types and NOT NULL flags MUST match Room's expected
         * `CREATE TABLE` statement byte-for-byte (Room hashes the schema
         * at compile time and compares at runtime). The non-null
         * defaults on `imageFileIds` / `localImagePaths` mirror the
         * Kotlin defaults on the entity so a v2-era client that
         * upgrades after running `bulkSync` (which can pull bills via
         * the v3 server) never sees a null in those columns.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bills (
                        id TEXT NOT NULL PRIMARY KEY,
                        date TEXT NOT NULL,
                        supplier TEXT,
                        totalAmount REAL,
                        notes TEXT,
                        imageFileIds TEXT NOT NULL DEFAULT '',
                        localImagePaths TEXT NOT NULL DEFAULT '',
                        updatedAt TEXT NOT NULL,
                        deleted INTEGER NOT NULL DEFAULT 0,
                        pendingSync INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bills_date ON bills(date)")
            }
        }

        /**
         * v3 → v4: no schema change. Pure data repair for the
         * pre-v1.0.3 "locale dump" corruption that leaked into the
         * local DB via the Apps Script's old un-typed cell reads.
         *
         * Symptom the user saw: an item's home row reported the older
         * of two purchase entries as "Aakhri rate", and the Item Detail
         * list ordered entries inconsistently. Root cause: the
         * `purchase_entries.date` column for the affected row held a
         * value like `"Wed May 06 2026 00:00:00 GMT+0530 (India Standard Time)"`
         * instead of `"2026-05-06"`. SQLite compares TEXT
         * lexicographically, and `'W'` (0x57) sorts above `'2'` (0x32),
         * so any `ORDER BY date DESC` picked the corrupt row.
         *
         * The same shape also infected `updatedAt` on the same row —
         * which is worse, because LWW (both client `PullApplier` and
         * server-side Apps Script) compares `updatedAt` strings the
         * same way. The corrupt value lex-wins every comparison, so
         * fresh server data could never overwrite the row. The DB was
         * permanently stuck without intervention.
         *
         * This migration walks the affected tables exactly once:
         *
         *   1. `purchase_entries` — rewrite `date` to canonical
         *      `YYYY-MM-DD` when [Time.normalizeLocalDate] can parse
         *      the corrupt value. Refresh `updatedAt` to `nowIso()`
         *      and flag the row `pendingSync = 1` so the corrected
         *      value pushes to the sheet on the next sync. Setting
         *      `updatedAt` to "now" deliberately loses the original
         *      write timestamp (which was corrupt anyway) in exchange
         *      for an LWW comparator that finally beats the stale
         *      copy on the server.
         *
         *   2. `items` — same treatment for `updatedAt` alone. Items
         *      have no `date` column. Touch the row only if `updatedAt`
         *      doesn't already parse as canonical ISO 8601.
         *
         * Rows whose `date` is unparseable are left alone — there's no
         * safe default; better to surface a weird display than fabricate
         * a date. The user can edit the row by hand.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                repairPurchaseEntryDates(db)
                repairUpdatedAtColumn(db, table = "purchase_entries", pkCol = "entryId")
                repairUpdatedAtColumn(db, table = "items",            pkCol = "code")
            }

            /**
             * Scan `purchase_entries.date` for any value that isn't
             * already a canonical `YYYY-MM-DD` string (length 10 +
             * matches the digit-dash-digit GLOB) and try to repair it
             * via [Time.normalizeLocalDate]. Rows whose `date` is
             * already canonical are skipped here for cheapness;
             * `updatedAt` on those rows is handled separately by
             * [repairUpdatedAtColumn] with its own filter.
             */
            private fun repairPurchaseEntryDates(db: SupportSQLiteDatabase) {
                val now = Time.nowIso()
                val cursor = db.query(
                    """
                    SELECT entryId, date FROM purchase_entries
                    WHERE NOT (length(date) = 10
                               AND date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')
                    """.trimIndent()
                )
                cursor.use {
                    while (it.moveToNext()) {
                        // Wrap each row in runCatching so a single
                        // malformed row (e.g. wrong column type after
                        // a manual DB edit) can't abort the migration
                        // and leave the user stranded on app launch.
                        // The row stays as-is; the rest of the table
                        // still gets repaired.
                        runCatching {
                            val entryId = it.getString(0)
                            val bad = it.getString(1)
                            val fixed = Time.normalizeLocalDate(bad)
                                ?: return@runCatching
                            db.execSQL(
                                """
                                UPDATE purchase_entries
                                SET date = ?, updatedAt = ?, pendingSync = 1
                                WHERE entryId = ?
                                """.trimIndent(),
                                arrayOf<Any?>(fixed, now, entryId)
                            )
                        }
                    }
                }
            }

            /**
             * Parameterised over (table, primary-key column) because
             * the repair shape is identical for `purchase_entries` and
             * `items` — only the table name and PK differ. SQLite
             * doesn't allow binding identifiers as parameters, so we
             * splice them into the query text directly. Both arguments
             * are hard-coded constants from this migration, NOT user
             * input — no injection risk.
             *
             * Filter: a canonical ISO 8601 with `Z` starts with four
             * digits and has `'T'` at position 10 (1-indexed in
             * SQLite's `substr`). The GLOB on the first four chars
             * narrows to YYYY-shaped prefixes; the `substr(...,11,1)`
             * check pins the literal `T` separator. Anything failing
             * either check is considered corrupt and parsed in Kotlin.
             */
            private fun repairUpdatedAtColumn(
                db: SupportSQLiteDatabase,
                table: String,
                pkCol: String
            ) {
                val now = Time.nowIso()
                val cursor = db.query(
                    """
                    SELECT $pkCol, updatedAt FROM $table
                    WHERE NOT (substr(updatedAt, 1, 4) GLOB '[0-9][0-9][0-9][0-9]'
                               AND substr(updatedAt, 11, 1) = 'T')
                    """.trimIndent()
                )
                cursor.use {
                    while (it.moveToNext()) {
                        runCatching {
                            val pk = it.getString(0)
                            val bad = it.getString(1)
                            // Try to recover the original timestamp; if we
                            // can't, substitute `now` — the row will lose
                            // its true write-time but at least LWW becomes
                            // unstuck. Either outcome flags pendingSync so
                            // the sheet picks up the canonical form.
                            val fixed = Time.normalizeIsoTimestamp(bad) ?: now
                            db.execSQL(
                                """
                                UPDATE $table
                                SET updatedAt = ?, pendingSync = 1
                                WHERE $pkCol = ?
                                """.trimIndent(),
                                arrayOf<Any?>(fixed, pk)
                            )
                        }
                    }
                }
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }
}
