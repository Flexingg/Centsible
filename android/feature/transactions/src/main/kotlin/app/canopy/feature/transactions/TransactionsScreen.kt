package app.canopy.feature.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.canopy.core.designsystem.component.CanopyCard
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.component.LoadingState
import app.canopy.core.designsystem.component.TransactionRow
import app.canopy.core.designsystem.component.dayLabel
import app.canopy.core.designsystem.component.MessageState
import app.canopy.core.designsystem.component.MoneyText
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.model.Transaction
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TransactionsRoute(viewModel: TransactionsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TransactionsScreen(state, onRetry = { viewModel.refresh() }, onLoadMore = viewModel::loadMore)
}

@Composable
fun TransactionsScreen(state: Loadable<TransactionsData>, onRetry: () -> Unit, onLoadMore: () -> Unit, today: LocalDate = LocalDate.now()) {
    Column(Modifier.fillMaxSize().background(CanopyTheme.colors.canvas)) {
        Text("Transactions", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp))
        when (state) {
            Loadable.Loading -> LoadingState()
            is Loadable.Failed -> MessageState("Couldn't load transactions", state.message, emoji = "🔌", actionLabel = "Try again", onAction = onRetry)
            is Loadable.Ready -> {
                val data = state.value
                if (data.items.isEmpty()) {
                    MessageState("No transactions yet", "Add one here, import a file, or connect bank sync in Actual.", emoji = "🧾")
                    return@Column
                }
                val byDate = data.items.groupBy { it.date }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    byDate.forEach { (date, txs) ->
                        item(key = date) {
                            Text(
                                dayLabel(date, today),
                                style = MaterialTheme.typography.labelMedium,
                                color = CanopyTheme.colors.textSecondary,
                                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                            )
                            CanopyCard(contentPadding = PaddingValues(0.dp)) {
                                txs.forEachIndexed { i, t ->
                                    if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = CanopyTheme.colors.border)
                                    TransactionRow(t, data.categoryNames, data.accountNames)
                                }
                            }
                        }
                    }
                    if (data.nextCursor != null) {
                        item(key = "more") {
                            TextButton(onClick = onLoadMore, enabled = !data.loadingMore, modifier = Modifier.fillMaxWidth()) {
                                Text(if (data.loadingMore) "Loading…" else "Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}
