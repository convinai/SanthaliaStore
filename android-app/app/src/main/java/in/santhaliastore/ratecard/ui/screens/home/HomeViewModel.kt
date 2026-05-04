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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the home / search list.
 *
 * Search input is debounced 300ms before kicking off a new FTS query.
 * The `pagedItems` flow swaps between "all items" and "search results"
 * via flatMapLatest so the UI never sees stale data.
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

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onSyncTap() {
        syncRepo.requestImmediateSync()
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

