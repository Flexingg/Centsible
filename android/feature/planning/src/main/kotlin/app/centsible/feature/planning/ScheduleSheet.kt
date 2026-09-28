package app.centsible.feature.planning

import app.centsible.core.designsystem.component.toggleRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyInput
import app.centsible.core.designsystem.component.PickerItem
import app.centsible.core.designsystem.component.PickerSheet
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AccountId
import app.centsible.core.model.AmountOp
import app.centsible.core.model.Frequency
import app.centsible.core.model.Money
import app.centsible.core.model.Recurrence
import app.centsible.core.model.Schedule
import app.centsible.core.model.ScheduleDraft
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private enum class Repeat(val label: String, val frequency: Frequency?) {
    Once("Once", null), Weekly("Weekly", Frequency.Weekly), Monthly("Monthly", Frequency.Monthly), Yearly("Yearly", Frequency.Yearly)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSheet(
    schedule: Schedule?,
    data: RecurringData,
    canEdit: Boolean,
    canSkip: Boolean,
    canPost: Boolean,
    onDismiss: () -> Unit,
    onSave: (ScheduleDraft) -> Unit,
    onSkip: (Schedule) -> Unit,
    onPost: (Schedule) -> Unit,
    onDelete: (Schedule) -> Unit,
) {
    val colors = CentsibleTheme.colors
    val existing = schedule
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var merchant by remember { mutableStateOf(existing?.payeeId?.let { data.payeeNames[it.raw] }.orEmpty()) }
    var income by remember { mutableStateOf((existing?.amount?.minor ?: -1) > 0) }
    var amount by remember { mutableStateOf(existing?.amount?.let { MoneyInput.toInput(it.abs()) }.orEmpty()) }
    var approx by remember { mutableStateOf(existing?.amountOp != AmountOp.Is) }
    var account by remember { mutableStateOf(existing?.accountId ?: data.accounts.firstOrNull { !it.offBudget && !it.closed }?.id) }
    var repeat by remember {
        mutableStateOf(Repeat.entries.firstOrNull { it.frequency == existing?.recurrence?.frequency } ?: if (existing?.recurrence == null && existing != null) Repeat.Once else Repeat.Monthly)
    }
    var interval by remember { mutableIntStateOf(existing?.recurrence?.interval ?: 1) }
    var date by remember { mutableStateOf(existing?.nextDate?.let(LocalDate::parse) ?: LocalDate.now().plusDays(1)) }
    var auto by remember { mutableStateOf(existing?.postsTransaction ?: false) }
    var pickingAccount by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    val parsed = MoneyInput.parse(amount)?.abs()
    val accountNames = data.accounts.associate { it.id to it.name }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (existing == null) "New recurring" else data.title(existing), style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!income, { income = false }, SegmentedButtonDefaults.itemShape(0, 2), enabled = canEdit) { Text("Bill") }
                SegmentedButton(income, { income = true }, SegmentedButtonDefaults.itemShape(1, 2), enabled = canEdit) { Text("Income") }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, enabled = canEdit, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant") }, singleLine = true, enabled = canEdit, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    amount, { amount = it },
                    label = { Text("Amount") },
                    prefix = { Text("$") },
                    isError = amount.isNotEmpty() && parsed == null,
                    singleLine = true,
                    enabled = canEdit,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.toggleRow(approx, canEdit) { approx = it }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(checked = approx, onCheckedChange = null, enabled = canEdit)
                    Text("Varies", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                }
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = canEdit) { pickingAccount = true }.padding(vertical = 8.dp)) {
                StatLabel("Account", Modifier.width(96.dp))
                Text(account?.let { accountNames[it] } ?: "Choose account", style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider(color = colors.border)
            StatLabel("Repeats")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Repeat.entries.forEachIndexed { i, r ->
                    SegmentedButton(repeat == r, { repeat = r }, SegmentedButtonDefaults.itemShape(i, Repeat.entries.size), enabled = canEdit) { Text(r.label) }
                }
            }
            if (repeat != Repeat.Once) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { if (interval > 1) interval-- }, enabled = canEdit && interval > 1) { Text("−") }
                    Text("$interval", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { interval++ }, enabled = canEdit) { Text("+") }
                    Text(repeat.label.removeSuffix("ly").lowercase().let { if (it == "dai") "day" else it } + if (interval > 1) "s" else "", style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (existing?.recurrence?.patternsJson != null) {
                Text("This schedule uses a custom pattern set in Actual. Changing how it repeats here replaces that pattern.", style = MaterialTheme.typography.bodySmall, color = colors.warning)
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = canEdit) { pickingDate = true }.padding(vertical = 8.dp)) {
                StatLabel(if (repeat == Repeat.Once) "Date" else "Next", Modifier.width(96.dp))
                Text(Describe.shortDate(date.toString()) + " " + date.year, style = MaterialTheme.typography.bodyLarge)
            }
            Row(Modifier.toggleRow(auto, canEdit) { auto = it }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Add automatically", style = MaterialTheme.typography.bodyLarge)
                    Text("Actual creates the transaction on the due date", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                Switch(checked = auto, onCheckedChange = null, enabled = canEdit)
            }
            if (existing != null && existing.upcoming.size > 1) {
                Text("Coming up: " + existing.upcoming.joinToString(", ") { Describe.shortDate(it) }, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }

            if (canEdit) {
                Button(
                    onClick = {
                        val signed = Money(parsed!!.minor * if (income) 1 else -1)
                        val sameFrequency = repeat.frequency == existing?.recurrence?.frequency && interval == existing?.recurrence?.interval
                        onSave(
                            ScheduleDraft(
                                name = name.trim().ifEmpty { null },
                                payeeName = merchant.trim().ifEmpty { null },
                                accountId = account,
                                amount = signed,
                                amountOp = if (approx) AmountOp.IsApprox else AmountOp.Is,
                                recurrence = repeat.frequency?.let { f ->
                                    Recurrence(f, interval, date.toString(), patternsJson = existing?.recurrence?.patternsJson?.takeIf { sameFrequency })
                                },
                                date = date.toString().takeIf { repeat == Repeat.Once },
                                postsTransaction = auto,
                            ),
                        )
                    },
                    enabled = parsed != null && !parsed.isZero && account != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save") }
            }
            if (existing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canSkip) OutlinedButton(onClick = { onSkip(existing) }, modifier = Modifier.weight(1f)) { Text("Skip next") }
                    if (canPost) OutlinedButton(onClick = { onPost(existing) }, modifier = Modifier.weight(1f)) { Text("Add now") }
                }
                if (canEdit) TextButton(onClick = { onDelete(existing) }, modifier = Modifier.fillMaxWidth()) { Text("Delete", color = colors.negative) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (pickingAccount) {
        PickerSheet(
            "Account",
            data.accounts.filter { !it.closed }.map { PickerItem(it.id.raw, it.name, supporting = MoneyFormat.format(it.balance)) },
            account?.raw,
            onPick = { account = AccountId(it.key); pickingAccount = false },
            onDismiss = { pickingAccount = false },
        )
    }
    if (pickingDate) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("OK") }
            },
        ) { DatePicker(dateState) }
    }
}
