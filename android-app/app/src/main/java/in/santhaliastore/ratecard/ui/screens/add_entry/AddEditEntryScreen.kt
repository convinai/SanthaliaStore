package `in`.santhaliastore.ratecard.ui.screens.add_entry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.santhaliastore.ratecard.R
import `in`.santhaliastore.ratecard.util.Time

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditEntryScreen(
    itemCode: String,
    editingEntryId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: AddEditEntryViewModel = viewModel(factory = AddEditEntryViewModel.Factory)
) {
    LaunchedEffect(itemCode, editingEntryId) {
        viewModel.bind(itemCode, editingEntryId)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    var date by rememberSaveable { mutableStateOf(Time.todayLocal()) }
    var price by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var supplier by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var seeded by rememberSaveable { mutableStateOf(false) }

    // Seed the form once the VM has finished loading the existing
    // entry. Don't flip `seeded` in the add-mode branch eagerly —
    // at first composition the snapshot still reports
    // isEditMode = false (bind() hasn't run yet), which would block
    // the edit-mode seed when bind() flips it true.
    LaunchedEffect(state.isEditMode, state.isLoading) {
        if (!seeded && state.isEditMode && !state.isLoading) {
            date = state.initialDate
            price = state.initialPrice
            quantity = state.initialQuantity
            supplier = state.initialSupplier
            notes = state.initialNotes
            seeded = true
        }
    }

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEditMode) R.string.edit_entry_title
                            else R.string.add_entry_title
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
                        onClick = { viewModel.save(date, price, quantity, supplier, notes) },
                        enabled = !state.isSaving && !state.isLoading
                    ) {
                        Text(stringResource(R.string.action_save))
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
                .imePadding()
        ) {
            // Date — read-only field that opens a DatePickerDialog
            OutlinedTextField(
                value = Time.displayDate(date),
                onValueChange = { /* read-only */ },
                readOnly = true,
                label = { Text(stringResource(R.string.field_entry_date)) },
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    }
                },
                supportingText = {
                    if (state.dateError == "date_invalid") {
                        Text(
                            stringResource(R.string.error_date_invalid),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                isError = state.dateError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = price,
                onValueChange = {
                    price = it.filter { ch -> ch.isDigit() || ch == '.' }
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
                value = quantity,
                onValueChange = { quantity = it.filter { ch -> ch.isDigit() || ch == '.' } },
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
                value = supplier,
                onValueChange = { supplier = it },
                label = { Text(stringResource(R.string.field_entry_supplier)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.field_entry_notes)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                maxLines = 3
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDatePicker) {
        val initialMillis = Time.localDateToMillis(date) ?: System.currentTimeMillis()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        date = Time.millisToLocalDate(millis)
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
