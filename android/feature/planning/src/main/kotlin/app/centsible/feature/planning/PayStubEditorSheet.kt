package app.centsible.feature.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.DeductionKind
import app.centsible.core.model.DeductionMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PayStubEditorSheet(
    form: PayStubFormState,
    saving: Boolean,
    onUpdate: ((PayStubFormState) -> PayStubFormState) -> Unit,
    onSave: () -> Unit,
    onDelete: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = CentsibleTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pickingDate by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.card,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (form.id == null) "Add pay stub" else "Edit pay stub",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (form.id != null && onDelete != null) {
                    IconButton(onClick = { onDelete(form.id) }, enabled = !saving) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete pay stub", tint = colors.negative)
                    }
                }
            }

            // ── Date & Employer ──────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { pickingDate = true },
                    enabled = !saving,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text(form.date, maxLines = 1)
                }
                OutlinedTextField(
                    value = form.employer,
                    onValueChange = { v -> onUpdate { it.copy(employer = v) } },
                    label = { Text("Employer (optional)") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.weight(1.4f),
                )
            }

            // ── Hours, Hourly Rate & Gross Pay ───────────────────────────
            StatLabel("Hours & gross pay")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = form.hoursText,
                    onValueChange = { v -> onUpdate { IncomeCalc.updateHoursAndRate(it, hoursText = v) } },
                    label = { Text("Hours") },
                    placeholder = { Text("80") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form.hourlyRateText,
                    onValueChange = { v -> onUpdate { IncomeCalc.updateHoursAndRate(it, hourlyRateText = v) } },
                    label = { Text("Rate / hr") },
                    prefix = { Text("$") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = form.grossText,
                onValueChange = { v -> onUpdate { IncomeCalc.updateGross(it, v) } },
                label = { Text("Gross pay") },
                prefix = { Text("$") },
                singleLine = true,
                enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Enter hours to auto-calculate gross and %-based deductions, or type gross pay directly.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
            )

            // ── Deductions (401k, Taxes, Benefits) ───────────────────────
            HorizontalDivider(color = colors.border)
            StatLabel("Deductions (401(k), taxes, benefits)")

            val existingNames = remember(form.deductions) { form.deductions.map { it.name.trim().lowercase() }.toSet() }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IncomeCalc.presets.filter { it.name.lowercase() !in existingNames }.forEach { preset ->
                    AssistChip(
                        onClick = {
                            onUpdate {
                                it.copy(
                                    deductions = it.deductions + DeductionDraft(
                                        name = preset.name,
                                        kind = preset.kind,
                                        mode = preset.defaultMode,
                                        amountText = "",
                                        rateText = "",
                                    ),
                                )
                            }
                        },
                        enabled = !saving,
                        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        label = { Text(preset.name) },
                    )
                }
                AssistChip(
                    onClick = {
                        onUpdate {
                            it.copy(
                                deductions = it.deductions + DeductionDraft(
                                    name = "",
                                    kind = DeductionKind.Other,
                                    mode = DeductionMode.Fixed,
                                    amountText = "",
                                    rateText = "",
                                ),
                            )
                        }
                    },
                    enabled = !saving,
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    label = { Text("Custom") },
                )
            }

            form.deductions.forEachIndexed { idx, row ->
                DeductionRowEditor(
                    row = row,
                    enabled = !saving,
                    onChange = { transform -> onUpdate { IncomeCalc.updateDeduction(it, idx, transform) } },
                    onRemove = { onUpdate { it.copy(deductions = it.deductions.toMutableList().also { l -> l.removeAt(idx) }) } },
                )
            }

            // ── Notes ────────────────────────────────────────────────────
            OutlinedTextField(
                value = form.notes,
                onValueChange = { v -> onUpdate { it.copy(notes = v) } },
                label = { Text("Notes (optional)") },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Live Net Pay Summary ─────────────────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.cardMuted, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Gross pay", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    Text(MoneyFormat.format(form.gross), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("Total deductions", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    Text("-${MoneyFormat.format(form.totalDeductions)}", style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = colors.negative)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.border)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Net take-home", style = MaterialTheme.typography.titleSmall)
                        if (form.gross.minor > 0) {
                            Text(
                                "${IncomeCalc.formatPercent(form.takeHomePercent)}% of gross",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textTertiary,
                            )
                        }
                    }
                    Text(
                        MoneyFormat.format(form.net),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = colors.positive,
                    )
                }
            }

            Button(
                onClick = onSave,
                enabled = form.isValid && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (saving) "Saving…" else if (form.id == null) "Save pay stub" else "Update pay stub")
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (pickingDate) {
        val parsed = runCatching { LocalDate.parse(form.date) }.getOrNull() ?: LocalDate.now()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = parsed.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { ms ->
                            val iso = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                            onUpdate { it.copy(date = iso) }
                        }
                        pickingDate = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(pickerState) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeductionRowEditor(
    row: DeductionDraft,
    enabled: Boolean,
    onChange: ((DeductionDraft) -> DeductionDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = CentsibleTheme.colors
    var pickingKind by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.cardMuted, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = row.name,
                onValueChange = { v -> onChange { it.copy(name = v) } },
                label = { Text("Deduction") },
                placeholder = { Text("e.g. 401(k)") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove deduction")
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(
                    onClick = { pickingKind = true },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(row.kind.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = pickingKind, onDismissRequest = { pickingKind = false }) {
                    DeductionKind.entries.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(k.label) },
                            onClick = {
                                pickingKind = false
                                onChange { it.copy(kind = k) }
                            },
                        )
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = row.mode == DeductionMode.Fixed,
                    onClick = { onChange { it.copy(mode = DeductionMode.Fixed) } },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    enabled = enabled,
                ) { Text("Fixed", maxLines = 1) }
                SegmentedButton(
                    selected = row.mode == DeductionMode.Percent,
                    onClick = { onChange { it.copy(mode = DeductionMode.Percent) } },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    enabled = enabled,
                ) { Text("% Gross", maxLines = 1) }
            }
        }

        if (row.mode == DeductionMode.Fixed) {
            OutlinedTextField(
                value = row.amountText,
                onValueChange = { v -> onChange { it.copy(amountText = v) } },
                label = { Text("Amount per paycheck") },
                prefix = { Text("$") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = row.rateText,
                    onValueChange = { v -> onChange { it.copy(rateText = v) } },
                    label = { Text("% of gross") },
                    suffix = { Text("%") },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Calculated", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                    Text(
                        MoneyFormat.format(row.amount),
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        color = colors.textPrimary,
                    )
                }
            }
        }
    }
}
