package `in`.santhaliastore.ratecard.util

import android.content.Context
import `in`.santhaliastore.ratecard.R
import java.time.Instant
import java.time.LocalDate
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
