package app.centsible.feature.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.TransactionRow
import app.centsible.core.designsystem.component.dayLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AccountId
import app.centsible.core.model.TransactionId
import java.time.LocalDate

@Composable
fun TransactionsRoute(onOpen: (TransactionId) -> Unit, viewModel: TransactionsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TransactionsScreen(
        state = state,
        onRetry = { viewModel.refresh() },
        onLoadMore = viewModel::loadMore,
        onOpen = onOpen,
        onSearch = viewModel::setSearch,
        onToggleNeedsCategory = viewModel::toggleNeedsCategory,
        onAccount = viewModel::setAccount,
        onClearFilters = viewModel::clearFilters,
    )
}

@Composable
fun TransactionsScreen(
    state: TransactionsUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (TransactionId) -> Unit = {},
    onSearch: (String) -> Unit = {},
    onToggleNeedsCategory: () -> Unit = {},
    onAccount: (AccountId?) -> Unit = {},
    onClearFilters: () -> Unit = {},
    today: LocalDate = LocalDate.now(),
) {
    val colors = CentsibleTheme.colors
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        Text("Transactions", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp))
        OutlinedTextField(
            value = state.filters.search,
            onValueChange = onSearch,
            placeholder = { Text("Search merchants, notes, categories") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (state.filters.search.isNotEmpty()) {
                { IconButton(onClick = { onSearch("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") } }
            } else {
                null
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = colors.card, focusedContainerColor = colors.card),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        FilterRow(state, onToggleNeedsCategory, onAccount, onClearFilters)
        when (val data = state.data) {
            Loadable.Loading -> LoadingState()
            is Loadable.Failed -> MessageState("Couldn't load transactions", data.message, emoji = "🔌", actionLabel = "Try again", onAction = onRetry)
            is Loadable.Ready -> {
                val d = data.value
                if (d.items.isEmpty()) {
                    if (state.filters.isActive) {
                        MessageState("Nothing matches", "Try a different search or clear the filters.", emoji = "🔍", actionLabel = "Clear filters", onAction = onClearFilters)
                    } else {
                        MessageState("No transactions yet", "Tap + to add one, or import and sync in Actual.", emoji = "🧾")
                    }
                    return@Column
                }
                val byDate = d.items.groupBy { it.date }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    byDate.forEach { (date, txs) ->
                        item(key = date) {
                            Text(dayLabel(date, today), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                            CentsibleCard(contentPadding = PaddingValues(0.dp)) {
                                txs.forEachIndexed { i, t ->
                                    if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = colors.border)
                                    TransactionRow(t, d.categoryNames, d.accountNames, onClick = { onOpen(t.id) })
                                }
                            }
                        }
                    }
                    if (d.nextCursor != null) {
                        item(key = "more") {
                            TextButton(onClick = onLoadMore, enabled = !d.loadingMore, modifier = Modifier.fillMaxWidth()) {
                                Text(if (d.loadingMore) "Loading…" else "Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(state: TransactionsUiState, onToggleNeedsCategory: () -> Unit, onAccount: (AccountId?) -> Unit, onClear: () -> Unit) {
    val accounts = state.data.valueOrNull?.accounts.orEmpty().filter { !it.closed }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = state.filters.needsCategory, onClick = onToggleNeedsCategory, label = { Text("Needs category") })
        Box {
            val name = state.filters.account?.let { id -> accounts.firstOrNull { it.id == id }?.name }
            FilterChip(selected = name != null, onClick = { menu = true }, label = { Text(name ?: "All accounts") })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("All accounts") }, onClick = { menu = false; onAccount(null) })
                accounts.forEach { a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { menu = false; onAccount(a.id) }) }
            }
        }
        if (state.filters.isActive) TextButton(onClick = onClear) { Text("Clear") }
    }
}
