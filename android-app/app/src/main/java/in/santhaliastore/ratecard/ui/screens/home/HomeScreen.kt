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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import `in`.santhaliastore.ratecard.ui.components.SyncStatusIcon
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
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val items = viewModel.pagedItems.collectAsLazyPagingItems()
    val listState = rememberLazyListState()

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
                    SyncStatusIcon(status = syncStatus, onClick = { viewModel.onSyncTap() })
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            )
        },
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
 * Item row, two visual rows of info:
 *
 *   [ATA]  Aata 5kg
 *          ₹240 / Kg  ·  Last update: 4 May 2026
 *
 * Code is shown as a monospace chip so users can scan at a glance
 * (codes are short user-defined identifiers). The last purchase
 * rate is the primary number; the date is the secondary subtitle.
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
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // First line: code chip + bold name
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodeChip(row.code)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(8.dp))

            // Second line: last rate (prominent) + last-update date
            if (row.lastPrice != null) {
                val rateText = if (!row.unit.isNullOrBlank()) {
                    stringResource(R.string.last_rate_format, Money.plain(row.lastPrice), row.unit)
                } else {
                    stringResource(R.string.last_rate_no_unit_format, Money.plain(row.lastPrice))
                }
                Text(
                    text = rateText,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                if (lastUpdateText.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.home_last_updated_format, lastUpdateText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.home_no_purchase_caption),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CodeChip(code: String) {
    Box(
        Modifier
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
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

