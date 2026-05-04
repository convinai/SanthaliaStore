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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.santhaliastore.ratecard.R

/**
 * Combined add / edit form. Caller passes `editingCode = null` for
 * the add flow and an existing code to enter edit mode.
 *
 * Form state is kept in `rememberSaveable` so a config change (or
 * Activity recreation due to system pressure) preserves the typed
 * values without an extra ViewModel round trip.
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

    // Seed the form with the existing item's values exactly once, the
    // first time the VM has loaded them. Subsequent edits do not
    // overwrite what the user is typing.
    LaunchedEffect(state.isLoading, state.originalCode) {
        if (!seeded && !state.isLoading && state.isEditMode) {
            name = state.initialName
            unit = state.initialUnit
            seeded = true
        } else if (!state.isEditMode) {
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { viewModel.save(code, name, unit) },
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
}
