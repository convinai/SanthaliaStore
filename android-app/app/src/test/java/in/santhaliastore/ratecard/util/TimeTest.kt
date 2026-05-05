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
    fun `displayDate parses ISO 8601 timestamp prefix and formats the date part`() {
        // A misbehaving server (or a stale Apps Script version) might
        // send an ISO 8601 timestamp instead of a plain YYYY-MM-DD. We
        // peel off the date prefix rather than show the whole timestamp
        // — the row only has room for a short date.
        assertEquals("5 May 2026", Time.displayDate("2026-05-05T00:00:00Z"))
        assertEquals("5 May 2026", Time.displayDate("2026-05-05T13:45:30+05:30"))
    }

    @Test
    fun `displayDate clamps unknown formats to a layout-safe length`() {
        // Worst case: a Java Date.toString() locale dump leaks through.
        // Parsing it across locales is finicky, so we just clip the raw
        // input. We assert on the LENGTH (not a literal value) because
        // the exact substring depends on the input format and we only
        // care that the row layout can never blow out.
        val javaDateDump = "Tue May 05 2026 00:00:00 GMT+0530 (India Standard Time)"
        val out = Time.displayDate(javaDateDump)
        assertTrue("Expected fallback ≤ 15 chars, got: $out (len ${out.length})", out.length <= 15)
    }

    @Test
    fun `displayDate returns empty string for blank input`() {
        // Blank input is a valid "no date" sentinel — collapsing it to
        // an empty string lets the caller hide the line entirely.
        assertEquals("", Time.displayDate(""))
        assertEquals("", Time.displayDate(" "))
    }

    @Test
    fun `displayDate falls back to clamped raw input when string is invalid`() {
        // Deliberately not strict — the home row would rather show the
        // raw value than a blank. The clamp guarantees the row layout
        // never blows out regardless of what the server sends.
        val raw = "garbage"
        val out = Time.displayDate(raw)
        assertEquals(raw, out)
        assertTrue("Expected fallback ≤ 15 chars", out.length <= 15)
    }

    @Test
    fun `displayDateTime returns empty string for non-positive epoch`() {
        // 0L is the "never synced" sentinel. We don't want a 1970-era
        // string leaking into the UI for it.
        assertEquals("", Time.displayDateTime(0L))
        assertEquals("", Time.displayDateTime(-1L))
    }

    @Test
    fun `displayDateTime formats positive epoch as d MMM yyyy h colon mm a`() {
        // Compute a known instant and assert the SHAPE (not the exact
        // value) — the runner's timezone varies, so the rendered hour
        // is non-deterministic. The shape regex pins the format
        // contract: digits + month abbr + year + h:mm + AM/PM.
        //
        // We use `\s` (any whitespace) as the AM/PM separator because
        // newer JVMs (13+) emit a NARROW NO-BREAK SPACE there even with
        // a plain `Locale.ENGLISH` formatter — pinning to a literal
        // space character would make this test fragile across runners.
        val ms = LocalDate.of(2026, 5, 5)
            .atStartOfDay(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()
        val rendered = Time.displayDateTime(ms)
        assertTrue(
            "Expected format `d MMM yyyy h:mm AM/PM`, got: $rendered",
            rendered.matches(Regex("\\d{1,2} \\w{3} \\d{4} \\d{1,2}:\\d{2}\\s(AM|PM)"))
        )
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
