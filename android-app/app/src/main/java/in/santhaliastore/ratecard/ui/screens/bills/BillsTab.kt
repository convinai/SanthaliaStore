package `in`.santhaliastore.ratecard.ui.screens.bills

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
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.data.db.entity.BillEntity
import `in`.santhaliastore.ratecard.ui.components.EmptyState
import `in`.santhaliastore.ratecard.util.Money
import `in`.santhaliastore.ratecard.util.Time
import java.io.File

/**
 * Bills tab content.
 *
 * Pulled out of HomeScreen alongside ItemsTab so HomeScreen can host
 * both behind a single Scaffold (top bar, FAB, snackbar). The real
 * data layer lives behind [BillsViewModel] which observes
 * [in.santhaliastore.ratecard.data.repo.BillRepository].
 *
 * Layout mirrors ItemsTab as closely as possible — a status row
 * (total count + last-sync hint), then either an empty state or a
 * paged LazyColumn of [BillRow]s. The trailing column is
 * Start-aligned in a fixed-width slot so the rupee glyph lines up
 * vertically down the list (same convention as the items list).
 *
 * No search field — bills are sparse enough that scanning by date is
 * fast. Can add later without rearranging the surrounding layout.
 */
@Composable
fun BillsTab(
    onAddBill: () -> Unit,
    onBillClick: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: BillsViewModel = viewModel(factory = BillsViewModel.Factory)
) {
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsStateWithLifecycle()
    val bills = viewModel.pagedBills.collectAsLazyPagingItems()
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

    Column(
        modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        // Status bar: bills count on the left, last sync on the right.
        // Same split layout the Items tab uses so a user switching tabs
        // sees a consistent header treatment.
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
                // Tiny secondary line for the upload backlog. We only
                // surface it when there's actually something pending,
                // so a happy path stays visually quiet.
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

        val isInitialLoading = bills.loadState.refresh is LoadState.Loading
        val isEmpty = bills.itemCount == 0 && !isInitialLoading

        when {
            isEmpty && totalCount == 0 -> {
                EmptyState(
                    title = stringResource(R.string.bills_empty_title),
                    caption = stringResource(R.string.bills_empty_caption),
                    actionLabel = stringResource(R.string.home_fab_add_bill),
                    onAction = onAddBill
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
                        count = bills.itemCount,
                        key = bills.itemKey { it.id },
                        contentType = bills.itemContentType { "BillRow" }
                    ) { index ->
                        val row = bills[index] ?: return@items
                        BillRow(
                            bill = row,
                            onClick = { onBillClick(row.id) }
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
 * Bill row card.
 *
 * Three columns:
 *   [thumbnail]  supplier name        ₹ amount
 *                notes preview        date
 *
 * Thumbnail prefers the local cache (first entry of `localImagePaths`
 * CSV) — a freshly-captured bill always has a local copy. If the row
 * came in via sync from another device, `localImagePaths` may be empty
 * even when `imageFileIds` is not; in that case we show a placeholder
 * icon. The detail screen handles lazy-downloading the bytes from
 * Drive into the local cache; once that lands the list will repaint
 * with the thumbnail on the next observed update.
 *
 * Trailing column is Start-aligned in a fixed-width slot (110 dp) so
 * the rupee glyph lines up vertically across rows — same convention
 * the Items tab uses for its trailing rate column.
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
    // "Pending upload" = at least one local image is not yet on
    // Drive. We compare the local-path and drive-id counts after
    // dropping empty placeholder slots from each. A row in steady
    // state has equal non-empty counts; anything less on the Drive
    // side means there's still work for the uploader.
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
            // Thumbnail (or placeholder). 56x56 dp matches the row's
            // vertical rhythm on a 2-line layout. Coil decodes lazily;
            // when the path is missing we never construct an
            // AsyncImage at all — paint a static icon instead so the
            // list never thrashes Coil's loader on rows with no image.
            ThumbBox(
                localPath = firstLocalPath,
                hasAnyImage = hasAnyImage,
                pendingUpload = pendingUpload
            )

            // Middle column — supplier + notes preview.
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

            // Trailing — amount + date stacked, Start-aligned in a
            // fixed-width slot so the ₹ glyph lines up vertically
            // across rows (see ItemsTab kdoc — same convention).
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
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = Time.displayDate(bill.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
 *      receipt icon with a faint outline. The detail screen
 *      hydrates the local cache on view; the row will refresh on
 *      next observe.
 *   3. No image at all → same static icon, but no upload hint.
 *
 * `pendingUpload` overlays a tiny cloud-up icon at the corner so the
 * shop owner can see at a glance which rows still need to make it
 * to Drive. Surfaced ONLY when the bill row has a local image but no
 * Drive id yet — i.e. capture happened but upload hasn't completed.
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
            // Drive-only or no-image — same neutral icon so we don't
            // promise a thumbnail we can't deliver. The detail screen
            // is responsible for the lazy Drive→cache rehydration.
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
            // Bottom-right corner overlay — 14 dp keeps it readable
            // without crowding the thumbnail.
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
