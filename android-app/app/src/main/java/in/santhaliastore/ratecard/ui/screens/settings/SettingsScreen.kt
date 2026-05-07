package `in`.santhaliastore.ratecard.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.santhaliastore.ratecard.BuildConfig
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.ui.screens.settings.SettingsViewModel.TestResult
import `in`.santhaliastore.ratecard.ui.screens.settings.SettingsViewModel.UiEvent
import `in`.santhaliastore.ratecard.util.Time

/**
 * Settings screen.
 *
 * Three sections per spec:
 *   - Sync settings (URL field, sync now, test connection, last synced,
 *     pending count, last error, "view details")
 *   - Lock (PIN toggle + change-pin entry point)
 *   - About (version, made-for line)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var sheetUrlField by rememberSaveable { mutableStateOf("") }
    var seededUrl by rememberSaveable { mutableStateOf(false) }
    // Tracks whether the user has tapped the pencil to re-edit a saved URL.
    // Lives in the screen, not the VM — see spec.
    var editingUrl by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.sheetUrl) {
        // Seed once when DataStore returns its first value.
        if (!seededUrl) {
            sheetUrlField = state.sheetUrl
            seededUrl = true
        }
    }

    var showPinSheet by rememberSaveable { mutableStateOf(false) }
    var showSyncDetails by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Resolve the Hinglish snackbar copy here (not in the VM) so
    // every user-visible string lives in res/values/strings.xml.
    // Home and Settings share this resolver shape so the same outcome
    // surfaces the same message on both screens.
    val snackSyncDoneNoRows = stringResource(R.string.sync_done_no_rows)
    val snackSyncDoneWithRowsFormat = stringResource(R.string.sync_done_with_rows_format)
    val snackSyncDonePulledOnlyFormat = stringResource(R.string.sync_done_pulled_only_format)
    val snackSyncDoneCombinedFormat = stringResource(R.string.sync_done_combined_format)
    val snackSyncFailedFormat = stringResource(R.string.sync_failed_format)
    val snackResetDoneFormat = stringResource(R.string.settings_reset_done_format)
    val snackResetFailedFormat = stringResource(R.string.settings_reset_failed_format)

    // One-shot snackbar events from the VM (sync done / sync failed /
    // reset done / reset failed). Reset events go through the SAME
    // snackbar host as sync so the user sees a consistent feedback
    // affordance — the destructive nature of reset is conveyed by
    // the confirm dialog, not by surfacing it elsewhere.
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
                is UiEvent.ResetSuccess -> snackResetDoneFormat.format(
                    event.itemsApplied,
                    event.entriesApplied
                )
                is UiEvent.ResetFailure -> snackResetFailedFormat.format(event.message)
            }
            snackbarHostState.showSnackbar(
                message = text,
                duration = SnackbarDuration.Long
            )
        }
    }

    // Confirmation dialog state for the destructive reset action.
    // Lives in the screen so the user dismissing it can't be racing a
    // suspend launch — the VM only sees the "go" once the user has
    // tapped confirm.
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            SectionTitle(stringResource(R.string.settings_section_sync))

            // Pre-compute the human-friendly last-sync string once: it's
            // used both inside the summary card and as a standalone line
            // when the editor is open. We deliberately use Time.displayDateTime
            // (not the system DateFormat) so Settings and Home speak in
            // the same absolute format — `5 May 2026 2:30 PM`.
            //
            // While ANY sync is running (auto-sync on resume, post-write
            // debounced auto-sync, or the manual button) we flip to
            // "Sync ho raha hai…" so the user always sees what's actually
            // happening. The repository's `isSyncing` is the single
            // source of truth — both the summary card here and the Home
            // screen line read from the same flag.
            val lastSyncText = when {
                state.syncing -> stringResource(R.string.sync_status_in_progress)
                state.lastSyncedAt > 0L -> stringResource(
                    R.string.sync_last_synced_format,
                    Time.displayDateTime(state.lastSyncedAt)
                )
                else -> stringResource(R.string.sync_never)
            }

            // ----- Group 1: URL block (collapsed summary OR editable field) -----
            val hasSavedUrl = state.sheetUrl.isNotBlank()
            val showEditor = !hasSavedUrl || editingUrl

            if (!showEditor) {
                SheetUrlSummaryCard(
                    url = state.sheetUrl,
                    statusText = lastSyncText,
                    onEdit = {
                        // Pre-fill the editor with the currently-saved URL
                        // so the user can tweak instead of retyping.
                        sheetUrlField = state.sheetUrl
                        editingUrl = true
                    }
                )
            } else {
                OutlinedTextField(
                    value = sheetUrlField,
                    onValueChange = {
                        sheetUrlField = it
                        viewModel.clearTestResult()
                    },
                    label = { Text(stringResource(R.string.settings_sheet_url)) },
                    supportingText = { Text(stringResource(R.string.settings_sheet_url_helper)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.setSheetUrl(sheetUrlField)
                            // Only collapse back to summary if the user
                            // actually saved something. A blank save just
                            // keeps the editor open in "no URL yet" mode.
                            if (sheetUrlField.isNotBlank()) {
                                editingUrl = false
                            }
                        },
                        enabled = sheetUrlField.isNotBlank()
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                    // Cancel only makes sense when there's already a saved
                    // URL to fall back to — otherwise there's nothing to
                    // cancel into.
                    if (hasSavedUrl) {
                        OutlinedButton(
                            onClick = {
                                sheetUrlField = state.sheetUrl
                                editingUrl = false
                                viewModel.clearTestResult()
                            }
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ----- Group 2: action buttons (always visible) -----
            // Disabled when no URL is saved yet — they'd just no-op.
            val urlSaved = state.sheetUrl.isNotBlank()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.syncNow() },
                    modifier = Modifier.weight(1f),
                    enabled = urlSaved && !state.syncing
                ) {
                    if (state.syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.sync_now))
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.testConnection() },
                    modifier = Modifier.weight(1f),
                    enabled = urlSaved && !state.testingConnection && !state.syncing
                ) {
                    if (state.testingConnection) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.sync_test_connection))
                    }
                }
            }

            // Soft caption explaining WHY the buttons are greyed.
            if (!urlSaved) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_sheet_url_save_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Test result (separate from sync result).
            when (val r = state.testResult) {
                is TestResult.Ok -> InlineStatus(
                    text = stringResource(R.string.sync_test_ok),
                    success = true
                )
                is TestResult.Failed -> InlineStatus(
                    text = stringResource(R.string.sync_test_failed, r.message),
                    success = false
                )
                null -> Unit
            }

            Spacer(Modifier.height(16.dp))

            // ----- Group 3: pending count -----
            val pendingText = if (state.pendingCount > 0) {
                stringResource(R.string.sync_pending_format, state.pendingCount)
            } else {
                stringResource(R.string.sync_pending_none)
            }
            Text(
                text = pendingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ----- Group 4: last sync error (if any) -----
            val lastError = state.lastSyncError
            if (!lastError.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.sync_last_error_format, lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // ----- Group 5: details affordance -----
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showSyncDetails = true }) {
                Text(stringResource(R.string.sync_view_details))
            }

            // ----- Group 6: destructive recovery action -----
            // Wipes the local DB and re-pulls from the sheet. Lives
            // OUTSIDE the URL-collapse so the user can always reach it
            // even when the editor is open (or even when there's no
            // saved URL — though we disable it in that case because a
            // pull with no URL would just no-op).
            //
            // The error-tinted outline is the visual cue that this is
            // not a normal action. The caption underneath spells out
            // exactly what happens, and the confirm dialog reiterates
            // it once more — three layers of "are you sure" before
            // any data is touched.
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { showResetConfirm = true },
                enabled = urlSaved && !state.syncing && !state.resetting,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_reset_button))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_reset_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            SectionTitle(stringResource(R.string.settings_section_lock))
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_pin_enabled),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_pin_enabled_caption),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.pinEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) showPinSheet = true
                                else viewModel.togglePin(false)
                            }
                        )
                    }

                    if (state.pinEnabled) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showPinSheet = true }) {
                            Text(stringResource(R.string.settings_pin_change))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionTitle(stringResource(R.string.settings_section_about))
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_about_version_format, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_about_made_for),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showPinSheet) {
        PinEntryDialog(
            onDismiss = { showPinSheet = false },
            onConfirm = { pin ->
                viewModel.setPin(pin)
                showPinSheet = false
            }
        )
    }

    if (showSyncDetails) {
        SyncDetailsDialog(
            lastSyncedAt = state.lastSyncedAt,
            lastSyncError = state.lastSyncError,
            pendingCount = state.pendingCount,
            onDismiss = { showSyncDetails = false }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetLocalData()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.settings_reset_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Fullscreen blocking overlay while reset is in flight. We use a
    // semi-transparent scrim with an indeterminate spinner so the user
    // sees that something destructive is happening AND can't tap any
    // background controls (a stray tap on Sync now mid-reset would
    // race the post-reset pull). The clickable modifier swallows
    // touches without doing anything — that's the point.
    if (state.resetting) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_reset_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

/**
 * Compact, read-only summary of the saved Google Sheet URL.
 *
 * Layout: a single Card with the label, the URL (truncated with
 * ellipsis so the host stays visible) + a pencil edit affordance,
 * and a status line showing the last sync time.
 *
 * The URL Text is the only weight=1f sibling of the IconButton so
 * very long Apps Script URLs (~110 chars) cannot push the pencil
 * off the edge of the screen.
 */
@Composable
private fun SheetUrlSummaryCard(
    url: String,
    statusText: String,
    onEdit: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_sheet_url_set_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 44 dp hit target (Material's IconButton is 48 dp by
                // default — well above the 44 dp guideline).
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.settings_sheet_url_edit),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InlineStatus(text: String, success: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (success) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.error
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (success) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * Dialog showing sync state in human-friendly form. Useful when the
 * inline error text gets clipped — the dialog wraps freely.
 */
@Composable
private fun SyncDetailsDialog(
    lastSyncedAt: Long,
    lastSyncError: String?,
    pendingCount: Int,
    onDismiss: () -> Unit
) {
    val lastSyncText = if (lastSyncedAt > 0L) {
        stringResource(
            R.string.sync_last_synced_format,
            Time.displayDateTime(lastSyncedAt)
        )
    } else {
        stringResource(R.string.sync_never)
    }
    val pendingText = if (pendingCount > 0) {
        stringResource(R.string.sync_pending_format, pendingCount)
    } else {
        stringResource(R.string.sync_pending_none)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_details_title)) },
        text = {
            Column {
                Text(text = lastSyncText, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(text = pendingText, style = MaterialTheme.typography.bodyMedium)
                if (!lastSyncError.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.sync_last_error_format, lastSyncError),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        }
    )
}

/**
 * Tiny inline dialog for setting/changing the PIN. Shows two fields
 * (PIN + confirm) and writes only when they match and are 4 digits.
 */
@Composable
private fun PinEntryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val mismatchMessage = stringResource(R.string.lock_error_mismatch)
    val pinTooShortMessage = "PIN 4 digit ka hona chahiye"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_set_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(4); error = null },
                    label = { Text(stringResource(R.string.lock_caption)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { v -> confirm = v.filter { it.isDigit() }.take(4); error = null },
                    label = { Text(stringResource(R.string.lock_confirm_title)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pin.length != 4 || confirm.length != 4) {
                    error = pinTooShortMessage
                } else if (pin != confirm) {
                    error = mismatchMessage
                } else {
                    onConfirm(pin)
                }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
