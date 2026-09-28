package app.centsible.feature.budget

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.component.toggleRow
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Autopilot
import app.centsible.core.model.AverageBasis
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money

/** Budget → ⋮ → Autopilot and the overspending banner. */
data class BudgetPlanActions(
    val openAutopilot: (Boolean) -> Unit = {},
    val setBasis: (AverageBasis) -> Unit = {},
    val toggle: (CategoryId) -> Unit = {},
    val apply: () -> Unit = {},
    val openCover: (Boolean) -> Unit = {},
    val cover: () -> Unit = {},
)

/**
 * Suggested budgets from what each category really cost over the last 3, 6 or 12 months.
 * Categories whose suggestion differs are preselected; untick any to leave them alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutopilotSheet(state: BudgetUiState, actions: BudgetPlanActions) {
    val colors = CentsibleTheme.colors
    ModalBottomSheet(onDismissRequest = { actions.openAutopilot(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Autopilot", style = MaterialTheme.typography.titleLarge)
            Text(
                "Budget what each category actually costs. Amounts are your average spending, rounded up to the next dollar.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            when (val a = state.autopilot) {
                null, Loadable.Loading -> Text("Looking at your spending…", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(vertical = 24.dp))
                is Loadable.Failed -> Text(a.message, style = MaterialTheme.typography.bodyMedium, color = colors.negative, modifier = Modifier.padding(vertical = 24.dp))
                is Loadable.Ready -> AutopilotContent(a.value, state, actions)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AutopilotContent(a: Autopilot, state: BudgetUiState, actions: BudgetPlanActions) {
    val colors = CentsibleTheme.colors
    val basis = state.autopilotBasis
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AverageBasis.entries.forEach { b ->
            FilterChip(selected = basis == b, onClick = { actions.setBasis(b) }, label = { Text("${b.months} months") })
        }
    }
    val rows = a.suggestions.filter { it.monthsOfHistory > 0 || !it.budgeted.isZero }
    if (rows.isEmpty()) {
        Text("There's no spending history yet. Autopilot can help once a month or two of transactions are in.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        StatLabel("Category", Modifier.weight(1f))
        StatLabel("Now", Modifier.width(84.dp))
        StatLabel("Suggested", Modifier.width(92.dp))
    }
    rows.forEachIndexed { i, s ->
        if (i > 0) HorizontalDivider(color = colors.border)
        val checked = s.categoryId in state.autopilotSelected
        val suggestion = s.suggestion(basis)
        Row(
            Modifier.fillMaxWidth().toggleRow(checked, role = Role.Checkbox) { actions.toggle(s.categoryId) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (s.monthsOfHistory < basis.months) "Only ${s.monthsOfHistory} month${if (s.monthsOfHistory == 1) "" else "s"} of history" else "Spent ${MoneyFormat.format(s.lastMonthSpent)} last month",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            }
            MoneyText(s.budgeted, style = MaterialTheme.typography.bodyMedium, showCents = false, color = colors.textSecondary, modifier = Modifier.width(84.dp))
            MoneyText(
                suggestion,
                style = MaterialTheme.typography.bodyMedium,
                showCents = false,
                color = if (suggestion > s.budgeted) colors.warning else if (suggestion < s.budgeted) colors.positive else colors.textPrimary,
                modifier = Modifier.width(92.dp),
            )
        }
    }
    val chosen = rows.filter { it.categoryId in state.autopilotSelected }
    val delta = Money(chosen.sumOf { it.suggestion(basis).minor - it.budgeted.minor })
    Text(
        when {
            chosen.isEmpty() -> "Nothing selected."
            delta.isNegative -> "Frees up ${MoneyFormat.format(delta.abs())} for To Budget."
            delta.isZero -> "No change to To Budget."
            else -> "Uses ${MoneyFormat.format(delta)} of To Budget (${MoneyFormat.format(a.toBudget)} left now)."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (!delta.isNegative && delta > a.toBudget) colors.warning else colors.textSecondary,
        modifier = Modifier.padding(top = 12.dp),
    )
    Button(onClick = actions.apply, enabled = chosen.isNotEmpty() && !state.saving, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(if (chosen.size == 1) "Budget 1 category" else "Budget ${chosen.size} categories")
    }
}

/** Moves money into overspent categories: To Budget first, then whatever has the most left. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverSheet(state: BudgetUiState, actions: BudgetPlanActions) {
    val colors = CentsibleTheme.colors
    ModalBottomSheet(onDismissRequest = { actions.openCover(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Cover overspending", style = MaterialTheme.typography.titleLarge)
            when (val c = state.cover) {
                null, Loadable.Loading -> Text("Working out a plan…", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(vertical = 24.dp))
                is Loadable.Failed -> Text(c.message, style = MaterialTheme.typography.bodyMedium, color = colors.negative, modifier = Modifier.padding(vertical = 24.dp))
                is Loadable.Ready -> {
                    val plan = c.value
                    if (plan.coverMoves.isEmpty()) {
                        Text(
                            if (plan.overspent.isEmpty()) "Nothing is overspent this month." else "There's no money left anywhere to move. Add income or lower another budget first.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                        return@Column
                    }
                    Text(
                        "Takes from To Budget first, then from the categories with the most left.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    plan.coverMoves.forEachIndexed { i, m ->
                        if (i > 0) HorizontalDivider(color = colors.border)
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.toName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("from ${m.fromName}", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                            }
                            MoneyText(m.amount, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (!plan.uncovered.isZero) {
                        Text(
                            "${MoneyFormat.format(plan.uncovered)} can't be covered: there's nothing more left to move.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.warning,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Button(onClick = actions.cover, enabled = !state.saving, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Move the money") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
