package app.centsible.feature.budget

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.DialSpinner
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyInput
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.MonthSwitcher
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.motion.staggeredEntrance
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Automation
import app.centsible.core.model.AutomationSource
import app.centsible.core.model.Money
import app.centsible.core.model.YearMonth
import app.centsible.core.model.priorityOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class AutomationEditorActions(
    val back: () -> Unit = {},
    val openAdd: (Boolean) -> Unit = {},
    val add: (Automation) -> Unit = {},
    val change: (Int, Automation) -> Unit = { _, _ -> },
    val remove: (Int) -> Unit = {},
    val move: (Int, Int) -> Unit = { _, _ -> },
    val save: (Boolean) -> Unit = {},
    val askNotes: (Boolean) -> Unit = {},
    val useNotes: () -> Unit = {},
    val retry: () -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun AutomationEditorRoute(onDone: () -> Unit, viewModel: AutomationEditorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    AutomationEditorScreen(
        state,
        AutomationEditorActions(
            back = onDone, openAdd = viewModel::openAdd, add = viewModel::add, change = viewModel::change, remove = viewModel::remove,
            move = viewModel::move, save = viewModel::save, askNotes = viewModel::askNotes, useNotes = viewModel::useNotes,
            retry = { viewModel.load() }, messageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun AutomationEditorScreen(state: AutomationEditorUiState, actions: AutomationEditorActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    val d = state.data.valueOrNull
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text(d?.name ?: "Automations", style = MaterialTheme.typography.headlineSmall)
                    Text("Automations", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                if (state.canEdit && d != null && d.source == AutomationSource.Automations && d.notesHaveTemplates) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Go back to the #template notes") }, onClick = { menu = false; actions.askNotes(true) })
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (state.canEdit && d != null) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { actions.save(false) }, enabled = state.dirty && !state.saving, modifier = Modifier.weight(1f)) { Text("Save") }
                    Button(onClick = { actions.save(true) }, enabled = !state.saving && state.draft.isNotEmpty(), modifier = Modifier.weight(1.4f)) {
                        Text(if (state.saving) "Saving…" else "Save & budget ${monthLabel(state.month).substringBefore(' ')}")
                    }
                }
            }
        },
    ) { padding ->
        when (val data = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load automations", data.message, emoji = "⚙️", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> {
                val info = data.value
                val incomeNames = info.incomeCategories.toMap()
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Projection(state) }
                    if (info.source == AutomationSource.Notes && state.draft.isNotEmpty()) {
                        item {
                            Notice("These come from #template lines in the category's notes. Saving here turns them into automations, and the notes stop counting.")
                        }
                    }
                    if (info.isIncome) {
                        item { Notice("Income categories can only have a long-term goal.") }
                    }
                    itemsIndexed(state.draft, key = { i, a -> "$i-${a::class.simpleName}" }) { i, a ->
                        AutomationCard(
                            a, i, state, info, incomeNames,
                            onChange = { actions.change(i, it) },
                            onRemove = { actions.remove(i) },
                            onMove = { by -> actions.move(i, by) },
                            modifier = Modifier.staggeredEntrance(i),
                        )
                    }
                    if (state.canEdit) {
                        item {
                            OutlinedButton(onClick = { actions.openAdd(true) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (state.draft.isEmpty()) "Add an automation" else "Add another")
                            }
                        }
                    }
                    item {
                        Text(
                            "Automations run in priority order, lowest first. Only priority 0 may budget more than you have to budget; " +
                                "\"Whatever is left\" always runs last.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                if (state.adding) AddSheet(info, state.month, onPick = actions.add, onDismiss = { actions.openAdd(false) })
            }
        }
    }
    if (state.confirmNotes) {
        AlertDialog(
            onDismissRequest = { actions.askNotes(false) },
            title = { Text("Go back to notes?") },
            text = { Text("This category's automations will come from the #template lines in its notes again. What's set up here is removed.") },
            confirmButton = { TextButton(onClick = actions.useNotes) { Text("Use notes") } },
            dismissButton = { TextButton(onClick = { actions.askNotes(false) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Projection(state: AutomationEditorUiState) {
    val colors = CentsibleTheme.colors
    CentsibleCard(contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatLabel("Would budget in ${monthLabel(state.month)}", Modifier.weight(1f))
            if (state.previewing) DialSpinner(size = 18.dp)
        }
        MoneyText(state.projected ?: Money.Zero, style = MaterialTheme.typography.displaySmall, animate = true)
        state.previewError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning) }
    }
}

@Composable
private fun Notice(text: String) {
    val colors = CentsibleTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

// ── One automation ───────────────────────────────────────────────────────────

@Composable
private fun AutomationCard(
    a: Automation,
    index: Int,
    state: AutomationEditorUiState,
    info: AutomationEditorData,
    incomeNames: Map<String, String>,
    onChange: (Automation) -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CentsibleTheme.colors
    val kind = AutomationKind.of(a)
    val editable = state.canEdit && a !is Automation.Unreadable
    CentsibleCard(modifier.animateContentSize(), contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kind.emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (a is Automation.Unreadable) "Can't read this line" else kind.title, style = MaterialTheme.typography.titleMedium)
                Text(a.summary(incomeNames), style = MaterialTheme.typography.bodySmall, color = if (a is Automation.Unreadable) colors.negative else colors.textSecondary)
            }
            state.perAutomation?.getOrNull(index)?.takeIf { a !is Automation.Goal && a !is Automation.Unreadable }?.let {
                Text(MoneyFormat.format(it), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
            }
            if (state.canEdit) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove ${kind.title}") }
        }
        if (a is Automation.Unreadable) {
            Text(a.error, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            return@CentsibleCard
        }
        if (!editable) return@CentsibleCard
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (a) {
                is Automation.Fixed, is Automation.Periodic -> FixedFields(a, state.month, onChange)
                is Automation.SaveBy -> SaveByFields(a, onChange)
                is Automation.CoverSchedule -> ScheduleFields(a, info.schedules, onChange)
                is Automation.Average, is Automation.Copy -> HistoryFields(a, onChange)
                is Automation.PercentOfIncome -> PercentFields(a, info.incomeCategories, onChange)
                is Automation.Refill -> CapFields(a.cap, required = true) { onChange(a.copy(cap = it!!)) }
                is Automation.Remainder -> {
                    Stepper("Weight", a.weight.toInt().coerceAtLeast(1), 1..10, "How big a share compared with other categories") { onChange(a.copy(weight = it.toDouble())) }
                    CapFields(a.cap, required = false) { onChange(a.copy(cap = it)) }
                }
                is Automation.Goal -> MoneyField("Goal balance", a.amount) { onChange(a.copy(amount = it ?: Money.Zero)) }
                is Automation.Unreadable -> Unit
            }
            a.priorityOrNull?.let { p ->
                Stepper("Priority", p, 0..20, "Lower runs first") { onChange(a.withPriority(it)) }
            }
            if (index > 0 || index < state.draft.lastIndex) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (index > 0) TextButton(onClick = { onMove(-1) }) { Text("Move up") }
                    if (index < state.draft.lastIndex) TextButton(onClick = { onMove(1) }) { Text("Move down") }
                }
            }
        }
    }
}

private fun Automation.withPriority(p: Int): Automation = when (this) {
    is Automation.Fixed -> copy(priority = p)
    is Automation.Periodic -> copy(priority = p)
    is Automation.SaveBy -> copy(priority = p)
    is Automation.CoverSchedule -> copy(priority = p)
    is Automation.Average -> copy(priority = p)
    is Automation.Copy -> copy(priority = p)
    is Automation.PercentOfIncome -> copy(priority = p)
    is Automation.Refill -> copy(priority = p)
    else -> this
}

@Composable
private fun FixedFields(a: Automation, month: YearMonth, onChange: (Automation) -> Unit) {
    val repeating = a is Automation.Periodic
    val priority = a.priorityOrNull ?: 0
    Segmented(listOf("Every month", "Repeating"), if (repeating) 1 else 0) { i ->
        if (i == 1 && a is Automation.Fixed) {
            onChange(Automation.Periodic(priority, a.monthly ?: Money.Zero, Automation.PeriodUnit.Week, 1, "${month.raw}-01", a.cap, a.description))
        } else if (i == 0 && a is Automation.Periodic) {
            onChange(Automation.Fixed(priority, a.amount, a.cap, a.description))
        }
    }
    when (a) {
        is Automation.Fixed -> {
            MoneyField("Amount each month", a.monthly) { onChange(a.copy(monthly = it)) }
            CapFields(a.cap, required = false) { onChange(a.copy(cap = it)) }
        }
        is Automation.Periodic -> {
            MoneyField("Amount each time", a.amount) { onChange(a.copy(amount = it ?: Money.Zero)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper("Every", a.count, 1..52, null, Modifier.weight(1f)) { onChange(a.copy(count = it)) }
                Choice(
                    listOf("day", "week", "month", "year").map { if (a.count == 1) it else it + "s" },
                    a.unit.ordinal,
                ) { onChange(a.copy(unit = Automation.PeriodUnit.entries[it])) }
            }
            DateField("Starting", a.starting) { onChange(a.copy(starting = it)) }
            CapFields(a.cap, required = false) { onChange(a.copy(cap = it)) }
        }
        else -> Unit
    }
}

@Composable
private fun SaveByFields(a: Automation.SaveBy, onChange: (Automation) -> Unit) {
    MoneyField("Total to save", a.amount) { onChange(a.copy(amount = it ?: Money.Zero)) }
    LabeledMonth("By", a.month) { onChange(a.copy(month = it)) }
    ToggleRow("Repeat", a.repeat != null) { on -> onChange(a.copy(repeat = if (on) Automation.Repeat(yearly = true, count = 1) else null)) }
    a.repeat?.let { r ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper("Every", r.count, 1..12, null, Modifier.weight(1f)) { onChange(a.copy(repeat = r.copy(count = it))) }
            Choice(if (r.count == 1) listOf("month", "year") else listOf("months", "years"), if (r.yearly) 1 else 0) { onChange(a.copy(repeat = r.copy(yearly = it == 1))) }
        }
    }
    ToggleRow("Spend from it before then", a.spendFrom != null) { on -> onChange(a.copy(spendFrom = if (on) a.month.plus(-2) else null)) }
    a.spendFrom?.let { from -> LabeledMonth("Spending from", from) { onChange(a.copy(spendFrom = it)) } }
}

@Composable
private fun ScheduleFields(a: Automation.CoverSchedule, schedules: List<String>, onChange: (Automation) -> Unit) {
    if (schedules.isEmpty()) {
        Text("No recurring bills yet. Add one in Recurring first.", style = MaterialTheme.typography.bodyMedium, color = CentsibleTheme.colors.warning)
    } else {
        Choice(schedules, schedules.indexOf(a.schedule).coerceAtLeast(0), label = "Schedule") { onChange(a.copy(schedule = schedules[it])) }
    }
    Segmented(listOf("Save ahead", "When it's due"), if (a.full) 1 else 0) { onChange(a.copy(full = it == 1)) }
    AdjustmentFields(a.adjustment) { onChange(a.copy(adjustment = it)) }
}

@Composable
private fun HistoryFields(a: Automation, onChange: (Automation) -> Unit) {
    val priority = a.priorityOrNull ?: 0
    Segmented(listOf("Average spent", "Copy budget"), if (a is Automation.Copy) 1 else 0) { i ->
        if (i == 1 && a is Automation.Average) onChange(Automation.Copy(priority, 12, a.description))
        if (i == 0 && a is Automation.Copy) onChange(Automation.Average(priority, 3, null, a.description))
    }
    when (a) {
        is Automation.Average -> {
            Stepper("Months", a.months, 1..24, "Average of what was spent over this many months") { onChange(a.copy(months = it)) }
            AdjustmentFields(a.adjustment) { onChange(a.copy(adjustment = it)) }
        }
        is Automation.Copy -> Stepper("Months ago", a.monthsAgo, 1..24, "Budget what was budgeted then (12 = same month last year)") { onChange(a.copy(monthsAgo = it)) }
        else -> Unit
    }
}

@Composable
private fun PercentFields(a: Automation.PercentOfIncome, income: List<Pair<String, String>>, onChange: (Automation) -> Unit) {
    var text by remember(a.percent) { mutableStateOf(trimNumber(a.percent)) }
    OutlinedTextField(
        text,
        { t ->
            text = t
            t.toDoubleOrNull()?.let { onChange(a.copy(percent = it)) }
        },
        label = { Text("Percent") },
        suffix = { Text("%") },
        isError = text.toDoubleOrNull()?.let { it <= 0 || it > 100 } ?: true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    val options = listOf(Automation.ALL_INCOME to "All income", Automation.AVAILABLE_FUNDS to "What's left to budget") + income
    Choice(options.map { it.second }, options.indexOfFirst { it.first == a.of }.coerceAtLeast(0), label = "Of") { onChange(a.copy(of = options[it].first)) }
    if (a.of != Automation.AVAILABLE_FUNDS) ToggleRow("Use last month's income", a.previousMonth) { onChange(a.copy(previousMonth = it)) }
}

@Composable
private fun AdjustmentFields(adj: Automation.Adjustment?, onChange: (Automation.Adjustment?) -> Unit) {
    ToggleRow("Adjust up or down", adj != null) { on -> onChange(if (on) Automation.Adjustment.Percent(10.0) else null) }
    if (adj == null) return
    Segmented(listOf("By %", "By amount"), if (adj is Automation.Adjustment.Fixed) 1 else 0) { i ->
        onChange(if (i == 0) Automation.Adjustment.Percent(10.0) else Automation.Adjustment.Fixed(Money(1000)))
    }
    when (adj) {
        is Automation.Adjustment.Percent -> {
            var text by remember(adj.percent) { mutableStateOf(trimNumber(adj.percent)) }
            OutlinedTextField(
                text, { t -> text = t; t.toDoubleOrNull()?.let { onChange(Automation.Adjustment.Percent(it)) } },
                label = { Text("Change (use − to lower)") }, suffix = { Text("%") }, singleLine = true,
                isError = text.toDoubleOrNull()?.let { it <= -100 || it > 1000 } ?: true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth(),
            )
        }
        is Automation.Adjustment.Fixed -> MoneyField("Change (use − to lower)", adj.amount, allowNegative = true) { onChange(Automation.Adjustment.Fixed(it ?: Money.Zero)) }
    }
}

@Composable
private fun CapFields(cap: Automation.Cap?, required: Boolean, onChange: (Automation.Cap?) -> Unit) {
    if (!required) ToggleRow("Cap the balance", cap != null) { on -> onChange(if (on) Automation.Cap(Money(10000)) else null) }
    if (cap == null) return
    MoneyField(if (required) "Refill up to" else "Up to", cap.amount) { onChange(cap.copy(amount = it ?: Money.Zero)) }
    Choice(listOf("a month", "a day", "a week"), when (cap.period) { Automation.CapPeriod.Monthly -> 0; Automation.CapPeriod.Daily -> 1; Automation.CapPeriod.Weekly -> 2 }, label = "Per") { i ->
        val period = listOf(Automation.CapPeriod.Monthly, Automation.CapPeriod.Daily, Automation.CapPeriod.Weekly)[i]
        onChange(cap.copy(period = period, start = if (period == Automation.CapPeriod.Weekly) cap.start ?: LocalDate.now().toString() else null))
    }
    if (cap.period == Automation.CapPeriod.Weekly) DateField("Weeks start", cap.start.orEmpty()) { onChange(cap.copy(start = it)) }
    ToggleRow("Keep anything above the cap", cap.hold) { onChange(cap.copy(hold = it)) }
}

// ── Small controls ───────────────────────────────────────────────────────────

@Composable
private fun MoneyField(label: String, value: Money?, allowNegative: Boolean = false, onChange: (Money?) -> Unit) {
    var text by remember { mutableStateOf(value?.takeIf { !it.isZero }?.let(MoneyInput::toInput).orEmpty()) }
    val parsed = MoneyInput.parse(text)
    OutlinedTextField(
        text,
        { t ->
            text = t
            val m = MoneyInput.parse(t)
            if (t.isBlank()) onChange(null) else if (m != null && (allowNegative || !m.isNegative)) onChange(m)
        },
        label = { Text(label) },
        prefix = { Text("$") },
        isError = text.isNotBlank() && (parsed == null || (!allowNegative && parsed.isNegative)),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (allowNegative) KeyboardType.Text else KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, help: String?, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    val colors = CentsibleTheme.colors
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = "$value" }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary) }
        }
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first) { Icon(Icons.Rounded.Remove, contentDescription = "Less $label") }
        Text("$value", style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) { Icon(Icons.Rounded.Add, contentDescription = "More $label") }
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!on) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(i == selected, { onSelect(i) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(o) }
        }
    }
}

@Composable
private fun Choice(options: List<String>, selected: Int, label: String? = null, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        label?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)) }
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "${label ?: "Choose"}: ${options.getOrNull(selected)}" }) {
                Text(options.getOrNull(selected) ?: "Choose")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) }) }
            }
        }
    }
}

@Composable
private fun LabeledMonth(label: String, month: YearMonth, onChange: (YearMonth) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        MonthSwitcher(month, onPrevious = { onChange(month.plus(-1)) }, onNext = { onChange(month.plus(1)) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, iso: String, onChange: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(iso) }.getOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { picking = true }) { Text(date?.let { dayLabel(it.toString()) + ", " + it.year } ?: "Choose") }
    }
    if (picking) {
        val s = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    s.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    picking = false
                }) { Text("OK") }
            },
        ) { DatePicker(s) }
    }
}

private fun trimNumber(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

// ── Adding ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(info: AutomationEditorData, month: YearMonth, onPick: (Automation) -> Unit, onDismiss: () -> Unit) {
    val colors = CentsibleTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Add an automation", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            val kinds = if (info.isIncome) listOf(AutomationKind.Goal) else AutomationKind.entries
            kinds.forEachIndexed { i, k ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = colors.border)
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(k.starter(month, info.schedules)) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(k.emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(44.dp))
                    Column {
                        Text(k.title, style = MaterialTheme.typography.titleMedium)
                        Text(k.explain, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}
