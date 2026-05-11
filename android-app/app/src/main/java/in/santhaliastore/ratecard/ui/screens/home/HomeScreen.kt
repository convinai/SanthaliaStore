package `in`.santhaliastore.ratecard.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.ui.screens.bills.BillsTab
import `in`.santhaliastore.ratecard.ui.screens.home.HomeViewModel.UiEvent

/**
 * Two bottom-nav tabs hosted under a shared TopAppBar / FAB / snackbar.
 *
 * **Items** (default) — the rate card list, search, status row. Owned
 * by [ItemsTab] which reads from the shared [HomeViewModel] passed in
 * via the local `viewModel(...)` resolution.
 *
 * **Bills** — supplier-bill photos with metadata. Currently a
 * placeholder ([BillsTab]) until the data layer + capture flow land.
 *
 * The two tabs share:
 *   - TopAppBar (logo, refresh, settings) — sync is a global action,
 *     it doesn't make sense to duplicate the refresh button per tab.
 *   - Snackbar host — sync outcome events are global.
 *   - FAB — same slot, but the icon and label swap based on the
 *     selected tab (Add item / Add bill). The callback dispatches to
 *     the appropriate route via [AppNavigation].
 *
 * Tab state survives configuration changes via `rememberSaveable` but
 * deliberately resets on a fresh process — landing on Items is the
 * expected first-thing-you-see for a shop owner opening the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onItemClick: (String) -> Unit,
    onAddItem: () -> Unit,
    onAddBill: () -> Unit,
    onBillClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.Items) }

    // Back from a non-Items tab returns to Items rather than exiting
    // the app — matches the pattern users expect from WhatsApp,
    // Instagram, etc. Only intercept when we're not already on Items.
    BackHandler(enabled = selectedTab != HomeTab.Items) {
        selectedTab = HomeTab.Items
    }

    // Resolve the Hinglish snackbar copy at the composable layer so all
    // user-visible strings stay in res/values/strings.xml. Mirrors the
    // pattern from SettingsScreen so both screens speak the same
    // language for the same outcome.
    val snackSyncDoneNoRows = stringResource(R.string.sync_done_no_rows)
    val snackSyncDoneWithRowsFormat = stringResource(R.string.sync_done_with_rows_format)
    val snackSyncDonePulledOnlyFormat = stringResource(R.string.sync_done_pulled_only_format)
    val snackSyncDoneCombinedFormat = stringResource(R.string.sync_done_combined_format)
    val snackSyncFailedFormat = stringResource(R.string.sync_failed_format)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                is UiEvent.SyncSuccess -> {
                    val pulled = event.pulledItems + event.pulledEntries + event.pulledBills
                    when {
                        event.pushed == 0 && pulled == 0 -> snackSyncDoneNoRows
                        event.pushed > 0 && pulled == 0 ->
                            snackSyncDoneWithRowsFormat.format(event.pushed)
                        event.pushed == 0 && pulled > 0 ->
                            snackSyncDonePulledOnlyFormat.format(pulled)
                        else ->
                            snackSyncDoneCombinedFormat.format(event.pushed, pulled)
                    }
                }
                is UiEvent.SyncFailure -> snackSyncFailedFormat.format(event.message)
            }
            snackbarHostState.showSnackbar(
                message = text,
                duration = SnackbarDuration.Long
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Image(
                        painter = painterResource(R.drawable.santha_logo),
                        contentDescription = stringResource(R.string.app_logo_cd),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(40.dp)
                            .padding(vertical = 2.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    // Same refresh / spinner / settings slot the original
                    // HomeScreen had. 48 dp tap target preserved by keeping
                    // the IconButton wrapper around the spinner.
                    IconButton(
                        onClick = { viewModel.syncNow() },
                        enabled = !syncing
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.home_refresh_cd)
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == HomeTab.Items,
                    onClick = { selectedTab = HomeTab.Items },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ListAlt,
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(R.string.home_tab_items)) }
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.Bills,
                    onClick = { selectedTab = HomeTab.Bills },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.ReceiptLong,
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(R.string.home_tab_bills)) }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // FAB label + action swap with the current tab. The icon is
            // always a generic plus — the label and route do the talking.
            when (selectedTab) {
                HomeTab.Items -> ExtendedFloatingActionButton(
                    onClick = onAddItem,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.home_fab_add_item)) }
                )
                HomeTab.Bills -> ExtendedFloatingActionButton(
                    onClick = onAddBill,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.home_fab_add_bill)) }
                )
            }
        }
    ) { padding ->
        // We pass the Scaffold's padding INTO each tab rather than
        // applying it here so each tab body can decide its own scroll /
        // edge behaviour (e.g. a future Bills list might pin a date
        // header bar against the top inset).
        when (selectedTab) {
            HomeTab.Items -> ItemsTab(
                viewModel = viewModel,
                onItemClick = onItemClick,
                onAddItem = onAddItem,
                contentPadding = padding,
                modifier = Modifier.fillMaxSize()
            )
            HomeTab.Bills -> BillsTab(
                onAddBill = onAddBill,
                onBillClick = onBillClick,
                contentPadding = padding,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Two bottom-nav destinations. Kept as a local enum (not Routes) since
 * the swap is in-place via state — no NavController push, so we never
 * need to serialise the value into a route string.
 */
private enum class HomeTab { Items, Bills }
