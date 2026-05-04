package `in`.santhaliastore.ratecard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao
    abstract fun purchaseEntryDao(): PurchaseEntryDao

    companion object {
        const val DB_NAME = "ratecard.db"

        fun build(context: Context): AppDatabase = Room
            .databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
            // WAL is on by default — leaving the explicit setter out so we
            // pick up Room's recommended journal mode for the platform.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }
}
