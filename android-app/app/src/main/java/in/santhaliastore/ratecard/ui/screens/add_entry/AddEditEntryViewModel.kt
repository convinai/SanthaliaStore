package `in`.santhaliastore.ratecard.ui.screens.add_entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.repo.PurchaseRepository
import `in`.santhaliastore.ratecard.util.Time
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Add/edit purchase entry. Mirrors AddEditItemViewModel — form data
 * sits on the screen, this VM only carries side effects and the
 * "snapshot" needed to seed the form when editing.
 */
class AddEditEntryViewModel(
    private val purchaseRepo: PurchaseRepository
) : ViewModel() {

    data class Snapshot(
        val isEditMode: Boolean = false,
        val originalEntryId: String? = null,
        val resolvedItemCode: String = "",
        val initialDate: String = Time.todayLocal(),
        val initialPrice: String = "",
        val initialQuantity: String = "",
        val initialSupplier: String = "",
        val initialNotes: String = "",
        val isLoading: Boolean = false,
        val isSaving: Boolean = false,
        val priceError: String? = null,
        val dateError: String? = null,
        val saved: Boolean = false
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun bind(itemCode: String, editingEntryId: String?) {
        if (editingEntryId == null) {
            // Add mode — set the item code, leave defaults.
            if (_state.value.resolvedItemCode == itemCode) return
            _state.value = Snapshot(resolvedItemCode = itemCode)
            return
        }
        if (_state.value.originalEntryId == editingEntryId) return // already bound
        _state.update {
            it.copy(
                isLoading = true,
                isEditMode = true,
                originalEntryId = editingEntryId
            )
        }
        viewModelScope.launch {
            val existing = purchaseRepo.findById(editingEntryId)
            if (existing != null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        resolvedItemCode = existing.itemCode,
                        initialDate = existing.date,
                        initialPrice = existing.pricePerUnit.toString(),
                        initialQuantity = existing.quantity.orEmpty(),
                        initialSupplier = existing.supplier.orEmpty(),
                        initialNotes = existing.notes.orEmpty()
                    )
                }
            } else {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun save(
        date: String,
        priceText: String,
        quantityText: String,
        supplier: String,
        notes: String
    ) {
        val price = `in`.santhaliastore.ratecard.util.Money.parse(priceText)
        if (price == null || price < 0.0) {
            _state.update { it.copy(priceError = "price_invalid") }
            return
        }
        if (date.isBlank()) {
            _state.update { it.copy(dateError = "date_invalid") }
            return
        }

        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            purchaseRepo.save(
                entryId = current.originalEntryId,
                itemCode = current.resolvedItemCode,
                date = date,
                pricePerUnit = price,
                // Free-form text — the repo trims and nulls empty.
                quantity = quantityText,
                supplier = supplier,
                notes = notes
            )
            _state.update { it.copy(isSaving = false, saved = true) }
        }
    }

    fun clearErrors() {
        _state.update { it.copy(priceError = null, dateError = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as RateCardApp)
                AddEditEntryViewModel(purchaseRepo = app.container.purchaseRepo)
            }
        }
    }
}
