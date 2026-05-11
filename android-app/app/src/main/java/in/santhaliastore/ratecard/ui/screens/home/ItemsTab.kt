package `in`.santhaliastore.ratecard.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.data.db.entity.ItemWithLastEntry
import `in`.santhaliastore.ratecard.ui.components.EmptyState
import `in`.santhaliastore.ratecard.ui.components.SearchField
import `in`.santhaliastore.ratecard.util.Money
import `in`.santhaliastore.ratecard.util.Time

/**
 * Items tab content. Pulled out of [HomeScreen] when the bottom-nav
 * split landed so HomeScreen could host two sibling tabs (Items and
 * Bills) sharing the same TopAppBar / FAB / snackbar host.
 *
 * Search + status line + list live here; the outer scaffold (topbar,
 * bottom nav, FAB) is owned by [HomeScreen]. We re-resolve sync-status
 * strings from the shared [HomeViewModel] so both tabs read the same
 * "Last sync" timestamp without anyone needing to plumb it through.
 */
@Composable
fun ItemsTab(
    viewModel: HomeViewModel,
    onItemClick: (String) -> Unit,
    onAddItem: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsStateWithLifecycle()
    val items = viewModel.pagedItems.collectAsLazyPagingItems()
    val listState = rememberLazyListState()

    // Pre-resolve sync-status copy off the composable layer so the
    // `derivedStateOf` below stays pure.
    val neverSyncedLabel = stringResource(R.string.home_never_synced)
    val lastSyncFormat = stringResource(R.string.home_last_sync_format)
    val syncInProgressLabel = stringResource(R.string.sync_status_in_progress)

    val lastSyncLine by remember(lastSyncedAt, syncing) {
        derivedStateOf {
            when {
                syncing -> syncInProgressLabel
                lastSyncedAt <= 0L -> neverSyncedLabel
                else -> lastSyncFormat.format(Time.displayDateTime(lastSyncedAt))
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
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

        // Status bar under the search field: total active items count on
        // the left, last sync on the right. Same split layout as the
        // pre-split HomeScreen — count grows with adds (FAB), sync grows
        // with the refresh button in the top bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_total_items_format, totalCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = lastSyncLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        val isInitialLoading = items.loadState.refresh is LoadState.Loading
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
                    contentPadding = PaddingValues(
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
                    // Bottom spacer so the FAB doesn't sit on the last row.
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }
}

/**
 * Item row — primary identity (code chip + name) on the left,
 * trailing meta (price + date) on the right of a single visual row.
 *
 *   [ATA   ]  Aata 5kg            ₹240 / Kg
 *                                 4 May 2026
 *
 * Trailing column is Start-aligned within a fixed width so the price's
 * left edge (the ₹) lines up vertically across rows regardless of how
 * long each individual rate string is. Previously this column was
 * End-aligned, which kept the right edge stable but caused the ₹ to
 * jitter horizontally as digit counts changed — the eye reads the
 * leading currency mark, not the trailing unit, so Start wins.
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
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Trailing: price + date stacked, Start-aligned inside a
            // fixed-width column. The fixed width anchors the column's
            // leading edge to the same x-position on every row, and
            // Start-alignment puts each price's ₹ mark exactly there —
            // the column reads as a clean vertical line.
            //
            // Children clamp to maxLines=1 + ellipsis so an unusually
            // long unit ("Packets") can't break the column.
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.width(120.dp)
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
                        overflow = TextOverflow.Ellipsis
                    )
                    if (lastUpdateText.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = lastUpdateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.home_no_purchase_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
            .width(80.dp)
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
