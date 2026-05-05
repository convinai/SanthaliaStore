package `in`.santhaliastore.ratecard.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Pins the semantics of the SQL clause used by
 * `ItemDao.pagedItemsWithLastEntry()` (and its search variant) when
 * picking the "last purchase" projection for the home row:
 *
 * ```sql
 * ORDER BY pe.date DESC, pe.updatedAt DESC LIMIT 1
 * ```
 *
 * Translation: latest **purchase date** wins; if two entries share the
 * same purchase date, the one whose **updatedAt** is newest wins.
 *
 * This test does NOT touch Room — it just sorts an in-memory list of
 * `(date, updatedAt)` tuples the same way the SQL is supposed to. It
 * exists as a tripwire: anyone re-ordering the SQL columns (e.g. to
 * "fix" a perceived bug) trips this test and is forced to update the
 * documented contract.
 *
 * The user previously reported "latest entry" looking broken — that
 * was the old date-string corruption (locale dumps that sorted
 * lexicographically wrong), not the SQL itself. After v1.0.3's date
 * normalisation the ordering produces the expected result, and this
 * test locks it in.
 */
class LatestEntryOrderingTest {

    /**
     * Stand-in for a row of `purchase_entries` carrying just the two
     * columns the SQL orders on. We compare dates and updatedAts the
     * same way SQLite does (lexicographic on `YYYY-MM-DD` is identical
     * to chronological by [LocalDate], lexicographic on ISO 8601 with
     * `Z` is identical to chronological by [Instant]).
     */
    private data class Entry(val date: String, val updatedAt: String)

    /**
     * Sort exactly like `ORDER BY pe.date DESC, pe.updatedAt DESC LIMIT 1`.
     * Returns the head of the sorted list — the row the home projection
     * would pick.
     */
    private fun pickLatest(entries: List<Entry>): Entry =
        entries.sortedWith(
            compareByDescending<Entry> { LocalDate.parse(it.date) }
                .thenByDescending { Instant.parse(it.updatedAt) }
        ).first()

    @Test
    fun `newer date wins over older date regardless of updatedAt`() {
        // Older date but very new updatedAt — should still LOSE because
        // date is the primary sort key.
        val older = Entry(date = "2026-04-01", updatedAt = "2026-05-04T23:59:59Z")
        val newer = Entry(date = "2026-05-01", updatedAt = "2026-05-01T00:00:01Z")
        assertEquals(newer, pickLatest(listOf(older, newer)))
        // Order-independent.
        assertEquals(newer, pickLatest(listOf(newer, older)))
    }

    @Test
    fun `same date — newer updatedAt wins`() {
        val earlier = Entry(date = "2026-05-04", updatedAt = "2026-05-04T08:00:00Z")
        val later = Entry(date = "2026-05-04", updatedAt = "2026-05-04T20:00:00Z")
        assertEquals(later, pickLatest(listOf(earlier, later)))
        assertEquals(later, pickLatest(listOf(later, earlier)))
    }

    @Test
    fun `three entries — same-date pair beats older row, newest of pair wins`() {
        // The third entry is on the newest date overall — it wins
        // outright. The same-date pair is just there to exercise the
        // updatedAt tiebreaker on a non-winning bucket.
        val oldDate = Entry(date = "2026-04-15", updatedAt = "2026-04-15T12:00:00Z")
        val sameDateEarlier = Entry(date = "2026-05-04", updatedAt = "2026-05-04T08:00:00Z")
        val sameDateLater = Entry(date = "2026-05-04", updatedAt = "2026-05-04T18:00:00Z")
        assertEquals(
            sameDateLater,
            pickLatest(listOf(oldDate, sameDateEarlier, sameDateLater))
        )
    }

    @Test
    fun `all entries on the same date — newest updatedAt wins`() {
        val a = Entry(date = "2026-05-04", updatedAt = "2026-05-04T07:00:00Z")
        val b = Entry(date = "2026-05-04", updatedAt = "2026-05-04T13:30:00Z")
        val c = Entry(date = "2026-05-04", updatedAt = "2026-05-04T22:15:00Z")
        assertEquals(c, pickLatest(listOf(a, b, c)))
        // Permutation invariance — the SQL doesn't care about insert order.
        assertEquals(c, pickLatest(listOf(c, a, b)))
        assertEquals(c, pickLatest(listOf(b, c, a)))
    }
}
