package app.centsible.feature.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyInput
import app.centsible.core.designsystem.component.ProgressRing
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AccountId
import app.centsible.core.model.Money
import app.centsible.core.model.Target
import app.centsible.core.model.TargetInput
import app.centsible.core.model.YearMonth
import java.time.LocalDate

/** Adding or changing a goal on an account or on monthly spending. */
data class TargetEditor(val kind: Target.Kind, val target: Target?)

/** What a goal can be: the chooser behind the + button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalKindSheet(canSaveInCategory: Boolean, onPick: (Target.Kind?) -> Unit, onDismiss: () -> Unit) {
    val colors = CentsibleTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("New goal", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            @Composable
            fun Choice(emoji: String, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).background(colors.cardMuted, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) colors.textPrimary else colors.textTertiary)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                    }
                }
            }
            Choice("🎯", "Save up in a category", if (canSaveInCategory) "An emergency fund, a trip: money you budget toward a target" else "Every category already has a goal", canSaveInCategory) { onPick(null) }
            Choice("🏦", "Grow an account", "Reach a balance in savings or investments, by a date if you like") { onPick(Target.Kind.Account) }
            Choice("✋", "Spend less than", "Keep a category under an amount each month, like eating out") { onPick(Target.Kind.SpendUnder) }
            Choice("🤲", "Spend at least", "Put at least an amount, or a share of income, toward something each month, like giving") { onPick(Target.Kind.SpendAtLeast) }
        }
    }
}

@Composable
internal fun TargetCard(t: Target, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = CentsibleTheme.colors
    CentsibleCard(onClick = onClick, contentPadding = PaddingValues(16.dp), modifier = modifier) {
        if (t.kind == Target.Kind.Account) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(t.progress, 52.dp, 6.dp, color = if (t.status == Target.Status.Reached) colors.positive else colors.accent)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${MoneyFormat.format(t.current)} of ${MoneyFormat.format(t.goal)}" + (t.targetMonth?.let { " by ${monthName(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    val (text, tone) = describe(t)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = toneColor(tone))
                }
            }
            return@CentsibleCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (t.kind == Target.Kind.SpendUnder) "Under ${MoneyFormat.format(t.goal, showCents = false)}"
                else t.percentOfIncome?.let { "At least ${pct(it)} of income" } ?: "At least ${MoneyFormat.format(t.goal, showCents = false)}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textTertiary,
            )
        }
        Spacer(Modifier.height(10.dp))
        GoalBar(t)
        Spacer(Modifier.height(8.dp))
        Text(
            "${MoneyFormat.format(t.current)} of ${MoneyFormat.format(t.goal)} this month",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        val (text, tone) = describe(t)
        Text(text, style = MaterialTheme.typography.bodySmall, color = toneColor(tone))
        if (t.history.size > 1) {
            Spacer(Modifier.height(10.dp))
            History(t)
        }
    }
}

@Composable
private fun toneColor(tone: Int) = when (tone) {
    1 -> CentsibleTheme.colors.positive
    -1 -> CentsibleTheme.colors.warning
    -2 -> CentsibleTheme.colors.negative
    else -> CentsibleTheme.colors.textTertiary
}

/** How much of the month's amount is used (or reached), with a tick at how far through the month it is. */
@Composable
private fun GoalBar(t: Target) {
    val colors = CentsibleTheme.colors
    val fill = when {
        t.kind == Target.Kind.SpendUnder && t.status == Target.Status.Over -> colors.negative
        t.kind == Target.Kind.SpendUnder && t.status == Target.Status.Behind -> colors.warning
        t.status == Target.Status.Reached || t.kind == Target.Kind.SpendUnder -> colors.positive
        else -> colors.accent
    }
    Box(Modifier.fillMaxWidth().height(10.dp).background(colors.cardMuted, RoundedCornerShape(5.dp)).clearAndSetSemantics { }) {
        Box(Modifier.fillMaxWidth(t.progress.coerceIn(0f, 1f)).fillMaxHeight().background(fill, RoundedCornerShape(5.dp)))
        if (t.pace in 0.01f..0.99f) {
            Box(Modifier.fillMaxWidth(t.pace).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.width(2.dp).height(14.dp).offset(y = 0.dp).background(colors.textPrimary))
            }
        }
    }
}

/** A dot a month, oldest first: kept or missed. */
@Composable
private fun History(t: Target) {
    val colors = CentsibleTheme.colors
    val past = t.history.dropLast(1)
    val kept = t.monthsKept - if (kept(t, t.history.last())) 1 else 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Kept $kept of the last ${past.size} months" },
    ) {
        past.forEach { m ->
            Box(Modifier.size(10.dp).background(if (kept(t, m)) colors.positive else colors.negative.copy(alpha = 0.7f), CircleShape))
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text("Kept $kept of the last ${past.size} month${if (past.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
    }
}

private fun kept(t: Target, m: Target.Month) = if (t.kind == Target.Kind.SpendUnder) m.value.minor <= m.goal.minor else m.goal.minor > 0 && m.value.minor >= m.goal.minor

private fun pct(p: Double) = if (p % 1.0 == 0.0) "${p.toInt()}%" else "$p%"

/** A sentence about where it stands, and whether that's good (1), neutral (0), needs attention (-1) or went wrong (-2). */
internal fun describe(t: Target): Pair<String, Int> {
    if (t.missing) return "Its account or category was deleted. Edit or remove this goal." to -1
    return when (t.kind) {
        Target.Kind.Account -> when (t.status) {
            Target.Status.Reached -> "Reached 🎉" to 1
            Target.Status.Stalled -> "It hasn't grown lately" + (t.monthlyNeeded?.let { ". Add ${MoneyFormat.format(it)} a month to make it." } ?: ".") to -1
            Target.Status.Behind -> "Add ${MoneyFormat.format(t.monthlyNeeded ?: t.remaining)} a month to make it (lately ${MoneyFormat.format(t.avgChange)})" to -1
            else -> (t.projectedMonth?.let { "On track: there by ${monthName(it)} at ${MoneyFormat.format(t.avgChange)} a month" } ?: "On track") to 1
        }
        Target.Kind.SpendUnder -> when (t.status) {
            Target.Status.Over -> "Over by ${MoneyFormat.format(Money(t.current.minor - t.goal.minor))}" to -2
            Target.Status.Behind -> "${MoneyFormat.format(t.remaining)} left, and spending is ahead of the month" to -1
            Target.Status.Reached -> "Kept under 🎉" to 1
            else -> "${MoneyFormat.format(t.remaining)} left · on pace" to 1
        }
        Target.Kind.SpendAtLeast -> when (t.status) {
            Target.Status.Reached -> "Reached 🎉" to 1
            Target.Status.Behind -> if (t.pace >= 1f) "Fell ${MoneyFormat.format(t.remaining)} short" to -1 else "${MoneyFormat.format(t.remaining)} to go, behind the month" to -1
            else -> "${MoneyFormat.format(t.remaining)} to go" to 0
        }
    }
}

/** The form for an account or spending goal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TargetSheet(
    editor: TargetEditor,
    accounts: List<Pair<AccountId, String>>,
    categoryNames: Map<String, String>,
    busy: Boolean,
    onSave: (String?, TargetInput) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CentsibleTheme.colors
    val t = editor.target
    val kind = editor.kind
    var name by remember { mutableStateOf(t?.name.orEmpty()) }
    var account by remember { mutableStateOf(t?.accountId) }
    var category by remember { mutableStateOf(t?.categoryId) }
    var categoryName by remember { mutableStateOf(t?.categoryId?.let { categoryNames[it.raw] }) }
    var picking by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf(t?.amount?.let(MoneyInput::toInput).orEmpty()) }
    var byShare by remember { mutableStateOf(t?.percentOfIncome != null) }
    var percent by remember { mutableStateOf(t?.percentOfIncome?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()) }
    val thisMonth = LocalDate.now().let { YearMonth.of(it.year, it.monthValue) }
    var byDate by remember { mutableStateOf(t?.targetMonth != null) }
    var month by remember { mutableStateOf(t?.targetMonth ?: thisMonth.plus(12)) }
    var confirmRemove by remember { mutableStateOf(false) }
    val parsed = MoneyInput.parse(amount)?.takeIf { it.minor >= 100 }
    val share = percent.toDoubleOrNull()?.takeIf { it > 0 && it <= 100 }
    val ready = when (kind) {
        Target.Kind.Account -> account != null && parsed != null
        Target.Kind.SpendUnder -> category != null && parsed != null
        Target.Kind.SpendAtLeast -> category != null && (if (byShare) share != null else parsed != null)
    }

    if (picking) {
        app.centsible.core.ui.CategoryPickerSheet(
            title = "Which category?",
            selected = category,
            includeIncome = false,
            onPick = { picking = false },
            onPickNamed = { id, n, _ -> category = id; categoryName = n },
            onDismiss = { picking = false },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when (kind) {
                    Target.Kind.Account -> "Grow an account"
                    Target.Kind.SpendUnder -> "Spend less than"
                    Target.Kind.SpendAtLeast -> "Spend at least"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            if (kind == Target.Kind.Account) {
                StatLabel("Account")
                Column {
                    accounts.forEach { (id, n) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(account == id, role = Role.RadioButton) { account = id }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = account == id, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(n, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            } else {
                StatLabel("Category")
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(categoryName ?: "Choose a category", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (kind == Target.Kind.SpendAtLeast) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!byShare, { byShare = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("An amount") }
                    SegmentedButton(byShare, { byShare = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Share of income") }
                }
            }
            if (kind == Target.Kind.SpendAtLeast && byShare) {
                OutlinedTextField(
                    percent, { percent = it },
                    label = { Text("Share of each month's income") },
                    suffix = { Text("%") },
                    isError = percent.isNotEmpty() && share == null,
                    supportingText = { Text("Worked out from what came in that month, so it follows your paychecks.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    amount, { amount = it },
                    label = { Text(when (kind) { Target.Kind.Account -> "Balance to reach"; Target.Kind.SpendUnder -> "Monthly limit"; else -> "Each month, at least" }) },
                    prefix = { Text("$") },
                    isError = amount.isNotEmpty() && parsed == null,
                    supportingText = if (amount.isNotEmpty() && parsed == null) ({ Text("At least $1.00") }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (kind == Target.Kind.Account) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("By a date", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = byDate, onCheckedChange = { byDate = it }, modifier = Modifier.semantics { contentDescription = "By a date" })
                }
                if (byDate) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { month = month.plus(-1) }, enabled = month > thisMonth) { Text("‹") }
                        Text(monthName(month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { month = month.plus(1) }) { Text("›") }
                    }
                }
            }
            OutlinedTextField(
                name, { name = it },
                label = { Text("Name (optional)") },
                placeholder = { Text(if (kind == Target.Kind.Account) "The account's name" else categoryName ?: "The category's name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    onSave(
                        t?.id,
                        TargetInput(
                            kind = kind,
                            name = name.trim().ifEmpty { null },
                            accountId = account.takeIf { kind == Target.Kind.Account },
                            categoryId = category.takeIf { kind != Target.Kind.Account },
                            amount = if (kind == Target.Kind.SpendAtLeast && byShare) null else parsed,
                            percentOfIncome = share.takeIf { kind == Target.Kind.SpendAtLeast && byShare },
                            targetMonth = month.takeIf { kind == Target.Kind.Account && byDate },
                        ),
                    )
                },
                enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save goal") }
            if (t != null) {
                OutlinedButton(onClick = { confirmRemove = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Remove goal", color = colors.negative) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmRemove && t != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove this goal?") },
            text = { Text("Only the goal goes; your money and categories stay as they are.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove(t.id) }) { Text("Remove", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
