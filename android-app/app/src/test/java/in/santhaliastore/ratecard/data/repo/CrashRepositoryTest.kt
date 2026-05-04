package `in`.santhaliastore.ratecard.data.repo

import `in`.santhaliastore.ratecard.sync.CrashEvent
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the on-disk crash queue. We avoid touching
 * `recordCrashSync` directly because it pulls `android.os.Build`
 * which the host JVM doesn't load — the file format is the
 * contract we want pinned, and we can hand-write JSON lines into
 * the temp file to exercise both the read and clear paths.
 */
class CrashRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(CrashEvent::class.java)

    private fun newRepo(file: File) = CrashRepository(
        crashFile = file,
        appVersion = "1.0.0",
        appVersionCode = 1,
        moshi = moshi
    )

    private fun sampleEvent(id: String, msg: String = "boom") = CrashEvent(
        crashId = id,
        timestamp = "2026-05-04T10:00:00Z",
        appVersion = "1.0.0",
        appVersionCode = 1,
        androidVersion = "13 (API 33)",
        deviceModel = "Pixel 4a",
        threadName = "main",
        message = msg,
        stackTrace = "stack\n"
    )

    @Test
    fun `pendingCrashes returns empty list when file does not exist`() {
        val file = File(tempFolder.root, "crashes.log")
        assertFalse(file.exists())
        val repo = newRepo(file)
        val pending = runBlocking { repo.pendingCrashes() }
        assertEquals(0, pending.size)
    }

    @Test
    fun `pendingCrashes parses every JSON line in order`() {
        val file = tempFolder.newFile("crashes.log")
        val a = sampleEvent("id-a", "first")
        val b = sampleEvent("id-b", "second")
        val c = sampleEvent("id-c", "third")
        file.writeText(adapter.toJson(a) + "\n" + adapter.toJson(b) + "\n" + adapter.toJson(c) + "\n")

        val repo = newRepo(file)
        val pending = runBlocking { repo.pendingCrashes() }

        assertEquals(3, pending.size)
        assertEquals(listOf("id-a", "id-b", "id-c"), pending.map { it.crashId })
        // Round-trip preserves the message field too — sanity check
        // that we're not accidentally returning a half-parsed copy.
        assertEquals("first", pending[0].message)
    }

    @Test
    fun `pendingCrashes skips malformed lines instead of throwing`() {
        // A process killed mid-write can leave half a line. We must
        // be tolerant: skip the bad line, keep the good ones.
        val file = tempFolder.newFile("crashes.log")
        val a = sampleEvent("id-a")
        val b = sampleEvent("id-b")
        file.writeText(adapter.toJson(a) + "\n{not valid json\n" + adapter.toJson(b) + "\n")

        val repo = newRepo(file)
        val pending = runBlocking { repo.pendingCrashes() }

        assertEquals(2, pending.size)
        assertEquals(listOf("id-a", "id-b"), pending.map { it.crashId })
    }

    @Test
    fun `pendingCrashes ignores blank lines`() {
        val file = tempFolder.newFile("crashes.log")
        val a = sampleEvent("id-a")
        // Trailing newline + empty line in the middle simulate
        // imperfect appends.
        file.writeText("\n" + adapter.toJson(a) + "\n\n")

        val repo = newRepo(file)
        val pending = runBlocking { repo.pendingCrashes() }
        assertEquals(1, pending.size)
        assertEquals("id-a", pending[0].crashId)
    }

    @Test
    fun `clearUploaded removes only the uploaded ids`() {
        val file = tempFolder.newFile("crashes.log")
        val a = sampleEvent("id-a")
        val b = sampleEvent("id-b")
        val c = sampleEvent("id-c")
        file.writeText(adapter.toJson(a) + "\n" + adapter.toJson(b) + "\n" + adapter.toJson(c) + "\n")

        val repo = newRepo(file)
        runBlocking { repo.clearUploaded(listOf("id-a", "id-c")) }

        val remaining = runBlocking { repo.pendingCrashes() }
        assertEquals(1, remaining.size)
        assertEquals("id-b", remaining[0].crashId)
    }

    @Test
    fun `clearUploaded with all ids deletes the file outright`() {
        // Once the queue is empty there's no value in keeping a
        // zero-byte file around — the next crash recreates it.
        val file = tempFolder.newFile("crashes.log")
        val a = sampleEvent("id-a")
        val b = sampleEvent("id-b")
        file.writeText(adapter.toJson(a) + "\n" + adapter.toJson(b) + "\n")

        val repo = newRepo(file)
        runBlocking { repo.clearUploaded(listOf("id-a", "id-b")) }

        assertFalse("file should be gone after full drain", file.exists())
        // pendingCrashes() must still work when the file is missing.
        val remaining = runBlocking { repo.pendingCrashes() }
        assertEquals(0, remaining.size)
    }

    @Test
    fun `clearUploaded with empty list is a no-op`() {
        val file = tempFolder.newFile("crashes.log")
        val a = sampleEvent("id-a")
        file.writeText(adapter.toJson(a) + "\n")

        val repo = newRepo(file)
        runBlocking { repo.clearUploaded(emptyList()) }

        assertTrue(file.exists())
        val remaining = runBlocking { repo.pendingCrashes() }
        assertEquals(1, remaining.size)
    }

    @Test
    fun `clearUploaded with unknown ids leaves the file untouched`() {
        val file = tempFolder.newFile("crashes.log")
        val a = sampleEvent("id-a")
        val b = sampleEvent("id-b")
        file.writeText(adapter.toJson(a) + "\n" + adapter.toJson(b) + "\n")

        val repo = newRepo(file)
        runBlocking { repo.clearUploaded(listOf("id-zzz")) }

        val remaining = runBlocking { repo.pendingCrashes() }
        assertEquals(2, remaining.size)
    }

    @Test
    fun `truncateStackTrace returns trace verbatim when under cap`() {
        val small = "line a\nline b\nline c\n"
        assertEquals(small, CrashRepository.truncateStackTrace(small))
    }

    @Test
    fun `truncateStackTrace cuts on a line boundary and appends marker`() {
        // Build a stack trace that's just over the 8 KB cap. Each
        // line is short so the cut point is unambiguous.
        val line = "at com.example.Foo.bar(Foo.kt:1)\n" // 33 bytes
        val lineCount = (CrashRepository.MAX_STACK_TRACE_BYTES / line.length) + 50
        val raw = buildString { repeat(lineCount) { append(line) } }
        val truncated = CrashRepository.truncateStackTrace(raw)

        // Must not exceed the cap by more than the marker length.
        assertTrue(
            "truncated bytes should not be much larger than cap, was ${truncated.toByteArray().size}",
            truncated.toByteArray().size <= CrashRepository.MAX_STACK_TRACE_BYTES + "... (truncated)\n".toByteArray().size
        )
        // Must end with our marker so a reader knows the cut happened.
        assertTrue(truncated.endsWith("... (truncated)\n"))
        // Cut should be on a line boundary — the byte right before
        // the marker should be a newline from the last preserved
        // line, never a half-line.
        val withoutMarker = truncated.removeSuffix("... (truncated)\n")
        assertTrue(
            "cut should land on a line boundary, last 5 chars: '${withoutMarker.takeLast(5)}'",
            withoutMarker.endsWith("\n")
        )
    }
}
