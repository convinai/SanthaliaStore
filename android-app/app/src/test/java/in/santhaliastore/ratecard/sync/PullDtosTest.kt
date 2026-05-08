package `in`.santhaliastore.ratecard.sync

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for the bidirectional `pullChanges` action.
 *
 * The Apps Script side parses these JSON envelopes by exact field
 * name. A rename here would silently break cloud pull, so this suite
 * pins the wire format down for the request payload, the response
 * envelope, and both row shapes.
 *
 * Mirrors [SyncDtosTest] in style — runs as part of
 * `./gradlew testDebugUnitTest`.
 */
class PullDtosTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `pullChanges payload exposes sinceCursor`() {
        val adapter = moshi.adapter(PullChangesPayload::class.java)
        val json = adapter.toJson(PullChangesPayload(sinceCursor = "abc"))
        assertTrue("Missing sinceCursor: $json", json.contains("\"sinceCursor\":\"abc\""))
    }

    @Test
    fun `pullChanges payload empty cursor still serialises sinceCursor field`() {
        val adapter = moshi.adapter(PullChangesPayload::class.java)
        val json = adapter.toJson(PullChangesPayload(sinceCursor = ""))
        // Empty cursor on first install → server returns the entire
        // dataset. The field MUST be present (not omitted) so the
        // server can distinguish "fresh install" from "missing field".
        assertTrue("Missing sinceCursor: $json", json.contains("\"sinceCursor\":\"\""))
    }

    @Test
    fun `pulled item field names match contract`() {
        val adapter = moshi.adapter(PulledItem::class.java)
        val json = adapter.toJson(
            PulledItem(
                code = "BC100",
                name = "Bansal Chai",
                unit = "Kg",
                updatedAt = "2026-05-04T10:00:00Z",
                deleted = false,
                serverUpdatedAt = "2026-05-04T10:00:01Z"
            )
        )
        listOf(
            "\"code\":\"BC100\"",
            "\"name\":\"Bansal Chai\"",
            "\"unit\":\"Kg\"",
            "\"updatedAt\":\"2026-05-04T10:00:00Z\"",
            "\"deleted\":false",
            "\"serverUpdatedAt\":\"2026-05-04T10:00:01Z\""
        ).forEach { needle ->
            assertTrue("Missing $needle in $json", json.contains(needle))
        }
    }

    @Test
    fun `pulled item parses null unit and deleted true`() {
        val adapter = moshi.adapter(PulledItem::class.java)
        val parsed = adapter.fromJson(
            """{"code":"X","name":"Tomb","unit":null,"updatedAt":"2026-05-04T10:00:00Z","deleted":true,"serverUpdatedAt":"2026-05-04T10:00:01Z"}"""
        )
        assertNotNull(parsed)
        assertNull(parsed!!.unit)
        assertEquals(true, parsed.deleted)
    }

    @Test
    fun `pulled entry field names match contract`() {
        val adapter = moshi.adapter(PulledEntry::class.java)
        val json = adapter.toJson(
            PulledEntry(
                entryId = "uuid-1",
                itemCode = "BC100",
                date = "2026-05-04",
                pricePerUnit = 21.5,
                quantity = "100",
                supplier = "Sharma Traders",
                notes = "Bulk",
                updatedAt = "2026-05-04T10:00:00Z",
                deleted = false,
                serverUpdatedAt = "2026-05-04T10:00:01Z"
            )
        )
        listOf(
            "\"entryId\":\"uuid-1\"",
            "\"itemCode\":\"BC100\"",
            "\"date\":\"2026-05-04\"",
            "\"pricePerUnit\":21.5",
            "\"quantity\":\"100\"",
            "\"supplier\":\"Sharma Traders\"",
            "\"notes\":\"Bulk\"",
            "\"updatedAt\":\"2026-05-04T10:00:00Z\"",
            "\"deleted\":false",
            "\"serverUpdatedAt\":\"2026-05-04T10:00:01Z\""
        ).forEach { needle ->
            assertTrue("Missing $needle in $json", json.contains(needle))
        }
    }

    @Test
    fun `pulled entry parses optional fields as null`() {
        val adapter = moshi.adapter(PulledEntry::class.java)
        val parsed = adapter.fromJson(
            """{"entryId":"u","itemCode":"X","date":"2026-05-04","pricePerUnit":1.0,"quantity":null,"supplier":null,"notes":null,"updatedAt":"2026-05-04T10:00:00Z","deleted":false,"serverUpdatedAt":"2026-05-04T10:00:01Z"}"""
        )
        assertNotNull(parsed)
        assertNull(parsed!!.quantity)
        assertNull(parsed.supplier)
        assertNull(parsed.notes)
    }

    @Test
    fun `pullChanges response parses with items entries cursor and optional metadata`() {
        val adapter = moshi.adapter(PullChangesResponse::class.java)
        val json = """
            {
              "ok": true,
              "items": [
                {"code":"A","name":"Apple","unit":"Kg","updatedAt":"2026-05-04T10:00:00Z","deleted":false,"serverUpdatedAt":"2026-05-04T10:00:01Z"}
              ],
              "entries": [
                {"entryId":"u","itemCode":"A","date":"2026-05-04","pricePerUnit":50.0,"quantity":"1.5 kg","supplier":null,"notes":null,"updatedAt":"2026-05-04T10:00:00Z","deleted":false,"serverUpdatedAt":"2026-05-04T10:00:01Z"}
              ],
              "cursor": "next-token",
              "schemaVersion": 1,
              "time": "2026-05-04T10:00:02Z"
            }
        """.trimIndent()
        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(true, parsed!!.ok)
        assertEquals(1, parsed.items.size)
        assertEquals(1, parsed.entries.size)
        assertEquals("next-token", parsed.cursor)
        assertEquals(1, parsed.schemaVersion)
    }

    @Test
    fun `pullChanges response parses with optional metadata omitted`() {
        val adapter = moshi.adapter(PullChangesResponse::class.java)
        val parsed = adapter.fromJson(
            """{"ok":true,"items":[],"entries":[],"cursor":"x"}"""
        )
        assertNotNull(parsed)
        assertEquals(true, parsed!!.ok)
        assertEquals(0, parsed.items.size)
        assertEquals(0, parsed.entries.size)
        assertEquals("x", parsed.cursor)
        assertNull(parsed.schemaVersion)
        assertNull(parsed.time)
    }

    @Test
    fun `pullChanges response handles empty cursor on a fully-empty server`() {
        // First-pull-against-empty-sheet edge case: the server replies
        // with no rows AND an empty cursor. The client must accept this
        // and store the empty cursor (which is also the default) without
        // crashing — otherwise a fresh phone against a fresh sheet would
        // never finish bootstrapping.
        val adapter = moshi.adapter(PullChangesResponse::class.java)
        val parsed = adapter.fromJson(
            """{"ok":true,"items":[],"entries":[],"cursor":""}"""
        )
        assertNotNull(parsed)
        assertEquals("", parsed!!.cursor)
    }
}
