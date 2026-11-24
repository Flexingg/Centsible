package app.centsible.feature.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.AccountServices
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Account
import app.centsible.core.model.AccountType
import app.centsible.core.model.BudgetSwitchPreview
import app.centsible.core.model.CategoryId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Investments, mortgages and loans are tracked, not budgeted: money moved into them leaves the budget. */
val OFF_BUDGET_TYPES = setOf(AccountType.Investment, AccountType.Mortgage, AccountType.Loan)

/** On budget, but its type (set or guessed) usually lives off budget, and nobody chose to keep it there. */
fun Account.shouldBeOffBudget(kept: Set<app.centsible.core.model.AccountId>) =
    !closed && !offBudget && kind() in OFF_BUDGET_TYPES && id !in kept

/** What a screen can do with [BudgetSwitcher] (defaults are no-ops, for previews and tests). */
data class BudgetSwitchActions(
    val start: (Account, Boolean) -> Unit = { _, _ -> },
    val category: (CategoryId?) -> Unit = {},
    val confirm: () -> Unit = {},
    val keep: () -> Unit = {},
    val dismiss: () -> Unit = {},
)

fun BudgetSwitcher.actions() = BudgetSwitchActions(
    start = { a, suggested -> start(a, suggested) },
    category = ::category,
    confirm = { confirm() },
    keep = { confirm(keep = true) },
    dismiss = ::dismiss,
)

@Composable
fun BudgetSwitchHost(state: BudgetSwitchState?, actions: BudgetSwitchActions) {
    state?.let { BudgetSwitchSheet(it, actions.category, actions.confirm, actions.keep, actions.dismiss) }
}

/** The question being asked about one account; see [BudgetSwitcher]. */
data class BudgetSwitchState(
    val account: Account,
    /** Null while it loads. */
    val preview: BudgetSwitchPreview? = null,
    /** For its transfers, moving off budget. */
    val category: CategoryId? = null,
    val categoryNames: Map<String, String> = emptyMap(),
    /** Offered by the app (its type lives off budget), so "Keep on budget" is an answer too. */
    val suggested: Boolean = false,
    val working: Boolean = false,
) {
    val toOffBudget get() = !(preview?.offBudget ?: account.offBudget)
}

/**
 * Moving an account on or off budget, for any screen: loads what the switch would do, asks
 * for a category for its transfers, and reports how it went with [onDone].
 */
class BudgetSwitcher(
    private val scope: CoroutineScope,
    private val engine: BudgetEngine,
    private val services: AccountServices,
    private val selectedBudget: SelectedBudget,
    private val onDone: (String) -> Unit,
) {
    private val state = MutableStateFlow<BudgetSwitchState?>(null)
    val current: StateFlow<BudgetSwitchState?> = state.asStateFlow()

    fun start(account: Account, suggested: Boolean = false) {
        state.value = BudgetSwitchState(account, suggested = suggested)
        scope.launch {
            runCatching {
                val budget = selectedBudget()
                val preview = services.budgetSwitch(budget, account.id)
                val names = engine.categoryGroups(budget).flatMap { it.categories }.associate { it.id.raw to it.name }
                preview to names
            }
                .onSuccess { (p, names) ->
                    state.update { s -> s?.copy(preview = p, categoryNames = names, category = s.category ?: p.suggestedCategory) }
                }
                .onFailure { e -> state.value = null; onDone(e.userMessage()) }
        }
    }

    fun category(id: CategoryId?) = state.update { it?.copy(category = id) }

    fun dismiss() {
        state.value = null
    }

    /** Switches it (or, when it was only suggested, keeps it where it is on purpose). */
    fun confirm(keep: Boolean = false) {
        val s = state.value ?: return
        state.update { it?.copy(working = true) }
        scope.launch {
            runCatching {
                val budget = selectedBudget()
                val off = if (keep) false else s.toOffBudget
                // Confirming a guessed type: keep that type, so the account stays grouped where it is.
                if (!keep && s.account.type == null && off) engine.setAccountType(budget, s.account.id, s.account.kind())
                services.switchBudget(budget, s.account.id, off, if (off) s.category else null)
            }
                .onSuccess { r ->
                    state.value = null
                    onDone(
                        when {
                            keep -> "${s.account.name} stays on budget"
                            r.account.offBudget -> "${s.account.name} is off budget" + if (r.categorized > 0) " · ${plural(r.categorized, "transfer")} categorized" else ""
                            else -> "${s.account.name} is on budget" + if (r.cleared > 0) " · ${plural(r.cleared, "transfer")} uncategorized" else ""
                        },
                    )
                }
                .onFailure { e ->
                    state.update { it?.copy(working = false) }
                    onDone(e.userMessage())
                }
        }
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetSwitchSheet(
    state: BudgetSwitchState,
    onCategory: (CategoryId?) -> Unit,
    onConfirm: () -> Unit,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CentsibleTheme.colors
    var picking by remember { mutableStateOf(false) }
    val p = state.preview
    val off = state.toOffBudget
    val name = state.account.name
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (off) "Move $name off budget?" else "Move $name on budget?", style = MaterialTheme.typography.titleLarge)
            if (state.suggested && off) {
                val guessed = if (state.account.type == null) " (going by its name)" else ""
                Text(
                    "It looks like ${article(state.account.kind())}$guessed. Those are usually tracked, not budgeted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
            Text(
                if (off) {
                    "Its balance still counts in your net worth, but it stops adding to the money you can budget."
                } else {
                    "Its balance becomes money you can budget, and its spending goes in categories like any other budgeted account."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (p == null) {
                app.centsible.core.designsystem.component.DialSpinner(size = 24.dp)
            } else if (off) {
                val open = p.transfers - p.categorized
                if (p.transfers == 0) {
                    Text("It has no transfers from budgeted accounts, so there's nothing else to sort out.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                } else {
                    Text(
                        "Money moved here from budgeted accounts (${plural(p.transfers, "transfer")}, like contributions or payments) will count as spending. " +
                            if (open > 0) "Which category should ${if (open == 1) "it" else "the $open without one"} count in?" else "They all have a category already.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (open > 0) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.cardMuted,
                            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Choose a category") { picking = true },
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Category", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                                    Text(
                                        state.category?.let { state.categoryNames[it.raw] } ?: "Leave uncategorized",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
                            }
                        }
                        if (state.category == null) {
                            Text(
                                "Without one they count as uncategorized spending and show up in Review, past months included.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.warning,
                            )
                        }
                    }
                }
            } else {
                if (p.categorized > 0) {
                    Text(
                        "${plural(p.categorized, "transfer")} with budgeted accounts will lose ${if (p.categorized == 1) "its category" else "their categories"}: moving money between budgeted accounts isn't spending.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (p.uncategorized > 0) {
                    Text(
                        "${plural(p.uncategorized, "transaction")} here ${if (p.uncategorized == 1) "has" else "have"} no category yet; ${if (p.uncategorized == 1) "it" else "they"}'ll show up in Review.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(onClick = onConfirm, enabled = p != null && !state.working, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.working) "Moving…" else if (off) "Move off budget" else "Move on budget")
            }
            if (state.suggested && off) {
                TextButton(onClick = onKeep, enabled = !state.working, modifier = Modifier.fillMaxWidth()) { Text("Keep it on budget") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (picking) {
        app.centsible.core.ui.CategoryPickerSheet(
            title = "Transfers to $name count in",
            selected = state.category,
            onPick = { onCategory(it); picking = false },
            onDismiss = { picking = false },
            includeIncome = false,
        )
    }
}

private fun article(t: AccountType) = when (t) {
    AccountType.Investment -> "an investment account"
    AccountType.Mortgage -> "a mortgage"
    AccountType.Loan -> "a loan"
    else -> "a ${t.singular.lowercase()} account"
}
