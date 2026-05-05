package `in`.santhaliastore.ratecard.sync

import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the soft-delete + last-writer-wins conflict
 * matrix that drives [PullApplier].
 *
 * The actual `apply()` path requires Room and is exercised manually
 * (see TESTING.md "First-install pull populates the local DB" and
 * the bidirectional smoke checklist). What we CAN test cheaply is
 * the predicate that decides whether a single pulled row should win
 * over the existing local row — it's a pure function of two
 * timestamps + the optional existing-row reference, so a unit test
 * pins the behaviour without standing up a database.
 *
 * The four cases mirror the four interesting corners of the matrix:
 *
 *   1. pulled=tombstone, local=live, pulled newer  → apply (delete propagates)
 *   2. pulled=tombstone, local=live, pulled older  → skip (local edit wins)
 *   3. pulled=live (un-delete), local=tombstone, pulled newer → apply (resurrect)
 *   4. equal timestamps, deleted flag differs      → apply (tie goes to remote)
 *
 * Bug we're guarding against: any "deleted=true should always win"
 * kludge would silently overwrite a live local edit that was made
 * AFTER the server's tombstone — destroying user work. The predicate
 * must be timestamp-only, with no special casing on `deleted`.
 */
class PullApplierConflictTest {

    private val oldTime = "2026-05-04T10:00:00Z"
    private val newTime = "2026-05-04T11:00:00Z"

    /* ------------------------ items ------------------------ */

    @Test
    fun `tombstone propagates when pulled updatedAt is newer than local`() {
        // Phone A soft-deleted item X at 11:00. Phone B's local copy
        // was last touched at 10:00 (no pending edit). Phone B pulls →
        // tombstone applies, item disappears.
        val existing = item(updatedAt = oldTime, deleted = false)
        val pulled = pulledItem(updatedAt = newTime, deleted = true)
        assertTrue(
            "Newer remote tombstone must apply over older live local",
            shouldApplyItem(existing, pulled)
        )
    }

    @Test
    fun `tombstone is skipped when local edit is newer than the pulled tombstone`() {
        // Phone A soft-deleted item X at 10:00, but Phone B made a
        // local edit at 11:00 that hasn't been pushed yet. Phone B
        // pulls → the older tombstone is skipped; the next push will
        // upload the live row and last-writer-wins resolves on the
        // server side.
        val existing = item(updatedAt = newTime, deleted = false)
        val pulled = pulledItem(updatedAt = oldTime, deleted = true)
        assertFalse(
            "Older remote tombstone must NOT clobber a newer pending local edit",
            shouldApplyItem(existing, pulled)
        )
    }

    @Test
    fun `un-delete propagates when pulled live row is newer than local tombstone`() {
        // Phone A soft-deleted item X at 10:00, then someone (Phone C
        // perhaps) re-created it at 11:00. Phone B's local copy is
        // still the 10:00 tombstone. Phone B pulls → the newer live
        // row applies, the item reappears.
        val existing = item(updatedAt = oldTime, deleted = true)
        val pulled = pulledItem(updatedAt = newTime, deleted = false)
        assertTrue(
            "Newer remote live row must apply over older local tombstone",
            shouldApplyItem(existing, pulled)
        )
    }

    @Test
    fun `equal timestamps with differing deleted flag — pulled wins`() {
        // Edge case where a server-side write and a local-side write
        // produced the same ISO 8601 millisecond stamp. Tie-breaker:
        // pulled wins (`>=` not `>`). The alternative (local wins on
        // tie) would silently suppress server tombstones in the rare
        // collision case.
        val existing = item(updatedAt = oldTime, deleted = false)
        val pulled = pulledItem(updatedAt = oldTime, deleted = true)
        assertTrue(
            "Equal-timestamp tie must apply the pulled row (>=, not >)",
            shouldApplyItem(existing, pulled)
        )
    }

    @Test
    fun `no existing local row — pulled always applies`() {
        // First pull on a fresh install. Nothing local to conflict
        // with; the pulled row writes through unconditionally.
        val pulled = pulledItem(updatedAt = oldTime, deleted = false)
        assertTrue(shouldApplyItem(existing = null, pulled = pulled))
    }

    /* ------------------------ entries ------------------------ */

    @Test
    fun `entry tombstone propagates when pulled is newer than local`() {
        val existing = entry(updatedAt = oldTime, deleted = false)
        val pulled = pulledEntry(updatedAt = newTime, deleted = true)
        assertTrue(shouldApplyEntry(existing, pulled))
    }

    @Test
    fun `entry tombstone skipped when local edit is newer`() {
        val existing = entry(updatedAt = newTime, deleted = false)
        val pulled = pulledEntry(updatedAt = oldTime, deleted = true)
        assertFalse(shouldApplyEntry(existing, pulled))
    }

    @Test
    fun `entry un-delete propagates when pulled live is newer than local tombstone`() {
        val existing = entry(updatedAt = oldTime, deleted = true)
        val pulled = pulledEntry(updatedAt = newTime, deleted = false)
        assertTrue(shouldApplyEntry(existing, pulled))
    }

    @Test
    fun `entry equal timestamp tie — pulled wins`() {
        val existing = entry(updatedAt = oldTime, deleted = false)
        val pulled = pulledEntry(updatedAt = oldTime, deleted = true)
        assertTrue(shouldApplyEntry(existing, pulled))
    }

    /* ------------------------ helpers ------------------------ */

    private fun item(
        code: String = "X",
        updatedAt: String,
        deleted: Boolean
    ) = ItemEntity(
        code = code,
        name = "Item $code",
        unit = "Kg",
        updatedAt = updatedAt,
        deleted = deleted,
        pendingSync = false
    )

    private fun pulledItem(
        code: String = "X",
        updatedAt: String,
        deleted: Boolean
    ) = PulledItem(
        code = code,
        name = "Item $code",
        unit = "Kg",
        updatedAt = updatedAt,
        deleted = deleted,
        serverUpdatedAt = updatedAt
    )

    private fun entry(
        entryId: String = "u1",
        updatedAt: String,
        deleted: Boolean
    ) = PurchaseEntryEntity(
        entryId = entryId,
        itemCode = "X",
        date = "2026-05-04",
        pricePerUnit = 50.0,
        quantity = 1.0,
        supplier = null,
        notes = null,
        updatedAt = updatedAt,
        deleted = deleted,
        pendingSync = false
    )

    private fun pulledEntry(
        entryId: String = "u1",
        updatedAt: String,
        deleted: Boolean
    ) = PulledEntry(
        entryId = entryId,
        itemCode = "X",
        date = "2026-05-04",
        pricePerUnit = 50.0,
        quantity = 1.0,
        supplier = null,
        notes = null,
        updatedAt = updatedAt,
        deleted = deleted,
        serverUpdatedAt = updatedAt
    )
}
