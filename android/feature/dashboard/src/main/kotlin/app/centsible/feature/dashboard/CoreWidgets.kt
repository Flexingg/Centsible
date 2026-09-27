package app.centsible.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.BudgetProgressBar
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.CategoryAvatar
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.SectionCard
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.component.TransactionRow
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.extensions.DashboardContext
import app.centsible.core.extensions.DashboardWidget
import app.centsible.core.extensions.Destination
import app.centsible.core.model.Feature
import app.centsible.core.model.sum
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

// The dashboard's own cards are registered exactly like extension widgets will be.

class NetWorthWidget @Inject constructor() : DashboardWidget {
    override val id = "core.net-worth"
    override val order = 100
    override val requires = setOf(Feature.AccountsRead)

    @Composable
    override fun Content(context: DashboardContext) {
        val open = context.accounts.filter { !it.closed }
        val assets = open.filter { !it.balance.isNegative }.map { it.balance }.sum()
        val debts = open.filter { it.balance.isNegative }.map { it.balance }.sum()
        CentsibleCard(onClick = { context.navigate(Destination.Accounts) }, contentPadding = PaddingValues(20.dp)) {
            StatLabel("Net worth")
            MoneyText(assets + debts, style = MaterialTheme.typography.displaySmall, showCents = false)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column2("Assets") { MoneyText(assets, style = MaterialTheme.typography.titleSmall, showCents = false, color = CentsibleTheme.colors.positive) }
                Column2("Liabilities") { MoneyText(debts.abs(), style = MaterialTheme.typography.titleSmall, showCents = false, color = CentsibleTheme.colors.negative) }
                Column2("Accounts") { Text("${open.size}", style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
}

class BudgetSummaryWidget @Inject constructor() : DashboardWidget {
    override val id = "core.budget-summary"
    override val order = 200
    override val requires = setOf(Feature.BudgetEnvelope)

    @Composable
    override fun Content(context: DashboardContext) {
        val month = context.month ?: return
        val colors = CentsibleTheme.colors
        SectionCard("Budget this month", action = "Open", onAction = { context.navigate(Destination.Budget) }) {
            val spent = month.totalSpent.abs()
            val share = if (month.totalBudgeted.minor > 0) spent.minor.toFloat() / month.totalBudgeted.minor else 0f
            Row(verticalAlignment = Alignment.Bottom) {
                MoneyText(spent, style = MaterialTheme.typography.headlineSmall, showCents = false)
                Text(
                    "  spent of ${MoneyFormat.format(month.totalBudgeted, showCents = false)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            BudgetProgressBar(share, overspent = share > 1f, height = 8.dp, color = colors.accent.takeIf { share <= 1f })
            Spacer(Modifier.height(10.dp))
            Text(
                if (month.toBudget.isNegative) "Overbudgeted by ${MoneyFormat.format(month.toBudget.abs())}"
                else "${MoneyFormat.format(month.toBudget)} left to budget",
                style = MaterialTheme.typography.labelLarge,
                color = if (month.toBudget.isNegative) colors.negative else colors.positive,
            )
            // Envelopes that need attention: overspent first, then nearly empty.
            val watch = month.expenseGroups.flatMap { it.categories }
                .filter { !it.hidden && it.budgeted.minor > 0 && (it.isOverspent || it.progress >= 0.85f) && !it.balance.isZero }
                .sortedBy { it.balance.minor }
                .take(3)
            if (watch.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.border)
                StatLabel("Keep an eye on")
                watch.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryAvatar(c.name, size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            if (c.isOverspent) "${MoneyFormat.format(c.balance.abs())} over" else "${MoneyFormat.format(c.balance)} left",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (c.isOverspent) colors.negative else colors.warning,
                        )
                    }
                }
            }
        }
    }
}

class RecentTransactionsWidget @Inject constructor() : DashboardWidget {
    override val id = "core.recent-transactions"
    override val order = 300
    override val requires = setOf(Feature.TransactionsRead)

    @Composable
    override fun Content(context: DashboardContext) {
        val accountNames = context.accounts.associate { it.id.raw to it.name }
        SectionCard(
            "Recent transactions",
            action = "See all",
            onAction = { context.navigate(Destination.Transactions) },
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            context.recentTransactions.take(5).forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = CentsibleTheme.colors.border)
                TransactionRow(t, context.categoryNames, accountNames, onClick = { context.navigate(Destination.Transaction(t.id)) })
            }
        }
    }
}

@Composable
private fun Column2(label: String, value: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column {
        StatLabel(label)
        value()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreWidgetsModule {
    @Binds @IntoSet abstract fun netWorth(w: NetWorthWidget): DashboardWidget
    @Binds @IntoSet abstract fun budget(w: BudgetSummaryWidget): DashboardWidget
    @Binds @IntoSet abstract fun recent(w: RecentTransactionsWidget): DashboardWidget
}
