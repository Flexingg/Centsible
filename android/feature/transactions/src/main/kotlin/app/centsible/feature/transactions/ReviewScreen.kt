package app.centsible.feature.transactions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.AnimatedCheck
import app.centsible.core.designsystem.component.CardShape
import app.centsible.core.designsystem.component.CategoryEmoji
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MerchantAvatar
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.MoneyTone
import app.centsible.core.designsystem.component.PickerItem
import app.centsible.core.designsystem.component.PickerSheet
import app.centsible.core.designsystem.component.dayLabel
import app.centsible.core.designsystem.motion.Motion
import app.centsible.core.designsystem.motion.reducedMotion
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransactionId
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.launch

data class ReviewActions(
    val back: () -> Unit = {},
    val approve: (Transaction) -> Unit = {},
    val later: (Transaction) -> Unit = {},
    val changeCategory: (Transaction) -> Unit = {},
    val setCategory: (CategoryId?) -> Unit = {},
    val dismissPicker: () -> Unit = {},
    val open: (TransactionId) -> Unit = {},
    val askReviewAll: (Boolean) -> Unit = {},
    val reviewAll: () -> Unit = {},
    val retry: () -> Unit = {},
    val messageShown: () -> Unit = {},
    /** Opens a new rule for this merchant (and category, if it has one). */
    val makeRule: (app.centsible.core.model.PayeeId, CategoryId?) -> Unit = { _, _ -> },
    val rulePromptShown: () -> Unit = {},
    val matchTransfers: () -> Unit = {},
    /** Opens (or with null, closes) the sheet to link this card to its other side. */
    val linkTransfer: (Transaction?) -> Unit = {},
    val transferLinked: (Transaction, TransactionId, String) -> Unit = { _, _, _ -> },
)

@Composable
fun ReviewRoute(
    onBack: () -> Unit,
    onOpen: (TransactionId) -> Unit,
    onMakeRule: (app.centsible.core.model.PayeeId, CategoryId?) -> Unit = { _, _ -> },
    onMatchTransfers: () -> Unit = {},
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Coming back from the editor or the rule editor: show what changed there.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.resumed()
        onPauseOrDispose { }
    }
    ReviewScreen(
        state,
        ReviewActions(
            back = onBack,
            approve = viewModel::approve,
            later = viewModel::later,
            changeCategory = viewModel::changeCategory,
            setCategory = viewModel::setCategory,
            dismissPicker = viewModel::dismissPicker,
            open = { viewModel.leaving(); onOpen(it) },
            askReviewAll = viewModel::askReviewAll,
            reviewAll = viewModel::reviewAll,
            retry = { viewModel.load() },
            messageShown = viewModel::messageShown,
            makeRule = { p, c -> viewModel.makingRule(p); onMakeRule(p, c) },
            rulePromptShown = viewModel::rulePromptShown,
            matchTransfers = { viewModel.leaving(); onMatchTransfers() },
            linkTransfer = viewModel::linkTransfer,
            transferLinked = viewModel::transferLinked,
        ),
    )
}

@Composable
fun ReviewScreen(state: ReviewUiState, actions: ReviewActions, today: LocalDate = LocalDate.now()) {
    val colors = CentsibleTheme.colors
    Column(Modifier.fillMaxSize().background(colors.canvas).navigationBarsPadding()) {
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("Review", style = MaterialTheme.typography.headlineMedium)
                val left = state.left
                if (state.data is Loadable.Ready && left > 0) {
                    Text("$left to go", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
            }
            if (state.data is Loadable.Ready) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Match transfers") }, onClick = { menu = false; actions.matchTransfers() })
                        if (state.left > 0) DropdownMenuItem(text = { Text("Mark all reviewed") }, onClick = { menu = false; actions.askReviewAll(true) })
                    }
                }
            }
        }
        when (val data = state.data) {
            Loadable.Loading -> LoadingState()
            is Loadable.Failed -> MessageState("Couldn't load your inbox", data.message, emoji = "🔌", actionLabel = "Try again", onAction = actions.retry)
            is Loadable.Ready -> {
                val d = data.value
                if (d.queue.isEmpty()) {
                    CaughtUp(d.done)
                    return@Column
                }
                // Progress through this visit.
                val all = d.done + state.left
                if (all > 0) {
                    app.centsible.core.designsystem.component.BudgetProgressBar(
                        d.done.toFloat() / all,
                        overspent = false,
                        height = 4.dp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
                    // Up to two cards peek out from behind the top one.
                    d.queue.take(3).asReversed().forEach { t ->
                        val depth = d.queue.indexOf(t)
                        key(t.id.raw) {
                            ReviewCard(t, d, depth, today, actions)
                        }
                    }
                }
                val top = d.queue.first()
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { actions.later(top) }, modifier = Modifier.weight(1f)) { Text("Later") }
                    OutlinedButton(onClick = { actions.changeCategory(top) }, modifier = Modifier.weight(1.2f)) { Text("Category") }
                    Button(onClick = { actions.approve(top) }, modifier = Modifier.weight(1.4f)) { Text("Looks right") }
                }
                Text(
                    "Swipe right when it looks right, left for later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                state.linking?.let { t ->
                    LinkTransferSheet(
                        t,
                        onLinked = { other, account -> actions.transferLinked(t, other.transaction.id, account) },
                        onDismiss = { actions.linkTransfer(null) },
                    )
                }
                state.categorizing?.let { t ->
                    app.centsible.core.ui.CategoryPickerSheet(
                        title = t.payeeName?.let { "Category for $it" } ?: "Category",
                        selected = t.categoryId,
                        onPick = { id -> if (id != null) actions.setCategory(id) },
                        onDismiss = actions.dismissPicker,
                    )
                }
            }
        }
    }
    if (state.confirmAll) {
        AlertDialog(
            onDismissRequest = { actions.askReviewAll(false) },
            title = { Text("Mark everything reviewed?") },
            text = { Text("Your inbox starts fresh from now. This is only for you; others in the household keep their own.") },
            confirmButton = { TextButton(onClick = actions.reviewAll) { Text("Mark all") } },
            dismissButton = { TextButton(onClick = { actions.askReviewAll(false) }) { Text("Cancel") } },
        )
    }
    state.rulePrompt?.let { p ->
        RulePromptBar(p, onMake = { actions.makeRule(p.payeeId, p.categoryId) }, onDismiss = actions.rulePromptShown)
    }
    state.message?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3_000)
            actions.messageShown()
        }
    }
}

/** One transaction as a card: the top one follows your finger and flies off. */
@Composable
private fun ReviewCard(t: Transaction, d: ReviewData, depth: Int, today: LocalDate, actions: ReviewActions) {
    val colors = CentsibleTheme.colors
    val reduced = reducedMotion
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val widthPx = with(LocalDensity.current) { 420.dp.toPx() }
    val threshold = widthPx * 0.3f
    // Cards behind settle forward as the top one leaves.
    val lift = remember { Animatable(depth.toFloat()) }
    LaunchedEffect(depth) { if (reduced) lift.snapTo(depth.toFloat()) else lift.animateTo(depth.toFloat(), spring(dampingRatio = 0.7f)) }
    val isTop = depth == 0
    val category = t.categoryId?.let { d.categoryNames[it.raw] }
    val haptics = app.centsible.core.designsystem.motion.rememberHaptics()
    if (isTop) {
        // A bump when letting go would act.
        val past = abs(offset.value) > threshold
        LaunchedEffect(past) { if (past) haptics.threshold() }
    }
    fun fling(right: Boolean) = scope.launch {
        if (!reduced) offset.animateTo(if (right) widthPx * 1.4f else -widthPx * 1.4f, tween(Motion.SHORT))
        if (right) actions.approve(t) else actions.later(t)
        offset.snapTo(0f)
    }
    Surface(
        shape = CardShape,
        color = colors.card,
        shadowElevation = if (isTop) 6.dp else 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val l = lift.value
                translationY = l * 14.dp.toPx()
                scaleX = 1f - l * 0.05f
                scaleY = 1f - l * 0.05f
                alpha = if (l > 2.5f) 0f else 1f
                translationX = offset.value
                rotationZ = offset.value / 40f
            }
            .then(
                if (isTop) {
                    Modifier
                        .pointerInput(t.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (abs(offset.value) > threshold) fling(offset.value > 0)
                                    else scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.6f)) }
                                },
                                onDragCancel = { scope.launch { offset.animateTo(0f) } },
                            ) { _, dx -> scope.launch { offset.snapTo(offset.value + dx) } }
                        }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Looks right") { actions.approve(t); true },
                                CustomAccessibilityAction("Later") { actions.later(t); true },
                                CustomAccessibilityAction("Change category") { actions.changeCategory(t); true },
                                CustomAccessibilityAction("Open") { actions.open(t.id); true },
                            )
                        }
                } else {
                    // Cards waiting behind are scenery: not focusable, not tappable.
                    Modifier.clearAndSetSemantics { }
                },
            ),
    ) {
        Box {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MerchantAvatar(t.payeeName, size = 64.dp)
                Spacer(Modifier.height(12.dp))
                Text(t.payeeName ?: "No payee", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(
                    listOfNotNull(dayLabel(t.date, today), d.accountNames[t.accountId.raw]).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(16.dp))
                MoneyText(t.amount, tone = MoneyTone.Signed, signed = t.amount.minor > 0, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(16.dp))
                when {
                    t.isParent -> Chip("Split · ${t.subtransactions.size}", colors.cardMuted, colors.textSecondary)
                    t.isTransfer -> Chip("Transfer", colors.cardMuted, colors.textSecondary)
                    category != null -> Chip("${app.centsible.core.designsystem.component.categoryEmoji(category)} $category", colors.cardMuted, colors.textPrimary)
                    else -> Chip("Needs a category", colors.accentSoft, colors.accent)
                }
                t.notes?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                // Kept on the cards behind (invisible) so every card is the same height.
                Row(Modifier.graphicsLayer { alpha = if (isTop) 1f else 0f }) {
                    TextButton(onClick = { actions.open(t.id) }, enabled = isTop) { Text("Edit details") }
                    // So the next one from this merchant sorts itself out.
                    if (t.payeeId != null && !t.isTransfer) {
                        TextButton(onClick = { actions.makeRule(t.payeeId!!, t.categoryId) }, enabled = isTop) { Text("Make rule") }
                    }
                    // A card payment (or any move between accounts): find the other side.
                    if (!t.isTransfer && !t.isParent) {
                        TextButton(onClick = { actions.linkTransfer(t) }, enabled = isTop) { Text("Transfer") }
                    }
                }
            }
            // What letting go will do.
            if (isTop && offset.value != 0f) {
                val right = offset.value > 0
                val strength = (abs(offset.value) / threshold).coerceIn(0f, 1f)
                Text(
                    if (right) "LOOKS RIGHT" else "LATER",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (right) colors.positive else colors.warning,
                    modifier = Modifier
                        .align(if (right) Alignment.TopStart else Alignment.TopEnd)
                        .padding(20.dp)
                        .graphicsLayer { alpha = strength; rotationZ = if (right) -12f else 12f },
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, background: androidx.compose.ui.graphics.Color, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun CaughtUp(done: Int) {
    val colors = CentsibleTheme.colors
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AnimatedCheck(size = 72.dp, description = null, haptic = true)
        Spacer(Modifier.height(20.dp))
        Text("All caught up", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            if (done > 0) "You reviewed $done. New and uncategorized transactions will show up here." else "New and uncategorized transactions will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** "Always put Trader Joe's in Groceries?" after a categorize, for a few seconds. */
@Composable
private fun RulePromptBar(p: RulePrompt, onMake: () -> Unit, onDismiss: () -> Unit) {
    val colors = CentsibleTheme.colors
    LaunchedEffect(p) {
        kotlinx.coroutines.delay(6_000)
        onDismiss()
    }
    Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 132.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = RoundedCornerShape(14.dp), color = colors.textPrimary, contentColor = colors.card, shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Always put ${p.payeeName} in ${p.categoryName ?: "this category"}?",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                TextButton(onClick = onMake) { Text("Make rule", color = colors.accent) }
            }
        }
    }
}

