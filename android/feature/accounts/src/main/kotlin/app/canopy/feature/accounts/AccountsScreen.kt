package app.canopy.feature.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.canopy.core.designsystem.component.CanopyCard
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.component.LoadingState
import app.canopy.core.designsystem.component.MessageState
import app.canopy.core.designsystem.component.MoneyText
import app.canopy.core.designsystem.component.StatLabel
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.model.Account

@Composable
fun AccountsRoute(viewModel: AccountsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AccountsScreen(state, onRetry = { viewModel.refresh() })
}

@Composable
fun AccountsScreen(state: Loadable<AccountsSummary>, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().background(CanopyTheme.colors.canvas)) {
        Text("Accounts", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp))
        when (state) {
            Loadable.Loading -> LoadingState()
            is Loadable.Failed -> MessageState("Couldn't load accounts", state.message, emoji = "🔌", actionLabel = "Try again", onAction = onRetry)
            is Loadable.Ready -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { NetWorthCard(state.value) }
                items(state.value.sections, key = { it.kind.name }) { SectionCard(it) }
            }
        }
    }
}

@Composable
fun NetWorthCard(summary: AccountsSummary) {
    val colors = CanopyTheme.colors
    CanopyCard(contentPadding = PaddingValues(20.dp)) {
        StatLabel("Net worth")
        MoneyText(summary.netWorth, style = MaterialTheme.typography.displaySmall, showCents = false)
        Spacer(Modifier.height(16.dp))
        val total = (summary.assets.minor - summary.liabilities.minor).coerceAtLeast(1)
        val assetShare = summary.assets.minor.toFloat() / total
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            if (assetShare > 0f) Box(Modifier.weight(assetShare).fillMaxHeight().background(colors.positive))
            if (assetShare < 1f) Box(Modifier.weight(1f - assetShare).fillMaxHeight().background(colors.negative.copy(alpha = 0.75f)))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Legend("Assets", colors.positive) { MoneyText(summary.assets, style = MaterialTheme.typography.titleSmall, showCents = false) }
            Legend("Liabilities", colors.negative) { MoneyText(summary.liabilities.abs(), style = MaterialTheme.typography.titleSmall, showCents = false) }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color, value: @Composable () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            StatLabel(label)
        }
        value()
    }
}

@Composable
private fun SectionCard(section: AccountSection) {
    val colors = CanopyTheme.colors
    CanopyCard(contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(section.kind.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            MoneyText(section.total, style = MaterialTheme.typography.titleSmall)
        }
        section.accounts.forEachIndexed { i, account ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.border)
            AccountRow(account)
        }
    }
}

@Composable
private fun AccountRow(account: Account) {
    val colors = CanopyTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(account.name, style = MaterialTheme.typography.bodyLarge)
            Text(if (account.offBudget) "Tracking" else "On budget", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        }
        MoneyText(account.balance, style = MaterialTheme.typography.bodyLarge, color = if (account.balance.isNegative) colors.negative else colors.textPrimary)
    }
}
