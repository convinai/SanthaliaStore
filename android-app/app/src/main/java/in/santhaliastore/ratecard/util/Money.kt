package `in`.santhaliastore.ratecard.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * INR money formatting helpers.
 *
 * Indian grouping (1,23,456.78) keeps the rupee values familiar to a
 * shop owner. We strip trailing zeros from the decimal portion so
 * "₹120" reads cleaner than "₹120.00" in dense list rows.
 */
object Money {

    private val IN_LOCALE = Locale("en", "IN")

    private val twoDp: DecimalFormat by lazy {
        (DecimalFormat.getInstance(IN_LOCALE) as DecimalFormat).apply {
            applyPattern("#,##,##0.00")
            decimalFormatSymbols = DecimalFormatSymbols(IN_LOCALE)
            isDecimalSeparatorAlwaysShown = false
        }
    }

    private val twoDpStrip: DecimalFormat by lazy {
        (DecimalFormat.getInstance(IN_LOCALE) as DecimalFormat).apply {
            applyPattern("#,##,##0.##")
            decimalFormatSymbols = DecimalFormatSymbols(IN_LOCALE)
        }
    }

    /** "₹1,23,456" or "₹120.50" — strips trailing zeros for cleaner UI. */
    fun rupees(amount: Double?): String {
        if (amount == null) return ""
        return "₹" + twoDpStrip.format(amount)
    }

    /** Always two decimals — useful for forms / invoices. */
    fun rupeesPrecise(amount: Double?): String {
        if (amount == null) return ""
        return "₹" + twoDp.format(amount)
    }

    /** Just the number portion, no rupee sign — used in TextField defaults. */
    fun plain(amount: Double?): String {
        if (amount == null) return ""
        // Keep up to 2dp but strip trailing zeros.
        val rounded = (Math.round(amount * 100.0) / 100.0)
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else "%.2f".format(rounded)
    }

    /** Parse a user-typed number, tolerating commas and ₹ prefixes. */
    fun parse(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.replace("₹", "").replace(",", "").trim()
        return cleaned.toDoubleOrNull()
    }
}
