package `in`.santhaliastore.ratecard.ui.screens.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.db.entity.BillEntity
import `in`.santhaliastore.ratecard.data.repo.BillRepository
import `in`.santhaliastore.ratecard.util.BillImageCache
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the bill-detail screen.
 *
 * Holds the bill id given to it via CreationExtras, exposes the live
 * [BillEntity] (nullable — see below), and offers a `delete()`
 * convenience that wraps the repo's soft-delete.
 *
 * Why is the entity nullable?
 *   - On a fresh launch the Room observe-flow hasn't emitted yet; we
 *     render a loading state in the UI.
 *   - If another device deletes this bill while we're viewing it, the
 *     observe-flow emits null. The UI swaps to a "yeh bill ab nahi
 *     hai" message and offers a back button — same shape as
 *     ItemDetailScreen's missing-row recovery.
 *
 * `cache` is exposed so the screen can compute the local file path
 * for a Drive id (rehydrate flow) and persist the cached path back
 * via [updateImageState]. The VM doesn't itself download images —
 * Coil does that lazily — but it owns the bookkeeping that writes a
 * just-cached Drive id back into the row's `localImagePaths`.
 */
class BillDetailViewModel(
    private val billId: String,
    private val billRepo: BillRepository,
    val cache: BillImageCache
) : ViewModel() {

    /**
     * Live entity, null when missing / soft-deleted. We derive from
     * [BillRepository.observeAll] + filter rather than a dedicated
     * `observeById` because the repo API doesn't expose one, and the
     * cost is trivial (a few dozen rows in the user's lifetime).
     */
    val bill: StateFlow<BillState> =
        billRepo.observeAll()
            .map { all ->
                val found = all.firstOrNull { it.id == billId && !it.deleted }
                if (found == null) BillState.Missing else BillState.Loaded(found)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, BillState.Loading)

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            billRepo.softDelete(billId)
            onDone()
        }
    }

    /**
     * Persist a freshly-downloaded local cache path back onto the
     * bill row. Called from the carousel after Coil successfully
     * paints a Drive-only image into the on-disk cache; this writes
     * the local path so subsequent opens (and the thumbnail in the
     * list) can paint instantly from disk.
     *
     * `localPathsCsv` is the FULL new CSV (caller has already
     * computed the merge), not just the new path.
     */
    fun updateImageState(localPathsCsv: String, imageFileIdsCsv: String) {
        viewModelScope.launch {
            billRepo.updateImageState(
                id = billId,
                imageFileIds = imageFileIdsCsv,
                localImagePaths = localPathsCsv
            )
        }
    }

    /**
     * Three-state load model so the screen can distinguish "still
     * loading" from "the bill is gone".
     */
    sealed interface BillState {
        data object Loading : BillState
        data object Missing : BillState
        data class Loaded(val bill: BillEntity) : BillState
    }

    companion object {
        val ID_KEY = object : CreationExtras.Key<String> {}

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as RateCardApp)
                val id = this[ID_KEY] ?: error("Missing bill id in CreationExtras")
                BillDetailViewModel(
                    billId = id,
                    billRepo = app.container.billRepo,
                    cache = app.container.billImageCache
                )
            }
        }
    }
}
