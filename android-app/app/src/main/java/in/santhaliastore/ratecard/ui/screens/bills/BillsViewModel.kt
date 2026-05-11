package `in`.santhaliastore.ratecard.ui.screens.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.cachedIn
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.db.entity.BillEntity
import `in`.santhaliastore.ratecard.data.prefs.SettingsRepository
import `in`.santhaliastore.ratecard.data.repo.BillRepository
import `in`.santhaliastore.ratecard.sync.SyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the Bills tab.
 *
 * Mirrors [in.santhaliastore.ratecard.ui.screens.home.HomeViewModel]'s
 * shape: a paged stream of rows, a live count, plus the shared sync
 * status flags so the status row under the tab's header speaks the
 * same language as the Items tab.
 *
 * No search filtering yet — bills are typically only a few dozen per
 * month and the user scans them visually by date. We can add a
 * supplier-search later without breaking this contract.
 */
class BillsViewModel(
    private val billRepo: BillRepository,
    syncRepo: SyncRepository,
    settingsRepo: SettingsRepository
) : ViewModel() {

    /**
     * Newest-first paged stream of non-deleted bills. cachedIn keeps
     * the page contents alive across config changes so the list
     * doesn't blank during a rotation.
     */
    val pagedBills: Flow<PagingData<BillEntity>> =
        billRepo.pagedBills().cachedIn(viewModelScope)

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
