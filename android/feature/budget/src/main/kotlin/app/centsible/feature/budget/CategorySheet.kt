package app.centsible.feature.budget

import app.centsible.core.designsystem.component.toggleRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.BudgetProgressBar
import app.centsible.core.designsystem.component.CategoryAvatar
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyInput
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.component.displayName
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.BudgetCategory
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.BudgetPot
import app.centsible.core.model.Money

private enum class SheetMode { Details, Move }
private enum class Direction { In, Out }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategorySheet(
    category: BudgetCategory,
    month: BudgetMonth,
    state: BudgetUiState,
    onDismiss: () -> Unit,
    onAssign: (Money) -> Unit,
    onMove: (BudgetPot, BudgetPot, Money) -> Unit,
    onRollover: (Boolean) -> Unit,
    onSaveNote: (String) -> Unit = {},
    onSeeTransactions: (() -> Unit)? = null,
    onAutomations: (() -> Unit)? = null,
    onAnnual: (Money?, Int) -> Unit = { _, _ -> },
) {
    var mode by remember { mutableStateOf(SheetMode.Details) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CentsibleTheme.colors.card,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            when (mode) {
                SheetMode.Details -> {
                    CategoryDetails(category, state, onAssign, onRollover, onMoveMoney = { mode = SheetMode.Move }, onSeeTransactions = onSeeTransactions, onAutomations = onAutomations, onAnnual = onAnnual)
                    if (state.canEditNotes) NoteEditor(category.id, state, onSaveNote)
                }
                SheetMode.Move -> MoveMoneyForm(category, month, onBack = { mode = SheetMode.Details }, onMove = { from, to, amount ->
                    onMove(from, to, amount)
                    mode = SheetMode.Details
                })
            }
        }
    }
}

@Composable
private fun CategoryDetails(
    category: BudgetCategory,
    state: BudgetUiState,
    onAssign: (Money) -> Unit,
    onRollover: (Boolean) -> Unit,
    onMoveMoney: () -> Unit,
    onSeeTransactions: (() -> Unit)? = null,
    onAutomations: (() -> Unit)? = null,
    onAnnual: (Money?, Int) -> Unit = { _, _ -> },
) {
    val colors = CentsibleTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        CategoryAvatar(category.name, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(category.name, style = MaterialTheme.typography.titleLarge)
            Text(state.month.displayName(), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
    Spacer(Modifier.height(20.dp))
    StatLabel(if (category.isOverspent) "Overspent" else "Available")
    MoneyText(category.balance.abs(), style = MaterialTheme.typography.displaySmall, color = if (category.isOverspent) colors.negative else colors.textPrimary, animate = true)
    Spacer(Modifier.height(12.dp))
    BudgetProgressBar(category.progress, category.isOverspent, height = 8.dp)
    Spacer(Modifier.height(16.dp))

    val rollover = category.balance - category.budgeted - category.spent
    DetailRow("Budgeted", category.budgeted)
    DetailRow("Spent", category.spent.abs())
    if (!rollover.isZero) DetailRow("Rolled over from last month", rollover)
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.border)

    if (state.canEdit) {
        var input by remember(category.id, category.budgeted) { mutableStateOf(MoneyInput.toInput(category.budgeted)) }
        val parsed = MoneyInput.parse(input)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Budgeted this month") },
                prefix = { Text("$") },
                isError = parsed == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { parsed?.let(onAssign) },
                enabled = parsed != null && parsed != category.budgeted && !state.saving,
            ) { Text("Save") }
        }
        Spacer(Modifier.height(12.dp))
        if (state.canMoveMoney) {
            OutlinedButton(onClick = onMoveMoney, modifier = Modifier.fillMaxWidth()) { Text("Move money") }
        }
        val income = state.data.valueOrNull?.incomeGroups?.any { g -> g.categories.any { it.id == category.id } } == true
        if (!income) YearlyBudget(state.annual[category.id.raw], state.saving, onAnnual)
        onAutomations?.let { open ->
            androidx.compose.material3.TextButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text("Automations") }
        }
        onSeeTransactions?.let { see ->
            androidx.compose.material3.TextButton(onClick = see, modifier = Modifier.fillMaxWidth()) { Text("See this month's transactions") }
        }
        if (state.canToggleRollover) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).toggleRow(category.carryover, !state.saving, onChange = onRollover), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Roll over overspending", style = MaterialTheme.typography.bodyLarge)
                    Text("Carry a negative balance into next month instead of taking it from To Budget", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                Switch(checked = category.carryover, onCheckedChange = null, enabled = !state.saving)
            }
        }
    } else {
        Text("View only", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
    }
}

@Composable
private fun DetailRow(label: String, amount: Money) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = CentsibleTheme.colors.textSecondary, modifier = Modifier.weight(1f))
        MoneyText(amount, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MoveMoneyForm(category: BudgetCategory, month: BudgetMonth, onBack: () -> Unit, onMove: (BudgetPot, BudgetPot, Money) -> Unit) {
    val colors = CentsibleTheme.colors
    var direction by remember { mutableStateOf(if (category.isOverspent) Direction.In else Direction.Out) }
    var other by remember { mutableStateOf<BudgetPot>(BudgetPot.ToBudget) }
    var input by remember { mutableStateOf(if (category.isOverspent) MoneyInput.toInput(category.balance.abs()) else "") }
    val amount = MoneyInput.parse(input)?.takeIf { it.minor > 0 }

    Text("Move money", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(direction == Direction.In, { direction = Direction.In }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Into ${category.name}") }
        SegmentedButton(direction == Direction.Out, { direction = Direction.Out }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Out of it") }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text("Amount") },
        prefix = { Text("$") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    StatLabel(if (direction == Direction.In) "From" else "To")
    val choices = listOf<Pair<BudgetPot, String>>(BudgetPot.ToBudget to "To Budget · ${MoneyFormat.format(month.toBudget)}") +
        month.expenseGroups.flatMap { it.categories }.filter { it.id != category.id && !it.hidden }
            .map { BudgetPot.Envelope(it.id) to "${it.name} · ${MoneyFormat.format(it.balance)}" }
    LazyColumn(Modifier.heightIn(max = 240.dp)) {
        items(choices, key = { it.second }) { (pot, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { other = pot }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (pot == other) "●" else "○", color = if (pot == other) colors.accent else colors.textTertiary)
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(
            onClick = {
                val self = BudgetPot.Envelope(category.id)
                if (direction == Direction.In) onMove(other, self, amount!!) else onMove(self, other, amount!!)
            },
            enabled = amount != null,
            modifier = Modifier.weight(1f),
        ) { Text("Move") }
    }
}

/** Notes and goals share Actual's category note: `#template` lines in it are goals. */
@Composable
private fun NoteEditor(category: app.centsible.core.model.CategoryId, state: BudgetUiState, onSave: (String) -> Unit) {
    val colors = CentsibleTheme.colors
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.border)
    StatLabel("Notes & goals")
    if (!state.noteLoaded) {
        Text("Loading…", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        return
    }
    var text by remember(category, state.note) { mutableStateOf(state.note.orEmpty()) }
    val goals = text.lines().count { it.trimStart().startsWith("#template") || it.trimStart().startsWith("#goal") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("#template 400\nor #template up to 1200") },
        minLines = 2,
        maxLines = 6,
        enabled = state.canEdit,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (goals > 0) "$goals goal line${if (goals == 1) "" else "s"}. Apply goals from the Budget menu." else "Add a #template line to set a goal",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (state.canEdit) androidx.compose.material3.TextButton(onClick = { onSave(text) }, enabled = text != state.note.orEmpty()) { Text("Save note") }
    }
}

/**
 * A yearly amount: a twelfth a month, leftovers carried forward, a bill covered from the
 * rest of the year, nothing once the year is used up. For insurance, memberships, gifts.
 */
@Composable
private fun YearlyBudget(annual: app.centsible.core.model.AnnualBudget?, saving: Boolean, onSave: (Money?, Int) -> Unit) {
    val colors = CentsibleTheme.colors
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(colors.cardMuted, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Yearly budget", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (annual == null) "Set a yearly amount: a twelfth a month, and a big bill draws from the rest of the year."
                    else "${MoneyFormat.format(annual.amount, showCents = false)} a year · ${MoneyFormat.format(annual.remaining, showCents = false)} left to budget this year",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                if (annual != null && annual.suggested != annual.budgeted) {
                    Text("Updates this month to ${MoneyFormat.format(annual.suggested)} shortly", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                }
            }
            androidx.compose.material3.TextButton(onClick = { editing = true }, enabled = !saving) { Text(if (annual == null) "Set up" else "Change") }
        }
    }
    if (editing) YearlyDialog(annual, onDismiss = { editing = false }, onSave = { a, s -> editing = false; onSave(a, s) })
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun YearlyDialog(annual: app.centsible.core.model.AnnualBudget?, onDismiss: () -> Unit, onSave: (Money?, Int) -> Unit) {
    val colors = CentsibleTheme.colors
    var amount by remember { mutableStateOf(annual?.amount?.let(MoneyInput::toInput).orEmpty()) }
    var start by remember { mutableStateOf(annual?.startMonth ?: 1) }
    var open by remember { mutableStateOf(false) }
    val parsed = MoneyInput.parse(amount)?.takeIf { it.minor >= 100 }
    val monthName = { m: Int -> java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yearly budget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Each month gets a twelfth. What isn't spent carries forward, so a quiet month leaves more for the next. When the bill comes, that month takes what it needs from the rest of the year; once the year's amount is used, the rest of the year is 0.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    amount, { amount = it },
                    label = { Text("Each year") },
                    prefix = { Text("$") },
                    isError = amount.isNotEmpty() && parsed == null,
                    supportingText = parsed?.let { { Text("${MoneyFormat.format(Money(Math.round(it.minor / 12.0)))} a month") } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                    OutlinedTextField(
                        monthName(start), {},
                        readOnly = true,
                        label = { Text("Year starts in") },
                        trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        (1..12).forEach { m -> androidx.compose.material3.DropdownMenuItem(text = { Text(monthName(m)) }, onClick = { start = m; open = false }) }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { parsed?.let { onSave(it, start) } }, enabled = parsed != null) { Text("Save") } },
        dismissButton = {
            Row {
                if (annual != null) androidx.compose.material3.TextButton(onClick = { onSave(null, start) }) { Text("Stop", color = colors.negative) }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
