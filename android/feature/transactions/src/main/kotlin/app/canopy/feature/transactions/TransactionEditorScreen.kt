package app.canopy.feature.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.canopy.core.designsystem.component.CanopyCard
import app.canopy.core.designsystem.component.CategoryAvatar
import app.canopy.core.designsystem.component.LoadingState
import app.canopy.core.designsystem.component.MessageState
import app.canopy.core.designsystem.component.MoneyFormat
import app.canopy.core.designsystem.component.PickerItem
import app.canopy.core.designsystem.component.PickerSheet
import app.canopy.core.designsystem.component.StatLabel
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.model.AccountId
import app.canopy.core.model.CategoryId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun TransactionEditorRoute(onClose: () -> Unit, viewModel: TransactionEditorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onClose() }
    TransactionEditorScreen(
        state = state,
        actions = EditorActions(
            close = onClose,
            retry = { viewModel.load() },
            rememberCategory = viewModel::setRememberCategory,
            edit = viewModel::edit,
            addSplit = viewModel::addSplit,
            updateSplit = viewModel::updateSplit,
            removeSplit = viewModel::removeSplit,
            save = viewModel::save,
            delete = viewModel::delete,
        ),
    )
}

data class EditorActions(
    val close: () -> Unit = {},
    val retry: () -> Unit = {},
    val edit: ((TransactionForm) -> TransactionForm) -> Unit = {},
    val rememberCategory: (Boolean) -> Unit = {},
    val addSplit: () -> Unit = {},
    val updateSplit: (Long, (SplitRow) -> SplitRow) -> Unit = { _, _ -> },
    val removeSplit: (Long) -> Unit = {},
    val save: () -> Unit = {},
    val delete: () -> Unit = {},
)

private sealed interface Picker {
    data object Category : Picker
    data object Account : Picker
    data object TransferAccount : Picker
    data class SplitCategory(val key: Long) : Picker
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionEditorScreen(state: EditorUiState, actions: EditorActions) {
    val colors = CanopyTheme.colors
    var picker by remember { mutableStateOf<Picker?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val form = state.form
    val enabled = state.canEdit && !state.saving
    val categoryNames = remember(state.groups) { state.groups.flatMap { it.categories }.associate { it.id to it.name } }
    val accountNames = remember(state.accounts) { state.accounts.associate { it.id to it.name } }

    Column(Modifier.fillMaxSize().padding(bottom = 0.dp).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.close) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
            Text(state.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.canEdit) {
                TextButton(onClick = actions.save, enabled = !state.saving && !state.loading) { Text(if (state.saving) "Saving…" else "Save") }
            }
        }
        when {
            state.loading -> LoadingState()
            state.loadError != null -> MessageState("Couldn't open this transaction", state.loadError, emoji = "🔌", actionLabel = "Try again", onAction = actions.retry)
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Kind + amount hero
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val kinds = TxKind.entries.filter { it != TxKind.Transfer || state.canTransfer || form.kind == TxKind.Transfer }
                    kinds.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = form.kind == k,
                            onClick = { actions.edit { it.copy(kind = k, splits = if (k == TxKind.Transfer) emptyList() else it.splits) } },
                            shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                            enabled = enabled,
                        ) { Text(k.name) }
                    }
                }
                val amountColor = when (form.kind) {
                    TxKind.Income -> colors.positive
                    else -> colors.textPrimary
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (form.kind == TxKind.Income || (form.kind == TxKind.Transfer && form.transferIn)) "+$" else "-$",
                        style = MaterialTheme.typography.displaySmall,
                        color = colors.textTertiary,
                    )
                    BasicTextField(
                        value = form.amount,
                        onValueChange = { v -> actions.edit { it.copy(amount = v.filter { c -> c.isDigit() || c == '.' || c == ',' }) } },
                        enabled = enabled,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.displaySmall.copy(color = amountColor, textAlign = TextAlign.Start, fontFeatureSettings = "tnum"),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        decorationBox = { inner ->
                            if (form.amount.isEmpty()) Text("0.00", style = MaterialTheme.typography.displaySmall, color = colors.textTertiary)
                            inner()
                        },
                        modifier = Modifier.width(IntrinsicWidthFor(form.amount)),
                    )
                }

                CanopyCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    if (form.kind == TxKind.Transfer) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            SegmentedButton(!form.transferIn, { actions.edit { it.copy(transferIn = false) } }, SegmentedButtonDefaults.itemShape(0, 2), enabled = enabled) { Text("Send") }
                            SegmentedButton(form.transferIn, { actions.edit { it.copy(transferIn = true) } }, SegmentedButtonDefaults.itemShape(1, 2), enabled = enabled) { Text("Receive") }
                        }
                        FieldRow(if (form.transferIn) "From" else "To", form.transferAccountId?.let { accountNames[it] } ?: "Choose account", enabled) { picker = Picker.TransferAccount }
                    } else {
                        OutlinedTextField(
                            value = form.payee,
                            onValueChange = { v -> actions.edit { it.copy(payee = v) } },
                            label = { Text("Merchant") },
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                        if (enabled && state.payeeSuggestions.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.payeeSuggestions.forEach { name ->
                                    AssistChip(onClick = { actions.edit { it.copy(payee = name) } }, label = { Text(name) })
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = colors.border)
                    FieldRow(if (form.kind == TxKind.Transfer && form.transferIn) "Into account" else "Account", form.accountId?.let { accountNames[it] } ?: "Choose account", enabled) { picker = Picker.Account }
                    if (form.kind != TxKind.Transfer && !form.isSplit) {
                        HorizontalDivider(color = colors.border)
                        val name = form.categoryId?.let { categoryNames[it] }
                        FieldRow("Category", name ?: "Needs category", enabled, emoji = name, warn = name == null) { picker = Picker.Category }
                        if (state.canRememberCategory && name != null) {
                            Row(
                                Modifier.fillMaxWidth().clickable { actions.rememberCategory(!state.rememberCategory) }.padding(bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Checkbox(checked = state.rememberCategory, onCheckedChange = actions.rememberCategory)
                                Text(
                                    "Always use $name for ${form.payee.trim()}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = colors.border)
                    FieldRow("Date", form.date.pretty(), enabled) { pickingDate = true }
                    HorizontalDivider(color = colors.border)
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Cleared", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = form.cleared, onCheckedChange = { c -> actions.edit { it.copy(cleared = c) } }, enabled = enabled)
                    }
                }

                OutlinedTextField(
                    value = form.notes,
                    onValueChange = { v -> actions.edit { it.copy(notes = v) } },
                    label = { Text("Notes") },
                    placeholder = { Text("Add a note or #tag") },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (form.kind != TxKind.Transfer && (form.isSplit || (state.canSplit && enabled))) {
                    SplitsCard(state, enabled, actions, categoryNames) { key -> picker = Picker.SplitCategory(key) }
                }

                state.original?.takeIf { it.reconciled }?.let {
                    Text("🔒 Reconciled transactions are read-only here. Unlock them in Actual to edit.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.negative) }
                if (state.canDelete) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = colors.negative)
                        Text("  Delete transaction", color = colors.negative)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    when (val p = picker) {
        Picker.Category, is Picker.SplitCategory -> {
            val incomeFirst = form.kind == TxKind.Income
            val groups = state.groups.filter { !it.hidden }.sortedBy { if (it.isIncome == incomeFirst) 0 else 1 }
            val items = groups.flatMap { g -> g.categories.filter { !it.hidden }.map { PickerItem(it.id.raw, it.name, section = g.name, emoji = true) } }
            val selected = if (p is Picker.SplitCategory) form.splits.firstOrNull { it.key == p.key }?.categoryId else form.categoryId
            PickerSheet("Category", items, selected?.raw, onDismiss = { picker = null }, onPick = { item ->
                val id = CategoryId(item.key)
                if (p is Picker.SplitCategory) actions.updateSplit(p.key) { it.copy(categoryId = id) } else actions.edit { it.copy(categoryId = id) }
                picker = null
            })
        }
        Picker.Account, Picker.TransferAccount -> {
            val exclude = if (p == Picker.TransferAccount) form.accountId else form.transferAccountId
            val items = state.accounts.filter { it.id != exclude }.map {
                PickerItem(it.id.raw, it.name, section = if (it.offBudget) "Tracking" else "On budget", supporting = MoneyFormat.format(it.balance))
            }.sortedBy { it.section != "On budget" }
            val selected = if (p == Picker.TransferAccount) form.transferAccountId else form.accountId
            PickerSheet(if (p == Picker.TransferAccount) "Other account" else "Account", items, selected?.raw, onDismiss = { picker = null }, onPick = { item ->
                val id = AccountId(item.key)
                actions.edit { if (p == Picker.TransferAccount) it.copy(transferAccountId = id) else it.copy(accountId = id) }
                picker = null
            })
        }
        null -> Unit
    }

    if (pickingDate) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = form.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { ms -> actions.edit { it.copy(date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) } }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(dateState) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = {
                Text(
                    if (state.original?.isTransfer == true) "Both sides of the transfer will be removed. This can't be undone from the app."
                    else "This can't be undone from the app.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; actions.delete() }) { Text("Delete", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SplitsCard(
    state: EditorUiState,
    enabled: Boolean,
    actions: EditorActions,
    categoryNames: Map<CategoryId, String>,
    onPickCategory: (Long) -> Unit,
) {
    val colors = CanopyTheme.colors
    val form = state.form
    CanopyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Split", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (form.isSplit) {
                val remaining = form.splitRemaining
                Text(
                    when {
                        remaining == null -> ""
                        remaining.isZero -> "Fully assigned ✓"
                        remaining.isNegative -> "${MoneyFormat.format(remaining.abs())} over"
                        else -> "${MoneyFormat.format(remaining)} left"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (remaining?.isZero == true) colors.positive else colors.warning,
                )
            }
        }
        form.splits.forEach { split ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val name = split.categoryId?.let { categoryNames[it] }
                Row(
                    Modifier.weight(1f).clickable(enabled = enabled) { onPickCategory(split.key) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryAvatar(name ?: "?", size = 30.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(name ?: "Choose category", style = MaterialTheme.typography.bodyMedium, color = if (name == null) colors.accent else colors.textPrimary)
                }
                OutlinedTextField(
                    value = split.amount,
                    onValueChange = { v -> actions.updateSplit(split.key) { it.copy(amount = v) } },
                    prefix = { Text("$") },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = TextStyle(fontFeatureSettings = "tnum"),
                    modifier = Modifier.width(128.dp),
                )
                if (enabled) {
                    IconButton(onClick = { actions.removeSplit(split.key) }) { Icon(Icons.Rounded.Close, contentDescription = "Remove split") }
                }
            }
        }
        if (enabled) {
            OutlinedButton(onClick = actions.addSplit, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(if (form.isSplit) "Add another split" else "Split across categories")
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, enabled: Boolean, emoji: String? = null, warn: Boolean = false, onClick: () -> Unit) {
    val colors = CanopyTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatLabel(label, Modifier.width(96.dp))
        if (emoji != null) {
            CategoryAvatar(emoji, size = 26.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(value, style = MaterialTheme.typography.bodyLarge, color = if (warn) colors.accent else colors.textPrimary, modifier = Modifier.weight(1f))
        if (enabled) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
    }
}

private fun LocalDate.pretty(today: LocalDate = LocalDate.now()): String = when (this) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> format(DateTimeFormatter.ofPattern(if (year == today.year) "EEE, MMM d" else "MMM d, yyyy"))
}

/** Grows the amount field with its text so the "$" stays next to the number. */
private fun IntrinsicWidthFor(text: String) = ((text.ifEmpty { "0.00" }.length + 1) * 22).dp
