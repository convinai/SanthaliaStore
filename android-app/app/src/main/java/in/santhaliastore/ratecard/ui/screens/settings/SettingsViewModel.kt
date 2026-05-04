package `in`.santhaliastore.ratecard.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.sync.SyncRepository
import `in`.santhaliastore.ratecard.util.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val syncRepo: SyncRepository
) : ViewModel() {

    data class Snapshot(
        val sheetUrl: String = "",
        val pinEnabled: Boolean = false,
        val lastSyncedAt: Long = 0L,
        val lastSyncError: String? = null,
        val testingConnection: Boolean = false,
        val testResult: TestResult? = null
    )

    sealed interface TestResult {
        data object Ok : TestResult
        data class Failed(val message: String) : TestResult
    }

    private val _testing = MutableStateFlow(false)
    private val _testResult = MutableStateFlow<TestResult?>(null)

    val state: StateFlow<Snapshot> = combine(
        settingsRepo.sheetUrl,
        settingsRepo.pinEnabled,
        settingsRepo.lastSyncedAt,
        settingsRepo.lastSyncError,
        combine(_testing, _testResult) { t, r -> t to r }
    ) { url, pin, last, err, (testing, testRes) ->
        Snapshot(
            sheetUrl = url,
            pinEnabled = pin,
            lastSyncedAt = last,
            lastSyncError = err,
            testingConnection = testing,
            testResult = testRes
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Snapshot())

    fun setSheetUrl(url: String) {
        viewModelScope.launch { settingsRepo.setSheetUrl(url) }
        _testResult.value = null
    }

    fun togglePin(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setPinEnabled(enabled) }
    }

    fun setPin(rawPin: String) {
        viewModelScope.launch { settingsRepo.setPin(rawPin) }
    }

    /**
     * "Sync now" pushes the full local rate card up to the sheet —
     * not just the rows that changed since last sync. Marks every
     * active item + entry pending, then triggers the worker.
     */
    fun syncNow() {
        viewModelScope.launch {
            syncRepo.requestFullSync()
        }
    }

    fun testConnection() {
        _testing.value = true
        _testResult.value = null
        viewModelScope.launch {
            val result = syncRepo.ping()
            _testResult.update {
                when (result) {
                    is AppResult.Ok -> TestResult.Ok
                    is AppResult.Err -> TestResult.Failed(result.message)
                }
            }
            _testing.value = false
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as RateCardApp)
                SettingsViewModel(
                    settingsRepo = app.container.settingsRepo,
                    syncRepo = app.container.syncRepo
                )
            }
        }
    }
}
