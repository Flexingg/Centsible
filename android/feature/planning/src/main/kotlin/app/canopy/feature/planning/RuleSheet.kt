package app.canopy.feature.planning

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.canopy.core.designsystem.component.MoneyInput
import app.canopy.core.designsystem.component.PickerItem
import app.canopy.core.designsystem.component.PickerSheet
import app.canopy.core.designsystem.component.StatLabel
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.model.Rule
import app.canopy.core.model.RuleClause
import app.canopy.core.model.RuleDraft
import app.canopy.core.model.RuleValue

/** What the simple editor can express; anything else stays read-only here. */
internal enum class CondField(val label: String, val wire: String, val ops: List<Pair<String, String>>, val kind: ValueKind) {
    Merchant("Merchant", "payee", listOf("is" to "is", "isNot" to "is not"), ValueKind.Payee),
    BankText("Bank description", "imported_payee", listOf("contains" to "contains", "is" to "is"), ValueKind.Text),
    Notes("Notes", "notes", listOf("contains" to "contains", "is" to "is"), ValueKind.Text),
    Amount("Amount", "amount", listOf("is" to "is", "lt" to "is less than", "gt" to "is more than"), ValueKind.Money),
}

internal enum class ActField(val label: String, val wire: String, val kind: ValueKind) {
    Category("Set category", "category", ValueKind.Category),
    Merchant("Set merchant", "payee", ValueKind.Payee),
    Notes("Set notes", "notes", ValueKind.Text),
}

internal enum class ValueKind { Payee, Category, Text, Money }

internal data class Row2(val field: String, val op: String, val text: String)

internal object RuleForm {
    fun isSimple(r: Rule): Boolean =
        r.conditions.all { c ->
            val f = CondField.entries.firstOrNull { it.wire == c.field }
            f != null && f.ops.any { it.first == c.op } && (c.value is RuleValue.Text || c.value is RuleValue.Number)
        } && r.actions.all { a -> a.op == "set" && ActField.entries.any { it.wire == a.field } && a.value is RuleValue.Text }

    fun valueOf(kind: ValueKind, text: String): RuleValue? = when (kind) {
        ValueKind.Money -> MoneyInput.parse(text.replace("−", "-"))?.let { RuleValue.Number(it.minor) }
        else -> text.trim().takeIf { it.isNotEmpty() }?.let(RuleValue::Text)
    }

    fun typeOf(kind: ValueKind) = when (kind) {
        ValueKind.Payee, ValueKind.Category -> "id"
        ValueKind.Money -> "number"
        ValueKind.Text -> "string"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleSheet(rule: Rule?, data: RulesData, canEdit: Boolean, onDismiss: () -> Unit, onSave: (RuleDraft) -> Unit, onDelete: (String) -> Unit) {
    val colors = CanopyTheme.colors
    val editable = canEdit && (rule == null || RuleForm.isSimple(rule))
    var anyOf by remember { mutableStateOf(rule?.conditionsOp == "or") }
    val conditions = remember {
        (rule?.conditions?.map { Row2(it.field.orEmpty(), it.op, (it.value as? RuleValue.Text)?.value ?: (it.value as? RuleValue.Number)?.let { n -> MoneyInput.toInput(app.canopy.core.model.Money(n.value)) } ?: "") }
            ?: listOf(Row2("payee", "is", ""))).toMutableStateList()
    }
    val actions = remember {
        (rule?.actions?.map { Row2(it.field.orEmpty(), it.op, (it.value as? RuleValue.Text)?.value ?: "") } ?: listOf(Row2("category", "set", ""))).toMutableStateList()
    }
    var picking by remember { mutableStateOf<Pair<Boolean, Int>?>(null) } // (isAction, index)

    fun display(kind: ValueKind, text: String) = when (kind) {
        ValueKind.Payee -> data.names.payees[text] ?: "Choose merchant"
        ValueKind.Category -> data.names.categories[text] ?: "Choose category"
        else -> text
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (rule == null) "New rule" else "Rule", style = MaterialTheme.typography.titleLarge)
            if (rule != null && !editable) {
                val (ifText, thenText) = Describe.rule(rule, data.names)
                Text(ifText, style = MaterialTheme.typography.bodyLarge)
                Text("→ $thenText", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                Text(
                    if (canEdit) "This rule uses options the app can't edit yet. Change it in Actual's web app; you can still delete it here."
                    else "You can view rules but not change them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
                if (canEdit) TextButton(onClick = { onDelete(rule.id) }) { Text("Delete rule", color = colors.negative) }
                Spacer(Modifier.height(16.dp))
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatLabel("When", Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(!anyOf, { anyOf = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("All match") }
                    SegmentedButton(anyOf, { anyOf = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Any") }
                }
            }
            conditions.forEachIndexed { i, c ->
                val field = CondField.entries.first { it.wire == c.field }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Menu(field.label, CondField.entries.map { it.label }) { idx -> conditions[i] = Row2(CondField.entries[idx].wire, CondField.entries[idx].ops.first().first, "") }
                    Menu(field.ops.first { it.first == c.op }.second, field.ops.map { it.second }) { idx -> conditions[i] = c.copy(op = field.ops[idx].first) }
                    Spacer(Modifier.weight(1f))
                    if (conditions.size > 1) IconButton(onClick = { conditions.removeAt(i) }) { Icon(Icons.Rounded.Close, contentDescription = "Remove") }
                }
                ValueInput(field.kind, c.text, display(field.kind, c.text), onText = { conditions[i] = c.copy(text = it) }, onPick = { picking = false to i })
            }
            TextButton(onClick = { conditions.add(Row2("imported_payee", "contains", "")) }) { Text("+ Add condition") }

            StatLabel("Then")
            actions.forEachIndexed { i, a ->
                val field = ActField.entries.first { it.wire == a.field }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Menu(field.label, ActField.entries.map { it.label }) { idx -> actions[i] = Row2(ActField.entries[idx].wire, "set", "") }
                    Spacer(Modifier.weight(1f))
                    if (actions.size > 1) IconButton(onClick = { actions.removeAt(i) }) { Icon(Icons.Rounded.Close, contentDescription = "Remove") }
                }
                ValueInput(field.kind, a.text, display(field.kind, a.text), onText = { actions[i] = a.copy(text = it) }, onPick = { picking = true to i })
            }
            TextButton(onClick = { actions.add(Row2("notes", "set", "")) }) { Text("+ Add action") }

            val draft = run {
                val conds = conditions.map { c ->
                    val f = CondField.entries.first { it.wire == c.field }
                    RuleForm.valueOf(f.kind, c.text)?.let { RuleClause(f.wire, c.op, it, RuleForm.typeOf(f.kind)) }
                }
                val acts = actions.map { a ->
                    val f = ActField.entries.first { it.wire == a.field }
                    RuleForm.valueOf(f.kind, a.text)?.let { RuleClause(f.wire, "set", it, RuleForm.typeOf(f.kind)) }
                }
                if (conds.any { it == null } || acts.any { it == null } || acts.isEmpty()) null
                else RuleDraft(rule?.stage, if (anyOf) "or" else "and", conds.filterNotNull(), acts.filterNotNull())
            }
            Button(onClick = { draft?.let(onSave) }, enabled = draft != null, modifier = Modifier.fillMaxWidth()) { Text("Save rule") }
            if (rule != null) TextButton(onClick = { onDelete(rule.id) }, modifier = Modifier.fillMaxWidth()) { Text("Delete rule", color = colors.negative) }
            Spacer(Modifier.height(16.dp))
        }
    }

    picking?.let { (isAction, index) ->
        val row = if (isAction) actions[index] else conditions[index]
        val kind = if (isAction) ActField.entries.first { it.wire == row.field }.kind else CondField.entries.first { it.wire == row.field }.kind
        val items = if (kind == ValueKind.Category) {
            data.groups.filter { !it.hidden }.flatMap { g -> g.categories.filter { !it.hidden }.map { PickerItem(it.id.raw, it.name, section = g.name, emoji = true) } }
        } else {
            data.payees.filter { it.transferAccountId == null }.sortedBy { it.name.lowercase() }.map { PickerItem(it.id.raw, it.name) }
        }
        PickerSheet(if (kind == ValueKind.Category) "Category" else "Merchant", items, row.text, onPick = { item ->
            if (isAction) actions[index] = row.copy(text = item.key) else conditions[index] = row.copy(text = item.key)
            picking = null
        }, onDismiss = { picking = null })
    }
}

@Composable
private fun Menu(label: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        AssistChip(onClick = { open = true }, label = { Text(label) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
    Spacer(Modifier.width(8.dp))
}

@Composable
private fun ValueInput(kind: ValueKind, text: String, shown: String, onText: (String) -> Unit, onPick: () -> Unit) {
    when (kind) {
        ValueKind.Payee, ValueKind.Category -> OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text(shown) }
        ValueKind.Money -> OutlinedTextField(text, onText, prefix = { Text("$") }, placeholder = { Text("-50.00 for spending") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ValueKind.Text -> OutlinedTextField(text, onText, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

