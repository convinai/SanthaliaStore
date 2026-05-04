package `in`.santhaliastore.ratecard.ui.screens.item_detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.db.entity.ItemEntity
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity
import `in`.santhaliastore.ratecard.data.repo.ItemRepository
import `in`.santhaliastore.ratecard.data.repo.PurchaseRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ItemDetailUiState(
    val isLoading: Boolean = true,
    val item: ItemEntity? = null,
    val entries: List<PurchaseEntryEntity> = emptyList(),
    // True when the row at this code is missing OR soft-deleted. The
    // screen uses this to render a "yeh item ab nahi raha" error state
    // and disable the FAB so the user cannot create entries against a
    // dead code (which would be silently orphaned).
    val isMissing: Boolean = false
)

class ItemDetailViewModel(
    private val itemCode: String,
    private val itemRepo: ItemRepository,
    private val purchaseRepo: PurchaseRepository
) : ViewModel() {

    val state: StateFlow<ItemDetailUiState> = combine(
        itemRepo.observeItem(itemCode),
        purchaseRepo.observeForItem(itemCode)
    ) { item, entries ->
        // Treat a soft-deleted row as "missing" so the screen never
        // surfaces stale data or accepts writes against a dead code.
        // Bug 1b: a rename moves the row out from under us (deleted=1
        // tombstone) so the back-stack-snapshot of Item Detail must
        // refuse writes instead of silently orphaning them.
        val live = item?.takeUnless { it.deleted }
        ItemDetailUiState(
            isLoading = false,
            item = live,
            entries = entries,
            isMissing = live == null
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ItemDetailUiState())

    fun deleteItem(onDone: () -> Unit) {
        viewModelScope.launch {
            itemRepo.softDelete(itemCode)
            onDone()
        }
    }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch {
            purchaseRepo.softDelete(entryId)
        }
    }

    companion object {
        const val ARG_CODE = "itemCode"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as RateCardApp)
                val code = this[CODE_KEY] ?: error("Missing item code in CreationExtras")
                ItemDetailViewModel(
                    itemCode = code,
                    itemRepo = app.container.itemRepo,
                    purchaseRepo = app.container.purchaseRepo
                )
            }
        }

        // Custom CreationExtras key so we can pass the item code into
        // the VM without using SavedStateHandle (which would require a
        // navigation re-architecture).
        val CODE_KEY = object : CreationExtras.Key<String> {}
    }
}
