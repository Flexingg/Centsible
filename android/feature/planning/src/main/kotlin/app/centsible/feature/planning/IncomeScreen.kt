package app.centsible.feature.planning

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.BudgetProgressBar
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.component.categoryEmoji
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.extensions.Destination
import app.centsible.core.model.CategoryId
import app.centsible.core.model.DeductionMode
import app.centsible.core.model.IncomeMode
import app.centsible.core.model.IncomeMonth
import app.centsible.core.model.IncomeOverview
import app.centsible.core.model.Money
import app.centsible.core.model.PayStub
import app.centsible.core.model.PayStubSummary
import app.centsible.core.model.YearMonth
import app.centsible.core.ui.CategoryPickerSheet
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

data class IncomeActions(
    val back: () -> Unit = {},
    val setTab: (IncomeTab) -> Unit = {},
    val shiftYear: (Int) -> Unit = {},
    val selectMonth: (Int?) -> Unit = {},
    val setMode: (IncomeMode) -> Unit = {},
    val openCategoryPicker: (Boolean) -> Unit = {},
    val addCategory: (CategoryId) -> Unit = {},
    val removeCategory: (CategoryId) -> Unit = {},
    val startNewStub: () -> Unit = {},
    val editStub: (PayStub) -> Unit = {},
    val dismissStubEditor: () -> Unit = {},
    val updateForm: ((PayStubFormState) -> PayStubFormState) -> Unit = {},
    val saveStub: () -> Unit = {},
    val deleteStub: (String) -> Unit = {},
    val openMonthTransactions: (YearMonth, CategoryId?) -> Unit = { _, _ -> },
    val retry: () -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun IncomeRoute(
    onBack: () -> Unit,
    onOpenTransactions: (Destination.TransactionsFor) -> Unit = {},
    viewModel: IncomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    IncomeScreen(
        state = state,
        actions = IncomeActions(
            back = onBack,
            setTab = viewModel::setTab,
            shiftYear = viewModel::shiftYear,
            selectMonth = viewModel::selectMonth,
            setMode = viewModel::setMode,
            openCategoryPicker = viewModel::openCategoryPicker,
            addCategory = viewModel::addCategory,
            removeCategory = viewModel::removeCategory,
            startNewStub = viewModel::startNewStub,
            editStub = viewModel::editStub,
            dismissStubEditor = viewModel::dismissStubEditor,
            updateForm = viewModel::updateForm,
            saveStub = viewModel::saveStub,
            deleteStub = viewModel::deleteStub,
            openMonthTransactions = { ym, catId ->
                val title = Month.of(ym.month).getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + ym.year
                onOpenTransactions(Destination.TransactionsFor.month(title, ym, categoryId = catId))
            },
            retry = { viewModel.refresh() },
            messageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun IncomeScreen(state: IncomeUiState, actions: IncomeActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
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
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = actions.back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Text("Income & pay stubs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { actions.shiftYear(-1) }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous year")
                    }
                    Text(
                        state.year.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    )
                    IconButton(onClick = { actions.shiftYear(1) }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next year")
                    }
                }
                PrimaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = colors.canvas) {
                    IncomeTab.entries.forEach { t ->
                        Tab(
                            selected = state.tab == t,
                            onClick = { actions.setTab(t) },
                            text = { Text(t.label) },
                            selectedContentColor = colors.accent,
                            unselectedContentColor = colors.textSecondary,
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState(
                title = "Couldn't load income",
                message = d.message,
                emoji = "💵",
                actionLabel = "Try again",
                onAction = actions.retry,
                modifier = Modifier.padding(padding),
            )
            is Loadable.Ready -> {
                val overview = d.value.overview
                val categoryNames = d.value.categoryNames
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (state.tab) {
                        IncomeTab.Overview -> {
                            item { TrackingSourceCard(overview, categoryNames, state.canEdit, actions) }
                            item { YearlyProjectionCard(overview) }
                            item { MonthlyIncomeChartCard(overview, state.selectedMonth, actions) }
                            item { MonthlyBreakdownCard(overview, actions) }
                        }
                        IncomeTab.PayStubs -> {
                            if (state.canEdit) {
                                item {
                                    Button(onClick = actions.startNewStub, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Rounded.Add, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Add pay stub")
                                    }
                                }
                            }
                            if (overview.template.deductions.isNotEmpty() || overview.template.hourlyRate != null) {
                                item { LearnedTemplateCard(overview) }
                            }
                            if (overview.payStubSummary.stubCount > 0) {
                                item { PayStubYtdCard(overview.payStubSummary, overview.year) }
                            }
                            item { PayStubListCard(overview.payStubs, state.canEdit, actions) }
                        }
                    }
                }
            }
        }
    }

    if (state.pickingCategory) {
        val currentIds = state.data.valueOrNull?.overview?.categoryIds.orEmpty().toSet()
        CategoryPickerSheet(
            title = "Track income category",
            selected = null,
            exclude = currentIds,
            onPick = { id -> if (id != null) actions.addCategory(id) else actions.openCategoryPicker(false) },
            onDismiss = { actions.openCategoryPicker(false) },
        )
    }

    state.editingStub?.let { form ->
        PayStubEditorSheet(
            form = form,
            saving = state.saving,
            onUpdate = actions.updateForm,
            onSave = actions.saveStub,
            onDelete = if (state.canEdit) actions.deleteStub else null,
            onDismiss = actions.dismissStubEditor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TrackingSourceCard(
    overview: IncomeOverview,
    categoryNames: Map<String, String>,
    canEdit: Boolean,
    actions: IncomeActions,
) {
    val colors = CentsibleTheme.colors
    CentsibleCard(Modifier.animateContentSize()) {
        StatLabel("Track income from")
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = overview.mode == IncomeMode.All,
                onClick = { actions.setMode(IncomeMode.All) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                enabled = canEdit,
            ) { Text("All money in", maxLines = 1) }
            SegmentedButton(
                selected = overview.mode == IncomeMode.Categories,
                onClick = { actions.setMode(IncomeMode.Categories) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                enabled = canEdit,
            ) { Text("Specific categories", maxLines = 1) }
        }
        if (overview.mode == IncomeMode.Categories) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                overview.categoryIds.forEach { id ->
                    val name = categoryNames[id.raw] ?: "Category"
                    InputChip(
                        selected = true,
                        onClick = { if (canEdit) actions.removeCategory(id) },
                        enabled = canEdit,
                        label = { Text("${categoryEmoji(name)} $name") },
                        trailingIcon = if (canEdit) {
                            { Icon(Icons.Rounded.Close, contentDescription = "Remove $name") }
                        } else null,
                    )
                }
                if (canEdit) {
                    AssistChip(
                        onClick = { actions.openCategoryPicker(true) },
                        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        label = { Text("Add category") },
                    )
                }
            }
        }
    }
}

@Composable
private fun YearlyProjectionCard(overview: IncomeOverview) {
    val colors = CentsibleTheme.colors
    val s = overview.summary
    CentsibleCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                StatLabel("${overview.year} total")
                Spacer(Modifier.height(4.dp))
                Text(
                    MoneyFormat.format(s.ytdTotal),
                    style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.positive,
                )
            }
            if (s.remainingMonths > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    StatLabel("Projected year")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        MoneyFormat.format(s.projectedAnnual),
                        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                        color = colors.accent,
                    )
                    Text(
                        "${s.remainingMonths} mo left",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
        }

        if (s.prevYearTotal.minor > 0) {
            val compareTarget = if (s.remainingMonths > 0) s.projectedAnnual else s.ytdTotal
            val diff = compareTarget.minor - s.prevYearTotal.minor
            val pct = diff.toDouble() / s.prevYearTotal.minor * 100.0
            val sign = if (diff >= 0) "+" else ""
            Spacer(Modifier.height(6.dp))
            Text(
                "${if (s.remainingMonths > 0) "Projected" else "Total"} vs ${overview.year - 1} (${MoneyFormat.format(s.prevYearTotal)}): $sign${IncomeCalc.formatPercent(pct)}%",
                style = MaterialTheme.typography.bodySmall,
                color = if (diff >= 0) colors.positive else colors.warning,
            )
        }

        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IncomeMetricTile("Monthly avg", MoneyFormat.format(s.monthlyAverage), Modifier.weight(1f))
                IncomeMetricTile("Variation", "${IncomeCalc.formatPercent(s.variationPercent)}%", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val highText = s.maxMonth?.let { "${shortMonth(it.month)} · ${MoneyFormat.format(it.amount, showCents = false)}" } ?: "—"
                val lowText = s.minMonth?.let { "${shortMonth(it.month)} · ${MoneyFormat.format(it.amount, showCents = false)}" } ?: "—"
                IncomeMetricTile("Highest month", highText, Modifier.weight(1f))
                IncomeMetricTile("Lowest month", lowText, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MonthlyIncomeChartCard(overview: IncomeOverview, selectedMonth: Int?, actions: IncomeActions) {
    val colors = CentsibleTheme.colors
    val months = overview.months
    val avg = overview.summary.monthlyAverage.minor.toFloat()
    val maxVal = maxOf(months.maxOfOrNull { it.amount.minor } ?: 0L, overview.summary.monthlyAverage.minor, 1L).toFloat()

    CentsibleCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatLabel("Monthly income", Modifier.weight(1f))
            val sel = selectedMonth?.let { months.getOrNull(it) }
            if (sel != null) {
                val deltaText = sel.deltaPercent?.let { d ->
                    val sign = if (d >= 0) "+" else ""
                    " ($sign${IncomeCalc.formatPercent(d)}%)"
                }.orEmpty()
                Text(
                    "${shortMonth(sel.month)}: ${MoneyFormat.format(sel.amount)}$deltaText",
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.accent,
                )
            } else {
                Text("Tap a month", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
            }
        }
        Spacer(Modifier.height(12.dp))

        val barColor = colors.positive
        val barMuted = colors.positive.copy(alpha = 0.35f)
        val selectedColor = colors.accent
        val avgLineColor = colors.textTertiary.copy(alpha = 0.6f)

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .pointerInput(months.size) {
                    detectTapGestures { pos ->
                        val band = size.width / months.size.coerceAtLeast(1)
                        val idx = (pos.x / band).toInt().coerceIn(0, months.lastIndex)
                        actions.selectMonth(idx)
                    }
                },
        ) {
            val band = size.width / months.size.coerceAtLeast(1)
            val barWidth = (band * 0.58f).coerceAtMost(24.dp.toPx())
            val chartH = size.height - 4.dp.toPx()

            if (avg > 0f) {
                val avgY = chartH * (1f - (avg / maxVal).coerceIn(0f, 1f))
                drawLine(
                    color = avgLineColor,
                    start = Offset(0f, avgY),
                    end = Offset(size.width, avgY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            }

            months.forEachIndexed { idx, m ->
                val frac = (m.amount.minor.toFloat() / maxVal).coerceIn(0f, 1f)
                val h = (chartH * frac).coerceAtLeast(if (m.amount.minor > 0) 3.dp.toPx() else 0f)
                val x = idx * band + (band - barWidth) / 2f
                val y = chartH - h
                val color = when {
                    selectedMonth == idx -> selectedColor
                    selectedMonth != null -> barMuted
                    else -> barColor
                }
                if (h > 0f) {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            months.forEachIndexed { idx, m ->
                Text(
                    shortMonth(m.month).take(1),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selectedMonth == idx) colors.accent else colors.textTertiary,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MonthlyBreakdownCard(overview: IncomeOverview, actions: IncomeActions) {
    val colors = CentsibleTheme.colors
    val singleCat = overview.categoryIds.singleOrNull().takeIf { overview.mode == IncomeMode.Categories }
    val shown = overview.months.filter { it.amount.minor != 0L || it.count > 0 }.asReversed()
    CentsibleCard {
        StatLabel("Month-by-month")
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Text(
                "No income transactions recorded in ${overview.year} for this filter.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        } else {
            shown.forEachIndexed { idx, m ->
                if (idx > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.border)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { actions.openMonthTransactions(m.month, singleCat) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(fullMonth(m.month), style = MaterialTheme.typography.bodyLarge)
                        val meta = buildString {
                            append(if (m.count == 1) "1 deposit" else "${m.count} deposits")
                            m.deltaPercent?.let { d ->
                                val sign = if (d >= 0) "+" else ""
                                append(" · $sign${IncomeCalc.formatPercent(d)}% vs prior")
                            }
                        }
                        Text(meta, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                    }
                    Text(
                        MoneyFormat.format(m.amount),
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        color = colors.positive,
                    )
                }
            }
        }
    }
}

@Composable
private fun LearnedTemplateCard(overview: IncomeOverview) {
    val colors = CentsibleTheme.colors
    val tpl = overview.template
    CentsibleCard {
        StatLabel("Auto-fill paycheck template")
        Spacer(Modifier.height(4.dp))
        val rateLine = tpl.hourlyRate?.let { "${MoneyFormat.format(it)}/hr" }
        val dedSummary = tpl.deductions.take(4).joinToString(", ") { d ->
            if (d.mode == DeductionMode.Percent) "${d.name} ${IncomeCalc.formatPercent(d.rate)}%"
            else "${d.name} ${MoneyFormat.format(d.amount, showCents = false)}"
        }
        val body = buildString {
            if (rateLine != null) append("Learned $rateLine")
            if (rateLine != null && dedSummary.isNotEmpty()) append(" · ")
            if (dedSummary.isNotEmpty()) append(dedSummary)
            if (tpl.deductions.size > 4) append(" +${tpl.deductions.size - 4} more")
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Text(
            "Next time you add a pay stub, just enter hours worked and fixed / % deductions fill in automatically.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PayStubYtdCard(s: PayStubSummary, year: Int) {
    val colors = CentsibleTheme.colors
    CentsibleCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatLabel("$year pay stubs (${s.stubCount})", Modifier.weight(1f))
            if (s.totalHours > 0) {
                Text(
                    "${IncomeCalc.formatPercent(s.totalHours)} hrs",
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IncomeMetricTile("Gross pay", MoneyFormat.format(s.totalGross), Modifier.weight(1f))
            IncomeMetricTile("Net take-home", "${MoneyFormat.format(s.totalNet)} (${IncomeCalc.formatPercent(s.takeHomeRate)}%)", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IncomeMetricTile("401(k) / Retirement", MoneyFormat.format(s.byKind.retirement), Modifier.weight(1f))
            IncomeMetricTile("Taxes (${IncomeCalc.formatPercent(s.effectiveTaxRate)}%)", MoneyFormat.format(s.byKind.tax), Modifier.weight(1f))
        }
        if (s.byKind.benefit.minor > 0 || s.byKind.other.minor > 0) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IncomeMetricTile("Benefits", MoneyFormat.format(s.byKind.benefit), Modifier.weight(1f))
                IncomeMetricTile("Other", MoneyFormat.format(s.byKind.other), Modifier.weight(1f))
            }
        }

        if (s.byDeduction.isNotEmpty() && s.totalGross.minor > 0) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = colors.border)
            Spacer(Modifier.height(10.dp))
            StatLabel("Deduction breakdown")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                s.byDeduction.forEach { d ->
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${MoneyFormat.format(d.total)} (${IncomeCalc.formatPercent(d.avgPercentOfGross)}%)",
                                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                color = colors.textSecondary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        BudgetProgressBar(
                            progress = (d.total.minor.toFloat() / s.totalGross.minor.toFloat()).coerceIn(0f, 1f),
                            overspent = false,
                            height = 5.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PayStubListCard(stubs: List<PayStub>, canEdit: Boolean, actions: IncomeActions) {
    val colors = CentsibleTheme.colors
    CentsibleCard {
        StatLabel("Entered pay stubs")
        Spacer(Modifier.height(8.dp))
        if (stubs.isEmpty()) {
            Text(
                "No pay stubs yet. Add a couple of pay stubs with hours, gross pay, and deductions (401(k), taxes, health) — Centsible will learn your fixed vs % deductions automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        } else {
            stubs.forEachIndexed { idx, stub ->
                if (idx > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = colors.border)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canEdit) { actions.editStub(stub) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stub.employer ?: "Pay stub",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = buildList {
                            add(Describe.shortDate(stub.date))
                            stub.hours?.let { add("${IncomeCalc.formatPercent(it)} hrs") }
                            val dedTotal = Money(stub.gross.minor - stub.net.minor)
                            if (dedTotal.minor > 0) add("-${MoneyFormat.format(dedTotal)} deducted")
                        }.joinToString(" · ")
                        Text(meta, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            MoneyFormat.format(stub.net),
                            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                            color = colors.positive,
                        )
                        Text(
                            "Gross ${MoneyFormat.format(stub.gross)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = colors.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IncomeMetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = CentsibleTheme.colors
    Column(
        modifier
            .background(colors.cardMuted, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun shortMonth(ym: YearMonth): String = Month.of(ym.month).getDisplayName(TextStyle.SHORT, Locale.getDefault())
private fun fullMonth(ym: YearMonth): String = Month.of(ym.month).getDisplayName(TextStyle.FULL, Locale.getDefault())
