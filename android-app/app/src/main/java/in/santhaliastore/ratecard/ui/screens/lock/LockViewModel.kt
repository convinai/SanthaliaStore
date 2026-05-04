package `in`.santhaliastore.ratecard.ui.screens.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the unlock UX. The actual gating (full-screen overlay vs.
 * normal navigation) lives in MainActivity — this VM just verifies
 * a PIN attempt and exposes a simple error state.
 */
class LockViewModel(
    private val settings: SettingsRepository
) : ViewModel() {

    data class Snapshot(
        val attempt: String = "",
        val error: Boolean = false,
        val unlocked: Boolean = false
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun onDigit(d: Char) {
        val next = (_state.value.attempt + d).take(4)
        _state.update { it.copy(attempt = next, error = false) }
        if (next.length == 4) verify(next)
    }

    fun onBackspace() {
        _state.update {
            it.copy(attempt = it.attempt.dropLast(1), error = false)
        }
    }

    private fun verify(pin: String) {
        viewModelScope.launch {
            val ok = settings.verifyPin(pin)
            if (ok) {
                settings.stampForegroundNow()
                _state.update { it.copy(unlocked = true) }
            } else {
                _state.update { it.copy(error = true, attempt = "") }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as RateCardApp)
                LockViewModel(settings = app.container.settingsRepo)
            }
        }
    }
}
