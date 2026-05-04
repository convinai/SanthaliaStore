package `in`.santhaliastore.ratecard.sync

import com.squareup.moshi.JsonClass

/**
 * DTOs shipped to / received from the Apps Script web app. Field
 * names MUST exactly match the contract documented in apps-script/Code.gs:
 *
 *   POST { action, payload }
 *
 * Anything renamed here breaks the cloud sync silently. Treat with care.
 */

/** Top-level request envelope: `{ "action": "...", "payload": {...} }`. */
@JsonClass(generateAdapter = true)
data class SyncRequest<T>(
    val action: String,
    val payload: T
)

/** Server response envelope. `errors` is omitted when everything succeeded. */
@JsonClass(generateAdapter = true)
data class SyncResponse(
    val ok: Boolean,
    val processed: Int,
    val action: String? = null,
    val schemaVersion: Int? = null,
    val time: String? = null,
    val errors: List<SyncError>? = null
)

@JsonClass(generateAdapter = true)
data class SyncError(
    val index: Int,
    val key: String?,
    val message: String
)

/**
 * Health check payload — empty per the contract.
 *
 * Moshi's reflective adapter (KotlinJsonAdapterFactory) serialises
 * a no-arg class as an empty `{}`, which is exactly what the server
 * expects.
 */
class HealthPayload

/** `upsertItem` payload. `unit` is nullable. */
@JsonClass(generateAdapter = true)
data class UpsertItemPayload(
    val code: String,
    val name: String,
    val unit: String?,
    val updatedAt: String
)

/** `deleteItem` payload. */
@JsonClass(generateAdapter = true)
data class DeleteItemPayload(
    val code: String,
    val updatedAt: String
)

/** `upsertEntry` payload. `quantity`, `supplier`, `notes` nullable. */
@JsonClass(generateAdapter = true)
data class UpsertEntryPayload(
    val entryId: String,
    val itemCode: String,
    val date: String,
    val pricePerUnit: Double,
    val quantity: Double?,
    val supplier: String?,
    val notes: String?,
    val updatedAt: String
)

/** `deleteEntry` payload. */
@JsonClass(generateAdapter = true)
data class DeleteEntryPayload(
    val entryId: String,
    val updatedAt: String
)

/**
 * `bulkSync` payload. The server caps total changes at 200 per
 * call (see apps-script BULK_LIMIT) so the worker batches accordingly.
 */
@JsonClass(generateAdapter = true)
data class BulkSyncPayload(
    val items: List<UpsertItemPayload>,
    val entries: List<UpsertEntryPayload>,
    val deletedItems: List<DeleteItemPayload>,
    val deletedEntries: List<DeleteEntryPayload>
)
