package app.centsible.feature.planning

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.ProgressRing
import app.centsible.core.designsystem.component.MoneyInput
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Goal
import app.centsible.core.model.GoalInput
import app.centsible.core.model.YearMonth
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun GoalsRoute(onBack: () -> Unit, viewModel: GoalsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GoalsScreen(
        state,
        GoalsActions(
            back = onBack,
            retry = { viewModel.refresh() },
            edit = viewModel::edit,
            save = { c, g -> viewModel.save(c, g) },
            remove = { viewModel.remove(it) },
            messageShown = viewModel::messageShown,
            choose = viewModel::choose,
            editTarget = viewModel::editTarget,
            saveTarget = { id, t -> viewModel.saveTarget(id, t) },
            deleteTarget = { viewModel.deleteTarget(it) },
        ),
    )
}

data class GoalsActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val edit: (GoalEditor?) -> Unit = {},
    val save: (app.centsible.core.model.CategoryId, GoalInput) -> Unit = { _, _ -> },
    val remove: (app.centsible.core.model.CategoryId) -> Unit = {},
    val messageShown: () -> Unit = {},
    val choose: (Boolean) -> Unit = {},
    val editTarget: (TargetEditor?) -> Unit = {},
    val saveTarget: (String?, app.centsible.core.model.TargetInput) -> Unit = { _, _ -> },
    val deleteTarget: (String) -> Unit = {},
)

@Composable
fun GoalsScreen(state: GoalsUiState, actions: GoalsActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Goals", style = MaterialTheme.typography.headlineSmall)
            }
        },
        floatingActionButton = {
            if (state.canEdit && state.goals is Loadable.Ready) {
                FloatingActionButton(onClick = { actions.choose(true) }, containerColor = colors.accent, contentColor = colors.card) {
                    Icon(Icons.Rounded.Add, contentDescription = "New goal")
                }
            }
        },
    ) { padding ->
        when (val g = state.goals) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load goals", g.message, emoji = "🎯", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> if (g.value.isEmpty() && state.targets.isEmpty()) {
                MessageState(
                    "Set a goal",
                    "Save up in a category, grow an account, keep eating out under a limit, or give at least a share of your income each month.",
                    emoji = "🎯",
                    actionLabel = if (state.canEdit) "New goal" else null,
                    onAction = { actions.choose(true) },
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (g.value.isNotEmpty()) item { GoalsSummary(g.value) }
                    val saving = state.targets.filter { it.kind == app.centsible.core.model.Target.Kind.Account }
                    val spending = state.targets - saving.toSet()
                    if (g.value.isNotEmpty() || saving.isNotEmpty()) item { StatLabel("Saving", Modifier.padding(start = 4.dp, top = 4.dp)) }
                    items(g.value, key = { it.categoryId.raw }) { goal ->
                        GoalCard(goal, onClick = if (state.canEdit) ({ actions.edit(GoalEditor(goal, GoalCategory(goal.categoryId, goal.name, goal.groupName))) }) else null)
                    }
                    items(saving, key = { it.id }) { t ->
                        TargetCard(t, onClick = if (state.canEdit) ({ actions.editTarget(TargetEditor(t.kind, t)) }) else null)
                    }
                    if (spending.isNotEmpty()) item { StatLabel("Each month", Modifier.padding(start = 4.dp, top = 8.dp)) }
                    items(spending, key = { it.id }) { t ->
                        TargetCard(t, onClick = if (state.canEdit) ({ actions.editTarget(TargetEditor(t.kind, t)) }) else null)
                    }
                }
            }
        }
    }
    state.editor?.let { GoalSheet(it, state, actions) }
    if (state.choosing) {
        GoalKindSheet(
            canSaveInCategory = state.available.isNotEmpty(),
            onPick = { kind -> if (kind == null) actions.edit(GoalEditor(null, null)) else actions.editTarget(TargetEditor(kind, null)) },
            onDismiss = { actions.choose(false) },
        )
    }
    state.targetEditor?.let { e ->
        TargetSheet(
            e, state.accounts, state.categoryNames, state.busy,
            onSave = actions.saveTarget,
            onRemove = actions.deleteTarget,
            onDismiss = { actions.editTarget(null) },
        )
    }
}

@Composable
private fun GoalsSummary(goals: List<Goal>) {
    val colors = CentsibleTheme.colors
    val saved = goals.sumOf { minOf(it.balance.minor, it.target.minor).coerceAtLeast(0) }
    val target = goals.sumOf { it.target.minor }
    CentsibleCard(contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(if (target > 0) saved.toFloat() / target else 0f, 64.dp, 7.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                StatLabel("Saved toward goals")
                Text(
                    "${MoneyFormat.format(app.centsible.core.model.Money(saved))} of ${MoneyFormat.format(app.centsible.core.model.Money(target))}",
                    style = MaterialTheme.typography.titleLarge,
                )
                val behind = goals.count { it.status == Goal.Status.Behind || it.status == Goal.Status.Stalled }
                Text(
                    if (behind == 0) "Everything's on track" else "$behind need${if (behind == 1) "s" else ""} attention",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (behind == 0) colors.positive else colors.warning,
                )
            }
        }
    }
}

@Composable
private fun GoalCard(goal: Goal, onClick: (() -> Unit)?) {
    val colors = CentsibleTheme.colors
    CentsibleCard(onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(goal.progress, 52.dp, 6.dp, color = if (goal.status == Goal.Status.Reached) colors.positive else colors.accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(goal.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${MoneyFormat.format(goal.balance)} of ${MoneyFormat.format(goal.target)}" + (goal.targetMonth?.let { " by ${monthName(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                val (text, tone) = describe(goal)
                Text(text, style = MaterialTheme.typography.bodySmall, color = when (tone) { 1 -> colors.positive; -1 -> colors.warning; else -> colors.textTertiary })
            }
        }
    }
}

/** A sentence about where the goal stands, and whether that's good (1), neutral (0) or needs attention (-1). */
internal fun describe(goal: Goal): Pair<String, Int> = when (goal.status) {
    Goal.Status.Reached -> "Reached 🎉" to 1
    Goal.Status.Stalled -> (
        goal.monthlyNeeded?.let { "Nothing's gone in lately. Budget ${MoneyFormat.format(it)} a month to make it." }
            ?: "Nothing's gone in lately. Budget some money to it to get going."
        ) to -1
    Goal.Status.Behind -> "Budget ${MoneyFormat.format(goal.monthlyNeeded ?: goal.remaining)} a month to make it (lately ${MoneyFormat.format(goal.avgContribution)})" to -1
    Goal.Status.OnTrack -> (
        goal.projectedMonth?.let { "On track: there by ${monthName(it)} at ${MoneyFormat.format(goal.avgContribution)} a month" } ?: "On track"
        ) to 1
}

internal fun monthName(m: YearMonth): String = "${java.time.Month.of(m.month).getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${m.year}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalSheet(editor: GoalEditor, state: GoalsUiState, actions: GoalsActions) {
    val colors = CentsibleTheme.colors
    val existing = editor.goal
    var category by remember { mutableStateOf(editor.category) }
    var byDate by remember { mutableStateOf(existing?.kind != Goal.Kind.Balance) }
    var amount by remember { mutableStateOf(existing?.target?.let(MoneyInput::toInput).orEmpty()) }
    val thisMonth = LocalDate.now().let { YearMonth.of(it.year, it.monthValue) }
    var month by remember { mutableStateOf(existing?.targetMonth ?: thisMonth.plus(12)) }
    var confirmRemove by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val parsed = MoneyInput.parse(amount)?.takeIf { it.minor >= 100 }

    if (creating) {
        app.centsible.core.ui.CategoryPickerSheet(
            title = "Save in",
            selected = category?.id,
            includeIncome = false,
            startCreating = true,
            onPick = { creating = false },
            onPickNamed = { id, name, group -> category = GoalCategory(id, name, group) },
            onDismiss = { creating = false },
        )
    }
    ModalBottomSheet(onDismissRequest = { actions.edit(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (existing == null) "New goal" else existing.name, style = MaterialTheme.typography.titleLarge)
            if (existing == null) {
                StatLabel("Save in")
                // A category made just now shows here (and chosen) before the list reloads.
                val choices = state.available + listOfNotNull(category?.takeIf { c -> state.available.none { it.id == c.id } })
                if (choices.isEmpty()) Text("Every category already has a goal.", style = MaterialTheme.typography.bodyMedium)
                Column {
                    choices.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().selectable(category?.id == c.id, role = Role.RadioButton) { category = c }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = category?.id == c.id, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(c.groupName, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                        }
                    }
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Create a category")
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(byDate, { byDate = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Save by a date") }
                SegmentedButton(!byDate, { byDate = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Keep a balance") }
            }
            Text(
                if (byDate) "Budget toward it each month until the date. Autopilot and Apply goals fill in the monthly amount."
                else "A long-term target, like an emergency fund. Shows how close you are.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            OutlinedTextField(
                amount, { amount = it },
                label = { Text("Target") },
                prefix = { Text("$") },
                isError = amount.isNotEmpty() && parsed == null,
                supportingText = if (amount.isNotEmpty() && parsed == null) ({ Text("At least $1.00") }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            if (byDate) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("By", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(40.dp))
                    TextButton(onClick = { month = month.plus(-1) }, enabled = month > thisMonth) { Text("‹") }
                    Text(monthName(month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { month = month.plus(1) }) { Text("›") }
                }
                parsed?.let {
                    val months = (month.year * 12 + month.month) - (thisMonth.year * 12 + thisMonth.month) + 1
                    val saved = existing?.balance?.minor?.coerceAtLeast(0) ?: 0
                    val perMonth = ((it.minor - saved).coerceAtLeast(0) + months - 1) / months
                    Text("About ${MoneyFormat.format(app.centsible.core.model.Money(perMonth))} a month for $months month${if (months == 1) "" else "s"}.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
            val target = category
            Button(
                onClick = { if (target != null && parsed != null) actions.save(target.id, GoalInput(if (byDate) Goal.Kind.By else Goal.Kind.Balance, parsed, month.takeIf { byDate })) },
                enabled = target != null && parsed != null && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save goal") }
            if (existing != null) {
                OutlinedButton(onClick = { confirmRemove = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Remove goal", color = colors.negative) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmRemove && existing != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove this goal?") },
            text = { Text("${existing.name} and the money in it stay; only the target goes.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; actions.remove(existing.categoryId) }) { Text("Remove", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
