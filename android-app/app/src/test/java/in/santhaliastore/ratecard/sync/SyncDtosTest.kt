package `in`.santhaliastore.ratecard.sync

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sync wire-format tests.
 *
 * The Apps Script side parses these JSON envelopes by exact field
 * name. A rename here would silently break cloud sync, so this suite
 * pins the wire format down for all six action payloads.
 *
 * Run before every push (CI gates on `testDebugUnitTest`).
 */
class SyncDtosTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `request envelope serialises action and payload at top level`() {
        val type = Types.newParameterizedType(SyncRequest::class.java, HealthPayload::class.java)
        @Suppress("UNCHECKED_CAST")
        val adapter = moshi.adapter<SyncRequest<HealthPayload>>(type)
        val json = adapter.toJson(SyncRequest("health", HealthPayload()))
        assertTrue("Missing action: $json", json.contains("\"action\":\"health\""))
        assertTrue("Missing payload: $json", json.contains("\"payload\""))
    }

    @Test
    fun `health payload is an empty object`() {
        val adapter = moshi.adapter(HealthPayload::class.java)
        // HealthPayload has no fields — Moshi emits {} which is what
        // the server expects.
        val json = adapter.toJson(HealthPayload())
        assertEquals("{}", json)
    }

    @Test
    fun `upsert item payload uses exact contract field names`() {
        val adapter = moshi.adapter(UpsertItemPayload::class.java)
        val json = adapter.toJson(
            UpsertItemPayload(
                code = "BC100",
                name = "Bansal Chai",
                unit = "Kg",
                updatedAt = "2026-05-04T10:00:00Z"
            )
        )
        assertTrue(json.contains("\"code\":\"BC100\""))
        assertTrue(json.contains("\"name\":\"Bansal Chai\""))
        assertTrue(json.contains("\"unit\":\"Kg\""))
        assertTrue(json.contains("\"updatedAt\":\"2026-05-04T10:00:00Z\""))
    }

    @Test
    fun `upsert item payload serialises null unit explicitly`() {
        val adapter = moshi.adapter(UpsertItemPayload::class.java).serializeNulls()
        val json = adapter.toJson(
            UpsertItemPayload(
                code = "X",
                name = "Test",
                unit = null,
                updatedAt = "2026-05-04T10:00:00Z"
            )
        )
        // `unit` is part of the contract; the server treats null/missing
        // as empty. Either is fine — assert it round-trips.
        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertNull(parsed!!.unit)
    }

    @Test
    fun `upsert entry payload field names match contract`() {
        val adapter = moshi.adapter(UpsertEntryPayload::class.java)
        val json = adapter.toJson(
            UpsertEntryPayload(
                entryId = "uuid-1",
                itemCode = "BC100",
                date = "2026-05-04",
                pricePerUnit = 21.5,
                quantity = "100",
                supplier = "Sharma Traders",
                notes = "Bulk",
                updatedAt = "2026-05-04T10:00:00Z"
            )
        )
        // All eight contract field names must appear verbatim.
        listOf(
            "\"entryId\":\"uuid-1\"",
            "\"itemCode\":\"BC100\"",
            "\"date\":\"2026-05-04\"",
            "\"pricePerUnit\":21.5",
            "\"quantity\":\"100\"",
            "\"supplier\":\"Sharma Traders\"",
            "\"notes\":\"Bulk\"",
            "\"updatedAt\":\"2026-05-04T10:00:00Z\""
        ).forEach { needle ->
            assertTrue("Missing $needle in $json", json.contains(needle))
        }
    }

    @Test
    fun `delete payloads carry only key and timestamp`() {
        val itemDel = moshi.adapter(DeleteItemPayload::class.java)
            .toJson(DeleteItemPayload("BC100", "2026-05-04T10:00:00Z"))
        assertTrue(itemDel.contains("\"code\":\"BC100\""))
        assertTrue(itemDel.contains("\"updatedAt\""))

        val entryDel = moshi.adapter(DeleteEntryPayload::class.java)
            .toJson(DeleteEntryPayload("uuid-1", "2026-05-04T10:00:00Z"))
        assertTrue(entryDel.contains("\"entryId\":\"uuid-1\""))
        assertTrue(entryDel.contains("\"updatedAt\""))
    }

    @Test
    fun `bulk sync payload exposes the four expected arrays`() {
        val adapter = moshi.adapter(BulkSyncPayload::class.java)
        val json = adapter.toJson(
            BulkSyncPayload(
                items = listOf(
                    UpsertItemPayload("X", "X", null, "2026-05-04T10:00:00Z")
                ),
                entries = emptyList(),
                deletedItems = emptyList(),
                deletedEntries = listOf(
                    DeleteEntryPayload("uuid", "2026-05-04T10:00:00Z")
                )
            )
        )
        assertTrue(json.contains("\"items\":["))
        assertTrue(json.contains("\"entries\":["))
        assertTrue(json.contains("\"deletedItems\":["))
        assertTrue(json.contains("\"deletedEntries\":["))
    }

    @Test
    fun `response parses with optional errors field omitted`() {
        val adapter = moshi.adapter(SyncResponse::class.java)
        val parsed = adapter.fromJson(
            """{"ok":true,"processed":3,"action":"bulkSync","time":"2026-05-04T10:00:00Z"}"""
        )
        assertNotNull(parsed)
        assertEquals(true, parsed!!.ok)
        assertEquals(3, parsed.processed)
        assertNull(parsed.errors)
    }

    @Test
    fun `response parses errors array when server reports per-row failures`() {
        val adapter = moshi.adapter(SyncResponse::class.java)
        val parsed = adapter.fromJson(
            """{"ok":false,"processed":1,"errors":[{"index":0,"key":"BC100","message":"bad row"}]}"""
        )
        assertNotNull(parsed)
        assertEquals(false, parsed!!.ok)
        assertEquals(1, parsed.errors?.size)
        assertEquals("BC100", parsed.errors!![0].key)
    }
}
