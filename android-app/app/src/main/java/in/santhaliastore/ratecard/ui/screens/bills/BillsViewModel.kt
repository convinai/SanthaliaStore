package `in`.santhaliastore.ratecard.ui.screens.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.db.entity.BillEntity
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.data.repo.BillRepository
import `in`.santhaliastore.ratecard.sync.SyncRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the Bills tab.
 *
 * Mirrors [in.santhaliastore.ratecard.ui.screens.home.HomeViewModel]'s
 * shape: an observable list of rows, a live count, plus the shared
 * sync status flags so the status row under the tab's header speaks
 * the same language as the Items tab.
 *
 * **Not paged.** Bills are sparse (a few per week, dozens per year)
 * so the entire list fits comfortably in memory — that lets the UI
 * group rows by date with sticky LazyColumn headers without fighting
 * Paging's stream semantics. If volume ever grows past a few thousand
 * we can switch back to Paging + manual header insertion, but at the
 * realistic load the simpler model wins.
 *
 * Search filters supplier + notes via SQL LIKE — see
 * [BillRepository.searchBills] for the escaping rules.
 */
class BillsViewModel(
    private val billRepo: BillRepository,
    syncRepo: SyncRepository,
    settingsRepo: SettingsRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Newest-first list of non-deleted bills, optionally filtered by
     * the current search query. The 300 ms debounce mirrors the items
     * tab's search behaviour so the two surfaces feel identical.
     *
     * Empty / blank query short-circuits to the unfiltered stream
     * inside [BillRepository.searchBills] — no separate "all" path
     * needed here.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val bills: StateFlow<List<BillEntity>> =
        _query
            .debounce(300L)
            .flatMapLatest { q -> billRepo.searchBills(q) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalCount: StateFlow<Int> = billRepo.observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Pending-upload count surfaced as a discrete StateFlow so the
     * status row can show a tiny "X upload baaki" hint without
     * driving the rest of the tab's recomposition off the same flow.
     */
    val pendingCount: StateFlow<Int> = billRepo.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Mirrors `HomeViewModel.syncing` — single source of truth lives on
     * SyncRepository so all three tabs (Items, Bills, Settings) agree
     * about whether a sync is in flight. See HomeViewModel kdoc.
     */
    val syncing: StateFlow<Boolean> = syncRepo.isSyncing
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val lastSyncedAt: StateFlow<Long> = settingsRepo.lastSyncedAt
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun onQueryChange(value: String) {
        _query.value = value
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as RateCardApp)
                val container = app.container
                BillsViewModel(
                    billRepo = container.billRepo,
                    syncRepo = container.syncRepo,
                    settingsRepo = container.settingsRepo
                )
            }
        }
    }
}
