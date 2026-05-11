package `in`.santhaliastore.ratecard.ui.screens.bills

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.data.db.entity.BillEntity
import `in`.santhaliastore.ratecard.ui.components.EmptyState
import `in`.santhaliastore.ratecard.ui.components.SearchField
import `in`.santhaliastore.ratecard.util.Money
import `in`.santhaliastore.ratecard.util.Time
import java.io.File

/**
 * Bills tab content.
 *
 * Layout sketch:
 *
 *   [search field]
 *   X bills                                 Last sync: 12 May 2026 ...
 *   ─── 12 May 2026 ───────────────────────────────────
 *   [thumb] Reliance Mart                            ₹5,420
 *   [thumb] Garg General                               ₹890
 *   ─── 10 May 2026 ───────────────────────────────────
 *   [thumb] Bharat Petroleum                         ₹2,400
 *
 * Bills are grouped by their `date` field (YYYY-MM-DD) and rendered
 * under a sticky LazyColumn header per date. The date is dropped from
 * each row's trailing column — the header carries that information
 * once per group, which scans much faster than re-reading the same
 * date on every row.
 *
 * Search filters by supplier + notes via [BillsViewModel.onQueryChange].
 * The query is debounced inside the VM so we don't refilter on every
 * keystroke. The 300 ms window matches the items tab.
 *
 * Bills are loaded as a single in-memory list (not paged) — see the
 * [BillsViewModel] kdoc for the volume rationale. Grouping happens
 * inside `derivedStateOf` so the sticky-header structure is rebuilt
 * only when the underlying list changes, not on every recomposition.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BillsTab(
    onAddBill: () -> Unit,
    onBillClick: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: BillsViewModel = viewModel(factory = BillsViewModel.Factory)
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsStateWithLifecycle()
    val bills by viewModel.bills.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Hinglish copy resolved up front so the derivedStateOf stays pure.
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

    // Group by date once per list change. linkedMap preserves the
    // descending date order coming out of the DAO so the LazyColumn
    // doesn't have to re-sort. `derivedStateOf` makes the grouping
    // recompute only when `bills` actually changes value, not on every
    // unrelated state read (search text, sync flag, etc.).
    val grouped: List<Pair<String, List<BillEntity>>> by remember(bills) {
        derivedStateOf {
            val acc = LinkedHashMap<String, MutableList<BillEntity>>()
            bills.forEach { bill ->
                acc.getOrPut(bill.date) { mutableListOf() }.add(bill)
            }
            acc.map { (date, rows) -> date to rows }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        // Search box — same component the items tab uses so the
        // affordance feels identical when switching between tabs.
        Surface(
            tonalElevation = 0.dp,
            color = MaterialTheme.colorScheme.background
        ) {
            SearchField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = stringResource(R.string.bills_search_hint)
            )
        }

        // Status bar: bills count + pending-upload chip on the left,
        // last sync on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.bills_total_count_format, totalCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (pendingCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.CloudUpload,
                            contentDescription = stringResource(R.string.bill_upload_pending),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.bill_upload_pending),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1
                        )
                    }
                }
            }
            Text(
                text = lastSyncLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        when {
            // First-run / nothing-ever-saved state. We key off the
            // raw active count rather than the filtered list so the
            // empty state never flashes while a search filters every
            // existing row out.
            totalCount == 0 -> {
                EmptyState(
                    title = stringResource(R.string.bills_empty_title),
                    caption = stringResource(R.string.bills_empty_caption),
                    actionLabel = stringResource(R.string.home_fab_add_bill),
                    onAction = onAddBill
                )
            }

            // User has bills but the current query filters every one
            // out — guide them back without offering the FAB action
            // (their next step is to clear / refine the search, not
            // to add another bill).
            bills.isEmpty() && query.isNotBlank() -> {
                EmptyState(
                    title = stringResource(R.string.bills_no_results),
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
                    grouped.forEach { (date, rowsForDate) ->
                        stickyHeader(key = "header:$date") {
                            DateHeader(
                                dateIso = date,
                                count = rowsForDate.size
                            )
                        }
                        items(
                            items = rowsForDate,
                            key = { bill -> bill.id }
                        ) { bill ->
                            BillRow(
                                bill = bill,
                                onClick = { onBillClick(bill.id) }
                            )
                        }
                    }
                    // Bottom spacer so the FAB doesn't sit on the last row.
                    item("bottom-spacer") { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }
}

/**
 * Sticky group header. Slim row with the date on the left and the
 * count of bills under that date on the right — same SpaceBetween
 * treatment as the top status row, so the visual rhythm matches.
 *
 * Backgrounded with `surface` (not `background`) so the header stays
 * visually distinct from the list rows behind it while sticky. A
 * 1 dp bottom hairline (drawn as a separate Box) anchors it to the
 * rows below without adding any vertical noise on non-sticky frames.
 */
@Composable
private fun DateHeader(dateIso: String, count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = Time.displayDate(dateIso),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
            // Tiny count chip — present only when the date holds more
            // than one bill (single-bill dates don't need the noise).
            if (count > 1) {
                Text(
                    text = stringResource(R.string.bills_date_count_format, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Bill row card.
 *
 * Two columns now that the date moved into the sticky header above:
 *
 *   [thumbnail]  supplier name                          ₹ amount
 *                notes preview
 *
 * Thumbnail prefers the local cache (first entry of `localImagePaths`
 * CSV) — a freshly-captured bill always has a local copy. If the row
 * came in via sync from another device, `localImagePaths` may be empty
 * even when `imageFileIds` is not; in that case we show a placeholder
 * icon. The detail screen handles lazy-downloading the bytes from
 * Drive into the local cache; once that lands the list will repaint
 * with the thumbnail on the next observed update.
 *
 * Trailing column is Start-aligned in a fixed-width slot so the rupee
 * glyph lines up vertically across rows — same convention the Items
 * tab uses.
 */
@Composable
private fun BillRow(
    bill: BillEntity,
    onClick: () -> Unit
) {
    val firstLocalPath = bill.localImagePaths
        .split(',')
        .firstOrNull { it.isNotBlank() }
        ?.trim()
    val hasAnyImage = bill.imageFileIds.isNotBlank() || !firstLocalPath.isNullOrBlank()
    val pendingUpload = run {
        val driveCount = bill.imageFileIds
            .split(',')
            .count { it.isNotBlank() }
        val localCount = bill.localImagePaths
            .split(',')
            .count { it.isNotBlank() }
        localCount > driveCount
    }

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
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ThumbBox(
                localPath = firstLocalPath,
                hasAnyImage = hasAnyImage,
                pendingUpload = pendingUpload
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = bill.supplier?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.bills_no_supplier),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!bill.notes.isNullOrBlank()) {
                    Text(
                        text = bill.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Trailing — amount only (date moved to sticky header).
            // Fixed width so the ₹ lines up vertically across rows.
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.width(110.dp)
            ) {
                if (bill.totalAmount != null) {
                    Text(
                        text = "₹${Money.plain(bill.totalAmount)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = stringResource(R.string.bill_detail_meta_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 56x56 dp thumbnail container.
 *
 * Three render paths, picked in order:
 *   1. Local cache hit (`localPath` non-null) → Coil paints the JPEG.
 *      Hot path; covers every bill captured on this device.
 *   2. Drive-only (`hasAnyImage` true, `localPath` null) → static
 *      receipt icon. The detail screen hydrates the local cache on
 *      view; the row will refresh on next observe.
 *   3. No image at all → same static icon, but no upload hint.
 */
@Composable
private fun ThumbBox(
    localPath: String?,
    hasAnyImage: Boolean,
    pendingUpload: Boolean
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!localPath.isNullOrBlank()) {
            AsyncImage(
                model = File(localPath),
                contentDescription = stringResource(R.string.bills_thumb_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.ReceiptLong,
                contentDescription = stringResource(
                    if (hasAnyImage) R.string.bills_thumb_cd
                    else R.string.bills_thumb_placeholder_cd
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }

        if (pendingUpload) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudUpload,
                    contentDescription = stringResource(R.string.bills_pending_upload_cd),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
