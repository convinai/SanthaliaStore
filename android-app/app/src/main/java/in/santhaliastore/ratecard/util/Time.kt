package `in`.santhaliastore.ratecard.util

import android.content.Context
import `in`.santhaliastore.ratecard.R
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Time helpers used across the app.
 *
 * Two formats matter:
 *   - `YYYY-MM-DD` for purchase dates (matches the sync contract).
 *   - ISO 8601 `Z` for `updatedAt` (server uses this for last-write-wins).
 *
 * All formatting/parsing is done with java.time which is desugared via
 * AGP's core library desugaring (enabled by default at compileSdk 34).
 */
object Time {

    private val PURCHASE_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val DISPLAY_DATE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val DISPLAY_DATETIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy h:mm a", Locale.ENGLISH)
    /**
     * Parser for the leading 15 chars of JavaScript's `Date.toString()`
     * output — e.g. `"Wed May 06 2026"` taken from the full string
     * `"Wed May 06 2026 00:00:00 GMT+0530 (India Standard Time)"`.
     *
     * This is the corruption shape produced by the pre-v1.0.3 Apps
     * Script when a sheet cell holding a Date object was read via raw
     * `String(cell)` instead of the typed normalisers it now uses. Such
     * strings ended up persisted in the local Room DB and there's no
     * way to recover them without parsing in Kotlin.
     *
     * Locale.ENGLISH because JavaScript emits English day/month names
     * regardless of the device locale — locking the parser to ENGLISH
     * means a Hinglish-locale phone still parses the value correctly.
     */
    private val JS_DATE_PREFIX_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM dd yyyy", Locale.ENGLISH)

    /**
     * Hard ceiling on the fallback string returned by [displayDate] when
     * every parse attempt fails. Picked so even a Java `Date.toString()`
     * dump (~58 chars) gets clipped down to roughly "Tue May 05 2026"
     * — long enough to be informative, short enough that no row layout
     * can possibly break.
     */
    private const val DISPLAY_DATE_FALLBACK_MAX = 15

    /** Current instant as ISO 8601 with `Z` (UTC). */
    fun nowIso(): String = Instant.now().toString()

    /** Today as `YYYY-MM-DD` in the device timezone. */
    fun todayLocal(): String =
        LocalDate.now(ZoneId.systemDefault()).format(PURCHASE_DATE_FMT)

    /** Convert a `YYYY-MM-DD` string into milliseconds since epoch (UTC midnight). */
    fun localDateToMillis(date: String): Long? = runCatching {
        val ld = LocalDate.parse(date, PURCHASE_DATE_FMT)
        ld.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    }.getOrNull()

    /** Convert a millis-from-epoch UTC value back to `YYYY-MM-DD`. */
    fun millisToLocalDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate().format(PURCHASE_DATE_FMT)

    /**
     * Pretty version: `4 May 2026` for display in lists / headers.
     *
     * Defensive ladder — the UI must never display a multi-line locale
     * dump (e.g. `Tue May 05 2026 00:00:00 GMT+0530 (India Standard Time)`)
     * that would push the trailing column off the row:
     *
     *   1. ISO_LOCAL_DATE — the canonical wire format from the server.
     *      Hot path; covers every value the app itself writes.
     *   2. ISO 8601 timestamp prefix (`2026-05-05T...`). Stale payloads
     *      from an older Apps Script version sometimes carry the time
     *      portion; we only care about the date.
     *   3. Last-resort clamp: return at most [DISPLAY_DATE_FALLBACK_MAX]
     *      characters of the raw input so a Java `Date.toString()` locale
     *      dump degrades to e.g. "Tue May 05 2026" instead of overflowing
     *      the row. We don't try to parse that format because it varies
     *      across locales and the cost of getting it wrong is far higher
     *      than just truncating.
     */
    fun displayDate(date: String): String {
        if (date.isBlank()) return ""
        // 1) Canonical YYYY-MM-DD.
        runCatching {
            return LocalDate.parse(date, PURCHASE_DATE_FMT).format(DISPLAY_DATE_FMT)
        }
        // 2) ISO 8601 timestamp prefix — `2026-05-05T...`. Cheap shape
        //    check so we don't pay parser cost on obviously-wrong input.
        if (date.length >= 10 && date[4] == '-' && date[7] == '-') {
            runCatching {
                return LocalDate.parse(date.substring(0, 10), PURCHASE_DATE_FMT)
                    .format(DISPLAY_DATE_FMT)
            }
        }
        // 3) Anything else (bad format, locale Date.toString(), user-typed
        //    label) — clamp to a layout-safe length and return as-is.
        return date.take(DISPLAY_DATE_FALLBACK_MAX)
    }

    /**
     * Pretty version with date + time, used for the "Last sync: …" line
     * on Home and Settings. Format: `5 May 2026 2:30 PM`.
     *
     * `epochMillis <= 0` is the sentinel for "never synced" — callers
     * should branch on that themselves and show a localised label, but
     * we still guard against it here so a stray call site can't render
     * "1 Jan 1970" by accident.
     */
    fun displayDateTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return ""
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(DISPLAY_DATETIME_FMT)
    }

    /**
     * Hinglish relative-time string: "abhi abhi", "5 minute pehle",
     * "kal", "3 din pehle", etc.
     *
     * Comparing dates only (not full instants) keeps the result stable
     * across timezones for purchase entries, which carry only a date.
     */
    fun relativeFromIsoInstant(context: Context, iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        val then = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
        return relativeFromInstant(context, then)
    }

    /**
     * Same as the iso variant but takes a `YYYY-MM-DD` purchase date.
     * Evaluated against today in the device timezone.
     */
    fun relativeFromLocalDate(context: Context, date: String?): String {
        if (date.isNullOrBlank()) return ""
        val ld = runCatching { LocalDate.parse(date, PURCHASE_DATE_FMT) }.getOrNull() ?: return ""
        val today = LocalDate.now(ZoneId.systemDefault())
        val daysDiff = today.toEpochDay() - ld.toEpochDay()
        return when {
            daysDiff <= 0L -> context.getString(R.string.time_just_now)
            daysDiff == 1L -> context.getString(R.string.time_yesterday)
            daysDiff < 7L -> context.getString(R.string.time_days_ago_format, daysDiff.toInt())
            daysDiff < 30L -> context.getString(R.string.time_weeks_ago_format, (daysDiff / 7).toInt())
            daysDiff < 365L -> context.getString(R.string.time_months_ago_format, (daysDiff / 30).toInt())
            else -> context.getString(R.string.time_years_ago_format, (daysDiff / 365).toInt())
        }
    }

    /**
     * Coerce a possibly-corrupt purchase-date string into canonical
     * `YYYY-MM-DD`, or return `null` if the input is unrecognisable.
     *
     * Accepted shapes, in order:
     *
     *   1. **Canonical** `YYYY-MM-DD` — passes through unchanged
     *      (after a parse / format round-trip that also strips
     *      any leading zeros / whitespace anomalies).
     *
     *   2. **ISO 8601 timestamp prefix** `YYYY-MM-DDT...` — strip to
     *      the leading date. The shape check guards a cheap path: we
     *      verify `[4] == '-'` and `[7] == '-'` before paying parser
     *      cost on obviously-wrong input.
     *
     *   3. **JavaScript `Date.toString()` locale dump** —
     *      `"Wed May 06 2026 00:00:00 GMT+0530 (India Standard Time)"`.
     *      Parse the leading `"EEE MMM dd yyyy"` (15 chars) with the
     *      English-locale formatter and re-emit canonical.
     *
     * The function is the single canonical place where corrupt date
     * strings are repaired. Used by [in.santhaliastore.ratecard.data.db.AppDatabase]'s
     * v3→v4 migration (sweeps existing rows) and by
     * [in.santhaliastore.ratecard.sync.PullApplier] (prevents future
     * server pulls from re-introducing corruption).
     *
     * Returns `null` on anything else — caller should leave the value
     * alone rather than guess. A `null` return is not an error; it just
     * means "I cannot prove this is salvageable."
     */
    fun normalizeLocalDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        // 1) Canonical YYYY-MM-DD.
        runCatching {
            return LocalDate.parse(s, PURCHASE_DATE_FMT).format(PURCHASE_DATE_FMT)
        }
        // 2) ISO 8601 timestamp prefix `YYYY-MM-DDT...`.
        if (s.length >= 10 && s[4] == '-' && s[7] == '-') {
            runCatching {
                return LocalDate.parse(s.substring(0, 10), PURCHASE_DATE_FMT)
                    .format(PURCHASE_DATE_FMT)
            }
        }
        // 3) JS Date.toString() prefix.
        if (s.length >= 15) {
            runCatching {
                return LocalDate.parse(s.substring(0, 15), JS_DATE_PREFIX_FMT)
                    .format(PURCHASE_DATE_FMT)
            }
        }
        return null
    }

    /**
     * Coerce a possibly-corrupt `updatedAt` string into canonical ISO
     * 8601 with `Z`, or return `null` if it's unrecognisable.
     *
     * Sync uses `updatedAt` for last-writer-wins on both the client and
     * the Apps Script server. SQLite / V8 compare these strings
     * lexicographically — so a corrupt locale dump like
     * `"Wed May 06 2026 …"` (starting with `'W'`) always lex-wins over
     * any canonical ISO string (starting with a digit), permanently
     * preventing a fresh pull from overwriting the row. Normalising
     * the column unsticks that.
     *
     * Accepted shapes, in order:
     *
     *   1. Canonical [Instant.parse]able string —
     *      `"2026-05-06T00:00:00Z"` or `"2026-05-06T00:00:00.123Z"`.
     *
     *   2. ISO 8601 with an explicit offset — parsed via
     *      [OffsetDateTime.parse] and re-emitted in `Z` form.
     *
     * Anything else (notably the JS locale dump, which carries timezone
     * information in a non-ISO format) returns `null`. The migration
     * substitutes [nowIso] in that case — accepting some loss of write
     * history in exchange for an LWW comparator that actually works.
     */
    fun normalizeIsoTimestamp(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        runCatching {
            return Instant.parse(s).toString()
        }
        runCatching {
            return OffsetDateTime.parse(s).toInstant().toString()
        }
        return null
    }

    private fun relativeFromInstant(context: Context, then: Instant): String {
        val now = Instant.now()
        val secs = (now.epochSecond - then.epochSecond).coerceAtLeast(0)
        val mins = secs / 60
        val hours = mins / 60
        val days = hours / 24
        return when {
            secs < 45 -> context.getString(R.string.time_just_now)
            mins < 60 -> context.getString(R.string.time_minutes_ago_format, mins.toInt())
            hours < 2 -> context.getString(R.string.time_hour_ago)
            hours < 24 -> context.getString(R.string.time_hours_ago_format, hours.toInt())
            days < 2 -> context.getString(R.string.time_yesterday)
            days < 7 -> context.getString(R.string.time_days_ago_format, days.toInt())
            days < 30 -> context.getString(R.string.time_weeks_ago_format, (days / 7).toInt())
            days < 365 -> context.getString(R.string.time_months_ago_format, (days / 30).toInt())
            else -> context.getString(R.string.time_years_ago_format, (days / 365).toInt())
        }
    }
}
