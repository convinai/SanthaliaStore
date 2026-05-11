package `in`.santhaliastore.ratecard.ui.screens.bills

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.ui.components.bringIntoViewOnFocus
import `in`.santhaliastore.ratecard.util.Time
import java.io.File

/**
 * Combined add / edit screen for a single bill.
 *
 * In *add* mode the VM mints a fresh UUID before any image work so
 * the compressor can write straight to a deterministic per-bill cache
 * file. In *edit* mode the route arg's id is bound and the form
 * pre-populates from Room.
 *
 * Layout (top-to-bottom):
 *   1. Photo strip — horizontal scroll of staged thumbnails with an
 *      X to remove. Two CTA buttons below the strip: "Camera se
 *      photo" / "Gallery se chunein".
 *   2. Date (DatePicker, default today).
 *   3. Supplier (single-line text).
 *   4. Total amount (decimal keyboard).
 *   5. Notes (multi-line).
 *
 * Form scrolls vertically; every field is wrapped in
 * [bringIntoViewOnFocus] so the IME doesn't cover the active control.
 * imePadding is applied BEFORE verticalScroll — the same ordering
 * AddEditItemScreen documents — otherwise the scroll viewport
 * doesn't shrink and the bring-into-view target lands behind the
 * keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditBillScreen(
    editingBillId: String?,
    onSaved: (savedId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: AddEditBillViewModel = viewModel(factory = AddEditBillViewModel.Factory)
) {
    LaunchedEffect(editingBillId) { viewModel.bind(editingBillId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackHost = remember { SnackbarHostState() }

    // ---- Camera ----------------------------------------------------
    //
    // TakePicture wants a content Uri it can write into. We mint a
    // throwaway capture file per launch inside `bills/` (which the
    // FileProvider in AndroidManifest exposes), pass the URI to the
    // camera, and on a successful return hand the raw file URI to
    // the VM which runs the same compressor used for gallery picks.
    //
    // Keeping the capture URI in a remember-by-launcher state instead
    // of rememberSaveable is intentional: a fresh process recreates
    // the launcher, and any half-captured file in the cache is
    // disposable. The compressor would happily overwrite it.
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (success && uri != null) {
            viewModel.addImageFromUri(uri)
        }
    }

    // ---- Gallery (multi-pick) --------------------------------------
    //
    // PickMultipleVisualMedia (Photo Picker) is supported on API 33+
    // and gracefully back-ports to a system implementation on lower
    // versions via the activity-result contract — no version branch
    // needed at the call site.
    //
    // maxItems is fixed at the contract's construction time and cannot
    // be re-derived per launch, so we set it to the maximum bill cap.
    // The VM then enforces per-bill remaining capacity at
    // addImageFromUri time — any pick beyond the available slot count
    // surfaces a "5 photos tak" snackbar and the extra URIs are
    // silently discarded. This belt-and-suspenders approach is
    // intentional: the contract-level cap saves the user from picking
    // 50 photos by accident; the VM-level cap protects against the
    // edge case where the user already has N captures and re-opens
    // the picker.
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = AddEditBillViewModel.MAX_IMAGES
        )
    ) { uris ->
        uris.forEach { viewModel.addImageFromUri(it) }
    }

    // Hinglish toast strings resolved on the composable layer.
    val maxReachedMsg = stringResource(R.string.bill_capture_max_reached)
    val captureFailedMsg = stringResource(R.string.bill_capture_failed)
    val emptyMsg = stringResource(R.string.bill_save_no_data)

    LaunchedEffect(state.maxImagesReached) {
        if (state.maxImagesReached) {
            snackHost.showSnackbar(maxReachedMsg, duration = SnackbarDuration.Short)
            viewModel.clearMaxImagesFlag()
        }
    }
    LaunchedEffect(state.captureError) {
        if (state.captureError != null) {
            snackHost.showSnackbar(captureFailedMsg, duration = SnackbarDuration.Short)
            viewModel.clearCaptureError()
        }
    }
    LaunchedEffect(state.saveError) {
        if (state.saveError == "empty") {
            snackHost.showSnackbar(emptyMsg, duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(state.saved) {
        if (state.saved && state.billId != null) {
            onSaved(state.billId!!)
        }
    }

    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEditMode) R.string.edit_bill_title
                            else R.string.add_bill_title
                        )
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
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = !state.isSaving && !state.isLoading
                    ) {
                        Text(stringResource(R.string.bill_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackHost) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                // imePadding BEFORE verticalScroll — see kdoc above.
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            PhotoStripSection(
                images = state.images,
                processing = state.processing,
                onAddCamera = {
                    val file = newCaptureFile(context, state.billId.orEmpty())
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    pendingCaptureUri = uri
                    cameraLauncher.launch(uri)
                },
                onAddGallery = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                onRemove = { viewModel.removeImage(it) }
            )

            Spacer(Modifier.height(24.dp))

            // Date — read-only field that opens a DatePickerDialog.
            OutlinedTextField(
                value = if (state.date.isNotBlank()) Time.displayDate(state.date) else "",
                onValueChange = { /* read-only */ },
                readOnly = true,
                label = { Text(stringResource(R.string.bill_field_date)) },
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewOnFocus()
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.supplier,
                onValueChange = viewModel::onSupplierChange,
                label = { Text(stringResource(R.string.bill_field_supplier)) },
                supportingText = { Text(stringResource(R.string.bill_field_supplier_helper)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewOnFocus()
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.amount,
                onValueChange = viewModel::onAmountChange,
                label = { Text(stringResource(R.string.bill_field_amount)) },
                supportingText = {
                    Text(
                        if (state.amountError == "invalid")
                            stringResource(R.string.error_price_invalid)
                        else stringResource(R.string.bill_field_amount_helper),
                        color = if (state.amountError != null)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                isError = state.amountError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewOnFocus()
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text(stringResource(R.string.bill_field_notes)) },
                supportingText = { Text(stringResource(R.string.bill_field_notes_helper)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .bringIntoViewOnFocus()
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDatePicker) {
        val initialMillis = Time.localDateToMillis(state.date) ?: System.currentTimeMillis()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        viewModel.onDateChange(Time.millisToLocalDate(millis))
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * Photo strip + capture buttons.
 *
 * Visual layout: small section header, then a horizontal LazyRow of
 * thumbnails (each with an X overlay to remove), then a busy
 * spinner when compression is in flight, then the two CTA buttons
 * stacked horizontally so the camera path is on the left (primary
 * intent for new captures).
 */
@Composable
private fun PhotoStripSection(
    images: List<AddEditBillViewModel.StagedImage>,
    processing: Int,
    onAddCamera: () -> Unit,
    onAddGallery: () -> Unit,
    onRemove: (Int) -> Unit
) {
    Text(
        text = stringResource(R.string.bill_photos_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = stringResource(R.string.bill_photos_caption),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(12.dp))

    if (images.isNotEmpty()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(
                items = images,
                key = { img ->
                    // Stable key per staged image so a remove
                    // doesn't trigger a full LazyRow rebuild. We
                    // prefer the Drive id (immutable post-upload),
                    // fall back to the local path (stable across
                    // the lifetime of the staging entry), and
                    // finally to the index — rare, only happens
                    // when both are blank which shouldn't occur in
                    // practice.
                    img.driveFileId ?: img.localPath ?: img.hashCode().toString()
                }
            ) { img ->
                val i = images.indexOf(img)
                StagedThumb(
                    image = img,
                    onRemove = { onRemove(i) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (processing > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.bill_capture_processing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onAddCamera,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.bill_capture_camera), maxLines = 1)
        }
        OutlinedButton(
            onClick = onAddGallery,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.bill_capture_gallery), maxLines = 1)
        }
    }
}

/**
 * One staged thumbnail. 88x88 dp gives the user enough surface to
 * verify they captured the right page without dominating the form.
 *
 * The X (remove) button sits at the top-right inside its own
 * tappable container so the user can clearly hit-test it; small
 * remove targets are a top reason users accidentally tap the
 * thumbnail itself.
 */
@Composable
private fun StagedThumb(
    image: AddEditBillViewModel.StagedImage,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            !image.localPath.isNullOrBlank() -> {
                AsyncImage(
                    model = File(image.localPath),
                    contentDescription = stringResource(R.string.bills_thumb_cd),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Filled.ReceiptLong,
                    contentDescription = stringResource(R.string.bills_thumb_placeholder_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Remove (X) button — opaque background so the icon stays
        // readable against light or dark thumbnails.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.bill_image_remove_cd),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Mint a fresh capture file inside `<filesDir>/bills/`. We use a
 * timestamp + random suffix rather than the bill's `(id, index)`
 * tuple because:
 *   - The TakePicture URI must exist before we know what index
 *     we're about to assign (the VM bumps `nextCaptureIndex` only
 *     after compression).
 *   - The compressor overwrites whatever it writes to into the
 *     canonical `(id, index).jpg` path anyway — this file is a
 *     transient buffer the camera writes into and the compressor
 *     reads from.
 *
 * Returns a `File` that's guaranteed to exist (zero-byte) so the
 * camera contract considers it writable.
 */
private fun newCaptureFile(context: android.content.Context, billId: String): File {
    val dir = File(context.filesDir, "bills").also { it.mkdirs() }
    val stamp = System.currentTimeMillis()
    val name = if (billId.isNotBlank()) "capture_${billId.takeLast(8)}_$stamp.jpg"
    else "capture_$stamp.jpg"
    val file = File(dir, name)
    file.createNewFile()
    return file
}

