package `in`.santhaliastore.ratecard.ui.screens.home

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
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
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
                                relativeDate = Time.relativeFromLocalDate(context, row.lastDate),
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
 * Two-line item row.
 *
 *   ATA       Aata 5kg              <- bold name + monospace code chip
 *   ₹240 / Kg          2 din pehle  <- last rate + relative date
 */
@Composable
private fun ItemRow(
    row: ItemWithLastEntry,
    relativeDate: String,
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
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

            Spacer(Modifier.height(6.dp))

            // Second line: last rate + relative time
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val rateText = if (row.lastPrice != null) {
                    if (!row.unit.isNullOrBlank()) {
                        stringResource(R.string.last_rate_format, Money.plain(row.lastPrice), row.unit)
                    } else {
                        stringResource(R.string.last_rate_no_unit_format, Money.plain(row.lastPrice))
                    }
                } else {
                    stringResource(R.string.no_purchase_yet)
                }
                Text(
                    text = rateText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (row.lastPrice != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (row.lastPrice != null) FontWeight.SemiBold else FontWeight.Normal
                )
                if (relativeDate.isNotEmpty()) {
                    Text(
                        text = relativeDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

