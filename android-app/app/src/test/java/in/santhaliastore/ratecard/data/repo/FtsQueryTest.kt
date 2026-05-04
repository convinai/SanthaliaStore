package `in`.santhaliastore.ratecard.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the FTS4 prefix-query escaping logic. The behaviour matters
 * for two reasons: a buggy escape can crash SQLite at runtime
 * (FTS phrase rules are strict), and a too-aggressive escape would
 * lose tokens the user typed.
 */
class FtsQueryTest {

    @Test
    fun `empty query returns empty phrase, not crash`() {
        // An empty MATCH expression would throw; we sentinel it as "".
        assertEquals("\"\"", ItemRepository.toFtsPrefixQuery(""))
        assertEquals("\"\"", ItemRepository.toFtsPrefixQuery("   "))
    }

    @Test
    fun `single token is lower-cased and prefixed`() {
        assertEquals("rice*", ItemRepository.toFtsPrefixQuery("rice"))
        assertEquals("rice*", ItemRepository.toFtsPrefixQuery("Rice"))
        assertEquals("rice*", ItemRepository.toFtsPrefixQuery("RICE"))
    }

    @Test
    fun `multiple tokens are split on whitespace`() {
        assertEquals(
            "bansal* chai*",
            ItemRepository.toFtsPrefixQuery("Bansal Chai")
        )
        assertEquals(
            "ata* 5* kg*",
            ItemRepository.toFtsPrefixQuery("Ata 5 kg")
        )
    }

    @Test
    fun `special characters that FTS treats specially are stripped`() {
        // dashes, quotes, asterisks etc. would otherwise produce phrase
        // tokens or unexpected wildcards. We squash them to nothing.
        assertEquals("abc*", ItemRepository.toFtsPrefixQuery("a-b-c"))
        assertEquals("abc*", ItemRepository.toFtsPrefixQuery("\"abc\""))
        assertEquals("abc*", ItemRepository.toFtsPrefixQuery("a*b*c"))
    }

    @Test
    fun `digits and alphanumeric mixes survive`() {
        // Item codes look like ATA10 or RICE5 — those should match fine.
        assertEquals("ata10*", ItemRepository.toFtsPrefixQuery("ATA10"))
        assertEquals("rice5*", ItemRepository.toFtsPrefixQuery("rice5"))
    }

    @Test
    fun `extra whitespace collapses cleanly`() {
        assertEquals("ric*", ItemRepository.toFtsPrefixQuery("  ric  "))
        assertEquals(
            "ric* chai*",
            ItemRepository.toFtsPrefixQuery("  ric    chai  ")
        )
    }
}
