package app.canopy.feature.budget

import androidx.compose.foundation.clickable
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
import app.canopy.core.designsystem.component.BudgetProgressBar
import app.canopy.core.designsystem.component.CategoryAvatar
import app.canopy.core.designsystem.component.MoneyFormat
import app.canopy.core.designsystem.component.MoneyInput
import app.canopy.core.designsystem.component.MoneyText
import app.canopy.core.designsystem.component.StatLabel
import app.canopy.core.designsystem.component.displayName
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.model.BudgetCategory
import app.canopy.core.model.BudgetMonth
import app.canopy.core.model.BudgetPot
import app.canopy.core.model.Money

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
) {
    var mode by remember { mutableStateOf(SheetMode.Details) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CanopyTheme.colors.card,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            when (mode) {
                SheetMode.Details -> CategoryDetails(category, state, onAssign, onRollover, onMoveMoney = { mode = SheetMode.Move })
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
) {
    val colors = CanopyTheme.colors
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
    MoneyText(category.balance.abs(), style = MaterialTheme.typography.displaySmall, color = if (category.isOverspent) colors.negative else colors.textPrimary)
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
        if (state.canToggleRollover) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Roll over overspending", style = MaterialTheme.typography.bodyLarge)
                    Text("Carry a negative balance into next month instead of taking it from To Budget", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                Switch(checked = category.carryover, onCheckedChange = onRollover, enabled = !state.saving)
            }
        }
    } else {
        Text("View only", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
    }
}

@Composable
private fun DetailRow(label: String, amount: Money) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = CanopyTheme.colors.textSecondary, modifier = Modifier.weight(1f))
        MoneyText(amount, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MoveMoneyForm(category: BudgetCategory, month: BudgetMonth, onBack: () -> Unit, onMove: (BudgetPot, BudgetPot, Money) -> Unit) {
    val colors = CanopyTheme.colors
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
