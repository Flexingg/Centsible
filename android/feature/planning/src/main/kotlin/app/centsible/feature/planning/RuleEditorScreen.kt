package app.centsible.feature.planning

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.DialSpinner
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MerchantAvatar
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyInput
import app.centsible.core.designsystem.component.PickerItem
import app.centsible.core.designsystem.component.PickerSheet
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Money
import app.centsible.core.model.RuleValue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class RuleEditorActions(
    val back: () -> Unit = {},
    val edit: ((RuleFormState) -> RuleFormState) -> Unit = {},
    val save: () -> Unit = {},
    val runOnExisting: (Boolean) -> Unit = {},
    val askDelete: (Boolean) -> Unit = {},
    val delete: () -> Unit = {},
    val retry: () -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun RuleEditorRoute(onDone: () -> Unit, viewModel: RuleEditorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    RuleEditorScreen(
        state,
        RuleEditorActions(
            back = onDone, edit = viewModel::edit, save = viewModel::save, runOnExisting = viewModel::runOnExisting,
            askDelete = viewModel::askDelete, delete = viewModel::delete, retry = { viewModel.load() }, messageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun RuleEditorScreen(state: RuleEditorUiState, actions: RuleEditorActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    val f = state.form
    val edit = actions.edit
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text(if (state.id == null) "New rule" else if (state.canEdit) "Edit rule" else "Rule", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (state.id != null && state.canEdit) IconButton(onClick = { actions.askDelete(true) }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete rule") }
            }
        },
        bottomBar = {
            if (state.canEdit && state.data is Loadable.Ready) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    state.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(bottom = 6.dp)) }
                    Button(onClick = actions.save, enabled = state.problem == null && !state.saving, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.saving) "Saving…" else "Save rule")
                    }
                }
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't open this rule", d.message, emoji = "🧩", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> {
                val data = d.value
                val enabled = state.canEdit
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (data.scheduleRule) item { Hint("This rule belongs to a recurring schedule. Change it under Recurring.") }
                    // ── When ──
                    item {
                        CentsibleCard(Modifier.animateContentSize()) {
                            StatLabel("When")
                            Spacer(Modifier.height(8.dp))
                            if (f.conditions.size > 1) {
                                Segmented(listOf("All of these", "Any of these"), if (f.anyOf) 1 else 0, enabled) { i -> edit { it.copy(anyOf = i == 1) } }
                                Spacer(Modifier.height(8.dp))
                            }
                            if (f.conditions.isEmpty()) Text("Every transaction", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                            f.conditions.forEachIndexed { i, c ->
                                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.border)
                                ConditionRow(c, data, enabled,
                                    onChange = { n -> edit { s -> s.copy(conditions = s.conditions.toMutableList().also { it[i] = n }) } },
                                    onRemove = { edit { s -> s.copy(conditions = s.conditions.toMutableList().also { it.removeAt(i) }) } },
                                )
                            }
                            if (enabled) {
                                TextButton(onClick = { edit { it.copy(conditions = it.conditions + CondRow("notes", "contains", RuleValue.Null)) } }) {
                                    Icon(Icons.Rounded.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Add condition")
                                }
                            }
                        }
                    }
                    // ── Then ──
                    item {
                        CentsibleCard(Modifier.animateContentSize()) {
                            StatLabel("Then")
                            Spacer(Modifier.height(8.dp))
                            f.actions.forEachIndexed { i, a ->
                                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.border)
                                ActionRow(a, data, enabled,
                                    onChange = { n -> edit { s -> s.copy(actions = s.actions.toMutableList().also { it[i] = n }) } },
                                    onRemove = { edit { s -> s.copy(actions = s.actions.toMutableList().also { it.removeAt(i) }) } },
                                )
                            }
                            if (enabled) {
                                TextButton(onClick = { edit { it.copy(actions = it.actions + ActRow("set", "notes", RuleValue.Null)) } }) {
                                    Icon(Icons.Rounded.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Add action")
                                }
                            }
                            f.kept.forEach { k -> Text("Also: ${Describe.action(k, data.names)}", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary) }
                        }
                    }
                    // ── Splits ──
                    item {
                        CentsibleCard(Modifier.animateContentSize()) {
                            StatLabel("Split it")
                            if (f.splits.isEmpty()) {
                                Text("Optionally break each match into parts: a fixed amount, a share, or the rest.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
                            }
                            f.splits.forEachIndexed { i, s ->
                                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.border)
                                SplitEditor(i, s, data, enabled,
                                    onChange = { n -> edit { st -> st.copy(splits = st.splits.toMutableList().also { it[i] = n }) } },
                                    onRemove = { edit { st -> st.copy(splits = st.splits.toMutableList().also { it.removeAt(i) }) } },
                                )
                            }
                            if (enabled) {
                                TextButton(onClick = {
                                    edit { it.copy(splits = it.splits + SplitBlock(if (it.splits.isEmpty()) SplitMethod.Fixed else SplitMethod.Remainder, actions = listOf(ActRow("set", "category", RuleValue.Null)))) }
                                }) { Icon(Icons.Rounded.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(if (f.splits.isEmpty()) "Split into parts" else "Add a part") }
                            }
                        }
                    }
                    // ── Order ──
                    item {
                        CentsibleCard {
                            StatLabel("Runs")
                            Spacer(Modifier.height(8.dp))
                            Segmented(listOf("First", "Normal", "Last"), when (f.stage) { "pre" -> 0; "post" -> 2; else -> 1 }, enabled) { i ->
                                edit { it.copy(stage = listOf("pre", null, "post")[i]) }
                            }
                            Text(
                                "First-run rules clean up the raw bank data before the others see it; last-run rules have the final word.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textTertiary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    // ── Preview ──
                    item { PreviewCard(state, data) }
                }
            }
        }
    }
    state.offerRun?.let { n ->
        AlertDialog(
            onDismissRequest = { actions.runOnExisting(false) },
            title = { Text("Run it on existing transactions?") },
            text = { Text("It matches $n transaction${if (n == 1) "" else "s"} you already have. New ones get it automatically either way.") },
            confirmButton = { TextButton(onClick = { actions.runOnExisting(true) }) { Text("Run on $n") } },
            dismissButton = { TextButton(onClick = { actions.runOnExisting(false) }) { Text("Not now") } },
        )
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = { actions.askDelete(false) },
            title = { Text("Delete this rule?") },
            text = { Text("Transactions it already changed stay as they are.") },
            confirmButton = { TextButton(onClick = actions.delete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { actions.askDelete(false) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = CentsibleTheme.colors.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))

// ── Conditions ───────────────────────────────────────────────────────────────

@Composable
private fun ConditionRow(c: CondRow, data: RuleEditorData, enabled: Boolean, onChange: (CondRow) -> Unit, onRemove: () -> Unit) {
    val colors = CentsibleTheme.colors
    if (c.locked) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Describe.condition(app.centsible.core.model.RuleClause(c.field, c.op, c.value), data.names), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (enabled) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove condition") }
        }
        Text("Set up in Actual; kept as it is.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        return
    }
    val field = RuleField.of(c.field) ?: RuleField.Notes
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice(RuleField.entries.map { it.label }, field.ordinal, enabled) { i ->
                val nf = RuleField.entries[i]
                onChange(CondRow(nf.wire, nf.ops.first(), if (nf.type == ValueType.Bool) RuleValue.Bool(true) else RuleValue.Null))
            }
            Choice(field.ops.map { OP_LABELS[it] ?: it }, field.ops.indexOf(c.op).coerceAtLeast(0), enabled, Modifier.weight(1f)) { i ->
                val op = field.ops[i]
                val keep = (op in MULTI) == (c.op in MULTI) && op != "isbetween" && c.op != "isbetween"
                onChange(c.copy(op = op, value = if (keep) c.value else if (op == "isbetween") RuleFormCodec.between(0, 0) else RuleValue.Null))
            }
            if (enabled) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove condition") }
        }
        if (field == RuleField.Amount) {
            Segmented(listOf("Either way", "Money in", "Money out"), c.sign.ordinal, enabled) { onChange(c.copy(sign = AmountSign.entries[it])) }
        }
        ValueEditor(field.type, field.idKind, c.op, c.value, data, enabled, sign = c.sign) { onChange(c.copy(value = it)) }
    }
}

private val MULTI = setOf("oneOf", "notOneOf")
private val TEXT_ON_ID = setOf("contains", "doesNotContain", "matches")

// ── Actions ──────────────────────────────────────────────────────────────────

private enum class ActKind(val label: String) { Set("Set"), Prepend("Add to start of notes"), Append("Add to end of notes"), Delete("Delete the transaction") }

@Composable
private fun ActionRow(a: ActRow, data: RuleEditorData, enabled: Boolean, onChange: (ActRow) -> Unit, onRemove: () -> Unit, allowed: List<SetField> = SetField.entries) {
    val colors = CentsibleTheme.colors
    if (a.locked) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Describe.action(app.centsible.core.model.RuleClause(a.field, a.op, a.value), data.names), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (enabled) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove action") }
        }
        Text("Set up in Actual; kept as it is.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        return
    }
    val kind = when (a.op) { "prepend-notes" -> ActKind.Prepend; "append-notes" -> ActKind.Append; "delete-transaction" -> ActKind.Delete; else -> ActKind.Set }
    val kinds = if (allowed.size == SetField.entries.size) ActKind.entries else listOf(ActKind.Set)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice(kinds.map { it.label }, kinds.indexOf(kind), enabled) { i ->
                onChange(
                    when (kinds[i]) {
                        ActKind.Set -> ActRow("set", allowed.first().wire, RuleValue.Null)
                        ActKind.Prepend -> ActRow("prepend-notes", null, RuleValue.Text(""))
                        ActKind.Append -> ActRow("append-notes", null, RuleValue.Text(""))
                        ActKind.Delete -> ActRow("delete-transaction", null, RuleValue.Null)
                    },
                )
            }
            if (kind == ActKind.Set) {
                val field = SetField.of(a.field) ?: allowed.first()
                Choice(allowed.map { it.label }, allowed.indexOf(field).coerceAtLeast(0), enabled, Modifier.weight(1f)) { i ->
                    onChange(ActRow("set", allowed[i].wire, if (allowed[i].type == ValueType.Bool) RuleValue.Bool(true) else RuleValue.Null))
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (enabled) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove action") }
        }
        when (kind) {
            ActKind.Delete -> Text("Matching transactions are deleted. Use with care.", style = MaterialTheme.typography.bodySmall, color = colors.warning)
            ActKind.Prepend, ActKind.Append -> TextValue((a.value as? RuleValue.Text)?.value.orEmpty(), "Text", enabled) { onChange(a.copy(value = RuleValue.Text(it))) }
            ActKind.Set -> {
                val field = SetField.of(a.field) ?: allowed.first()
                val modes = if (field.type == ValueType.Text) ValueMode.entries else listOf(ValueMode.Value, ValueMode.Formula)
                Segmented(modes.map { when (it) { ValueMode.Value -> "Value"; ValueMode.Formula -> "Formula"; ValueMode.Template -> "Template" } }, modes.indexOf(a.mode).coerceAtLeast(0), enabled) { i ->
                    onChange(a.copy(mode = modes[i], formula = if (modes[i] == ValueMode.Formula && !a.formula.startsWith("=")) "=" else a.formula))
                }
                when (a.mode) {
                    ValueMode.Value -> ValueEditor(field.type, field.idKind, "is", a.value, data, enabled) { onChange(a.copy(value = it)) }
                    ValueMode.Formula -> FormulaEditor(a.formula, field, enabled) { onChange(a.copy(formula = it)) }
                    ValueMode.Template -> TemplateEditor(a.formula, enabled) { onChange(a.copy(formula = it)) }
                }
            }
        }
    }
}

@Composable
private fun SplitEditor(index: Int, s: SplitBlock, data: RuleEditorData, enabled: Boolean, onChange: (SplitBlock) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Part ${index + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Choice(SplitMethod.entries.map { it.label }, s.method.ordinal, enabled) { onChange(s.copy(method = SplitMethod.entries[it], formula = if (SplitMethod.entries[it] == SplitMethod.Formula && s.formula.isBlank()) "=" else s.formula)) }
            if (enabled) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove part ${index + 1}") }
        }
        when (s.method) {
            SplitMethod.Fixed -> MoneyValue(Money(s.amount), "Amount", enabled) { onChange(s.copy(amount = it?.minor ?: 0)) }
            SplitMethod.Percent -> {
                var text by remember(s.percent) { mutableStateOf(if (s.percent % 1.0 == 0.0) s.percent.toLong().toString() else s.percent.toString()) }
                OutlinedTextField(text, { t -> text = t; t.toDoubleOrNull()?.let { onChange(s.copy(percent = it)) } }, label = { Text("Percent of what's not fixed") }, suffix = { Text("%") },
                    singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            }
            SplitMethod.Remainder -> Text("Gets whatever the other parts leave (shared if there are several).", style = MaterialTheme.typography.bodySmall, color = CentsibleTheme.colors.textSecondary)
            SplitMethod.Formula -> FormulaEditor(s.formula, SetField.Amount, enabled) { onChange(s.copy(formula = it)) }
        }
        s.actions.forEachIndexed { i, a ->
            ActionRow(a, data, enabled,
                onChange = { n -> onChange(s.copy(actions = s.actions.toMutableList().also { it[i] = n })) },
                onRemove = { onChange(s.copy(actions = s.actions.toMutableList().also { it.removeAt(i) })) },
                allowed = listOf(SetField.Category, SetField.Notes, SetField.Payee),
            )
        }
        if (enabled) TextButton(onClick = { onChange(s.copy(actions = s.actions + ActRow("set", "notes", RuleValue.Null))) }) { Text("Set something on this part") }
    }
}

// ── Values ───────────────────────────────────────────────────────────────────

@Composable
private fun ValueEditor(type: ValueType, idKind: IdKind?, op: String, value: RuleValue, data: RuleEditorData, enabled: Boolean, sign: AmountSign = AmountSign.Any, onChange: (RuleValue) -> Unit) {
    when {
        op == "onBudget" || op == "offBudget" -> Unit
        type == ValueType.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if ((value as? RuleValue.Bool)?.value != false) "Yes" else "No", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = (value as? RuleValue.Bool)?.value != false, onCheckedChange = { onChange(RuleValue.Bool(it)) }, enabled = enabled)
        }
        type == ValueType.Id && op in TEXT_ON_ID -> TextValue((value as? RuleValue.Text)?.value.orEmpty(), if (op == "matches") "Pattern (regular expression)" else "Text", enabled) { onChange(RuleValue.Text(it)) }
        type == ValueType.Id && op in MULTI -> IdList(idKind!!, (value as? RuleValue.Items)?.values.orEmpty(), data, enabled) { onChange(RuleValue.Items(it)) }
        type == ValueType.Id -> IdPick(idKind!!, (value as? RuleValue.Text)?.value, data, enabled) { onChange(RuleValue.Text(it)) }
        type == ValueType.Text && op in MULTI -> {
            val items = (value as? RuleValue.Items)?.values.orEmpty()
            TextValue(items.joinToString(", "), "Values, separated by commas", enabled) { t -> onChange(RuleValue.Items(t.split(",").map { it.trim() }.filter { it.isNotEmpty() })) }
        }
        type == ValueType.Text -> TextValue((value as? RuleValue.Text)?.value.orEmpty(), if (op == "matches") "Pattern (regular expression)" else if (op.startsWith("has")) "#tags" else "Text", enabled) { onChange(RuleValue.Text(it)) }
        type == ValueType.Number && op == "isbetween" -> {
            val (low, high) = RuleFormCodec.betweenOf(value)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { MoneyValue(Money(low), "From", enabled, allowNegative = sign == AmountSign.Any) { onChange(RuleFormCodec.between(it?.minor ?: 0, high)) } }
                Box(Modifier.weight(1f)) { MoneyValue(Money(high), "To", enabled, allowNegative = sign == AmountSign.Any) { onChange(RuleFormCodec.between(low, it?.minor ?: 0)) } }
            }
        }
        type == ValueType.Number -> MoneyValue((value as? RuleValue.Number)?.let { Money(it.value) }, if (sign == AmountSign.Any) "Amount (− for money out)" else "Amount", enabled, allowNegative = sign == AmountSign.Any) {
            onChange(it?.let { m -> RuleValue.Number(m.minor) } ?: RuleValue.Null)
        }
        type == ValueType.Date -> DateValue((value as? RuleValue.Text)?.value, enabled) { onChange(RuleValue.Text(it)) }
    }
}

private fun pickerItems(kind: IdKind, data: RuleEditorData): List<PickerItem> = when (kind) {
    IdKind.Payee -> data.payees.filter { it.transferAccountId == null }.map { PickerItem(it.id.raw, it.name) }
    IdKind.Category -> data.groups.flatMap { g -> g.categories.map { PickerItem(it.id.raw, it.name, section = g.name, emoji = true) } }
    IdKind.Account -> data.accounts.filter { !it.closed }.map { PickerItem(it.id.raw, it.name) }
    IdKind.Group -> data.groups.map { PickerItem(it.id.raw, it.name) }
}

private fun nameOf(kind: IdKind, id: String, data: RuleEditorData) = when (kind) {
    IdKind.Payee -> data.names.payees[id]
    IdKind.Category -> data.names.categories[id]
    IdKind.Account -> data.names.accounts[id]
    IdKind.Group -> data.groups.firstOrNull { it.id.raw == id }?.name
} ?: "Unknown"

private fun kindLabel(kind: IdKind) = when (kind) { IdKind.Payee -> "merchant"; IdKind.Category -> "category"; IdKind.Account -> "account"; IdKind.Group -> "group" }

@Composable
private fun IdPick(kind: IdKind, selected: String?, data: RuleEditorData, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(selected?.let { nameOf(kind, it, data) } ?: "Choose ${kindLabel(kind)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) PickerSheet("Choose ${kindLabel(kind)}", pickerItems(kind, data), selected, onPick = { onPick(it.key); open = false }, onDismiss = { open = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdList(kind: IdKind, ids: List<String>, data: RuleEditorData, enabled: Boolean, onChange: (List<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ids.forEach { id ->
            InputChip(selected = false, onClick = { if (enabled) onChange(ids - id) }, enabled = enabled, label = { Text(nameOf(kind, id, data)) },
                trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove") })
        }
        if (enabled) AssistChip(onClick = { open = true }, label = { Text("Add ${kindLabel(kind)}") }, leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) })
    }
    if (open) PickerSheet("Add ${kindLabel(kind)}", pickerItems(kind, data).filter { it.key !in ids }, null, onPick = { onChange(ids + it.key); open = false }, onDismiss = { open = false })
}

@Composable
private fun TextValue(value: String, label: String, enabled: Boolean, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(text, { text = it; onChange(it) }, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MoneyValue(value: Money?, label: String, enabled: Boolean, allowNegative: Boolean = false, onChange: (Money?) -> Unit) {
    var text by remember { mutableStateOf(value?.takeIf { !it.isZero }?.let(MoneyInput::toInput).orEmpty()) }
    val parsed = MoneyInput.parse(text.replace("−", "-"))
    OutlinedTextField(
        text,
        { t -> text = t; val m = MoneyInput.parse(t.replace("−", "-")); if (t.isBlank()) onChange(null) else if (m != null && (allowNegative || !m.isNegative)) onChange(m) },
        label = { Text(label) }, prefix = { Text("$") }, enabled = enabled, singleLine = true,
        isError = text.isNotBlank() && (parsed == null || (!allowNegative && parsed.isNegative)),
        keyboardOptions = KeyboardOptions(keyboardType = if (allowNegative) KeyboardType.Text else KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateValue(iso: String?, enabled: Boolean, onChange: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val date = iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    OutlinedButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(date?.let { Describe.shortDate(it.toString()) + ", " + it.year } ?: "Choose a date") }
    if (picking) {
        val s = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { s.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; picking = false }) { Text("OK") } },
        ) { DatePicker(s) }
    }
}

// ── Formulas and templates ───────────────────────────────────────────────────

private val VARIABLES = listOf("amount", "payee_name", "imported_payee", "notes", "date", "account_name", "category_name", "balance", "today", "cleared")
private val FUNCTIONS = listOf("ROUND(", "ABS(", "IF(", "UPPER(", "PROPER(", "TRIM(", "CONCATENATE(", "SUBSTITUTE(", "LEFT(", "TEXT(")

@Composable
private fun FormulaEditor(formula: String, field: SetField, enabled: Boolean, onChange: (String) -> Unit) {
    val colors = CentsibleTheme.colors
    var text by remember { mutableStateOf(formula.ifEmpty { "=" }) }
    fun insert(s: String) { text += s; onChange(text) }
    OutlinedTextField(
        text, { text = it; onChange(it) },
        label = { Text("Formula") }, enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        isError = !text.trim().startsWith("="),
        modifier = Modifier.fillMaxWidth(),
    )
    if (enabled) {
        ChipRow(VARIABLES) { insert(it) }
        ChipRow(FUNCTIONS) { insert(it) }
    }
    Text(
        when (field) {
            SetField.Amount -> "Inside a formula, amount and balance are in cents. A number that comes out is dollars: =amount/100*2 doubles it."
            SetField.Date -> "Should give a date, e.g. =date or =today."
            SetField.Cleared -> "Should give TRUE or FALSE."
            else -> "Excel-style, starting with =. E.g. =UPPER(payee_name) or =IF(amount<0, \"out\", \"in\")."
        },
        style = MaterialTheme.typography.bodySmall,
        color = colors.textTertiary,
    )
}

@Composable
private fun TemplateEditor(template: String, enabled: Boolean, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(template) }
    OutlinedTextField(text, { text = it; onChange(it) }, label = { Text("Template") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    if (enabled) ChipRow(listOf("payee_name", "imported_payee", "notes", "date", "amount")) { v -> text += "{{$v}}"; onChange(text) }
    Text("Fills in {{fields}}, e.g. {{payee_name}} – {{notes}}. Worked out when the rule runs.", style = MaterialTheme.typography.bodySmall, color = CentsibleTheme.colors.textTertiary)
}

@Composable
private fun ChipRow(items: List<String>, onClick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { v -> AssistChip(onClick = { onClick(v) }, label = { Text(v, fontFamily = FontFamily.Monospace) }) }
    }
}

// ── Preview ──────────────────────────────────────────────────────────────────

@Composable
private fun PreviewCard(state: RuleEditorUiState, data: RuleEditorData) {
    val colors = CentsibleTheme.colors
    CentsibleCard(Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatLabel("On your transactions", Modifier.weight(1f))
            if (state.previewing) DialSpinner(size = 18.dp)
        }
        val p = state.preview
        when {
            state.problem != null -> Text("Finish the rule to see what it matches.", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
            state.previewError != null -> Text(state.previewError, style = MaterialTheme.typography.bodyMedium, color = colors.warning, modifier = Modifier.padding(top = 6.dp))
            p == null -> Unit
            p.errors.isNotEmpty() -> Text(p.errors.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = colors.negative, modifier = Modifier.padding(top = 6.dp))
            else -> {
                Text(
                    when (p.matchCount) { 0 -> "Matches nothing you have yet"; 1 -> "Matches 1 transaction"; else -> "Matches ${p.matchCount} transactions" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                p.items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.border)
                    val t = item.transaction
                    Row(verticalAlignment = Alignment.Top) {
                        MerchantAvatar(t.payeeName, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(t.payeeName ?: "No payee", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(MoneyFormat.format(t.amount), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
                            }
                            Text(Describe.shortDate(t.date), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                            item.changes.forEach { c ->
                                val label = when (c.field) { "payee" -> "merchant"; "imported_payee" -> "bank description"; else -> c.field }
                                val shown = when {
                                    c.error != null -> "⚠ ${c.error}"
                                    c.note != null && c.value == null -> "$label: ${c.note}"
                                    c.field == "deleted" -> "deleted"
                                    c.field == "amount" || c.field.endsWith("amount") -> "$label → ${c.value?.toLongOrNull()?.let { MoneyFormat.format(Money(it)) } ?: c.value}"
                                    else -> "$label → ${c.value?.takeIf { it.isNotEmpty() } ?: "(empty)"}"
                                }
                                Text(
                                    shown,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (c.error != null) colors.negative else colors.accent,
                                    modifier = Modifier.background(if (c.error != null) colors.negative.copy(alpha = 0.06f) else colors.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                                Spacer(Modifier.height(2.dp))
                            }
                        }
                    }
                }
                if (p.matchCount > p.items.size) Text("…and ${p.matchCount - p.items.size} more", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

// ── Small controls ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o -> SegmentedButton(i == selected, { onSelect(i) }, SegmentedButtonDefaults.itemShape(i, options.size), enabled = enabled) { Text(o, maxLines = 1) } }
    }
}

@Composable
private fun Choice(options: List<String>, selected: Int, enabled: Boolean, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.semantics { contentDescription = options.getOrNull(selected) ?: "Choose" }) {
            Text(options.getOrNull(selected) ?: "Choose", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) }) }
        }
    }
}
