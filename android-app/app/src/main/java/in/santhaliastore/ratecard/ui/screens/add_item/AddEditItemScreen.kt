package `in`.santhaliastore.ratecard.ui.screens.add_item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.util.Time

/**
 * Combined add / edit form. Caller passes `editingCode = null` for
 * the add flow and an existing code to enter edit mode.
 *
 * Form state is kept in `rememberSaveable` so a config change (or
 * Activity recreation due to system pressure) preserves the typed
 * values without an extra ViewModel round trip.
 *
 * Add mode also exposes an optional "first purchase entry" section
 * — date + price + qty + supplier + notes — so the user can capture
 * the current rate at item-creation time without bouncing through a
 * second screen. The section is hidden in edit mode.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditItemScreen(
    editingCode: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: AddEditItemViewModel = viewModel(factory = AddEditItemViewModel.Factory)
) {
    LaunchedEffect(editingCode) { viewModel.bind(editingCode) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    var code by rememberSaveable { mutableStateOf(editingCode.orEmpty()) }
    var name by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var seeded by rememberSaveable { mutableStateOf(false) }

    // Optional first-purchase fields. Default the date to today so a
    // tap-and-save is the common case.
    var entryDate by rememberSaveable { mutableStateOf(Time.todayLocal()) }
    var entryPrice by rememberSaveable { mutableStateOf("") }
    var entryQuantity by rememberSaveable { mutableStateOf("") }
    var entrySupplier by rememberSaveable { mutableStateOf("") }
    var entryNotes by rememberSaveable { mutableStateOf("") }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    // Seed the form with the existing item's values exactly once, the
    // first time the VM has loaded them. Subsequent edits do not
    // overwrite what the user is typing.
    //
    // Note: we only flip `seeded` once we're sure we're in edit mode
    // AND the load has finished. Doing it eagerly in the add-mode
    // branch causes the seed to be skipped on edit, because at first
    // composition (before bind() runs) the snapshot still reports
    // isEditMode = false.
    LaunchedEffect(state.isEditMode, state.isLoading) {
        if (!seeded && state.isEditMode && !state.isLoading) {
            name = state.initialName
            unit = state.initialUnit
            seeded = true
        }
    }

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    val units = stringArrayResource(R.array.kirana_units)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEditMode) R.string.edit_item_title
                            else R.string.add_item_title
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it.trim().uppercase()
                    viewModel.clearErrors()
                },
                label = { Text(stringResource(R.string.field_item_code)) },
                supportingText = {
                    Text(
                        when (state.codeError) {
                            "code_empty" -> stringResource(R.string.error_code_empty)
                            "code_dup" -> stringResource(R.string.error_code_duplicate)
                            else -> stringResource(R.string.field_item_code_helper)
                        },
                        color = if (state.codeError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                isError = state.codeError != null,
                singleLine = true,
                enabled = !state.isLoading && !state.isSaving,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    viewModel.clearErrors()
                },
                label = { Text(stringResource(R.string.field_item_name)) },
                supportingText = {
                    Text(
                        if (state.nameError == "name_empty")
                            stringResource(R.string.error_name_empty)
                        else stringResource(R.string.field_item_name_helper),
                        color = if (state.nameError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                isError = state.nameError != null,
                singleLine = true,
                enabled = !state.isLoading && !state.isSaving,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = unit,
                onValueChange = { unit = it },
                label = { Text(stringResource(R.string.field_item_unit)) },
                supportingText = { Text(stringResource(R.string.field_item_unit_helper)) },
                singleLine = true,
                enabled = !state.isLoading && !state.isSaving,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // Quick-pick chips for common kirana units. Tapping fills the
            // unit field — much faster than typing on a low-end device.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                units.forEach { suggestion ->
                    AssistChip(
                        onClick = { unit = suggestion },
                        label = { Text(suggestion) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )
                }
            }

            // -------------------------------------------------------
            // Optional first purchase entry — only shown when adding
            // a new item. Lets the user log "today's rate" alongside
            // creating the item.
            // -------------------------------------------------------
            if (!state.isEditMode) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.add_item_initial_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.add_item_initial_section_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                // Date — read-only field that opens a DatePickerDialog
                OutlinedTextField(
                    value = Time.displayDate(entryDate),
                    onValueChange = { /* read-only */ },
                    readOnly = true,
                    label = { Text(stringResource(R.string.field_entry_date)) },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = entryPrice,
                    onValueChange = {
                        entryPrice = it.filter { ch -> ch.isDigit() || ch == '.' }
                        viewModel.clearErrors()
                    },
                    label = { Text(stringResource(R.string.field_entry_price)) },
                    supportingText = {
                        if (state.priceError == "price_invalid") {
                            Text(
                                stringResource(R.string.error_price_invalid),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    isError = state.priceError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = entryQuantity,
                    onValueChange = {
                        entryQuantity = it.filter { ch -> ch.isDigit() || ch == '.' }
                    },
                    label = { Text(stringResource(R.string.field_entry_quantity)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = entrySupplier,
                    onValueChange = { entrySupplier = it },
                    label = { Text(stringResource(R.string.field_entry_supplier)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = entryNotes,
                    onValueChange = { entryNotes = it },
                    label = { Text(stringResource(R.string.field_entry_notes)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    maxLines = 3
                )
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    viewModel.save(
                        code = code,
                        name = name,
                        unit = unit,
                        initialDate = entryDate,
                        initialPrice = entryPrice,
                        initialQuantity = entryQuantity,
                        initialSupplier = entrySupplier,
                        initialNotes = entryNotes
                    )
                },
                enabled = !state.isSaving && !state.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(stringResource(R.string.action_save))
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDatePicker) {
        val initialMillis = Time.localDateToMillis(entryDate) ?: System.currentTimeMillis()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        entryDate = Time.millisToLocalDate(millis)
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
