package `in`.santhaliastore.ratecard.ui.screens.item_detail

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.data.db.entity.PurchaseEntryEntity
import `in`.santhaliastore.ratecard.ui.components.ConfirmDialog
import `in`.santhaliastore.ratecard.ui.components.EmptyStateInline
import `in`.santhaliastore.ratecard.util.Money
import `in`.santhaliastore.ratecard.util.Time

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    itemCode: String,
    onBack: () -> Unit,
    onEditItem: () -> Unit,
    onAddEntry: () -> Unit,
    onEditEntry: (String) -> Unit,
    onDeletedItem: () -> Unit
) {
    val owner = LocalViewModelStoreOwner.current!!
    val app = LocalContext.current.applicationContext as `in`.santhaliastore.ratecard.RateCardApp

    val extras = remember(itemCode, owner) {
        val base: CreationExtras = (owner as? HasDefaultViewModelProviderFactory)
            ?.defaultViewModelCreationExtras
            ?: CreationExtras.Empty
        MutableCreationExtras(base).apply {
            set(ItemDetailViewModel.CODE_KEY, itemCode)
            set(androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app)
        }
    }

    val viewModel: ItemDetailViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "item-$itemCode",
        factory = ItemDetailViewModel.Factory,
        extras = extras
    )

    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var menuOpen by remember { mutableStateOf(false) }
    var confirmDeleteItem by remember { mutableStateOf(false) }
    var confirmDeleteEntry by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.item?.name ?: stringResource(R.string.loading),
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (state.item != null && !state.isMissing) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.action_more)
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEditItem()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_delete),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    confirmDeleteItem = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            // Bug 1b guard: do NOT show the FAB when the item is
            // missing or soft-deleted. A tap here would create an
            // entry against a dead code that the live UI never reads,
            // silently orphaning the entry.
            if (state.item != null && !state.isMissing) {
                ExtendedFloatingActionButton(
                    onClick = onAddEntry,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.item_detail_fab_add_entry)) }
                )
            }
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.loading))
            }
            return@Scaffold
        }

        if (state.isMissing) {
            // Item missing or soft-deleted — likely the user just
            // renamed its code from a different surface or deleted it.
            // Show a clear "yeh item ab nahi raha" message so the user
            // knows to go home rather than staring at a blank list.
            EmptyStateInline(
                title = stringResource(R.string.item_detail_missing_title),
                caption = stringResource(R.string.item_detail_missing_caption),
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HeaderCard(
                item = state.item!!,
                latestPrice = state.entries.firstOrNull()?.pricePerUnit,
                latestDate = state.entries.firstOrNull()?.date,
                relativeDate = Time.relativeFromLocalDate(context, state.entries.firstOrNull()?.date)
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.item_detail_history_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (state.entries.isEmpty()) {
                EmptyStateInline(
                    title = stringResource(R.string.item_detail_empty_title),
                    caption = stringResource(R.string.item_detail_empty_caption)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = state.entries,
                        key = { it.entryId },
                        contentType = { "EntryRow" }
                    ) { entry ->
                        EntryRow(
                            entry = entry,
                            unit = state.item?.unit,
                            onEdit = { onEditEntry(entry.entryId) },
                            onDelete = { confirmDeleteEntry = entry.entryId }
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    if (confirmDeleteItem) {
        ConfirmDialog(
            title = stringResource(R.string.delete_item_title),
            message = stringResource(R.string.delete_item_message),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                confirmDeleteItem = false
                viewModel.deleteItem(onDone = onDeletedItem)
            },
            onDismiss = { confirmDeleteItem = false }
        )
    }

    confirmDeleteEntry?.let { entryId ->
        ConfirmDialog(
            title = stringResource(R.string.delete_entry_title),
            message = stringResource(R.string.delete_entry_message),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                confirmDeleteEntry = null
                viewModel.deleteEntry(entryId)
            },
            onDismiss = { confirmDeleteEntry = null }
        )
    }
}

@Composable
private fun HeaderCard(
    item: `in`.santhaliastore.ratecard.data.db.entity.ItemEntity,
    latestPrice: Double?,
    latestDate: String?,
    relativeDate: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        // Cap the chip width so a long code doesn't push
                        // the name off the right edge of the card.
                        .widthIn(max = 120.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = item.code,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            if (!item.unit.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${stringResource(R.string.item_detail_unit_label)}: ${item.unit}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.item_detail_last_rate_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(2.dp))
            if (latestPrice != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = Money.rupees(latestPrice),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!item.unit.isNullOrBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "/ ${item.unit}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
                if (relativeDate.isNotEmpty() || !latestDate.isNullOrBlank()) {
                    val displayDate = latestDate?.let { Time.displayDate(it) }
                    Text(
                        text = listOfNotNull(displayDate, relativeDate.takeIf { it.isNotBlank() })
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.no_purchase_yet),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: PurchaseEntryEntity,
    unit: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember(entry.entryId) { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = Time.displayDate(entry.date),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (!unit.isNullOrBlank())
                        "${Money.rupees(entry.pricePerUnit)} / $unit"
                    else Money.rupees(entry.pricePerUnit),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                val secondary = buildList {
                    entry.quantity?.takeIf { it.isNotBlank() }?.let { add("Qty: $it") }
                    entry.supplier?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                if (secondary.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = secondary.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                if (!entry.notes.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = entry.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.action_more)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_edit)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}
