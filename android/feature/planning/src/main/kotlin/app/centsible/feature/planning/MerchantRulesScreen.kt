package app.centsible.feature.planning

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.centsible.core.ui.CategoryPickerSheet

data class MerchantRulesActions(
    val back: () -> Unit = {},
    val setMode: (MatchMode) -> Unit = {},
    val setKeyword: (String) -> Unit = {},
    val setRenameTo: (String) -> Unit = {},
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
    val snackbar = androidx.compose.runtime.remember { SnackbarHostState() }
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
                val primaryLabel = when {
                    state.saving -> "Saving…"
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
                            enabled = state.canEdit && !state.saving,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MerchantCard(
    card: MerchantRuleCardState,
    categoryNames: Map<String, String>,
    enabled: Boolean,
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
                "Matches any transaction whose merchant or bank description contains this text.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ── Live sample transactions ─────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        PreviewSection(card = card, categoryNames = categoryNames)
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
private fun PreviewSection(card: MerchantRuleCardState, categoryNames: Map<String, String>) {
    val colors = CentsibleTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        val label = when {
            card.previewError != null -> "Preview unavailable"
            card.preview == null && card.previewing -> "Checking matching transactions…"
            card.preview == null -> "Enter a keyword to preview matches"
            card.preview.matchCount == 0 -> "Matches no existing transactions"
            card.preview.matchCount == 1 -> "Matches 1 transaction"
            else -> "Matches ${card.preview.matchCount} transactions"
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (card.preview != null && card.preview.matchCount > 0) colors.accent else colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (card.previewing) DialSpinner(size = 16.dp)
    }

    val p = card.preview
    if (p != null && p.items.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.cardMuted, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            p.items.take(3).forEachIndexed { idx, item ->
                if (idx > 0) HorizontalDivider(color = colors.border)
                val t = item.transaction
                val existingCat = t.categoryId?.let { categoryNames[it.raw] }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.payeeName ?: card.merchant.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = buildString {
                            append(Describe.shortDate(t.date))
                            if (existingCat != null) append(" · $existingCat")
                            else append(" · Uncategorized")
                        }
                        Text(
                            meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        MoneyFormat.format(t.amount),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = colors.textPrimary,
                    )
                }
            }
            if (p.matchCount > 3) {
                Text(
                    "…and ${p.matchCount - 3} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
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
