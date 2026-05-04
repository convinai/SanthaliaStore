package `in`.santhaliastore.ratecard.sync

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for the crash-logging payloads.
 *
 * The Apps Script side reads these JSON envelopes by exact field
 * name (column order is derived from the same names). A rename
 * here would silently start writing to the wrong cell, so this
 * suite pins the contract — same pattern as `SyncDtosTest`.
 */
class CrashEventTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `crash event uses the exact nine contract field names`() {
        val adapter = moshi.adapter(CrashEvent::class.java)
        val event = CrashEvent(
            crashId = "550e8400-e29b-41d4-a716-446655440000",
            timestamp = "2026-05-04T10:00:00Z",
            appVersion = "1.0.0",
            appVersionCode = 1,
            androidVersion = "13 (API 33)",
            deviceModel = "samsung SM-A125F",
            threadName = "main",
            message = "boom",
            stackTrace = "java.lang.RuntimeException: boom\n\tat foo.Bar.baz(Bar.kt:1)\n"
        )
        val json = adapter.toJson(event)
        // Every field name must appear verbatim — Apps Script reads
        // these by string match.
        listOf(
            "\"crashId\":\"550e8400-e29b-41d4-a716-446655440000\"",
            "\"timestamp\":\"2026-05-04T10:00:00Z\"",
            "\"appVersion\":\"1.0.0\"",
            "\"appVersionCode\":1",
            "\"androidVersion\":\"13 (API 33)\"",
            "\"deviceModel\":\"samsung SM-A125F\"",
            "\"threadName\":\"main\"",
            "\"message\":\"boom\"",
            "\"stackTrace\":"
        ).forEach { needle ->
            assertTrue("Missing $needle in $json", json.contains(needle))
        }
    }

    @Test
    fun `crash event round-trips through moshi`() {
        val adapter = moshi.adapter(CrashEvent::class.java)
        val original = CrashEvent(
            crashId = "id-1",
            timestamp = "2026-05-04T10:00:00Z",
            appVersion = "1.0.0",
            appVersionCode = 42,
            androidVersion = "10 (API 29)",
            deviceModel = "Pixel 4a",
            threadName = "main",
            message = "NPE on save",
            stackTrace = "stack\nlines\n"
        )
        val json = adapter.toJson(original)
        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun `log crashes payload exposes a single crashes array`() {
        val adapter = moshi.adapter(LogCrashesPayload::class.java)
        val json = adapter.toJson(
            LogCrashesPayload(
                crashes = listOf(
                    CrashEvent(
                        crashId = "id-1",
                        timestamp = "2026-05-04T10:00:00Z",
                        appVersion = "1.0.0",
                        appVersionCode = 1,
                        androidVersion = "13 (API 33)",
                        deviceModel = "samsung SM-A125F",
                        threadName = "main",
                        message = "boom",
                        stackTrace = "trace"
                    )
                )
            )
        )
        // The `crashes` key — and nothing else — at the top level.
        assertTrue(json.contains("\"crashes\":["))
    }

    @Test
    fun `log crashes payload accepts an empty list`() {
        // Edge case: phone might call us right after clearing the
        // queue. Empty list must be a valid wire representation so
        // Moshi doesn't choke on no-pending state.
        val adapter = moshi.adapter(LogCrashesPayload::class.java)
        val json = adapter.toJson(LogCrashesPayload(crashes = emptyList()))
        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.crashes.size)
    }

    @Test
    fun `crash event integer version code stays an integer in JSON`() {
        // Sanity: appVersionCode is `Int` on the Kotlin side. If
        // Moshi started emitting it as a string we'd silently break
        // the apps-script Number coercion. Pin the JSON shape.
        val adapter = moshi.adapter(CrashEvent::class.java)
        val json = adapter.toJson(
            CrashEvent(
                crashId = "id",
                timestamp = "t",
                appVersion = "1.0.0",
                appVersionCode = 7,
                androidVersion = "v",
                deviceModel = "m",
                threadName = "main",
                message = "",
                stackTrace = ""
            )
        )
        assertTrue("appVersionCode should be a JSON number, got: $json",
            json.contains("\"appVersionCode\":7"))
        assertTrue("appVersionCode should NOT be quoted: $json",
            !json.contains("\"appVersionCode\":\""))
    }
}
