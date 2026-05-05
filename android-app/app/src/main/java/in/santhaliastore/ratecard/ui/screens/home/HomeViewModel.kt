package `in`.santhaliastore.ratecard.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.WorkInfo
import androidx.work.WorkManager
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.db.entity.ItemWithLastEntry
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.data.repo.ItemRepository
import `in`.santhaliastore.ratecard.sync.SyncRepository
import `in`.santhaliastore.ratecard.ui.components.SyncStatus
import `in`.santhaliastore.ratecard.util.AppResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the home / search list.
 *
 * Search input is debounced 300ms before kicking off a new FTS query.
 * The `pagedItems` flow swaps between "all items" and "search results"
 * via flatMapLatest so the UI never sees stale data.
 *
 * The Home screen also exposes a manual refresh button that calls
 * [syncNow] — the same push-then-pull flow the Settings screen drives.
 * Outcome events are surfaced via the [events] channel so the screen
 * can show a snackbar matching whatever Settings would show.
 */
class HomeViewModel(
    private val itemRepo: ItemRepository,
    private val syncRepo: SyncRepository,
    settingsRepo: SettingsRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val pagedItems: Flow<PagingData<ItemWithLastEntry>> =
        _query
            .debounce(300L)
            .flatMapLatest { q ->
                if (q.isBlank()) itemRepo.pagedItems()
                else itemRepo.searchItems(q)
            }
            .cachedIn(viewModelScope)

    val totalCount: StateFlow<Int> = itemRepo.observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Sync status derived from:
     *   - WorkManager state (is the worker running right now?)
     *   - last sync error string in DataStore
     *   - presence of pending rows
     */
    val syncStatus: StateFlow<SyncStatus> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(SyncRepository.UNIQUE_WORK_NAME),
        settingsRepo.lastSyncError,
        itemRepo.observeActiveCount() // proxy for "anything in DB"; pendingSync flow lives on PurchaseRepository too but the worker covers both
    ) { workInfos, lastError, _ ->
        when {
            workInfos.any { it.state == WorkInfo.State.RUNNING } -> SyncStatus.InProgress
            workInfos.any { it.state == WorkInfo.State.ENQUEUED } -> SyncStatus.Pending
            !lastError.isNullOrBlank() -> SyncStatus.Error
            else -> SyncStatus.Synced
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SyncStatus.Synced)

    val lastSyncedAt: StateFlow<Long> = settingsRepo.lastSyncedAt
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /**
     * `true` while the manual Home refresh is running. Drives the
     * top-bar spinner. Independent from [syncStatus] (which also flips
     * when the background WorkManager job runs) so a tap on the
     * refresh button always shows progress immediately.
     */
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** One-shot UI events (snackbars). Buffered so we never drop one. */
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    /**
     * Sync outcome events. The Compose layer translates these into
     * stringResource-backed snackbars so all user-visible copy stays
     * in `res/values/strings.xml`. Mirrors the shape exposed by
     * `SettingsViewModel.UiEvent` so both screens render the same
     * message for the same outcome.
     */
    sealed interface UiEvent {
        data class SyncSuccess(
            val pushed: Int,
            val pulledItems: Int,
            val pulledEntries: Int
        ) : UiEvent
        data class SyncFailure(val message: String) : UiEvent
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onSyncTap() {
        syncRepo.requestImmediateSync()
    }

    /**
     * Manual refresh from the Home top app bar — runs the full
     * push-then-pull sync inline and surfaces the outcome as a
     * snackbar. Ignores re-entrant taps so a double-tap doesn't
     * spawn two parallel syncs.
     */
    fun syncNow() {
        if (_syncing.value) return // ignore double taps
        _syncing.value = true
        viewModelScope.launch {
            val result = syncRepo.runFullSyncNow()
            _syncing.value = false
            val event = when (result) {
                is AppResult.Ok -> UiEvent.SyncSuccess(
                    pushed = result.value.pushedRows,
                    pulledItems = result.value.pulledItems,
                    pulledEntries = result.value.pulledEntries
                )
                is AppResult.Err -> UiEvent.SyncFailure(result.message)
            }
            _events.trySend(event)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as RateCardApp)
                val container = app.container
                HomeViewModel(
                    itemRepo = container.itemRepo,
                    syncRepo = container.syncRepo,
                    settingsRepo = container.settingsRepo,
                    workManager = WorkManager.getInstance(app)
                )
            }
        }
    }
}
