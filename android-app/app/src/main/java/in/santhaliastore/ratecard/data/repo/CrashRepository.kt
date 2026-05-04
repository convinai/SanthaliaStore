package `in`.santhaliastore.ratecard.data.repo

import android.os.Build
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import `in`.santhaliastore.ratecard.sync.CrashEvent
import `in`.santhaliastore.ratecard.util.Time
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Tiny on-disk crash queue.
 *
 * Why not Room: the database is the most likely place a fatal crash
 * actually originates from (migration bug, schema mismatch, FK
 * failure mid-transaction). If we tried to persist crashes to Room
 * the persist itself would fail and we'd lose the very breadcrumb
 * we're trying to capture. A single append-only text file is dumb
 * and reliable — it works even if Room is half-initialised.
 *
 * Format: one JSON-serialised [CrashEvent] per line. We tolerate
 * (and skip) malformed lines so a single half-written record from
 * an interrupted append doesn't poison the whole queue.
 *
 * The file lives in app-private storage (`<filesDir>/crashes.log`),
 * so the OS clears it on uninstall and other apps can't read it.
 */
class CrashRepository(
    private val crashFile: File,
    private val appVersion: String,
    private val appVersionCode: Int,
    moshi: Moshi = DEFAULT_MOSHI
) {

    private val adapter: JsonAdapter<CrashEvent> = moshi.adapter(CrashEvent::class.java)

    /**
     * Synchronous append used from the uncaught-exception handler.
     *
     * MUST be safe to call from a dying process:
     *   - No coroutines (the JVM is on its way out).
     *   - No locks the rest of the app might be holding.
     *   - All I/O goes through try/catch — we never want the crash
     *     handler itself to throw on top of the original crash.
     *   - File is opened in append mode + flushed + fsync'd so a
     *     half-written line from an OOM kill is at least limited to
     *     one record (and that record gets skipped on next parse).
     *
     * Caller is the static `Thread.UncaughtExceptionHandler` installed
     * in [in.santhaliastore.ratecard.RateCardApp.onCreate].
     */
    fun recordCrashSync(thread: Thread, throwable: Throwable) {
        try {
            val event = CrashEvent(
                crashId = UUID.randomUUID().toString(),
                timestamp = Time.nowIso(),
                appVersion = appVersion,
                appVersionCode = appVersionCode,
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                threadName = thread.name,
                message = throwable.message.orEmpty(),
                stackTrace = truncateStackTrace(throwable.stackTraceToString())
            )

            // Make sure the parent directory exists. filesDir is normally
            // present, but if the system has clobbered it we'd rather
            // create than throw.
            crashFile.parentFile?.mkdirs()

            val line = adapter.toJson(event) + "\n"
            FileOutputStream(crashFile, /* append = */ true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
                // fsync so the line survives a process kill that
                // immediately follows the write.
                try { out.fd.sync() } catch (_: Throwable) { /* ignore */ }
            }
        } catch (_: Throwable) {
            // Last-resort: never let the crash handler itself throw.
            // We've already lost this crash to the void; the previous
            // handler will still show Android's "App has stopped" UI.
        }
    }

    /**
     * Read the queue. Lines that fail JSON parse are silently
     * skipped — a half-written record from a dead process shouldn't
     * block the whole upload.
     */
    suspend fun pendingCrashes(): List<CrashEvent> {
        if (!crashFile.exists()) return emptyList()
        val text = runCatching { crashFile.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<CrashEvent>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val parsed = runCatching { adapter.fromJson(line) }.getOrNull() ?: continue
            out += parsed
        }
        return out
    }

    /**
     * Drop the events whose `crashId` is in [crashIds] from the file.
     * If everything has been uploaded we just delete the file; on
     * next crash it'll be re-created.
     *
     * The whole thing is rewritten in place — the file is always
     * tiny (a few hundred bytes per crash), so streaming complexity
     * isn't worth it.
     */
    suspend fun clearUploaded(crashIds: List<String>) {
        if (crashIds.isEmpty()) return
        if (!crashFile.exists()) return
        val ids = crashIds.toHashSet()
        val remaining = pendingCrashes().filterNot { it.crashId in ids }
        if (remaining.isEmpty()) {
            // Nothing left — truncate by deleting outright. Any
            // subsequent recordCrashSync recreates the file.
            runCatching { crashFile.delete() }
            return
        }
        val rewritten = remaining.joinToString(separator = "\n", postfix = "\n") { adapter.toJson(it) }
        runCatching {
            FileOutputStream(crashFile, /* append = */ false).use { out ->
                out.write(rewritten.toByteArray(Charsets.UTF_8))
                out.flush()
                try { out.fd.sync() } catch (_: Throwable) { /* ignore */ }
            }
        }
    }

    companion object {
        /** Default file name used when the repository is wired through DI. */
        const val CRASH_FILE_NAME = "crashes.log"

        /** 8 KB cap so a runaway recursion can't blow up a sheet cell. */
        const val MAX_STACK_TRACE_BYTES = 8 * 1024

        /**
         * Shared Moshi instance. Reuses adapter caching but we
         * fall back to a private one in the constructor for tests
         * that want to inject a custom instance.
         */
        private val DEFAULT_MOSHI: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        /**
         * Trim a stack trace to fit in [MAX_STACK_TRACE_BYTES] without
         * splitting a line in half. We work in bytes (UTF-8) since the
         * sheet cell limit and the storage limit are both byte-based.
         *
         * Strategy:
         *   1. Encode the full string as UTF-8.
         *   2. If under the cap, return verbatim.
         *   3. Otherwise pick the largest prefix that ends on a newline
         *      and decode it back. Append a "... (truncated)" marker so
         *      a reader knows the trace was cut.
         *
         * Visible for testing.
         */
        internal fun truncateStackTrace(raw: String): String {
            val bytes = raw.toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_STACK_TRACE_BYTES) return raw

            // Find the last newline at or before the cap. If we can't
            // find one (single huge line), fall back to a hard cut at
            // a UTF-8 char boundary so we don't emit invalid bytes.
            var cut = MAX_STACK_TRACE_BYTES
            while (cut > 0 && bytes[cut - 1] != '\n'.code.toByte()) cut--
            if (cut == 0) {
                // Hard cut at the cap, but back off to a safe UTF-8
                // boundary (continuation bytes start with 10xx_xxxx).
                cut = MAX_STACK_TRACE_BYTES
                while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
            }
            val truncated = String(bytes, 0, cut, Charsets.UTF_8)
            return truncated + "... (truncated)\n"
        }
    }
}
