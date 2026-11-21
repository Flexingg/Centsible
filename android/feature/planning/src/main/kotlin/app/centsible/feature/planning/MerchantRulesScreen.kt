package app.centsible.feature.planning

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.AnimatedCheck
import app.centsible.core.designsystem.component.BudgetProgressBar
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.DialSpinner
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MerchantAvatar
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.component.categoryEmoji
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.CategoryId
import app.centsible.core.model.PayeeId
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransactionId
import app.centsible.core.ui.CategoryPickerSheet

data class MerchantRulesActions(
    val back: () -> Unit = {},
    val setMode: (MatchMode) -> Unit = {},
    val setKeyword: (String) -> Unit = {},
    val setRenameTo: (String) -> Unit = {},
    val togglePreviewExpanded: () -> Unit = {},
    val excludeMerchant: (String) -> Unit = {},
    val addExcludeKeyword: (String) -> Unit = {},
    val removeExcludeKeyword: (String) -> Unit = {},
    val narrowKeywordTo: (String) -> Unit = {},
    val setShowNotesFilter: (Boolean) -> Unit = {},
    val setNotesContains: (String) -> Unit = {},
    val openTransaction: (TransactionId) -> Unit = {},
    val openCategoryPicker: (Boolean) -> Unit = {},
    val setCategory: (CategoryId?) -> Unit = {},
    val setApplyToExisting: (Boolean) -> Unit = {},
    val skip: () -> Unit = {},
    val saveAndNext: () -> Unit = {},
    val customizeRule: (PayeeId, CategoryId?) -> Unit = { _, _ -> },
    val retry: () -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun MerchantRulesRoute(
    onBack: () -> Unit,
    onCustomizeRule: (PayeeId, CategoryId?) -> Unit = { _, _ -> },
    onOpenTransaction: (TransactionId) -> Unit = {},
    viewModel: MerchantRulesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.resumed()
        onPauseOrDispose { }
    }
    MerchantRulesScreen(
        state = state,
        actions = MerchantRulesActions(
            back = onBack,
            setMode = viewModel::setMode,
            setKeyword = viewModel::setKeyword,
            setRenameTo = viewModel::setRenameTo,
            togglePreviewExpanded = viewModel::togglePreviewExpanded,
            excludeMerchant = viewModel::excludeMerchant,
            addExcludeKeyword = viewModel::addExcludeKeyword,
            removeExcludeKeyword = viewModel::removeExcludeKeyword,
            narrowKeywordTo = viewModel::narrowKeywordTo,
            setShowNotesFilter = viewModel::setShowNotesFilter,
            setNotesContains = viewModel::setNotesContains,
            openTransaction = { id ->
                viewModel.leavingForEditor()
                onOpenTransaction(id)
            },
            openCategoryPicker = viewModel::openCategoryPicker,
            setCategory = viewModel::setCategory,
            setApplyToExisting = viewModel::setApplyToExisting,
            skip = viewModel::skip,
            saveAndNext = viewModel::saveAndNext,
            customizeRule = { p, c ->
                viewModel.leavingForEditor()
                onCustomizeRule(p, c)
            },
            retry = { viewModel.load() },
            messageShown = viewModel::messageShown,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantRulesScreen(state: MerchantRulesUiState, actions: MerchantRulesActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    var addingExclude by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            actions.messageShown()
        }
    }

    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = actions.back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Quick rules", style = MaterialTheme.typography.titleLarge)
                        if (state.data is Loadable.Ready && state.remaining > 0) {
                            Text(
                                "${state.remaining} merchant${if (state.remaining == 1) "" else "s"} to go",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
                val d = state.data.valueOrNull
                if (d != null && d.totalInitial > 0 && d.queue.isNotEmpty()) {
                    val processed = d.doneCount + d.skippedCount
                    BudgetProgressBar(
                        progress = processed.toFloat() / d.totalInitial.coerceAtLeast(1),
                        overspent = false,
                        height = 4.dp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
            }
        },
        bottomBar = {
            val card = state.card
            if (state.data is Loadable.Ready && card != null && state.canEdit) {
                val matchCount = card.preview?.matchCount
                val isSplit = card.notesContains.trim().isNotEmpty()
                val primaryLabel = when {
                    state.saving -> "Saving…"
                    isSplit && card.applyToExisting && matchCount != null && matchCount > 0 -> "Save split ($matchCount)"
                    isSplit -> "Save split rule"
                    card.applyToExisting && matchCount != null && matchCount > 0 -> "Save & apply ($matchCount)"
                    card.applyToExisting -> "Save & apply"
                    else -> "Save rule"
                }
                Surface(color = colors.canvas, shadowElevation = 4.dp) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = actions.skip,
                                enabled = !state.saving,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Skip")
                            }
                            Button(
                                onClick = actions.saveAndNext,
                                enabled = card.canSave && !state.saving,
                                modifier = Modifier.weight(1.6f),
                            ) {
                                Text(primaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(
                            onClick = { actions.customizeRule(card.merchant.id, card.categoryId) },
                            enabled = !state.saving,
                        ) {
                            Text("Customize in full rule editor", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState(
                title = "Couldn't load merchants",
                message = d.message,
                actionLabel = "Try again",
                onAction = actions.retry,
                modifier = Modifier.padding(padding),
            )
            is Loadable.Ready -> {
                val data = d.value
                val card = state.card
                if (card == null) {
                    AllDoneState(
                        doneCount = data.doneCount,
                        skippedCount = data.skippedCount,
                        transactionsUpdated = data.transactionsUpdated,
                        onBack = actions.back,
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MerchantCard(
                            card = card,
                            categoryNames = data.categoryNames,
                            accountNames = data.accountNames,
                            enabled = state.canEdit && !state.saving,
                            onAskAddExclude = { addingExclude = true },
                            actions = actions,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    val card = state.card
    if (state.pickingCategory && card != null) {
        val displayName = card.renameTo.trim().ifEmpty { card.merchant.name }
        CategoryPickerSheet(
            title = "Category for $displayName",
            selected = card.categoryId,
            onPick = { id -> actions.setCategory(id) },
            onDismiss = { actions.openCategoryPicker(false) },
        )
    }

    if (addingExclude && card != null) {
        var term by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingExclude = false },
            title = { Text("Exclude a keyword") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Transactions whose merchant contains this word won't match this rule and will stay in your queue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    OutlinedTextField(
                        value = term,
                        onValueChange = { term = it },
                        label = { Text("Doesn't contain") },
                        placeholder = { Text("e.g. STORE or MARKET") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.addExcludeKeyword(term)
                        addingExclude = false
                    },
                    enabled = term.isNotBlank(),
                ) {
                    Text("Exclude")
                }
            },
            dismissButton = {
                TextButton(onClick = { addingExclude = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MerchantCard(
    card: MerchantRuleCardState,
    categoryNames: Map<String, String>,
    accountNames: Map<String, String>,
    enabled: Boolean,
    onAskAddExclude: () -> Unit,
    actions: MerchantRulesActions,
) {
    val colors = CentsibleTheme.colors
    val merchant = card.merchant

    // ── Header & Match rule ──────────────────────────────────────────────
    CentsibleCard(Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MerchantAvatar(merchant.name, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    merchant.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val txCount = merchant.transactionCount
                val matchCount = card.preview?.matchCount
                val subtitle = buildString {
                    append(if (txCount == 1) "1 transaction" else "$txCount transactions")
                    if (card.mode == MatchMode.Keyword && matchCount != null && matchCount > txCount) {
                        append(" · keyword matches $matchCount")
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }

        Spacer(Modifier.height(14.dp))
        StatLabel("Match transactions by")
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = card.mode == MatchMode.Exact,
                onClick = { actions.setMode(MatchMode.Exact) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                enabled = enabled,
            ) {
                Text("Exact merchant", maxLines = 1)
            }
            SegmentedButton(
                selected = card.mode == MatchMode.Keyword,
                onClick = { actions.setMode(MatchMode.Keyword) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                enabled = enabled,
            ) {
                Text("Keyword", maxLines = 1)
            }
        }

        if (card.mode == MatchMode.Keyword) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = card.keyword,
                onValueChange = actions.setKeyword,
                label = { Text("Contains keyword") },
                placeholder = { Text("e.g. TRADER JOE") },
                singleLine = true,
                enabled = enabled,
                trailingIcon = {
                    if (card.keyword.isNotEmpty() && enabled) {
                        IconButton(onClick = { actions.setKeyword("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear keyword")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (card.excludeKeywords.isEmpty() && card.notesContains.isBlank()) {
                    "Matches any transaction whose merchant or bank description contains this text."
                } else {
                    "Matches merchants containing this keyword while applying the filters below."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ── Narrow / Split chips (exclusions & notes filter) ─────────────
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            card.excludeKeywords.forEach { ex ->
                InputChip(
                    selected = true,
                    onClick = { if (enabled) actions.removeExcludeKeyword(ex) },
                    enabled = enabled,
                    label = { Text("Not \"$ex\"") },
                    trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove exclusion $ex") },
                )
            }
            if (enabled) {
                AssistChip(
                    onClick = onAskAddExclude,
                    label = { Text("Exclude word") },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                )
                if (!card.showNotesFilter) {
                    AssistChip(
                        onClick = { actions.setShowNotesFilter(true) },
                        label = { Text("Split by notes") },
                        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    )
                }
            }
        }

        if (card.showNotesFilter) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = card.notesContains,
                onValueChange = actions.setNotesContains,
                label = { Text("Notes contain") },
                placeholder = { Text("e.g. Gym or Subscription") },
                singleLine = true,
                enabled = enabled,
                trailingIcon = {
                    if (enabled) {
                        IconButton(onClick = { actions.setShowNotesFilter(false) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove notes filter")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Split mode: saving creates a rule for just these matches and keeps this merchant open for another rule.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ── Live matched transactions (expandable with details + split) ──
        Spacer(Modifier.height(12.dp))
        PreviewSection(
            card = card,
            categoryNames = categoryNames,
            accountNames = accountNames,
            enabled = enabled,
            actions = actions,
        )
    }

    // ── Category & Optional Rename ───────────────────────────────────────
    CentsibleCard(Modifier.animateContentSize()) {
        StatLabel("Always categorize as")
        Spacer(Modifier.height(8.dp))

        val selectedName = card.categoryId?.let { categoryNames[it.raw] }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (selectedName != null) colors.accentSoft else colors.cardMuted,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { actions.openCategoryPicker(true) },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedName?.let { "${categoryEmoji(it)} $it" } ?: "Choose a category",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selectedName != null) colors.textPrimary else colors.accent,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (selectedName != null) "Change" else "Pick",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accent,
                )
            }
        }

        val suggestedId = card.suggestedCategoryId
        val suggestedName = suggestedId?.let { categoryNames[it.raw] }
        if (suggestedId != null && suggestedName != null && suggestedId != card.categoryId) {
            Spacer(Modifier.height(8.dp))
            AssistChip(
                onClick = { actions.setCategory(suggestedId) },
                enabled = enabled,
                label = { Text("Use ${categoryEmoji(suggestedName)} $suggestedName") },
            )
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = colors.border)
        Spacer(Modifier.height(12.dp))

        StatLabel("Rename merchant (optional)")
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = card.renameTo,
            onValueChange = actions.setRenameTo,
            placeholder = { Text("Keep \"${merchant.name}\"") },
            singleLine = true,
            enabled = enabled,
            trailingIcon = {
                if (card.renameTo.isNotEmpty() && enabled) {
                    IconButton(onClick = { actions.setRenameTo("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear merchant name")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (card.mode == MatchMode.Keyword) {
                "Also cleans up matching bank descriptions to this merchant name."
            } else {
                "Renames this merchant across your budget."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.border)
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Apply to existing transactions", style = MaterialTheme.typography.bodyLarge)
                val count = card.preview?.matchCount
                Text(
                    if (card.applyToExisting) {
                        if (count != null) "Updates $count matching transaction${if (count == 1) "" else "s"} now"
                        else "Updates matching transactions now"
                    } else {
                        "Only applies to future imports"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            Switch(
                checked = card.applyToExisting,
                onCheckedChange = actions.setApplyToExisting,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun PreviewSection(
    card: MerchantRuleCardState,
    categoryNames: Map<String, String>,
    accountNames: Map<String, String>,
    enabled: Boolean,
    actions: MerchantRulesActions,
) {
    val colors = CentsibleTheme.colors
    val p = card.preview

    Row(verticalAlignment = Alignment.CenterVertically) {
        val label = when {
            card.previewError != null -> "Preview unavailable"
            p == null && card.previewing -> "Checking matching transactions…"
            p == null -> "Enter a keyword to preview matches"
            p.matchCount == 0 -> "Matches no existing transactions"
            p.matchCount == 1 -> "Matches 1 transaction"
            else -> "Matches ${p.matchCount} transactions"
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (p != null && p.matchCount > 0) colors.accent else colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (card.previewing) {
            DialSpinner(size = 16.dp)
        } else if (p != null && p.items.isNotEmpty()) {
            TextButton(
                onClick = actions.togglePreviewExpanded,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    if (card.previewExpanded) "Collapse" else "Inspect (${p.matchCount})",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }

    if (p != null && p.items.isNotEmpty()) {
        val shown = if (card.previewExpanded) p.items else p.items.take(3)
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.cardMuted, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shown.forEachIndexed { idx, item ->
                if (idx > 0) HorizontalDivider(color = colors.border)
                MatchedTransactionRow(
                    tx = item.transaction,
                    card = card,
                    categoryNames = categoryNames,
                    accountNames = accountNames,
                    expanded = card.previewExpanded,
                    enabled = enabled,
                    actions = actions,
                )
            }
            if (!card.previewExpanded && p.matchCount > 3) {
                Text(
                    "Show all ${p.matchCount} matched transactions & split options",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.togglePreviewExpanded() }
                        .padding(vertical = 4.dp),
                )
            } else if (card.previewExpanded && p.matchCount > p.items.size) {
                Text(
                    "Showing ${p.items.size} most recent of ${p.matchCount} matches",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchedTransactionRow(
    tx: Transaction,
    card: MerchantRuleCardState,
    categoryNames: Map<String, String>,
    accountNames: Map<String, String>,
    expanded: Boolean,
    enabled: Boolean,
    actions: MerchantRulesActions,
) {
    val colors = CentsibleTheme.colors
    val rowPayee = tx.payeeName ?: card.merchant.name
    val existingCat = tx.categoryId?.let { categoryNames[it.raw] }
    val account = accountNames[tx.accountId.raw]

    Column(
        Modifier
            .fillMaxWidth()
            .then(if (!expanded) Modifier.clickable { actions.togglePreviewExpanded() } else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    rowPayee,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    add(Describe.shortDate(tx.date))
                    if (expanded && account != null) add(account)
                    add(existingCat?.let { "${categoryEmoji(it)} $it" } ?: "Uncategorized")
                }.joinToString(" · ")
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    maxLines = if (expanded) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                MoneyFormat.format(tx.amount),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = colors.textPrimary,
            )
        }

        if (expanded) {
            val notes = tx.notes?.trim().orEmpty()
            if (notes.isNotEmpty()) {
                Text(
                    notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (enabled) {
                val distinctFromCurrent = !rowPayee.equals(card.merchant.name, ignoreCase = true)
                val suggestedRowKeyword = MerchantRuleHelper.suggest(rowPayee).keyword
                val canNarrow = card.mode == MatchMode.Keyword &&
                    suggestedRowKeyword.isNotBlank() &&
                    !suggestedRowKeyword.equals(card.keyword.trim(), ignoreCase = true)

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    if (card.mode == MatchMode.Keyword || distinctFromCurrent) {
                        TextButton(
                            onClick = { actions.excludeMerchant(rowPayee) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("Exclude", style = MaterialTheme.typography.labelSmall, color = colors.warning)
                        }
                    }
                    if (canNarrow) {
                        TextButton(
                            onClick = { actions.narrowKeywordTo(rowPayee) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("Only this", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    TextButton(
                        onClick = { actions.openTransaction(tx.id) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Open", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun AllDoneState(
    doneCount: Int,
    skippedCount: Int,
    transactionsUpdated: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CentsibleTheme.colors
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedCheck(size = 72.dp, description = null, haptic = true)
        Spacer(Modifier.height(20.dp))
        Text(
            if (doneCount > 0) "Queue complete" else "Every merchant has a rule",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        val body = when {
            doneCount > 0 && transactionsUpdated > 0 ->
                "You set up rules covering $doneCount merchant${if (doneCount == 1) "" else "s"} and updated $transactionsUpdated transaction${if (transactionsUpdated == 1) "" else "s"}."
            doneCount > 0 ->
                "You set up rules covering $doneCount merchant${if (doneCount == 1) "" else "s"}."
            skippedCount > 0 ->
                "You skipped $skippedCount merchant${if (skippedCount == 1) "" else "s"}. Come back anytime to set up rules for them."
            else ->
                "All of your active merchants already have auto-categorization rules."
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack) {
            Text("Back to merchants")
        }
    }
}
