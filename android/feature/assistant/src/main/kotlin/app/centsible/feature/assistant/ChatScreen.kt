package app.centsible.feature.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.assistant.ChangeStatus
import app.centsible.core.assistant.ChatItem
import app.centsible.core.assistant.LiveBlock
import app.centsible.core.assistant.ProposedChange
import app.centsible.core.assistant.StreamUpdate
import app.centsible.core.assistant.ToolGroup
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.DialSpinner
import app.centsible.core.designsystem.motion.rememberHaptics
import app.centsible.core.designsystem.theme.CentsibleTheme

@Composable
fun AssistantChatRoute(onBack: () -> Unit, onOpenChats: () -> Unit, onOpenSettings: () -> Unit, viewModel: AssistantChatViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AssistantChatScreen(
        state,
        ChatActions(
            send = viewModel::send,
            approve = viewModel::approve,
            decline = viewModel::decline,
            approveAll = viewModel::approveAll,
            declineAll = viewModel::declineAll,
            undo = viewModel::undo,
            stop = viewModel::stop,
            retry = viewModel::retry,
            dismissError = viewModel::dismissError,
            newChat = viewModel::newChat,
            openChats = onOpenChats,
            openSettings = onOpenSettings,
            back = onBack,
        ),
    )
}

/** What a chat row shows: lookups that ran back to back fold into one line. */
private sealed interface Row {
    val key: String

    data class Item(val item: ChatItem) : Row {
        override val key get() = item.key
    }
    data class Work(override val key: String, val steps: List<ChatItem>) : Row
}

private fun rows(items: List<ChatItem>): List<Row> {
    val out = mutableListOf<Row>()
    var run = mutableListOf<ChatItem>()
    fun flush() {
        if (run.isNotEmpty()) out += Row.Work("work-" + run.first().key, run)
        run = mutableListOf()
    }
    items.forEach { i ->
        if (i is ChatItem.Lookup || i is ChatItem.ServerWork || i is ChatItem.Thinking) run += i else {
            flush()
            out += Row.Item(i)
        }
    }
    flush()
    return out
}

val SUGGESTIONS = listOf(
    "How am I doing this month?",
    "Where could I cut back?",
    "Review my subscriptions",
    "Am I on track for my goals?",
    "Plan next month's budget",
    "How fast can I pay off my credit card?",
)

@Composable
fun AssistantChatScreen(state: ChatUiState, actions: ChatActions) {
    val colors = CentsibleTheme.colors
    val listState = rememberLazyListState()
    val display = remember(state.items) { rows(state.items) }
    val liveText = state.live.blocks.sumOf { it.text.length }
    // Follow the conversation as it grows.
    LaunchedEffect(display.size, liveText, state.live.running) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text(state.title ?: "Assistant", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${state.settings.model.label} · " + if (state.settings.writable.isEmpty() || !state.canWrite) "read only" else "can propose changes",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
                IconButton(onClick = actions.newChat) { Icon(Icons.Rounded.EditNote, contentDescription = "New chat") }
                IconButton(onClick = actions.openChats) { Icon(Icons.Rounded.History, contentDescription = "Past chats") }
                IconButton(onClick = actions.openSettings) { Icon(Icons.Rounded.Tune, contentDescription = "Assistant settings") }
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                if (state.proposed > 1 && !state.live.running) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = actions.declineAll, modifier = Modifier.weight(1f)) { Text("Decline all") }
                        Button(onClick = actions.approveAll, modifier = Modifier.weight(1f)) { Text("Approve all ${state.proposed}") }
                    }
                }
                Composer(
                    enabled = state.hasKey == true,
                    running = state.live.running,
                    pending = state.proposed > 0,
                    onSend = actions.send,
                    onStop = actions.stop,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.hasKey == false) {
                item(key = "key") { KeyCard(actions.openSettings) }
            } else if (display.isEmpty() && !state.live.running) {
                item(key = "welcome") { Welcome(state, actions.send) }
            }
            items(display, key = { it.key }) { row ->
                when (row) {
                    is Row.Work -> WorkLine(row.steps)
                    is Row.Item -> when (val i = row.item) {
                        is ChatItem.User -> UserBubble(i.text)
                        is ChatItem.Reply -> Markdown(i.markdown)
                        is ChatItem.Change -> ChangeCard(i.change, busy = state.live.running, actions)
                        else -> Unit
                    }
                }
            }
            if (state.live.running) {
                item(key = "live") { Live(state.live.blocks, state.live.activity) }
            }
            state.live.error?.let { error ->
                item(key = "error") { ErrorCard(error, state.live.retryable, state.live.badKey, actions) }
            }
            if (state.unanswered) {
                item(key = "unanswered") { ErrorCard("This one didn't finish (the app may have closed while it worked).", retryable = true, badKey = false, actions = actions, dismissable = false) }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val colors = CentsibleTheme.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = colors.accentSoft, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

/** "Looked at 3 things ▸": what it read, its reasoning, and code it ran, folded. */
@Composable
private fun WorkLine(steps: List<ChatItem>) {
    val colors = CentsibleTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val lookups = steps.count { it is ChatItem.Lookup || it is ChatItem.ServerWork }
    val failed = steps.any { it is ChatItem.Lookup && it.failed }
    val label = buildString {
        if (steps.any { it is ChatItem.Thinking }) append("Thought it through")
        if (lookups > 0) {
            if (isNotEmpty()) append(" · ")
            append(if (lookups == 1) (steps.first { it !is ChatItem.Thinking }.let { (it as? ChatItem.Lookup)?.label ?: (it as ChatItem.ServerWork).label }) else "Looked at $lookups things")
        }
    }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.clickable(onClickLabel = if (open) "Hide the steps" else "Show the steps") { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Psychology,
                contentDescription = null,
                tint = if (failed) colors.warning else colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
        }
        if (open) {
            Column(Modifier.padding(start = 22.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEach { s ->
                    when (s) {
                        is ChatItem.Thinking -> Text(s.summary, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        is ChatItem.Lookup -> Text(
                            (if (s.failed) "⚠ " else "✓ ") + s.label + (s.detail?.let { "  $it" }.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (s.failed) colors.warning else colors.textTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        is ChatItem.ServerWork -> Text("✓ ${s.label}" + (s.detail?.let { ": ${it.take(160)}" }.orEmpty()), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** The reply as it streams in, and what it's doing right now. */
@Composable
private fun Live(blocks: List<LiveBlock>, activity: String?) {
    val colors = CentsibleTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.filter { it.kind == StreamUpdate.Kind.Text && it.text.isNotBlank() }.forEach { Markdown(it.text) }
        val thinking = blocks.lastOrNull { it.kind == StreamUpdate.Kind.Thinking && it.text.isNotBlank() }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            DialSpinner(size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                activity ?: "Writing…",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
        }
        thinking?.let {
            Text(it.text.trim().lines().lastOrNull { l -> l.isNotBlank() }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun ToolGroup.verb() = when (this) {
    ToolGroup.Transactions -> "Transactions"
    ToolGroup.Budget -> "Budget"
    ToolGroup.Categories -> "Categories"
    ToolGroup.Rules -> "Rules & merchants"
    ToolGroup.Recurring -> "Recurring"
    ToolGroup.Plans -> "Goals & plans"
    ToolGroup.Accounts -> "Accounts"
}

/** A change the assistant wants to make: nothing happens until it's approved. */
@Composable
internal fun ChangeCard(change: ProposedChange, busy: Boolean, actions: ChatActions) {
    val colors = CentsibleTheme.colors
    val haptics = rememberHaptics()
    val group = ToolGroup.of(change.group)
    val tone = when (change.status) {
        ChangeStatus.Proposed, ChangeStatus.Approved -> colors.accent
        ChangeStatus.Done -> colors.positive
        ChangeStatus.Failed -> colors.negative
        ChangeStatus.Declined, ChangeStatus.Undone -> colors.textTertiary
    }
    CentsibleCard(contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().background(tone.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = tone, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when (change.status) {
                    ChangeStatus.Proposed -> "Proposed change" + (group?.let { " · ${it.verb()}" }.orEmpty())
                    ChangeStatus.Approved -> "Making the change…"
                    ChangeStatus.Done -> "Done"
                    ChangeStatus.Declined -> "Declined"
                    ChangeStatus.Failed -> "Didn't work"
                    ChangeStatus.Undone -> "Undone"
                },
                style = MaterialTheme.typography.labelLarge,
                color = tone,
            )
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(change.title, style = MaterialTheme.typography.titleSmall)
            change.details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = colors.textSecondary) }
            if (change.status == ChangeStatus.Failed || change.status == ChangeStatus.Done) {
                change.outcome?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (change.status == ChangeStatus.Failed) colors.negative else colors.textTertiary) }
            }
        }
        when (change.status) {
            ChangeStatus.Proposed -> Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { haptics.tap(); actions.decline(change.toolUseId) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Decline") }
                Button(onClick = { haptics.confirm(); actions.approve(change.toolUseId) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Approve") }
            }
            ChangeStatus.Done -> if (change.undo != null) {
                TextButton(onClick = { actions.undo(change.toolUseId) }, enabled = !busy, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp)) {
                    Icon(Icons.Rounded.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Undo")
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun ErrorCard(message: String, retryable: Boolean, badKey: Boolean, actions: ChatActions, dismissable: Boolean = true) {
    val colors = CentsibleTheme.colors
    CentsibleCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.negative)
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Assertive })
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            if (dismissable) TextButton(onClick = actions.dismissError) { Text("Dismiss") }
            if (badKey) TextButton(onClick = actions.openSettings) { Text("Settings") }
            if (retryable) TextButton(onClick = actions.retry) { Text("Try again") }
        }
    }
}

@Composable
private fun KeyCard(onSettings: () -> Unit) {
    val colors = CentsibleTheme.colors
    CentsibleCard {
        Icon(Icons.Rounded.Key, contentDescription = null, tint = colors.accent)
        Spacer(Modifier.size(8.dp))
        Text("Bring your own Claude key", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(
            "The assistant runs on Claude, using your own Anthropic API key, so you pay Anthropic directly for what it uses and nothing goes through anyone else. Get a key at console.anthropic.com, then add it here. It's kept encrypted on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        Spacer(Modifier.size(12.dp))
        Button(onClick = onSettings) { Text("Add your key") }
    }
}

@Composable
private fun Welcome(state: ChatUiState, onSend: (String) -> Unit) {
    val colors = CentsibleTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).background(colors.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accent)
        }
        Spacer(Modifier.size(12.dp))
        Text("Your financial planner", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(6.dp))
        Text(
            "Ask about anything in your budget: where the money goes, what's coming up, whether you can afford something, how to reach a goal. " +
                if (!state.canWrite || state.settings.writable.isEmpty()) "It can read everything but change nothing (you can allow changes in settings)."
                else "When it suggests a change, you'll see it first and approve it.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.size(16.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            SUGGESTIONS.forEach { s -> AssistChip(onClick = { onSend(s) }, label = { Text(s) }) }
        }
    }
}

@Composable
private fun Composer(enabled: Boolean, running: Boolean, pending: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    val colors = CentsibleTheme.colors
    val haptics = rememberHaptics()
    var text by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().background(colors.canvas).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            text,
            { text = it },
            enabled = enabled,
            placeholder = { Text(if (pending) "Reply (changes not approved are declined)" else "Ask about your money…") },
            modifier = Modifier.weight(1f).heightIn(max = 160.dp),
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        if (running) {
            FilledIconButton(onClick = { haptics.tap(); onStop() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.textPrimary, contentColor = colors.card)) {
                Icon(Icons.Rounded.Stop, contentDescription = "Stop")
            }
        } else {
            FilledIconButton(
                onClick = {
                    haptics.tap()
                    onSend(text.trim())
                    text = ""
                },
                enabled = enabled && text.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.accent, contentColor = colors.card),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
            }
        }
    }
}
