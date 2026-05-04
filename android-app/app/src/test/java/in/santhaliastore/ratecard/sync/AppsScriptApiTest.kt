package `in`.santhaliastore.ratecard.sync

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Retrofit interface itself.
 *
 * The failure we're guarding against: the interface used to declare
 * `@Body SyncRequest<*>` which Retrofit refuses to install at
 * construction time ("Parameter type must not include a type variable
 * or wildcard"). The crash only surfaced on real devices when the user
 * tapped "Test connection". Building the interface in a unit test
 * gives us a hard CI gate against re-introducing that pattern.
 */
class AppsScriptApiTest {

    @Test
    fun `Retrofit can install the interface (no wildcards or missing annotations)`() {
        // Just constructing the API forces Retrofit to walk every method.
        // If a method has a wildcard body, missing converter, or any
        // other shape Retrofit can't handle, this throws.
        val api = AppsScriptApi.create()
        assertNotNull(api)
    }

    @Test
    fun `envelope serialises health request with action and empty payload`() {
        val body = AppsScriptApi.envelope("health", HealthPayload())
        val sink = Buffer()
        body.writeTo(sink)
        val json = sink.readUtf8()
        assertEquals("application/json", body.contentType()?.type + "/" + body.contentType()?.subtype)
        assertTrue("Missing action: $json", json.contains("\"action\":\"health\""))
        // HealthPayload has no fields, Moshi emits {} for the payload value.
        assertTrue("Missing empty payload: $json", json.contains("\"payload\":{}"))
    }

    @Test
    fun `envelope round-trips upsert item payload field names`() {
        val payload = UpsertItemPayload(
            code = "BC100",
            name = "Bansal Chai",
            unit = "Kg",
            updatedAt = "2026-05-04T10:00:00Z"
        )
        val body = AppsScriptApi.envelope("upsertItem", payload)
        val sink = Buffer()
        body.writeTo(sink)
        val json = sink.readUtf8()
        listOf(
            "\"action\":\"upsertItem\"",
            "\"code\":\"BC100\"",
            "\"name\":\"Bansal Chai\"",
            "\"unit\":\"Kg\"",
            "\"updatedAt\":\"2026-05-04T10:00:00Z\""
        ).forEach { needle ->
            assertTrue("Missing $needle in $json", json.contains(needle))
        }
    }

    @Test
    fun `envelope serialises bulk sync payload with all four arrays`() {
        val body = AppsScriptApi.envelope(
            "bulkSync",
            BulkSyncPayload(
                items = listOf(UpsertItemPayload("X", "X", null, "2026-05-04T10:00:00Z")),
                entries = emptyList(),
                deletedItems = emptyList(),
                deletedEntries = listOf(DeleteEntryPayload("uuid", "2026-05-04T10:00:00Z"))
            )
        )
        val sink = Buffer()
        body.writeTo(sink)
        val json = sink.readUtf8()
        assertTrue(json.contains("\"action\":\"bulkSync\""))
        assertTrue(json.contains("\"items\":["))
        assertTrue(json.contains("\"entries\":["))
        assertTrue(json.contains("\"deletedItems\":["))
        assertTrue(json.contains("\"deletedEntries\":["))
    }
}
