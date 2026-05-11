package `in`.santhaliastore.ratecard.ui.screens.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.data.db.entity.BillEntity
import `in`.santhaliastore.ratecard.ui.components.ConfirmDialog
import `in`.santhaliastore.ratecard.ui.components.EmptyStateInline
import `in`.santhaliastore.ratecard.util.BillImageCache
import `in`.santhaliastore.ratecard.util.Money
import `in`.santhaliastore.ratecard.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bill-detail screen.
 *
 * Top section is a horizontal image pager (one image per page, dots
 * below if there are multiple). Below that, a card with the
 * structured metadata — date, supplier, amount, notes.
 *
 * **Lazy Drive rehydration.**
 *
 * A bill row arrives on this device with `imageFileIds` populated
 * (from sync) but `localImagePaths` empty whenever a sibling phone
 * captured it. The carousel's per-page composable handles this by:
 *
 *   1. Checking the local CSV first. If a local path exists for this
 *      pager index, paint from disk via Coil.
 *   2. Otherwise build a Drive viewer URL from the file id, hand it
 *      to Coil, AND in the onSuccess callback copy the cached bytes
 *      from Coil's disk cache into [BillImageCache.fileForDriveId].
 *      The screen then writes the new path back onto the row via
 *      [BillDetailViewModel.updateImageState] so the next view (and
 *      the thumbnail in the list) paints instantly from disk.
 *
 * This keeps the list scroll fast (no network on the list pass) and
 * the detail open fast on the *second* visit, while still working
 * end-to-end on the first visit to a synced-in bill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillDetailScreen(
    billId: String,
    onBack: () -> Unit,
    onEditBill: () -> Unit,
    onDeleted: () -> Unit,
    onOpenImage: (pageIndex: Int) -> Unit
) {
    val owner = LocalViewModelStoreOwner.current!!
    val app = LocalContext.current.applicationContext as RateCardApp

    val extras = remember(billId, owner) {
        val base: CreationExtras = (owner as? HasDefaultViewModelProviderFactory)
            ?.defaultViewModelCreationExtras
            ?: CreationExtras.Empty
        MutableCreationExtras(base).apply {
            set(BillDetailViewModel.ID_KEY, billId)
            set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app)
        }
    }

    val viewModel: BillDetailViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "bill-$billId",
        factory = BillDetailViewModel.Factory,
        extras = extras
    )

    val state by viewModel.bill.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (val s = state) {
                        is BillDetailViewModel.BillState.Loaded ->
                            s.bill.supplier?.takeIf { it.isNotBlank() }
                                ?: Time.displayDate(s.bill.date)
                        BillDetailViewModel.BillState.Missing ->
                            stringResource(R.string.bill_detail_missing_title)
                        BillDetailViewModel.BillState.Loading ->
                            stringResource(R.string.loading)
                    }
                    Text(title, maxLines = 1)
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
                    val loaded = state is BillDetailViewModel.BillState.Loaded
                    if (loaded) {
                        IconButton(onClick = onEditBill) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.action_edit)
                            )
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        when (val s = state) {
            BillDetailViewModel.BillState.Loading -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.loading))
                }
            }

            BillDetailViewModel.BillState.Missing -> {
                EmptyStateInline(
                    title = stringResource(R.string.bill_detail_missing_title),
                    caption = stringResource(R.string.bill_detail_missing_caption),
                    modifier = Modifier.padding(padding)
                )
            }

            is BillDetailViewModel.BillState.Loaded -> BillBody(
                bill = s.bill,
                cache = viewModel.cache,
                onLocalPathCached = { localPathsCsv ->
                    // The pager only mutates localImagePaths — the
                    // Drive ids stay exactly as they were. We pass
                    // the unchanged CSV alongside so the repo write
                    // sees a coherent row.
                    viewModel.updateImageState(
                        localPathsCsv = localPathsCsv,
                        imageFileIdsCsv = s.bill.imageFileIds
                    )
                },
                onOpenImage = onOpenImage,
                modifier = Modifier.padding(padding)
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_bill_title),
            message = stringResource(R.string.delete_bill_message),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                viewModel.delete(onDone = onDeleted)
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun BillBody(
    bill: BillEntity,
    cache: BillImageCache,
    onLocalPathCached: (newLocalPathsCsv: String) -> Unit,
    onOpenImage: (pageIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val driveIds = remember(bill.imageFileIds) {
        bill.imageFileIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    val localPaths = remember(bill.localImagePaths) {
        bill.localImagePaths.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    // Pages: max of the two arrays — local-only captures (length N
    // before upload) and Drive-only sync arrivals (length N before
    // first view) both need to render. We index both arrays by
    // page; whichever is shorter just falls back to the placeholder.
    val pageCount = maxOf(driveIds.size, localPaths.size)

    LazyColumn(
        modifier = modifier.fillMaxSize()
    ) {
        item {
            if (pageCount > 0) {
                Carousel(
                    pageCount = pageCount,
                    localPaths = localPaths,
                    driveIds = driveIds,
                    cache = cache,
                    onLocalPathCached = onLocalPathCached,
                    onPageTap = onOpenImage
                )
            } else {
                NoImagePlaceholder()
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            MetaCard(bill = bill)
            Spacer(Modifier.height(96.dp))
        }
    }
}

/**
 * Horizontal pager over the bill's images.
 *
 * Each page picks its source independently — see [BillImagePage] —
 * so a bill that mixes locally-cached and Drive-only images
 * (e.g. partial download state) renders correctly on every page.
 * Dots below the pager appear only when there is more than one page.
 */
@Composable
private fun Carousel(
    pageCount: Int,
    localPaths: List<String>,
    driveIds: List<String>,
    cache: BillImageCache,
    onLocalPathCached: (newLocalPathsCsv: String) -> Unit,
    onPageTap: (pageIndex: Int) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Column {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) { page ->
            BillImagePage(
                localPath = localPaths.getOrNull(page),
                driveId = driveIds.getOrNull(page),
                pageIndex = page,
                onTap = { onPageTap(page) },
                allLocalPaths = localPaths,
                cache = cache,
                onLocalPathCached = onLocalPathCached
            )
        }

        if (pageCount > 1) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(pageCount) { i ->
                    val selected = pagerState.currentPage == i
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (selected) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(
                        R.string.bill_detail_image_count_format,
                        pagerState.currentPage + 1,
                        pageCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/**
 * One image inside the carousel.
 *
 * Render order:
 *   1. Local file present? Use it. No network, no Coil disk-cache
 *      lookup beyond the bitmap pool.
 *   2. Drive id present? Build the public-viewer URL and let Coil
 *      stream-and-cache. On success, copy the cached bytes from
 *      Coil's disk cache into [BillImageCache.fileForDriveId] and
 *      bubble the new local path up so the row's
 *      `localImagePaths` CSV gets persisted.
 *   3. Nothing → placeholder. Should be unreachable while we're
 *      inside the pager (pageCount==0 takes the early-return path),
 *      but defensive.
 */
@Composable
private fun BillImagePage(
    localPath: String?,
    driveId: String?,
    pageIndex: Int,
    onTap: () -> Unit,
    allLocalPaths: List<String>,
    cache: BillImageCache,
    onLocalPathCached: (newLocalPathsCsv: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Tap-on-image opens the full-screen pinch-zoom viewer. We apply
    // the modifier on the outer Box surrounding each render path so
    // both local-paint and Drive-paint cases are tappable identically.
    val tapModifier = Modifier.pointerInput(onTap) {
        detectTapGestures(onTap = { onTap() })
    }

    when {
        !localPath.isNullOrBlank() -> {
            AsyncImage(
                model = File(localPath),
                contentDescription = stringResource(R.string.bill_image_open_full_cd),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .then(tapModifier)
            )
        }

        !driveId.isNullOrBlank() -> {
            val driveUrl = remember(driveId) { driveViewerUrl(driveId) }
            val targetFile = remember(driveId) { cache.fileForDriveId(driveId) }
            // We track a local-state copy of the cached path so the
            // page can swap to "paint from disk" mid-session without
            // waiting for the round-trip through Room and back.
            var rehydratedLocal by remember(driveId) {
                mutableStateOf<String?>(
                    if (targetFile.exists() && targetFile.length() > 0) {
                        targetFile.absolutePath
                    } else null
                )
            }

            if (rehydratedLocal != null) {
                AsyncImage(
                    model = File(rehydratedLocal!!),
                    contentDescription = stringResource(R.string.bill_image_open_full_cd),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(tapModifier)
                )
            } else {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(driveUrl)
                        // Diskcache the bytes so the rehydration copy
                        // is a simple file-system move from Coil's
                        // own cache rather than a second network hit.
                        .build(),
                    contentDescription = stringResource(R.string.bill_image_open_full_cd),
                    contentScale = ContentScale.Fit,
                    loading = {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    },
                    error = {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ReceiptLong,
                                contentDescription = stringResource(R.string.bill_detail_no_image),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    },
                    onSuccess = {
                        // Hop to IO so we don't block the frame on
                        // the file copy. Best-effort — if it fails
                        // we just paint via the network on the next
                        // open. We re-check existence inside the IO
                        // block in case a sibling page already
                        // rehydrated the same id.
                        scope.launch {
                            val newPath = withContext(Dispatchers.IO) {
                                runCatching {
                                    persistDriveToCache(
                                        context = context,
                                        driveUrl = driveUrl,
                                        targetFile = targetFile
                                    )
                                }.getOrNull()
                            } ?: return@launch
                            rehydratedLocal = newPath
                            // Merge into the bill row's CSV at this
                            // page's index. The CSV positions track
                            // page indices, padding with empty slots
                            // where earlier pages haven't been
                            // rehydrated yet.
                            val newCsv = mergeLocalPathAt(
                                existing = allLocalPaths,
                                index = pageIndex,
                                newPath = newPath
                            )
                            onLocalPathCached(newCsv)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .then(tapModifier)
                )
            }
        }

        else -> NoImagePlaceholder()
    }
}

/**
 * The viewer URL Google Drive serves for a public-readable file. The
 * Apps Script side sets the upload's sharing to "anyone with the link
 * can view"; this URL works for both the rendered image bytes (via
 * a CDN redirect) and as a deep-link fallback in case we ever want
 * to open the original. Cheap to construct, no auth.
 */
private fun driveViewerUrl(driveFileId: String): String =
    "https://drive.google.com/uc?id=$driveFileId&export=view"

/**
 * Copy whatever Coil cached for [driveUrl] into [targetFile] so the
 * pager can paint from disk on the next open.
 *
 * We avoid grabbing Coil's internal disk cache APIs directly — they
 * shift between minor versions and aren't part of the stable
 * contract. Instead we open the URL through Coil one more time with
 * a synchronous executor; the second call is a pure cache hit
 * (Coil's HTTP layer dedupes by URL key), so this is basically free.
 * Then we re-encode the bitmap as JPEG into the target file so the
 * cache layout stays uniform with locally-captured images.
 *
 * Returns the absolute path of the now-cached file.
 */
private suspend fun persistDriveToCache(
    context: android.content.Context,
    driveUrl: String,
    targetFile: File
): String = withContext(Dispatchers.IO) {
    if (targetFile.exists() && targetFile.length() > 0) return@withContext targetFile.absolutePath
    val loader = coil.ImageLoader(context)
    val req = ImageRequest.Builder(context)
        .data(driveUrl)
        .allowHardware(false) // Software bitmap so we can re-encode safely
        .build()
    val result = loader.execute(req)
    val drawable = (result as? coil.request.SuccessResult)?.drawable
        ?: error("Coil could not deliver bytes for $driveUrl")
    val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        ?: error("Coil delivered a non-bitmap drawable for $driveUrl")
    java.io.FileOutputStream(targetFile).use { out ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        out.flush()
    }
    targetFile.absolutePath
}

/**
 * Splice [newPath] into position [index] of the existing CSV. We pad
 * leading positions with empty strings so the CSV index continues to
 * match the carousel's page index even when earlier pages haven't
 * been rehydrated yet.
 *
 * Trailing slots are NOT pre-padded — the CSV stays as short as the
 * highest-rehydrated index because later rehydrations will extend it
 * naturally on their own write.
 */
private fun mergeLocalPathAt(
    existing: List<String>,
    index: Int,
    newPath: String
): String {
    val list = existing.toMutableList()
    while (list.size <= index) list.add("")
    list[index] = newPath
    return list.joinToString(",")
}

@Composable
private fun NoImagePlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.bill_detail_no_image),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetaCard(bill: BillEntity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MetaRow(
                label = stringResource(R.string.bill_detail_meta_date),
                value = Time.displayDate(bill.date)
            )
            MetaRow(
                label = stringResource(R.string.bill_detail_meta_supplier),
                value = bill.supplier?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.bill_detail_meta_missing)
            )
            MetaRow(
                label = stringResource(R.string.bill_detail_meta_amount),
                value = if (bill.totalAmount != null) "₹${Money.plain(bill.totalAmount)}"
                else stringResource(R.string.bill_detail_meta_missing),
                emphasise = bill.totalAmount != null
            )
            if (!bill.notes.isNullOrBlank()) {
                MetaRow(
                    label = stringResource(R.string.bill_detail_meta_notes),
                    value = bill.notes,
                    multiLine = true
                )
            }
            // Upload-state hint. Distinct from the title-bar "edit /
            // delete" so the user sees at a glance whether the row
            // is safely on Drive yet.
            if (bill.imageFileIds.isBlank() && bill.localImagePaths.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                    Text(
                        text = stringResource(R.string.bill_upload_pending),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaRow(
    label: String,
    value: String,
    emphasise: Boolean = false,
    multiLine: Boolean = false
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = if (emphasise) MaterialTheme.typography.titleLarge
            else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasise) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasise) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            maxLines = if (multiLine) Int.MAX_VALUE else 2
        )
    }
}
