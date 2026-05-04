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
    val entries: List<PurchaseEntryEntity> = emptyList()
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
        ItemDetailUiState(isLoading = false, item = item, entries = entries)
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
