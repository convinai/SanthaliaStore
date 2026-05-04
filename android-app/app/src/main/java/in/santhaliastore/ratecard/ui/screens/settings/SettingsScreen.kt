package `in`.santhaliastore.ratecard.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.santhaliastore.ratecard.BuildConfig
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.ui.screens.settings.SettingsViewModel.TestResult
import `in`.santhaliastore.ratecard.ui.screens.settings.SettingsViewModel.UiEvent
import java.text.DateFormat
import java.util.Date

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
    val snackSyncDoneNoRows = stringResource(R.string.sync_done_no_rows)
    val snackSyncDoneWithRowsFormat = stringResource(R.string.sync_done_with_rows_format)
    val snackSyncFailedFormat = stringResource(R.string.sync_failed_format)

    // One-shot snackbar events from the VM (sync done / sync failed).
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                is UiEvent.SyncSuccess -> if (event.processed > 0) {
                    snackSyncDoneWithRowsFormat.format(event.processed)
                } else {
                    snackSyncDoneNoRows
                }
                is UiEvent.SyncFailure -> snackSyncFailedFormat.format(event.message)
            }
            snackbarHostState.showSnackbar(
                message = text,
                duration = SnackbarDuration.Long
            )
        }
    }

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
        ) {
            SectionTitle(stringResource(R.string.settings_section_sync))

            OutlinedTextField(
                value = sheetUrlField,
                onValueChange = {
                    sheetUrlField = it
                    viewModel.setSheetUrl(it)
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
                    onClick = { viewModel.syncNow() },
                    modifier = Modifier.weight(1f),
                    enabled = !state.syncing
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
                    enabled = !state.testingConnection && !state.syncing
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

            // Pending count — tells the user what the next sync will push.
            Spacer(Modifier.height(12.dp))
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

            // Last synced.
            Spacer(Modifier.height(4.dp))
            val lastSyncText = if (state.lastSyncedAt > 0L) {
                stringResource(
                    R.string.sync_last_synced_format,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(state.lastSyncedAt))
                )
            } else {
                stringResource(R.string.sync_never)
            }
            Text(
                text = lastSyncText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Last sync error in red (if any), verbatim.
            val lastError = state.lastSyncError
            if (!lastError.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.sync_last_error_format, lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // "View details" — opens a dialog with the same info, useful
            // when error text overflows or the user wants to copy it.
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showSyncDetails = true }) {
                Text(stringResource(R.string.sync_view_details))
            }

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
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(lastSyncedAt))
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
