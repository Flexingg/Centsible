package app.centsible.feature.budget

import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.BudgetProgressBar
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.CategoryAvatar
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.MonthSwitcher
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.BudgetCategory
import app.centsible.core.model.BudgetGroup
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money

@Composable
fun BudgetRoute(
    onManageCategories: () -> Unit,
    onOpenTransactions: (app.centsible.core.extensions.Destination.TransactionsFor) -> Unit = {},
    onOpenAutomations: (app.centsible.core.model.CategoryId?, app.centsible.core.model.YearMonth) -> Unit = { _, _ -> },
    viewModel: BudgetViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BudgetScreen(
        onManageCategories = onManageCategories,
        state = state,
        onPreviousMonth = viewModel::previousMonth,
        onNextMonth = viewModel::nextMonth,
        onRetry = viewModel::refresh,
        onOpenCategory = viewModel::openCategory,
        onAssign = viewModel::assign,
        onMove = viewModel::move,
        onRollover = viewModel::setRollover,
        onMessageShown = viewModel::messageShown,
        onApplyGoals = viewModel::applyGoals,
        onSaveNote = viewModel::saveNote,
        onHold = viewModel::hold,
        plan = BudgetPlanActions(
            openAutopilot = viewModel::openAutopilot,
            setBasis = viewModel::setBasis,
            toggle = viewModel::toggleSuggestion,
            apply = viewModel::applyAutopilot,
            openCover = viewModel::openCover,
            cover = { viewModel.coverOverspending() },
            openTransactions = onOpenTransactions,
            openAutomations = onOpenAutomations,
        ),
    )
}

@Composable
fun BudgetScreen(
    state: BudgetUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onRetry: () -> Unit,
    onOpenCategory: (CategoryId?) -> Unit,
    onAssign: (CategoryId, Money) -> Unit,
    onMove: (app.centsible.core.model.BudgetPot, app.centsible.core.model.BudgetPot, Money) -> Unit,
    onRollover: (CategoryId, Boolean) -> Unit,
    onMessageShown: () -> Unit,
    onManageCategories: () -> Unit = {},
    onApplyGoals: (Boolean) -> Unit = {},
    onSaveNote: (CategoryId, String) -> Unit = { _, _ -> },
    onHold: (Money?) -> Unit = {},
    plan: BudgetPlanActions = BudgetPlanActions(),
) {
    var goalsMenu by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); onMessageShown() }
    }
    Scaffold(
        containerColor = CentsibleTheme.colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val switcher: @Composable () -> Unit = {
                MonthSwitcher(
                    month = state.month,
                    onPrevious = onPreviousMonth.takeIf { state.hasPrevious || state.availableMonths.isEmpty() },
                    onNext = onNextMonth.takeIf { state.hasNext || state.availableMonths.isEmpty() },
                )
            }
            val menu: @Composable () -> Unit = {
                if (state.canEdit || state.canApplyGoals) {
                    androidx.compose.foundation.layout.Box {
                        androidx.compose.material3.IconButton(onClick = { goalsMenu = true }, enabled = !state.saving) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.MoreVert, contentDescription = "Budget actions")
                        }
                        androidx.compose.material3.DropdownMenu(expanded = goalsMenu, onDismissRequest = { goalsMenu = false }) {
                            if (state.canEdit) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Autopilot: budget from past spending") },
                                    onClick = { goalsMenu = false; plan.openAutopilot(true) },
                                )
                            }
                            if (state.canApplyGoals) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Automations…") },
                                    onClick = { goalsMenu = false; plan.openAutomations(null, state.month) },
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Run automations") },
                                    onClick = { goalsMenu = false; onApplyGoals(false) },
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Run automations, overwriting amounts") },
                                    onClick = { goalsMenu = false; onApplyGoals(true) },
                                )
                            }
                        }
                    }
                }
            }
            // With large system text the month no longer fits beside the title: give it its own line.
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Budget", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        menu()
                    }
                    switcher()
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Budget", style = MaterialTheme.typography.headlineMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        switcher()
                        menu()
                    }
                }
            }
        },
    ) { padding ->
        when (val data = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState(
                title = "Couldn't load your budget",
                message = data.message,
                emoji = "🔌",
                actionLabel = "Try again",
                onAction = onRetry,
                modifier = Modifier.padding(padding),
            )
            is Loadable.Ready -> BudgetContent(
                data.value, state, onOpenCategory, onManageCategories,
                onHold = { holding = true }, onRelease = { onHold(null) }, onCover = { plan.openCover(true) },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (state.autopilot != null) AutopilotSheet(state, plan)
    if (state.cover != null) CoverSheet(state, plan)
    val month = state.data.valueOrNull
    if (holding && month != null) HoldDialog(month.toBudget, onDismiss = { holding = false }, onHold = { holding = false; onHold(it) })
    val selected = month?.groups?.flatMap { it.categories }?.firstOrNull { it.id == state.selectedCategory }
    if (month != null && selected != null) {
        CategorySheet(
            category = selected,
            month = month,
            state = state,
            onDismiss = { onOpenCategory(null) },
            onAssign = { onAssign(selected.id, it) },
            onMove = onMove,
            onRollover = { onRollover(selected.id, it) },
            onSaveNote = { onSaveNote(selected.id, it) },
            onAutomations = { onOpenCategory(null); plan.openAutomations(selected.id, month.month) }.takeIf { state.canApplyGoals },
            onSeeTransactions = {
                onOpenCategory(null)
                plan.openTransactions(app.centsible.core.extensions.Destination.TransactionsFor.month(selected.name, month.month, categoryId = selected.id))
            },
        )
    }
}

@Composable
private fun BudgetContent(
    month: BudgetMonth,
    state: BudgetUiState,
    onOpenCategory: (CategoryId) -> Unit,
    onManageCategories: () -> Unit,
    onHold: () -> Unit,
    onRelease: () -> Unit,
    onCover: () -> Unit,
    modifier: Modifier,
) {
    val collapsed = remember { mutableStateMapOf<CategoryGroupId, Boolean>() }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") { SummaryCard(month, state.canHold && !state.saving, onHold, onRelease) }
        val overspent = month.expenseGroups.filter { !it.hidden }.flatMap { it.categories }.filter { !it.hidden && it.balance.isNegative }
        if (overspent.isNotEmpty() && state.canMoveMoney) {
            item(key = "overspent") { OverspentBanner(overspent.size, Money(overspent.sumOf { it.balance.minor }), enabled = !state.saving, onCover = onCover) }
        }
        items(month.expenseGroups.filter { !it.hidden }, key = { it.id.raw }) { group ->
            GroupCard(
                group = group,
                collapsed = collapsed[group.id] == true,
                onToggle = { collapsed[group.id] = collapsed[group.id] != true },
                onOpenCategory = onOpenCategory,
            )
        }
        items(month.incomeGroups.filter { !it.hidden }, key = { it.id.raw }) { IncomeCard(it) }
        if (state.canManageCategories) {
            item(key = "manage") {
                androidx.compose.material3.TextButton(onClick = onManageCategories, modifier = Modifier.fillMaxWidth()) { Text("Edit categories") }
            }
        }
        if (!state.canEdit) {
            item(key = "readonly") {
                Text(
                    "You're viewing this budget. Ask an owner for edit access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CentsibleTheme.colors.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(month: BudgetMonth, canHold: Boolean = false, onHold: () -> Unit = {}, onRelease: () -> Unit = {}) {
    val colors = CentsibleTheme.colors
    val over = month.toBudget.isNegative
    CentsibleCard(contentPadding = PaddingValues(20.dp)) {
        StatLabel(if (over) "Overbudgeted" else "Left to budget")
        Spacer(Modifier.height(4.dp))
        MoneyText(
            month.toBudget.abs(),
            style = MaterialTheme.typography.displaySmall,
            color = when {
                over -> colors.negative
                month.toBudget.isZero -> colors.textPrimary
                else -> colors.positive
            },
            animate = true,
        )
        if (month.toBudget.isZero) {
            Text("Every dollar has a job 🎉", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Spacer(Modifier.height(16.dp))
        val spentShare = if (month.totalBudgeted.minor > 0) (-month.totalSpent.minor).toFloat() / month.totalBudgeted.minor else 0f
        BudgetProgressBar(progress = spentShare, overspent = spentShare > 1f, height = 8.dp, color = colors.accent.takeIf { spentShare <= 1f })
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryStat("Income", month.totalIncome)
            SummaryStat("Budgeted", month.totalBudgeted)
            SummaryStat("Spent", month.totalSpent.abs())
        }
        val offerHold = canHold && month.forNextMonth.isZero && month.toBudget.minor > 0
        if (!month.forNextMonth.isZero || month.lastMonthOverspent.isNegative || offerHold) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.border)
            if (!month.forNextMonth.isZero) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Holding ${MoneyFormat.format(month.forNextMonth)} for next month",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (canHold) androidx.compose.material3.TextButton(onClick = onRelease) { Text("Release") }
                }
            } else if (offerHold) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Already covered this month? Save the rest for next month.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = onHold) { Text("Hold") }
                }
            }
            if (month.lastMonthOverspent.isNegative) {
                Text("Overspent last month: ${MoneyFormat.format(month.lastMonthOverspent.abs())}", style = MaterialTheme.typography.bodySmall, color = colors.negative)
            }
        }
    }
}

/** Monarch-style nudge: one tap to cover every overspent category. */
@Composable
private fun OverspentBanner(count: Int, total: Money, enabled: Boolean, onCover: () -> Unit) {
    val colors = CentsibleTheme.colors
    CentsibleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (count == 1) "1 category is overspent" else "$count categories are overspent", style = MaterialTheme.typography.bodyLarge, color = colors.negative)
                Text("${MoneyFormat.format(total.abs())} over in total", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            androidx.compose.material3.TextButton(onClick = onCover, enabled = enabled) { Text("Cover") }
        }
    }
}

@Composable
private fun SummaryStat(label: String, amount: Money) {
    Column {
        StatLabel(label)
        MoneyText(amount, style = MaterialTheme.typography.titleMedium, showCents = false)
    }
}

@Composable
private fun GroupCard(group: BudgetGroup, collapsed: Boolean, onToggle: () -> Unit, onOpenCategory: (CategoryId) -> Unit) {
    val colors = CentsibleTheme.colors
    CentsibleCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (collapsed) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = colors.textTertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(group.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(group.balance, style = MaterialTheme.typography.titleSmall, color = if (group.balance.isNegative) colors.negative else colors.textPrimary)
                Text("of ${MoneyFormat.format(group.budgeted, showCents = false)}", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
            }
        }
        if (!collapsed) {
            group.categories.filter { !it.hidden }.forEachIndexed { i, category ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = colors.border)
                CategoryRow(category, onClick = { onOpenCategory(category.id) })
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun CategoryRow(category: BudgetCategory, onClick: () -> Unit) {
    val colors = CentsibleTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(category.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                RemainingLabel(category)
            }
            Spacer(Modifier.height(6.dp))
            BudgetProgressBar(progress = category.progress, overspent = category.isOverspent)
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    "${MoneyFormat.format(category.spent.abs(), showCents = false)} spent of ${MoneyFormat.format(category.budgeted, showCents = false)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (category.carryover) Text("↻ Rollover", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
            }
        }
    }
}

@Composable
private fun RemainingLabel(category: BudgetCategory) {
    val colors = CentsibleTheme.colors
    val over = category.isOverspent
    Box {
        Text(
            text = if (over) "${MoneyFormat.format(category.balance.abs())} over" else "${MoneyFormat.format(category.balance)} left",
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = if (over) colors.negative else colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun IncomeCard(group: BudgetGroup) {
    val colors = CentsibleTheme.colors
    CentsibleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            MoneyText(group.received, tone = app.centsible.core.designsystem.component.MoneyTone.Positive, style = MaterialTheme.typography.titleSmall)
        }
        group.categories.filter { !it.hidden }.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CategoryAvatar(c.name, size = 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(c.name, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
                MoneyText(c.received, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun HoldDialog(available: Money, onDismiss: () -> Unit, onHold: (Money) -> Unit) {
    var input by remember { mutableStateOf(app.centsible.core.designsystem.component.MoneyInput.toInput(available)) }
    val parsed = app.centsible.core.designsystem.component.MoneyInput.parse(input)?.takeIf { it.minor > 0 && it.minor <= available.minor }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hold for next month") },
        text = {
            Column {
                Text(
                    "Held money leaves this month's To Budget and shows up in next month's.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    input, { input = it },
                    label = { Text("Amount") },
                    prefix = { Text("$") },
                    supportingText = { Text("Up to ${MoneyFormat.format(available)}") },
                    isError = parsed == null,
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { parsed?.let(onHold) }, enabled = parsed != null) { Text("Hold") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
