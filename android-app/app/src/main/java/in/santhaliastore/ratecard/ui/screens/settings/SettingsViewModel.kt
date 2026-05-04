package `in`.santhaliastore.ratecard.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.data.repo.ItemRepository
import `in`.santhaliastore.ratecard.data.repo.PurchaseRepository
import `in`.santhaliastore.ratecard.sync.SyncRepository
import `in`.santhaliastore.ratecard.util.AppResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val syncRepo: SyncRepository,
    itemRepo: ItemRepository,
    purchaseRepo: PurchaseRepository
) : ViewModel() {

    data class Snapshot(
        val sheetUrl: String = "",
        val pinEnabled: Boolean = false,
        val lastSyncedAt: Long = 0L,
        val lastSyncError: String? = null,
        val testingConnection: Boolean = false,
        val testResult: TestResult? = null,
        val syncing: Boolean = false,
        val pendingCount: Int = 0
    )

    sealed interface TestResult {
        data object Ok : TestResult
        data class Failed(val message: String) : TestResult
    }

    private val _testing = MutableStateFlow(false)
    private val _testResult = MutableStateFlow<TestResult?>(null)
    private val _syncing = MutableStateFlow(false)

    private val pendingCountFlow = combine(
        itemRepo.observePendingCount(),
        purchaseRepo.observePendingCount()
    ) { items, entries -> items + entries }

    /** One-shot UI events (snackbars). Buffered so we never drop one. */
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Sync outcome events. The Compose layer translates these into
     * stringResource-backed snackbars so all user-visible copy stays
     * in `res/values/strings.xml` (and easy to localise later).
     */
    sealed interface UiEvent {
        data class SyncSuccess(val processed: Int) : UiEvent
        data class SyncFailure(val message: String) : UiEvent
    }

    val state: StateFlow<Snapshot> = combine(
        settingsRepo.sheetUrl,
        settingsRepo.pinEnabled,
        settingsRepo.lastSyncedAt,
        settingsRepo.lastSyncError,
        combine(_testing, _testResult, _syncing, pendingCountFlow) {
                testing, testRes, syncing, pending ->
            Quad(testing, testRes, syncing, pending)
        }
    ) { url, pin, last, err, q ->
        Snapshot(
            sheetUrl = url,
            pinEnabled = pin,
            lastSyncedAt = last,
            lastSyncError = err,
            testingConnection = q.testing,
            testResult = q.testRes,
            syncing = q.syncing,
            pendingCount = q.pending
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Snapshot())

    private data class Quad(
        val testing: Boolean,
        val testRes: TestResult?,
        val syncing: Boolean,
        val pending: Int
    )

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
     * "Sync now" — runs the push inline (NOT via WorkManager) so the
     * outcome shows up on the Settings screen immediately. The
     * background worker still handles auto-sync after writes.
     *
     * Surfaces every outcome via the snackbar channel:
     *   - Success with rows  -> "Sync ho gaya — N rows"
     *   - Success with zero  -> "Sync ho gaya — kuch naya nahi"
     *   - Failure            -> the error message verbatim
     */
    fun syncNow() {
        if (_syncing.value) return // ignore double taps
        _syncing.value = true
        viewModelScope.launch {
            val result = syncRepo.runFullSyncNow()
            _syncing.value = false
            val event = when (result) {
                is AppResult.Ok -> UiEvent.SyncSuccess(result.value)
                is AppResult.Err -> UiEvent.SyncFailure(result.message)
            }
            _events.trySend(event)
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
                    syncRepo = app.container.syncRepo,
                    itemRepo = app.container.itemRepo,
                    purchaseRepo = app.container.purchaseRepo
                )
            }
        }
    }
}
