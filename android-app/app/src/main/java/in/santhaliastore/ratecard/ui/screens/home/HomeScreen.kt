package `in`.santhaliastore.ratecard.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.data.db.entity.ItemWithLastEntry
import `in`.santhaliastore.ratecard.ui.components.EmptyState
import `in`.santhaliastore.ratecard.ui.components.SearchField
import `in`.santhaliastore.ratecard.ui.screens.home.HomeViewModel.UiEvent
import `in`.santhaliastore.ratecard.util.Money
import `in`.santhaliastore.ratecard.util.Time

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onItemClick: (String) -> Unit,
    onAddItem: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsStateWithLifecycle()
    val items = viewModel.pagedItems.collectAsLazyPagingItems()
    val listState = rememberLazyListState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Resolve the Hinglish snackbar copy here (not in the VM) so every
    // user-visible string stays in res/values/strings.xml. Mirrors the
    // pattern used in SettingsScreen so both screens speak the same
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
                    val pulled = event.pulledItems + event.pulledEntries
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

    // Pre-resolve the "never synced" copy so the derived state below
    // can stay pure (no Composable calls inside the lambda).
    val neverSyncedLabel = stringResource(R.string.home_never_synced)
    val lastSyncFormat = stringResource(R.string.home_last_sync_format)

    // Format the absolute-time string off the timestamp.
    // `derivedStateOf` keeps the recomposition scoped to just the
    // status line — the rest of Home doesn't re-render when the
    // value flips.
    //
    // We use absolute format ("5 May 2026 2:30 PM") rather than
    // relative ("5 min pehle") because two phone owners comparing
    // notes care about WHEN the data was fresh, not how long ago
    // that was — a relative label silently changes meaning the
    // moment they walk away from the screen.
    val lastSyncLine by remember(lastSyncedAt) {
        derivedStateOf {
            if (lastSyncedAt <= 0L) {
                neverSyncedLabel
            } else {
                lastSyncFormat.format(Time.displayDateTime(lastSyncedAt))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Use the actual shop sign as the title — strong brand
                    // anchor on every visit to home.
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
                    // Explicit refresh button. While syncing we swap the
                    // icon for a spinner of the same size so the slot
                    // stays exactly 48 dp wide — no layout shift in the
                    // app bar between idle and in-progress states.
                    //
                    // The IconButton itself is kept (rather than just a
                    // Box around the spinner) so the tap target stays
                    // 48 dp; we just disable it while a sync is running
                    // to prevent re-entrant taps.
                    IconButton(
                        onClick = { viewModel.syncNow() },
                        enabled = !syncing
                    ) {
                        if (syncing) {
                            // 24 dp matches Material's default Icon size,
                            // so the spinner occupies the same visual slot
                            // as Icons.Filled.Refresh.
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddItem,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_fab_add_item)) }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Sticky search bar
            Surface(
                tonalElevation = 0.dp,
                color = MaterialTheme.colorScheme.background
            ) {
                SearchField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = stringResource(R.string.home_search_hint)
                )
            }

            // "Last sync" status line — sits directly under the search
            // bar, right-aligned so it pairs visually with the refresh
            // button it tells the story of. Tiny, muted, never the focal
            // point but always available when two phone owners are
            // wondering whether they're on the same data.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = lastSyncLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            val isInitialLoading = items.loadState.refresh is androidx.paging.LoadState.Loading
            val isEmpty = items.itemCount == 0 && !isInitialLoading

            when {
                isEmpty && query.isBlank() && totalCount == 0 -> {
                    EmptyState(
                        title = stringResource(R.string.home_empty_title),
                        caption = stringResource(R.string.home_empty_caption),
                        actionLabel = stringResource(R.string.home_fab_add_item),
                        onAction = onAddItem
                    )
                }

                isEmpty && query.isNotBlank() -> {
                    EmptyState(
                        title = stringResource(R.string.home_no_results),
                        caption = ""
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            count = items.itemCount,
                            key = items.itemKey { it.code },
                            contentType = items.itemContentType { "ItemRow" }
                        ) { index ->
                            val row = items[index] ?: return@items
                            ItemRow(
                                row = row,
                                lastUpdateText = row.lastDate?.let { Time.displayDate(it) }.orEmpty(),
                                onClick = { onItemClick(row.code) }
                            )
                        }
                        // Bottom spacer so the FAB doesn't sit on the last row
                        item { Spacer(Modifier.height(96.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * Item row — primary identity (code chip + name) on the left,
 * trailing meta (price + date) on the right of a single visual row.
 *
 *   [ATA]  Aata 5kg                   ₹240 / Kg
 *                                     4 May 2026
 *
 * Layout follows the standard Material list-item anatomy: leading
 * supporting visual + headline take a flexible width, trailing
 * supporting text aligns to the end. The date sits as a secondary
 * caption directly under the price so the eye groups them together.
 */
@Composable
private fun ItemRow(
    row: ItemWithLastEntry,
    lastUpdateText: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Leading: code chip + bold name. Takes whatever width is
            // left after the trailing column claims its intrinsic size.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CodeChip(row.code)
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            // Trailing: price + date stacked, end-aligned. When there
            // is no purchase yet, fall back to a single muted caption.
            //
            // The whole column is capped at 140 dp so even if a stray
            // string slips through (e.g. a Date.toString() locale dump
            // from a misbehaving server payload) the row physically
            // cannot push the leading code chip + name off-screen. The
            // children also clamp themselves to maxLines=1 + ellipsis,
            // belt-and-suspenders style.
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.widthIn(max = 140.dp)
            ) {
                if (row.lastPrice != null) {
                    val rateText = if (!row.unit.isNullOrBlank()) {
                        stringResource(R.string.last_rate_format, Money.plain(row.lastPrice), row.unit)
                    } else {
                        stringResource(R.string.last_rate_no_unit_format, Money.plain(row.lastPrice))
                    }
                    Text(
                        text = rateText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (lastUpdateText.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = lastUpdateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.home_no_purchase_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeChip(code: String) {
    Box(
        Modifier
            // Cap the chip so a 50-char code can't push the name (or
            // worse, the trailing price) off the row. Single line +
            // ellipsis keeps the row exactly one line tall.
            .widthIn(max = 96.dp)
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
