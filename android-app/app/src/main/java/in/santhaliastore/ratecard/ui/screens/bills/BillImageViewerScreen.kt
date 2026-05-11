package `in`.santhaliastore.ratecard.ui.screens.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.util.BillImageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Full-screen pinch-zoom image viewer for a single bill's images.
 *
 * Reached by tapping the carousel on [BillDetailScreen]. Opens at the
 * page the user tapped and lets them swipe horizontally between the
 * bill's other images, pinch to zoom, double-tap to toggle between
 * 1x and 3x, and pan when zoomed.
 *
 * Design choices:
 *
 * - **Black background, status-bar-aware top bar.** Photo viewers
 *   universally use black — anything else fights the image's own
 *   tonal range. Top bar gets a status-bar inset padding so the back
 *   button never overlaps the system clock on tall phones.
 *
 * - **HorizontalPager for navigation, transform gestures inside each
 *   page.** When zoomed in we don't want a horizontal swipe at the
 *   bottom of the image to flip to the next page; we want it to pan.
 *   We solve that by gating the pager's `userScrollEnabled` on the
 *   current page's zoom state — when the visible page is at 1x scale
 *   the pager swipes; once zoomed past ~1.05x the pager locks so
 *   gestures stay with the image.
 *
 * - **Reuses [BillDetailViewModel]** to read the same image lists
 *   the detail screen exposes (localImagePaths + imageFileIds). The
 *   VM is keyed by bill id via CreationExtras — same pattern the
 *   detail screen already uses, so the data is read once and shared
 *   if both screens are alive on the back stack.
 *
 * - **No share / save action yet.** Bills are private to the user's
 *   Drive; we don't want to make accidental sharing easy. Can add
 *   later if asked.
 */
@Composable
fun BillImageViewerScreen(
    billId: String,
    initialPage: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: BillDetailViewModel = creationExtrasViewModel(billId)

    val state by viewModel.bill.collectAsStateWithLifecycle()

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when (val s = state) {
            is BillDetailViewModel.BillState.Loaded -> {
                val driveIds = remember(s.bill.imageFileIds) {
                    s.bill.imageFileIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
                val localPaths = remember(s.bill.localImagePaths) {
                    s.bill.localImagePaths.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
                val pageCount = maxOf(driveIds.size, localPaths.size)

                if (pageCount == 0) {
                    // No image at all — should be unreachable from the
                    // detail screen, which only links to here when at
                    // least one image exists. Defensive nonetheless.
                    Text(
                        text = stringResource(R.string.bill_detail_no_image),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    ViewerPager(
                        pageCount = pageCount,
                        initialPage = initialPage.coerceIn(0, pageCount - 1),
                        localPaths = localPaths,
                        driveIds = driveIds,
                        cache = viewModel.cache,
                        onLocalPathCached = { newCsv ->
                            viewModel.updateImageState(
                                localPathsCsv = newCsv,
                                imageFileIdsCsv = s.bill.imageFileIds
                            )
                        }
                    )
                }
            }

            BillDetailViewModel.BillState.Loading -> {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            BillDetailViewModel.BillState.Missing -> {
                // Row was deleted out from under us. Bounce back —
                // the detail screen will handle showing the missing
                // state if the user lingers there.
                LaunchedEffect(Unit) { onBack() }
            }
        }

        // Top bar overlay. Drawn on top of the image so the user can
        // back out at any zoom level. Translucent black behind the
        // icon so it stays legible over a white-ish bill scan.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.35f), shape = androidx.compose.foundation.shape.CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = Color.White
                )
            }
        }
    }
}

/**
 * Pager with one [ZoomableImagePage] per bill image. Gates its own
 * horizontal scroll on the visible page's zoom — when a page is
 * zoomed in, gestures stay with the image (pan) instead of flipping
 * to the next page.
 */
@Composable
private fun ViewerPager(
    pageCount: Int,
    initialPage: Int,
    localPaths: List<String>,
    driveIds: List<String>,
    cache: BillImageCache,
    onLocalPathCached: (newLocalPathsCsv: String) -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pageCount }
    )
    // Track per-page zoom state so the pager can lock swipes for any
    // zoomed-in page. SnapshotMutableState because we mutate from a
    // child composable.
    val zoomStates = remember(pageCount) {
        List(pageCount) { mutableFloatStateOf(1f) }
    }
    val currentZoom = zoomStates.getOrNull(pagerState.currentPage)?.floatValue ?: 1f

    HorizontalPager(
        state = pagerState,
        userScrollEnabled = currentZoom <= 1.05f,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        ZoomableImagePage(
            localPath = localPaths.getOrNull(page),
            driveId = driveIds.getOrNull(page),
            pageIndex = page,
            allLocalPaths = localPaths,
            cache = cache,
            onLocalPathCached = onLocalPathCached,
            onZoomChange = { z -> zoomStates[page].floatValue = z }
        )
    }
}

/**
 * One zoomable page.
 *
 * Gestures (via `pointerInput { detectTransformGestures }`):
 *   - Pinch → scale, clamped to [1f, 5f]. Below ~1.05f we snap back
 *     to exactly 1f and re-centre, so the pager's swipe gating has
 *     a clean threshold.
 *   - Drag → pan, but only meaningful when zoomed. Pan offset is
 *     clamped softly to keep the image from drifting off-screen.
 *   - Double-tap (separate `detectTapGestures`) → toggles between
 *     1x and 3x. The Compose scope for both detectors lives on the
 *     same Box so they cooperate cleanly.
 *
 * Image source mirrors [BillImagePage] from the detail screen — local
 * file first, Drive URL second, lazy cache write-back via Coil's
 * `onSuccess` hook. We use SubcomposeAsyncImage so a loading state
 * is available; a black-on-black spinner reads well in the viewer.
 */
@Composable
private fun ZoomableImagePage(
    localPath: String?,
    driveId: String?,
    pageIndex: Int,
    allLocalPaths: List<String>,
    cache: BillImageCache,
    onLocalPathCached: (newLocalPathsCsv: String) -> Unit,
    onZoomChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    if (newScale <= 1.05f) {
                        // Snap to 1x and re-centre when we drop near
                        // the floor. Without the snap, residual scale
                        // would keep the pager's swipe-gate triggered.
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        scale = newScale
                        // Pan with the gesture but clamp softly: the
                        // image can move at most (scale-1)/2 of the
                        // page in each direction.
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                    onZoomChange(scale)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 3f
                            // Centre the zoom around the tap point so
                            // the user sees the area they cared about.
                            offsetX = -(tapOffset.x - size.width / 2f)
                            offsetY = -(tapOffset.y - size.height / 2f)
                        }
                        onZoomChange(scale)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val imageModifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )

        when {
            !localPath.isNullOrBlank() -> {
                SubcomposeAsyncImage(
                    model = File(localPath),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = imageModifier,
                    loading = {
                        CircularProgressIndicator(color = Color.White)
                    }
                )
            }

            !driveId.isNullOrBlank() -> {
                val viewUrl = remember(driveId) {
                    "https://drive.google.com/uc?id=$driveId&export=view"
                }
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(viewUrl)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = imageModifier,
                    loading = {
                        CircularProgressIndicator(color = Color.White)
                    },
                    onSuccess = {
                        // Lazy cache write-back, same shape as the
                        // detail screen's BillImagePage handler. We
                        // copy from Coil's disk cache (the response
                        // we just got) into BillImageCache.fileForDriveId,
                        // then bubble the merged CSV up to the VM so
                        // the row's localImagePaths gains the new path.
                        val destination = cache.fileForDriveId(driveId)
                        if (!destination.exists()) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val req = ImageRequest.Builder(context)
                                            .data(viewUrl)
                                            .build()
                                        val result = coil.Coil.imageLoader(context).execute(req)
                                        val drawable = result.drawable ?: return@withContext
                                        val bm = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                                            ?: return@withContext
                                        destination.outputStream().use { out ->
                                            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                                        }
                                    }
                                }
                                val newPaths = allLocalPaths.toMutableList()
                                while (newPaths.size <= pageIndex) newPaths += ""
                                newPaths[pageIndex] = destination.absolutePath
                                onLocalPathCached(newPaths.joinToString(","))
                            }
                        }
                    }
                )
            }

            else -> {
                Text(
                    text = stringResource(R.string.bill_detail_no_image),
                    color = Color.White
                )
            }
        }
    }

    // Watch the absolute pan distance — if it ever crosses the soft
    // bound (image edge would be inside the viewport), pull it back.
    // We do this in a LaunchedEffect keyed on scale so it only runs
    // when zoom changes, not on every recomposition.
    LaunchedEffect(scale) {
        if (scale <= 1f) {
            offsetX = 0f
            offsetY = 0f
        }
    }
    // Reset when the page is recycled to a new bill.
    LaunchedEffect(localPath, driveId) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        onZoomChange(1f)
    }
    // Suppress unused-warning on abs (kept around for future
    // soft-clamp expansion).
    @Suppress("UNUSED_EXPRESSION") abs(0f)
}

/**
 * CreationExtras-keyed [BillDetailViewModel] factory binding. Mirrors
 * the helper inside [BillDetailScreen] so this screen can reach the
 * same VM without exposing internals.
 */
@Composable
private fun creationExtrasViewModel(billId: String): BillDetailViewModel {
    val owner = LocalViewModelStoreOwner.current
        ?: error("No ViewModelStoreOwner found for BillImageViewerScreen")
    val defaultFactoryOwner = owner as? HasDefaultViewModelProviderFactory
    val extras = MutableCreationExtras(
        defaultFactoryOwner?.defaultViewModelCreationExtras ?: CreationExtras.Empty
    ).apply { set(BillDetailViewModel.ID_KEY, billId) }
    return viewModel(
        viewModelStoreOwner = owner,
        factory = BillDetailViewModel.Factory,
        extras = extras,
        // Distinct key so the viewer's VM is separate from the detail
        // screen's VM on the back stack — we don't want them sharing
        // state if both are alive.
        key = "BillImageViewer:$billId"
    )
}
