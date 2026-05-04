package `in`.santhaliastore.ratecard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure JVM tests for util/Time.kt — only the non-Context-dependent
 * helpers (the Hinglish relative-time strings need an Android Context
 * which JVM tests can't provide).
 */
class TimeTest {

    @Test
    fun `nowIso returns ISO-8601 instant with Z suffix`() {
        val iso = Time.nowIso()
        assertTrue("Expected ISO 8601 ending with Z, got: $iso", iso.endsWith("Z"))
        // Round-trip through Instant.parse to confirm it is a valid ISO 8601 string.
        val parsed = Instant.parse(iso)
        assertNotNull(parsed)
    }

    @Test
    fun `todayLocal returns YYYY-MM-DD format`() {
        val today = Time.todayLocal()
        assertTrue(
            "Expected YYYY-MM-DD, got: $today",
            today.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
        )
    }

    @Test
    fun `displayDate formats valid YYYY-MM-DD as d MMM yyyy`() {
        assertEquals("4 May 2026", Time.displayDate("2026-05-04"))
        assertEquals("31 Dec 2025", Time.displayDate("2025-12-31"))
        assertEquals("1 Jan 2025", Time.displayDate("2025-01-01"))
    }

    @Test
    fun `displayDate falls back to raw input when string is invalid`() {
        // Deliberately not strict — the home row would rather show the
        // raw value than a blank. We just confirm it doesn't throw.
        val raw = "not-a-date"
        assertEquals(raw, Time.displayDate(raw))
    }

    @Test
    fun `localDateToMillis returns null for invalid input`() {
        assertNull(Time.localDateToMillis(""))
        assertNull(Time.localDateToMillis("not-a-date"))
        assertNull(Time.localDateToMillis("2026-13-40"))
    }

    @Test
    fun `localDateToMillis and millisToLocalDate round-trip`() {
        val original = "2026-05-04"
        val millis = Time.localDateToMillis(original)
        assertNotNull(millis)
        val back = Time.millisToLocalDate(millis!!)
        assertEquals(original, back)
    }

    @Test
    fun `localDateToMillis pins to UTC midnight`() {
        // Compute the expected millis from java.time directly so the
        // assertion documents the contract ("UTC midnight") rather
        // than a hardcoded magic number that drifts as you cross
        // months in your head. Robust to TZ on the runner.
        val ms = Time.localDateToMillis("2026-05-04")
        val expected = LocalDate.of(2026, 5, 4)
            .atStartOfDay(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, ms)
    }
}
