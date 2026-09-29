package app.centsible.feature.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.CategoryAvatar
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.MonthSwitcher
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.motion.staggeredEntrance
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Automation
import app.centsible.core.model.AutomationCategory
import app.centsible.core.model.AutomationSource
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.YearMonth

data class AutomationsActions(
    val back: () -> Unit = {},
    val open: (CategoryId, YearMonth) -> Unit = { _, _ -> },
    val month: (Int) -> Unit = {},
    val run: (Boolean) -> Unit = {},
    val askOverwrite: (Boolean) -> Unit = {},
    val retry: () -> Unit = {},
    val messageShown: () -> Unit = {},
    val problemsShown: () -> Unit = {},
)

@Composable
fun AutomationsRoute(onBack: () -> Unit, onOpen: (CategoryId, YearMonth) -> Unit, viewModel: AutomationsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AutomationsScreen(
        state,
        AutomationsActions(
            back = onBack, open = onOpen, month = viewModel::month, run = viewModel::run, askOverwrite = viewModel::askOverwrite,
            retry = { viewModel.load() }, messageShown = viewModel::messageShown, problemsShown = viewModel::problemsShown,
        ),
    )
}

@Composable
fun AutomationsScreen(state: AutomationsUiState, actions: AutomationsActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Automations", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load automations", d.message, emoji = "⚙️", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> {
                val all = d.value.filter { !it.isIncome && !it.hidden }
                val automated = all.filter { it.automations.isNotEmpty() }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { RunCard(state, automated, onFill = { actions.run(false) }, onOverwrite = { actions.askOverwrite(true) }, onMonth = actions.month) }
                    if (automated.isEmpty()) {
                        item {
                            Text(
                                "Automations budget your categories for you each month: a fixed amount, saving for a date, covering a bill, following your history and more. Pick a category to add one.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    all.groupBy { it.groupName }.forEach { (group, cats) ->
                        item(key = "g-$group") {
                            StatLabel(group, Modifier.padding(start = 4.dp, top = 8.dp))
                        }
                        item(key = "c-$group") {
                            CentsibleCard(contentPadding = PaddingValues(0.dp)) {
                                cats.forEachIndexed { i, c ->
                                    if (i > 0) HorizontalDivider(Modifier.padding(start = 60.dp), color = colors.border)
                                    CategoryRow(c, Modifier.staggeredEntrance(i, key = state.month)) { actions.open(c.categoryId, state.month) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { actions.askOverwrite(false) },
            title = { Text("Overwrite ${monthLabel(state.month)}?") },
            text = { Text("Every category with automations gets exactly what they work out, replacing what's budgeted now. Categories without automations are left alone.") },
            confirmButton = { TextButton(onClick = { actions.run(true) }) { Text("Overwrite") } },
            dismissButton = { TextButton(onClick = { actions.askOverwrite(false) }) { Text("Cancel") } },
        )
    }
    state.problems?.let { p ->
        AlertDialog(
            onDismissRequest = actions.problemsShown,
            title = { Text("Some automations need fixing") },
            text = { Text(p) },
            confirmButton = { TextButton(onClick = actions.problemsShown) { Text("OK") } },
        )
    }
}

@Composable
private fun RunCard(state: AutomationsUiState, automated: List<AutomationCategory>, onFill: () -> Unit, onOverwrite: () -> Unit, onMonth: (Int) -> Unit) {
    val colors = CentsibleTheme.colors
    val total = Money(automated.sumOf { it.projected?.minor ?: 0 })
    CentsibleCard(contentPadding = PaddingValues(20.dp)) {
        MonthSwitcher(state.month, onPrevious = { onMonth(-1) }, onNext = { onMonth(1) }, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        StatLabel("Budgeted by automation")
        MoneyText(total, style = MaterialTheme.typography.displaySmall, showCents = false, animate = true)
        Text(
            if (automated.isEmpty()) "No categories are automated yet" else "across ${automated.size} categor${if (automated.size == 1) "y" else "ies"}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        if (state.canEdit && automated.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onFill, enabled = !state.running, modifier = Modifier.weight(1f)) {
                    Text(if (state.running) "Running…" else "Fill empty")
                }
                OutlinedButton(onClick = onOverwrite, enabled = !state.running, modifier = Modifier.weight(1f)) { Text("Overwrite all") }
            }
        }
    }
}

@Composable
private fun CategoryRow(c: AutomationCategory, modifier: Modifier, onClick: () -> Unit) {
    val colors = CentsibleTheme.colors
    val broken = c.automations.any { it is Automation.Unreadable }
    Row(
        modifier.fillMaxWidth().clickable(onClickLabel = "Edit automations", onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(c.name, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (c.source == AutomationSource.Notes) {
                    Spacer(Modifier.width(6.dp))
                    // Still driven by #template lines in the category's notes.
                    Text(
                        "from notes",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        modifier = Modifier.background(colors.cardMuted, pillShape).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            val line = when {
                broken -> "Needs fixing"
                c.automations.isEmpty() -> "Add an automation"
                else -> c.automations.joinToString(" · ") { it.summary() }
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    broken -> colors.negative
                    c.automations.isEmpty() -> colors.accent
                    else -> colors.textSecondary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        c.projected?.let {
            Spacer(Modifier.width(8.dp))
            Text(MoneyFormat.format(it, showCents = false), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
        }
    }
}

private val pillShape = RoundedCornerShape(50)
