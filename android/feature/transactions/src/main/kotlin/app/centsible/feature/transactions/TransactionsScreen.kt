package app.centsible.feature.transactions

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.key
import app.centsible.core.designsystem.motion.Motion
import app.centsible.core.designsystem.motion.landing
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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
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

/** Swipes, multi-select, undo and the review inbox button. */
data class TransactionsActions(
    val toggleSelected: (TransactionId) -> Unit = {},
    val selectAll: () -> Unit = {},
    val clearSelection: () -> Unit = {},
    val categorize: (List<TransactionId>) -> Unit = {},
    val move: (List<TransactionId>) -> Unit = {},
    val setCleared: (Boolean) -> Unit = {},
    val rerunRules: () -> Unit = {},
    val delete: (List<TransactionId>) -> Unit = {},
    val undoDelete: () -> Unit = {},
    val pickCategory: (app.centsible.core.model.CategoryId?) -> Unit = {},
    val pickAccount: (AccountId) -> Unit = {},
    val dismissPicker: () -> Unit = {},
    val clearScope: () -> Unit = {},
    val openReview: () -> Unit = {},
    val messageShown: () -> Unit = {},
    /** Set when this list was opened from somewhere (a tap-through): shows a back arrow. */
    val back: (() -> Unit)? = null,
)

@Composable
fun TransactionsRoute(
    onOpen: (TransactionId) -> Unit,
    onOpenReview: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
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
        actions = TransactionsActions(
            toggleSelected = viewModel::toggleSelected,
            selectAll = viewModel::selectAll,
            clearSelection = viewModel::clearSelection,
            categorize = viewModel::startCategorizing,
            move = viewModel::startMoving,
            setCleared = viewModel::setCleared,
            rerunRules = viewModel::rerunRules,
            delete = viewModel::delete,
            undoDelete = viewModel::undoDelete,
            pickCategory = viewModel::categorize,
            pickAccount = viewModel::move,
            dismissPicker = viewModel::dismissPicker,
            clearScope = viewModel::clearScope,
            openReview = onOpenReview,
            messageShown = viewModel::messageShown,
            back = onBack,
        ),
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
    actions: TransactionsActions = TransactionsActions(),
) {
    val colors = CentsibleTheme.colors
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
    Column(Modifier.fillMaxSize()) {
        if (state.selecting) {
            SelectionBar(state, actions)
        } else {
            Row(Modifier.fillMaxWidth().padding(start = if (actions.back != null) 4.dp else 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                actions.back?.let { back -> IconButton(onClick = back) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } }
                val scoped = state.filters.scope
                Text(
                    scoped?.title ?: "Transactions",
                    style = if (scoped != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val count = state.reviewCount
                // The inbox button lives on the main list; a tap-through keeps its title room.
                if (count != null && count > 0 && actions.back == null) {
                    androidx.compose.material3.FilledTonalButton(onClick = actions.openReview, modifier = Modifier.semantics { contentDescription = "Review inbox, $count to review" }) {
                        Text("Review · ${if (count >= 500) "500+" else count}")
                    }
                }
            }
        }
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
        FilterRow(state, onToggleNeedsCategory, onAccount, onClearFilters, actions.clearScope)
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
                // Grouping thousands of loaded rows on every recomposition adds up; only redo it when the list changes.
                val hidden = state.pendingDelete?.ids.orEmpty()
                val byDate = androidx.compose.runtime.remember(d.items, hidden) { d.items.filter { it.id !in hidden }.groupBy { it.date } }
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                // Infinite scroll: fetch the next page a few rows before the end.
                androidx.compose.runtime.LaunchedEffect(listState, d.nextCursor) {
                    if (d.nextCursor == null) return@LaunchedEffect
                    androidx.compose.runtime.snapshotFlow {
                        val info = listState.layoutInfo
                        (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
                    }.collect { nearEnd -> if (nearEnd) onLoadMore() }
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    byDate.forEach { (date, txs) ->
                        item(key = date) {
                            // A saved transaction lands in its day; a deleted one's card closes up.
                            Column(Modifier.animateItem()) {
                                Text(dayLabel(date, today), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                                CentsibleCard(Modifier.animateContentSize(tween(Motion.MEDIUM, easing = Motion.EaseInOut)), contentPadding = PaddingValues(0.dp)) {
                                    txs.forEachIndexed { i, t ->
                                        key(t.id.raw) {
                                            Column(if (t.id.raw in d.fresh) Modifier.landing(t.id.raw) else Modifier) {
                                                if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = colors.border)
                                                val canSwipe = state.canEdit && !state.selecting
                                                SwipeActions(
                                                    enabled = canSwipe,
                                                    // Splits and transfers have no single category to set.
                                                    onCategorize = { actions.categorize(listOf(t.id)) }.takeIf { !t.isParent && !t.isTransfer },
                                                    onDelete = { actions.delete(listOf(t.id)) },
                                                ) {
                                                    TransactionRow(
                                                        t, d.categoryNames, d.accountNames,
                                                        onClick = { if (state.selecting) actions.toggleSelected(t.id) else onOpen(t.id) },
                                                        onLongClick = { actions.toggleSelected(t.id) }.takeIf { state.canEdit },
                                                        selected = if (state.selecting) t.id in state.selected else null,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (d.nextCursor != null) {
                        item(key = "more") {
                            TextButton(onClick = onLoadMore, enabled = !d.loadingMore, modifier = Modifier.fillMaxWidth()) {
                                Text(if (d.loadingMore) "Loading…" else "Load more") // shown if an automatic load failed
                            }
                        }
                    }
                }
            }
        }
    }
    // The undo bar for a delete, above the + button.
    androidx.compose.animation.AnimatedVisibility(
        visible = state.pendingDelete != null || state.message != null,
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
    ) {
        val pending = state.pendingDelete
        androidx.compose.material3.Surface(shape = RoundedCornerShape(14.dp), color = colors.textPrimary, contentColor = colors.card, shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(pending?.label ?: state.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
                if (pending != null) {
                    TextButton(onClick = actions.undoDelete) { Text("Undo", color = colors.accent) }
                } else {
                    TextButton(onClick = actions.messageShown) { Text("OK", color = colors.accent) }
                }
            }
        }
    }
    if (state.message != null && state.pendingDelete == null) {
        androidx.compose.runtime.LaunchedEffect(state.message) {
            kotlinx.coroutines.delay(3_500)
            actions.messageShown()
        }
    }
    val d = state.data.valueOrNull
    if (state.categorizing != null && d != null) {
        val current = state.categorizing.singleOrNull()?.let { id -> d.items.firstOrNull { it.id == id }?.categoryId?.raw }
        app.centsible.core.designsystem.component.PickerSheet(
            title = if (state.categorizing.size == 1) "Category" else "Category for ${state.categorizing.size}",
            items = listOf(app.centsible.core.designsystem.component.PickerItem(NO_CATEGORY, "No category")) +
                d.categories.map { app.centsible.core.designsystem.component.PickerItem(it.id.raw, it.name, section = it.group, emoji = true) },
            selectedKey = current,
            onPick = { item -> actions.pickCategory(item.key.takeIf { it != NO_CATEGORY }?.let { app.centsible.core.model.CategoryId(it) }) },
            onDismiss = actions.dismissPicker,
        )
    }
    if (state.moving != null && d != null) {
        app.centsible.core.designsystem.component.PickerSheet(
            title = "Move to account",
            items = d.accounts.filter { !it.closed }.map { app.centsible.core.designsystem.component.PickerItem(it.id.raw, it.name, section = if (it.offBudget) "Tracking" else "Budget") },
            selectedKey = null,
            onPick = { item -> actions.pickAccount(AccountId(item.key)) },
            onDismiss = actions.dismissPicker,
        )
    }
    }
}

private const val NO_CATEGORY = "__none__"

/** Multi-select: what's chosen, and what to do with it. */
@Composable
private fun SelectionBar(state: TransactionsUiState, actions: TransactionsActions) {
    val colors = CentsibleTheme.colors
    val ids = state.selected.toList()
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.clearSelection) { Icon(Icons.Rounded.Close, contentDescription = "Stop selecting") }
        Text("${ids.size} selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { actions.categorize(ids) }) { Icon(androidx.compose.material.icons.Icons.Rounded.Category, contentDescription = "Set category") }
        IconButton(onClick = { actions.delete(ids) }) { Icon(androidx.compose.material.icons.Icons.Rounded.Delete, contentDescription = "Delete", tint = colors.negative) }
        Box {
            IconButton(onClick = { more = true }) { Icon(androidx.compose.material.icons.Icons.Rounded.MoreVert, contentDescription = "More actions") }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text("Select all loaded") }, onClick = { more = false; actions.selectAll() })
                DropdownMenuItem(text = { Text("Move to account…") }, onClick = { more = false; actions.move(ids) })
                DropdownMenuItem(text = { Text("Mark cleared") }, onClick = { more = false; actions.setCleared(true) })
                DropdownMenuItem(text = { Text("Mark uncleared") }, onClick = { more = false; actions.setCleared(false) })
                DropdownMenuItem(text = { Text("Run rules again") }, onClick = { more = false; actions.rerunRules() })
            }
        }
    }
}

/**
 * Swipe right to set a category, left to delete (with undo). The row springs back after
 * a categorize swipe; a delete swipe lets it go.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeActions(enabled: Boolean, onCategorize: (() -> Unit)?, onDelete: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val colors = CentsibleTheme.colors
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> { onCategorize?.invoke(); false }
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
                else -> false
            }
        },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = onCategorize != null,
        backgroundContent = {
            val toDelete = state.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().background(if (toDelete) colors.negative else colors.accent).padding(horizontal = 20.dp),
                contentAlignment = if (toDelete) androidx.compose.ui.Alignment.CenterEnd else androidx.compose.ui.Alignment.CenterStart,
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(
                        if (toDelete) androidx.compose.material.icons.Icons.Rounded.Delete else androidx.compose.material.icons.Icons.Rounded.Category,
                        contentDescription = null,
                        tint = colors.card,
                    )
                    Text(if (toDelete) "  Delete" else "  Category", color = colors.card, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        // Screen readers get the same two actions without swiping.
        modifier = Modifier.semantics {
            customActions = listOfNotNull(
                onCategorize?.let { f -> androidx.compose.ui.semantics.CustomAccessibilityAction("Set category") { f(); true } },
                androidx.compose.ui.semantics.CustomAccessibilityAction("Delete") { onDelete(); true },
            )
        },
    ) { Box(Modifier.background(colors.card)) { content() } }
}

@Composable
private fun FilterRow(state: TransactionsUiState, onToggleNeedsCategory: () -> Unit, onAccount: (AccountId?) -> Unit, onClear: () -> Unit, onClearScope: () -> Unit) {
    val accounts = state.data.valueOrNull?.accounts.orEmpty().filter { !it.closed }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.filters.scope?.let { scope ->
            FilterChip(
                selected = true,
                onClick = onClearScope,
                label = { Text(scope.title) },
                trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove filter", modifier = Modifier.size(16.dp)) },
            )
        }
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
