package `in`.santhaliastore.ratecard.util

import android.content.Context
import java.io.File

/**
 * App-private storage for compressed bill images.
 *
 * The cache lives at `<filesDir>/bills/`. Surviving uninstall is NOT a
 * goal — Google Drive is the authoritative backup. Local files exist
 * purely so the Bills UI can paint thumbnails and the full-screen
 * viewer instantly on a 2 GB RAM phone without waiting on a Drive
 * round-trip (or working at all when the phone is offline).
 *
 * Path layout is intentionally deterministic:
 *
 *   - `<billId>_<index>.jpg`     for images captured / picked on this
 *                                device. Given the bill id + index we
 *                                can reconstruct the same path on every
 *                                call, so [BillEntity.localImagePaths]
 *                                isn't strictly required for the bill's
 *                                own writer — but we still persist the
 *                                CSV because it keeps the UI rendering
 *                                logic uniform between the writer and
 *                                anyone who pulled the bill from sync.
 *   - `drive_<driveFileId>.jpg`  for images pulled down by another flow
 *                                (e.g. a sibling device first uploaded
 *                                the image, this device discovered the
 *                                Drive id via sync, then downloaded the
 *                                bytes). The Drive id is opaque enough
 *                                to be safe as a filename fragment.
 *
 * Bill ids are UUIDs and Drive file ids are URL-safe base64-ish; both
 * are filename-safe so we don't sanitise.
 *
 * The actual file paths get stored on `BillEntity.localImagePaths` as a
 * CSV so the UI can map "this bill" → "these on-disk thumbnails"
 * without scanning the whole directory.
 */
class BillImageCache(private val context: Context) {

    /**
     * Return (creating if missing) the directory that holds every
     * cached bill image. Cheap to call every time — `mkdirs` is a no-op
     * when the directory already exists.
     */
    fun cacheDir(): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Path the [BillImageCompressor] will write a freshly-captured image
     * into. Does NOT create the file — the compressor opens it for
     * write. Idempotent for a given `(billId, indexInBill)` pair: a
     * retried capture overwrites the previous attempt in place rather
     * than leaking new files.
     */
    fun fileForCapture(billId: String, indexInBill: Int): File =
        File(cacheDir(), "${billId}_$indexInBill.jpg")

    /**
     * Path used when caching an image that arrived via sync — we know
     * its Drive id but have no `(billId, index)` history because the
     * bill itself was authored on a different device. Keyed on the
     * Drive id so two devices that download the same image land on the
     * same path.
     */
    fun fileForDriveId(driveFileId: String): File =
        File(cacheDir(), "drive_$driveFileId.jpg")

    /** Best-effort deletion — silently no-ops when the path is missing. */
    fun deleteFileIfExists(path: String) {
        val f = File(path)
        if (f.exists()) f.delete()
    }

    /**
     * Sum of every file in [cacheDir]. Used by the (future) eviction
     * policy to decide when to start pruning old captures. Cheap on a
     * typical install (a few dozen JPEGs) but explicitly avoids
     * recursing — the cache is flat by design.
     */
    fun totalSizeBytes(): Long =
        cacheDir().listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    /**
     * Delete every cached file whose absolute path is NOT in
     * [keepPaths]. Invoked from the "Reset local data" recovery flow
     * (after the bill rows themselves are wiped) and, later on, from
     * the periodic eviction job. `keepPaths` is the union of the
     * `localImagePaths` CSVs across all surviving bill rows — anything
     * else on disk is orphaned and safe to drop.
     */
    fun cleanOrphans(keepPaths: Set<String>) {
        val files = cacheDir().listFiles() ?: return
        for (f in files) {
            if (!f.isFile) continue
            if (f.absolutePath !in keepPaths) {
                f.delete()
            }
        }
    }

    /**
     * Nuke every file in the cache directory. Used by the Bills UI's
     * "Reset local data" path: after the Room rows are wiped there's
     * no point keeping the JPEGs around, and leaving them would mean
     * the next `cleanOrphans` (with an empty keep set) had to do the
     * same work anyway.
     *
     * The directory itself is preserved — losing it would race with
     * the next capture, which calls [cacheDir] to recreate it.
     */
    fun clearAll() {
        cacheDir().listFiles()?.forEach { it.delete() }
    }

    private companion object {
        /**
         * Single subfolder under `filesDir`. Keeping bill images out of
         * the root keeps `filesDir` tidy and lets [clearAll] /
         * [cleanOrphans] enumerate just this directory rather than
         * filtering by name prefix.
         */
        const val DIR_NAME = "bills"
    }
}
