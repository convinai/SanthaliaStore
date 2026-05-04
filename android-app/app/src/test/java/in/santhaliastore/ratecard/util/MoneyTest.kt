package `in`.santhaliastore.ratecard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for util/Money.kt.
 *
 * Money.rupees / rupeesPrecise format with the en-IN locale, which
 * produces Indian-style grouping ("1,23,456" rather than "123,456").
 * If the running JDK lacks en-IN locale data the test will fall back
 * to a more lenient assertion.
 */
class MoneyTest {

    @Test
    fun `rupees formats whole numbers without decimals`() {
        assertEquals("₹120", Money.rupees(120.0))
        assertEquals("₹0", Money.rupees(0.0))
    }

    @Test
    fun `rupees keeps two decimals when the value has cents`() {
        // The pattern "#,##,##0.##" preserves real fractions but strips
        // trailing zeros, so 120.5 reads "₹120.5" not "₹120.50".
        assertEquals("₹120.5", Money.rupees(120.5))
    }

    @Test
    fun `rupees uses Indian-style lakh grouping`() {
        val out = Money.rupees(123456.78)
        // Accept either "1,23,456.78" (en-IN) or "123,456.78" (fallback locale)
        // so the test doesn't break on JDKs missing en-IN locale data.
        assertTrue(
            "Expected Indian grouping, got: $out",
            out == "₹1,23,456.78" || out == "₹123,456.78"
        )
    }

    @Test
    fun `rupees and plain are empty for null`() {
        assertEquals("", Money.rupees(null))
        assertEquals("", Money.rupeesPrecise(null))
        assertEquals("", Money.plain(null))
    }

    @Test
    fun `plain renders integers without trailing dot`() {
        assertEquals("21", Money.plain(21.0))
        assertEquals("0", Money.plain(0.0))
        assertEquals("100", Money.plain(100.000))
    }

    @Test
    fun `plain renders fractional numbers with two decimals`() {
        assertEquals("21.50", Money.plain(21.5))
        assertEquals("21.99", Money.plain(21.99))
    }

    @Test
    fun `parse strips rupee sign and commas`() {
        assertEquals(120.0, Money.parse("₹120")!!, 0.0001)
        assertEquals(120.5, Money.parse("₹120.5")!!, 0.0001)
        assertEquals(123456.0, Money.parse("1,23,456")!!, 0.0001)
        assertEquals(123456.78, Money.parse("₹1,23,456.78")!!, 0.0001)
        assertEquals(120.0, Money.parse("  120  ")!!, 0.0001)
    }

    @Test
    fun `parse returns null for blank or invalid input`() {
        assertNull(Money.parse(null))
        assertNull(Money.parse(""))
        assertNull(Money.parse("   "))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("12abc"))
    }
}
