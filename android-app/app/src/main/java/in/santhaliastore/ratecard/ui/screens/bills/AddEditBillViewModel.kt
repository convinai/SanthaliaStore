package `in`.santhaliastore.ratecard.ui.screens.bills

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.repo.BillRepository
import `in`.santhaliastore.ratecard.sync.BillImageUploader
import `in`.santhaliastore.ratecard.util.BillImageCache
import `in`.santhaliastore.ratecard.util.BillImageCompressor
import `in`.santhaliastore.ratecard.util.Money
import `in`.santhaliastore.ratecard.util.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * ViewModel for the add / edit bill screen.
 *
 * Responsibilities:
 *   - Owns a stable [billId] (UUID v4) for the row — generated up
 *     front in *add* mode so the image compressor can name its files
 *     by `(billId, indexInBill)` before the row is persisted, and
 *     bound from the route arg in *edit* mode.
 *   - Maintains the in-progress list of [StagedImage] entries (local
 *     path, optional Drive id, upload state). The screen reflects
 *     this in a horizontal thumbnail row.
 *   - Coordinates the image lifecycle:
 *       * `addImageFromUri` runs the compressor and appends a
 *         [StagedImage] with status [UploadStatus.Pending].
 *       * `removeImage` deletes the local cache file and (if the
 *         image had already been uploaded) fires a best-effort
 *         Drive delete on the long-lived [applicationScope] so the
 *         deletion completes even if the user backs out.
 *       * `save` persists the row locally with whatever Drive ids
 *         we already have, then launches the upload pipeline on
 *         [applicationScope] so it survives the screen popping.
 *
 * The ViewModel uses [applicationScope] (passed from RateCardApp)
 * rather than [viewModelScope] for any fire-and-forget upload work.
 * The user's expected mental model is "I tapped Save, the bill is
 * saved" — they may then back out before all uploads complete.
 * Tying uploads to the VM scope would cancel them on screen close
 * and leave the bill with a permanently-pending upload state.
 *
 * [viewModelScope] is still used for everything else (compression
 * progress, validation) because those flows are bound to the screen
 * being open.
 */
class AddEditBillViewModel(
    private val billRepo: BillRepository,
    private val cache: BillImageCache,
    private val compressor: BillImageCompressor,
    private val uploader: BillImageUploader,
    private val applicationScope: CoroutineScope
) : ViewModel() {

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * Bind the VM to either a new bill (id == null) or an existing
     * one. Idempotent: called from a LaunchedEffect on the screen so
     * it survives recomposition.
     */
    fun bind(editingBillId: String?) {
        if (_state.value.bound && _state.value.billId == (editingBillId ?: _state.value.billId)) {
            return
        }
        if (editingBillId == null) {
            // New bill — generate a fresh UUID so the compressor can
            // start writing into a deterministic path immediately.
            _state.value = Snapshot(
                bound = true,
                billId = UUID.randomUUID().toString(),
                isEditMode = false,
                date = Time.todayLocal()
            )
            return
        }
        _state.update {
            it.copy(
                bound = true,
                billId = editingBillId,
                isEditMode = true,
                isLoading = true
            )
        }
        viewModelScope.launch {
            val existing = billRepo.findById(editingBillId)
            if (existing == null) {
                // Bill vanished out from under us. Surface a non-fatal
                // error and leave the form blank — the user can still
                // capture a fresh bill at this id.
                _state.update {
                    it.copy(
                        isLoading = false,
                        loadError = "missing",
                        date = Time.todayLocal()
                    )
                }
                return@launch
            }
            val driveIds = existing.imageFileIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val localPaths = existing.localImagePaths.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val staged = (0 until maxOf(driveIds.size, localPaths.size)).map { i ->
                StagedImage(
                    localPath = localPaths.getOrNull(i)?.takeIf { it.isNotEmpty() },
                    driveFileId = driveIds.getOrNull(i)?.takeIf { it.isNotEmpty() },
                    status = if (driveIds.getOrNull(i).isNullOrEmpty()) UploadStatus.Pending
                    else UploadStatus.Uploaded
                )
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    date = existing.date,
                    supplier = existing.supplier.orEmpty(),
                    amount = existing.totalAmount?.let { v -> Money.plain(v) }.orEmpty(),
                    notes = existing.notes.orEmpty(),
                    images = staged
                )
            }
        }
    }

    fun onDateChange(value: String) = _state.update { it.copy(date = value, saveError = null) }
    fun onSupplierChange(value: String) = _state.update { it.copy(supplier = value, saveError = null) }
    fun onAmountChange(value: String) {
        // Allow digits + at most one decimal. Mirrors AddEditItem's
        // price field input filter so the user gets identical
        // behaviour across forms.
        val filtered = value.filter { it.isDigit() || it == '.' }
        _state.update { it.copy(amount = filtered, amountError = null, saveError = null) }
    }

    fun onNotesChange(value: String) = _state.update { it.copy(notes = value, saveError = null) }

    /**
     * Compress a freshly-captured / picked image into the local
     * cache and append it to the staged list.
     *
     * Runs on viewModelScope (NOT applicationScope): compression is
     * fast and only useful if the screen is still open to receive
     * the result. If the user backs out mid-compression we drop the
     * work.
     *
     * Capacity enforcement and the index increment happen
     * *synchronously* inside the state update before the coroutine
     * launches. This matters when the caller fires several pickups in
     * a tight loop (e.g. the gallery contract returns 5 URIs and the
     * screen iterates them): each call sees a fresh post-reservation
     * snapshot, so the 6th request correctly surfaces the
     * `maxImagesReached` flag even though no compression has
     * finished yet.
     *
     * `processing` is bumped before and decremented after so the
     * screen can show a busy spinner while the work is in flight.
     */
    fun addImageFromUri(uri: Uri) {
        val billId = _state.value.billId ?: return
        // Reservation step: synchronously bump processing + capture
        // index OR flag the cap. `reservedIndex == null` means we hit
        // the cap and the coroutine below should bail out.
        var reservedIndex: Int? = null
        _state.update { snap ->
            // Reserved-slot count = already-in-list + in-flight
            // compressions. The compression result only appends on
            // success, but `processing` is decremented on both paths,
            // so reservation = images.size + processing is the right
            // ceiling.
            if (snap.images.size + snap.processing >= MAX_IMAGES) {
                return@update snap.copy(maxImagesReached = true)
            }
            reservedIndex = snap.nextCaptureIndex
            snap.copy(
                processing = snap.processing + 1,
                nextCaptureIndex = snap.nextCaptureIndex + 1
            )
        }
        val nextIndex = reservedIndex ?: return
        viewModelScope.launch {
            val result = runCatching {
                compressor.compressIntoCache(uri, billId, nextIndex)
            }
            _state.update { snap ->
                val newImages = if (result.isSuccess) {
                    snap.images + StagedImage(
                        localPath = result.getOrNull()!!.absolutePath,
                        driveFileId = null,
                        status = UploadStatus.Pending
                    )
                } else snap.images
                snap.copy(
                    images = newImages,
                    processing = (snap.processing - 1).coerceAtLeast(0),
                    captureError = result.exceptionOrNull()?.message
                )
            }
        }
    }

    /**
     * Remove a staged image. Three sub-cases:
     *
     *   1. Local-only, never uploaded → delete the cache file and
     *      drop the row from `images`.
     *   2. Already uploaded → fire-and-forget delete from Drive on
     *      the long-lived application scope (so it doesn't abort if
     *      the user backs out), delete the local cache copy, and
     *      drop the row.
     *   3. Drive-only (no local copy, e.g. arrived via sync) → fire
     *      the Drive delete; nothing to do on disk.
     */
    fun removeImage(index: Int) {
        var target: StagedImage? = null
        _state.update { snap ->
            if (index !in snap.images.indices) return@update snap
            target = snap.images[index]
            snap.copy(images = snap.images.toMutableList().also { it.removeAt(index) })
        }
        val removed = target ?: return
        // Drop the local cache copy first — the file's tiny and the
        // user just told us they don't want it.
        removed.localPath?.let { cache.deleteFileIfExists(it) }
        // Drive delete is fire-and-forget on the long-lived app
        // scope so it survives the user backing out of the screen.
        // We ignore the result deliberately: if it fails, the file
        // lingers on Drive but the bill row is no longer referencing
        // it, so it's already invisible in the app.
        removed.driveFileId?.let { id ->
            applicationScope.launch {
                runCatching { uploader.deleteFromDrive(id) }
            }
        }
    }

    fun clearCaptureError() = _state.update { it.copy(captureError = null) }
    fun clearMaxImagesFlag() = _state.update { it.copy(maxImagesReached = false) }

    /**
     * Persist the bill locally and queue uploads for any
     * still-pending images.
     *
     * Save is synchronous from the user's perspective: the row hits
     * Room before we return to the navigator, so the bills list
     * paints the new entry on the next observation. Uploads happen
     * after the row is written, on the long-lived application
     * scope, so they finish (or fail and surface for next sync) even
     * if the screen pops mid-upload.
     *
     * Validation:
     *   - At least one image OR a supplier name OR an amount is
     *     required. A row with none of those is a paper artefact
     *     the user almost certainly doesn't want; failing the save
     *     prevents accidental "empty bill" rows from polluting the
     *     list.
     */
    /**
     * Persist the row and queue uploads. Returns through the
     * `saved = true` flag on [state]; the screen owns the actual
     * navigation pop via a LaunchedEffect on that flag. Keeping the
     * navigation decision in the screen avoids the VM having to know
     * about a `(String) -> Unit` callback that would otherwise
     * couple the data layer to Compose semantics.
     */
    fun save() {
        val snap = _state.value
        val billId = snap.billId ?: return
        if (snap.images.isEmpty() && snap.supplier.isBlank() && snap.amount.isBlank()) {
            _state.update { it.copy(saveError = "empty") }
            return
        }
        val amountValue = if (snap.amount.isNotBlank()) {
            val parsed = snap.amount.toDoubleOrNull()
            if (parsed == null || parsed <= 0.0) {
                _state.update { it.copy(amountError = "invalid") }
                return
            }
            parsed
        } else null

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            // Snapshot the staged image lists. Anything with a Drive
            // id already (edit mode, partial-upload reload) goes in
            // imageFileIds; the corresponding local path goes in
            // localImagePaths at the matching index.
            val driveIds = snap.images.map { it.driveFileId.orEmpty() }
            val localPaths = snap.images.map { it.localPath.orEmpty() }
            val driveCsv = driveIds.joinToString(",")
            val localCsv = localPaths.joinToString(",")

            billRepo.save(
                id = billId,
                date = snap.date.ifBlank { Time.todayLocal() },
                supplier = snap.supplier.trim().ifEmpty { null },
                totalAmount = amountValue,
                notes = snap.notes.trim().ifEmpty { null },
                imageFileIds = driveCsv,
                localImagePaths = localCsv
            )

            _state.update { it.copy(isSaving = false, saved = true) }

            // Kick off uploads for everything still pending. The
            // uploadPendingImages call lives on the application
            // scope so it outlives the screen.
            applicationScope.launch {
                uploadPendingImages(
                    billId = billId,
                    staged = snap.images
                )
            }
        }
    }

    /**
     * Walk the staged images, upload anything without a Drive id,
     * and write each result back onto the bill row as it lands.
     *
     * Each successful upload causes a [BillRepository.updateImageState]
     * call — one round-trip per image. This is intentional: a single
     * batched write at the end would lose partial progress if the
     * process dies mid-flight, AND the per-image write feeds the
     * Bills list's observed flow so the pending-upload chip
     * disappears from each row as soon as its image lands on Drive.
     *
     * Failures don't roll back. The local row stays at
     * `pendingSync = true`, the next sync (or a retry from the
     * screen) will pick the work up from the same state.
     */
    private suspend fun uploadPendingImages(
        billId: String,
        staged: List<StagedImage>
    ) {
        // Mutable list so we can append Drive ids as they come back.
        val driveIds = staged.map { it.driveFileId.orEmpty() }.toMutableList()
        val localPaths = staged.map { it.localPath.orEmpty() }.toMutableList()

        staged.forEachIndexed { index, item ->
            if (!item.driveFileId.isNullOrEmpty()) return@forEachIndexed
            val path = item.localPath ?: return@forEachIndexed
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return@forEachIndexed

            val result = uploader.upload(billId, file)
            val ok = result as? `in`.santhaliastore.ratecard.util.AppResult.Ok ?: return@forEachIndexed
            driveIds[index] = ok.value.fileId
            // Persist the partial state. Each write bumps
            // pendingSync so the sync layer re-pushes the row with
            // the latest Drive id set.
            billRepo.updateImageState(
                id = billId,
                imageFileIds = driveIds.joinToString(","),
                localImagePaths = localPaths.joinToString(",")
            )
        }
    }

    @Immutable
    data class Snapshot(
        val bound: Boolean = false,
        val billId: String? = null,
        val isEditMode: Boolean = false,
        val isLoading: Boolean = false,
        val loadError: String? = null,
        val isSaving: Boolean = false,
        val saved: Boolean = false,
        val saveError: String? = null,

        val date: String = "",
        val supplier: String = "",
        val amount: String = "",
        val amountError: String? = null,
        val notes: String = "",

        val images: List<StagedImage> = emptyList(),
        // Captured-image counter — drives the indexInBill argument to
        // BillImageCompressor.compressIntoCache. Monotonic across
        // remove operations so a remove-then-add doesn't collide on
        // an already-deleted index's cache file.
        val nextCaptureIndex: Int = 0,
        // Live count of in-flight compression jobs. The screen reads
        // this to decide whether to show a busy spinner near the
        // photo strip.
        val processing: Int = 0,
        val captureError: String? = null,
        val maxImagesReached: Boolean = false
    )

    @Immutable
    data class StagedImage(
        val localPath: String?,
        val driveFileId: String?,
        val status: UploadStatus
    )

    enum class UploadStatus { Pending, Uploading, Uploaded, Failed }

    companion object {
        /**
         * Cap per bill. Five photos is enough for a multi-page bill
         * (most kirana bills are 1–3 pages) without letting a stray
         * gallery multi-select dump dozens of unrelated photos into
         * a single row. Matches the
         * `bill_capture_max_reached` toast copy.
         */
        const val MAX_IMAGES: Int = 5

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as RateCardApp)
                AddEditBillViewModel(
                    billRepo = app.container.billRepo,
                    cache = app.container.billImageCache,
                    compressor = app.container.billImageCompressor,
                    uploader = app.container.billImageUploader,
                    applicationScope = app.appScope
                )
            }
        }
    }
}
