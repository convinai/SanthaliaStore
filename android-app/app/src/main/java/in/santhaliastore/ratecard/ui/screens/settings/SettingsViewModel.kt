package `in`.santhaliastore.ratecard.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.data.repo.BillRepository
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
    purchaseRepo: PurchaseRepository,
    billRepo: BillRepository
) : ViewModel() {

    data class Snapshot(
        val sheetUrl: String = "",
        val pinEnabled: Boolean = false,
        val lastSyncedAt: Long = 0L,
        val lastSyncError: String? = null,
        val testingConnection: Boolean = false,
        val testResult: TestResult? = null,
        val syncing: Boolean = false,
        val resetting: Boolean = false,
        val pendingCount: Int = 0
    )

    sealed interface TestResult {
        data object Ok : TestResult
        data class Failed(val message: String) : TestResult
    }

    private val _testing = MutableStateFlow(false)
    private val _testResult = MutableStateFlow<TestResult?>(null)

    /**
     * Single source of truth for "is anything sync-shaped happening"
     * lives on [SyncRepository.isSyncing]. We collect it here so the
     * Settings snapshot can tick the same flag during:
     *   - manual `Sync now` (Settings or Home)
     *   - app-resume auto-sync
     *   - debounced post-write auto-sync
     * That keeps the Settings "Sync now" button greyed out and the
     * "Last sync" line flipped to "Sync ho raha hai…" for ALL of those
     * cases, so the user never sees the system mid-sync without an
     * indicator. The previous local `_syncing` was redundant once the
     * repository owned the flag.
     */
    private val syncingFlow = syncRepo.isSyncing
    /**
     * Drives the fullscreen blocking spinner shown over the Settings
     * screen while the destructive "Reset local data" action is in
     * flight. Independent from [syncingFlow] because reset includes
     * both a local wipe and a full pull, and we want a stronger UI
     * gate (the user just confirmed a destructive action — they
     * shouldn't be able to tap anything else until the recovery
     * resolves).
     */
    private val _resetting = MutableStateFlow(false)

    private val pendingCountFlow = combine(
        itemRepo.observePendingCount(),
        purchaseRepo.observePendingCount(),
        billRepo.observePendingCount()
    ) { items, entries, bills -> items + entries + bills }

    /** One-shot UI events (snackbars). Buffered so we never drop one. */
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Sync outcome events. The Compose layer translates these into
     * stringResource-backed snackbars so all user-visible copy stays
     * in `res/values/strings.xml` (and easy to localise later).
     *
     * `SyncSuccess` carries the bidirectional counts from
     * [SyncRepository.runFullSyncNow] — pushed rows are the local
     * pending edits that hit the sheet, pulled* are the rows the
     * server told us about that we applied locally.
     */
    sealed interface UiEvent {
        // `pulledBills` is defaulted so existing snackbar consumers that
        // pre-date Bills keep compiling — they ignore the field and
        // count only items + entries; sites that care about bills add
        // the field into their sum.
        data class SyncSuccess(
            val pushed: Int,
            val pulledItems: Int,
            val pulledEntries: Int,
            val pulledBills: Int = 0
        ) : UiEvent
        data class SyncFailure(val message: String) : UiEvent

        /**
         * The destructive "Reset local data" action completed cleanly —
         * the local DB was wiped AND the post-reset full pull
         * succeeded, applying the carried counts.
         */
        data class ResetSuccess(
            val itemsApplied: Int,
            val entriesApplied: Int,
            val billsApplied: Int = 0
        ) : UiEvent

        /**
         * Reset failed. Could mean either:
         *   - the local wipe itself blew up (rare — IO error in Room), or
         *   - the wipe succeeded but the subsequent pull failed (more
         *     likely — bad network, bad URL).
         *
         * In the second case the local DB is left empty and
         * `lastSyncError` carries the same message; the user can retry
         * by tapping Sync now without re-confirming the destructive
         * dialog.
         */
        data class ResetFailure(val message: String) : UiEvent
    }

    val state: StateFlow<Snapshot> = combine(
        settingsRepo.sheetUrl,
        settingsRepo.pinEnabled,
        settingsRepo.lastSyncedAt,
        settingsRepo.lastSyncError,
        combine(_testing, _testResult, syncingFlow, _resetting, pendingCountFlow) {
                testing, testRes, syncing, resetting, pending ->
            Quint(testing, testRes, syncing, resetting, pending)
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
            resetting = q.resetting,
            pendingCount = q.pending
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Snapshot())

    private data class Quint(
        val testing: Boolean,
        val testRes: TestResult?,
        val syncing: Boolean,
        val resetting: Boolean,
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
     * "Sync now" — runs push → pull inline (no background work) so the
     * outcome shows up on the Settings screen immediately.
     *
     * Surfaces every outcome via the snackbar channel:
     *   - Success           -> [UiEvent.SyncSuccess] with the three
     *                          bidirectional counts (pushed rows,
     *                          pulled items, pulled entries).
     *   - Failure           -> [UiEvent.SyncFailure] carrying the
     *                          error message verbatim.
     *
     * Re-entrancy: the syncing flag is read from
     * [SyncRepository.isSyncing] (the same flag that's flipped by
     * auto-sync paths) so a tap arriving while ANY sync is in flight
     * is dropped on the floor here. The repository's mutex guarantees
     * the same at the data layer, so even if the UI guard slips, two
     * syncs cannot run in parallel.
     */
    fun syncNow() {
        if (syncingFlow.value) return // ignore taps while any sync is running
        viewModelScope.launch {
            val result = syncRepo.runFullSyncNow()
            val event = when (result) {
                is AppResult.Ok -> UiEvent.SyncSuccess(
                    pushed = result.value.pushedRows,
                    pulledItems = result.value.pulledItems,
                    pulledEntries = result.value.pulledEntries,
                    pulledBills = result.value.pulledBills
                )
                is AppResult.Err -> UiEvent.SyncFailure(result.message)
            }
            _events.trySend(event)
        }
    }

    /**
     * Destructive recovery — wipe every local item / entry and pull
     * the entire dataset back from the sheet. Confirmed by the user
     * via the dialog in [SettingsScreen]; this method assumes consent
     * and runs immediately.
     *
     * Surfaces the outcome via the snackbar channel:
     *   - Success → [UiEvent.ResetSuccess] carrying the freshly-pulled
     *               item / entry counts so the snackbar can confirm
     *               "Reset ho gaya — N items, M entries fresh load".
     *   - Failure → [UiEvent.ResetFailure] with the error message.
     *               Note: even on failure the local DB is left wiped
     *               (per [SyncRepository.resetLocalAndPullFresh]'s
     *               documented contract) — the user can tap Sync now
     *               to retry without re-confirming the dialog.
     *
     * Re-entrancy: a tap while already resetting is ignored — the
     * dialog's confirm button is one-shot but a stuck network plus a
     * fast double-tap could otherwise queue two resets, the second of
     * which would race the first's pull.
     */
    fun resetLocalData() {
        if (_resetting.value) return
        _resetting.value = true
        viewModelScope.launch {
            val result = syncRepo.resetLocalAndPullFresh()
            _resetting.value = false
            val event = when (result) {
                is AppResult.Ok -> UiEvent.ResetSuccess(
                    itemsApplied = result.value.pulledItems,
                    entriesApplied = result.value.pulledEntries,
                    billsApplied = result.value.pulledBills
                )
                is AppResult.Err -> UiEvent.ResetFailure(result.message)
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
                    purchaseRepo = app.container.purchaseRepo,
                    billRepo = app.container.billRepo
                )
            }
        }
    }
}
