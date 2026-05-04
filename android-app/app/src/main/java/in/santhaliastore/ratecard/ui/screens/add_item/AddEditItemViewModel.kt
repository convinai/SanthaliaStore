package `in`.santhaliastore.ratecard.ui.screens.add_item

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.repo.ItemRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Add/edit item.
 *
 * Form data lives on the screen via rememberSaveable; this VM only
 * carries the editing-mode state and the save() side effect. That
 * keeps rotation behaviour simple and avoids state duplication.
 */
class AddEditItemViewModel(
    private val itemRepo: ItemRepository
) : ViewModel() {

    data class Snapshot(
        val isEditMode: Boolean = false,
        val originalCode: String? = null,
        val initialName: String = "",
        val initialUnit: String = "",
        val isLoading: Boolean = false,
        val isSaving: Boolean = false,
        val codeError: String? = null,
        val nameError: String? = null,
        val saved: Boolean = false
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * Bind the VM to a particular code. Pass `null` when adding a new
     * item; pass an existing code to switch into edit mode and prefill
     * the form fields with the row currently in Room.
     */
    fun bind(editingCode: String?) {
        if (_state.value.originalCode == editingCode) return // already bound
        if (editingCode == null) {
            _state.value = Snapshot(isEditMode = false)
            return
        }
        _state.update { it.copy(isLoading = true, isEditMode = true, originalCode = editingCode) }
        viewModelScope.launch {
            val existing = itemRepo.findByCode(editingCode)
            _state.update {
                it.copy(
                    isLoading = false,
                    initialName = existing?.name.orEmpty(),
                    initialUnit = existing?.unit.orEmpty()
                )
            }
        }
    }

    fun save(code: String, name: String, unit: String) {
        val isEdit = _state.value.isEditMode
        val originalCode = _state.value.originalCode
        viewModelScope.launch {
            val trimmedCode = code.trim()
            val trimmedName = name.trim()
            var error = false
            if (trimmedCode.isEmpty()) {
                _state.update { it.copy(codeError = "code_empty") }
                error = true
            }
            if (trimmedName.isEmpty()) {
                _state.update { it.copy(nameError = "name_empty") }
                error = true
            }
            if (error) return@launch

            // Duplicate check — only when adding a new code or changing
            // an existing one. Soft-deleted rows can be re-used.
            if (!isEdit || trimmedCode != originalCode) {
                if (itemRepo.existsActive(trimmedCode)) {
                    _state.update { it.copy(codeError = "code_dup") }
                    return@launch
                }
            }

            _state.update { it.copy(isSaving = true) }
            // If the user changed the code on edit, we soft-delete the
            // old row and write a new one — Room PK is the code.
            if (isEdit && originalCode != null && originalCode != trimmedCode) {
                itemRepo.softDelete(originalCode)
            }
            itemRepo.save(code = trimmedCode, name = trimmedName, unit = unit)
            _state.update { it.copy(isSaving = false, saved = true) }
        }
    }

    fun clearErrors() {
        _state.update { it.copy(codeError = null, nameError = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as RateCardApp)
                AddEditItemViewModel(itemRepo = app.container.itemRepo)
            }
        }
    }
}
