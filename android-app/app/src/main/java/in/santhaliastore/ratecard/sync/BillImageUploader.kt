package `in`.santhaliastore.ratecard.sync

import android.util.Base64
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.util.AppResult
import `in`.santhaliastore.ratecard.util.appResultOf
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Pure transport layer for the `uploadBillImage` / `deleteBillImage`
 * Apps Script actions.
 *
 * Deliberately does NOT touch [in.santhaliastore.ratecard.data.repo.BillRepository]:
 * after a successful upload the caller (the Bills UI) is the one that
 * appends the returned Drive id to the bill's `imageFileIds` CSV via
 * `BillRepository.updateImageState`. Keeping that responsibility in the
 * UI means a multi-image save can batch all the Drive ids into a
 * single Room write at the end of the loop, rather than thrashing the
 * row once per image.
 *
 * The `uploadBillImage` response envelope is intentionally different
 * from the standard [SyncResponse]: it carries `fileId` + `viewUrl`
 * which the standard envelope has no slot for. `deleteBillImage`
 * reuses [SyncResponse] because the server has nothing image-shaped
 * to hand back on a delete.
 *
 * Single image per call. Multi-image orchestration (capture loop, retry
 * policy, progress UI) belongs to the caller.
 */
class BillImageUploader(
    private val apiFactory: () -> AppsScriptApi,
    private val settings: SettingsRepository
) {

    /**
     * Successful upload result. [viewUrl] is informational — the UI
     * uses it as a fallback when the local cache copy is missing, but
     * the canonical pointer is [fileId] (which is what we persist into
     * the bill's `imageFileIds` CSV).
     */
    data class UploadResult(val fileId: String, val viewUrl: String?)

    /**
     * Upload [imageFile] to Drive against the user's configured sheet
     * URL. Returns [AppResult.Err] on:
     *
     *   - Sheet URL not set.
     *   - File missing / unreadable / empty.
     *   - Network or HTTP failure (wrapped by [appResultOf]).
     *   - Server reports `ok = false` or doesn't return a `fileId`.
     *
     * The Apps Script body cap is generous enough for a single
     * MAX_DIMENSION-bound JPEG at quality 80 (~300–500 KB raw ⇒
     * ~400–700 KB after base64 expansion), so we don't chunk.
     */
    suspend fun upload(billId: String, imageFile: File): AppResult<UploadResult> = appResultOf {
        val url = settings.sheetUrl.first()
        require(url.isNotBlank()) { "Sheet URL not set" }
        require(imageFile.exists()) { "Image file missing: ${imageFile.absolutePath}" }
        require(imageFile.length() > 0) { "Image file is empty: ${imageFile.absolutePath}" }

        val bytes = imageFile.readBytes()
        // NO_WRAP: Android's default Base64 inserts a newline every 76
        // chars, which a JSON string can technically carry but Apps
        // Script's logger then prints across multiple lines and
        // confuses HTTP body-size accounting. NO_WRAP keeps the
        // payload single-line.
        val dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val payload = UploadBillImagePayload(
            billId = billId,
            fileName = imageFile.name,
            mimeType = MIME_JPEG,
            dataBase64 = dataBase64
        )
        val body = AppsScriptApi.envelope("uploadBillImage", payload)
        val response = apiFactory().uploadBillImage(url, body)

        if (!response.ok || response.fileId == null) {
            val msg = response.errors?.joinToString("; ") { it.message }
                ?.takeIf { it.isNotBlank() }
                ?: "Upload failed (server returned ok=${response.ok}, fileId=${response.fileId})"
            error(msg)
        }
        UploadResult(fileId = response.fileId, viewUrl = response.viewUrl)
    }

    /**
     * Ask the server to delete a Drive file. Used when the user
     * removes an image from a bill — the UI strips the id from the
     * CSV first (so the local Room row is in the desired state even
     * if the network call fails), then fires this. On success the
     * caller may also delete the local cached JPEG via
     * `BillImageCache.deleteFileIfExists`.
     */
    suspend fun deleteFromDrive(fileId: String): AppResult<Unit> = appResultOf {
        val url = settings.sheetUrl.first()
        require(url.isNotBlank()) { "Sheet URL not set" }

        val payload = DeleteBillImagePayload(fileId = fileId)
        val body = AppsScriptApi.envelope("deleteBillImage", payload)
        val response = apiFactory().call(url, body)
        if (!response.ok) {
            val msg = response.errors?.joinToString("; ") { it.message }
                ?.takeIf { it.isNotBlank() }
                ?: "Delete failed (server returned ok=false)"
            error(msg)
        }
        Unit
    }

    private companion object {
        const val MIME_JPEG = "image/jpeg"
    }
}
