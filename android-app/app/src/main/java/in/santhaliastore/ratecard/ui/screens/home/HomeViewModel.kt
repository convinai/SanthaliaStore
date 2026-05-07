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
     * `true` while ANY sync (manual refresh, app-resume auto-sync, or
     * post-write debounced auto-sync) is in progress. Drives both:
     *   - the top-bar refresh icon → spinner swap
     *   - the "Sync ho raha hai…" line under the search bar
     *
     * Single source of truth lives on [SyncRepository.isSyncing] so
     * Home and Settings cannot disagree about sync state. The local
     * `_syncing` field that used to exist here was dropped — its
     * lifecycle was a strict subset of the repository flag, and
     * keeping two state holders meant the "auto-sync just kicked
     * off" case wouldn't light up the UI. See [HomeViewModel.syncNow]
     * for how re-entrant manual taps are still suppressed.
     */
    val syncing: StateFlow<Boolean> = syncRepo.isSyncing
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
     * snackbar.
     *
     * Re-entrancy: the [syncRepo] mutex single-flights syncs at the
     * data layer (a manual tap arriving while an auto-sync is in
     * flight waits for it to finish, then runs). The [syncing] flag
     * already disables the refresh button while any sync is in
     * progress, so a normal user can't double-fire from the UI; this
     * `if` is belt-and-suspenders for adb / accessibility paths that
     * could synthesise rapid taps.
     */
    fun syncNow() {
        if (syncing.value) return // ignore taps while any sync is running
        viewModelScope.launch {
            val result = syncRepo.runFullSyncNow()
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
