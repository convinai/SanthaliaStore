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

    /** Pretty version: `4 May 2026` for display in lists / headers. */
    fun displayDate(date: String): String = runCatching {
        LocalDate.parse(date, PURCHASE_DATE_FMT).format(DISPLAY_DATE_FMT)
    }.getOrDefault(date)

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
