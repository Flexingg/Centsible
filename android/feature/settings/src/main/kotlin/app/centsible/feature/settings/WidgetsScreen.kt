package app.centsible.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.SectionCard
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.designsystem.theme.rememberMaterialExpressiveTokens
import app.centsible.core.domain.ChartAccent
import app.centsible.core.domain.ChartKind
import app.centsible.core.domain.ChartSeries
import app.centsible.core.domain.ChartStyle
import app.centsible.core.domain.ChartWidgetConfig
import app.centsible.core.domain.DEFAULT_TILES
import app.centsible.core.domain.Glanceable
import app.centsible.core.domain.GlanceableStore
import app.centsible.core.domain.PlacedWidgets
import app.centsible.core.domain.SavedChart
import app.centsible.core.domain.TileConfig
import app.centsible.core.domain.TileMetric
import app.centsible.core.domain.TileMetrics
import app.centsible.core.domain.WidgetSettings
import app.centsible.core.domain.WidgetTheme
import app.centsible.core.ui.chart.ChartPreviewCard
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WidgetsUiState(
    /** The widgets' copy of the budget (null until the first refresh). */
    val data: Glanceable? = null,
    /** Chart widgets on the home screen, by id, with what each shows. */
    val placed: List<Pair<Int, ChartWidgetConfig>> = emptyList(),
    val tiles: List<TileConfig> = DEFAULT_TILES,
    /** The chart being chosen: a placed widget's id, or [NEW] for one to add. */
    val choosingChart: Int? = null,
    /** The tile slot being chosen. */
    val choosingTile: Int? = null,
    val message: String? = null,
    /** User-created custom charts that can be shown on the Home dashboard and/or pinned as widgets. */
    val savedCharts: List<SavedChart> = emptyList(),
    /** Custom chart currently being created/edited in the chart builder sheet. */
    val editingSavedChart: SavedChart? = null,
    /** Enables the Custom Charts studio section (kept false in legacy screenshot defaults). */
    val showCustomCharts: Boolean = false,
    /** Global visual design system for widgets & custom chart cards. */
    val widgetTheme: WidgetTheme = WidgetTheme.Classic,
) {
    companion object {
        const val NEW = -1
    }
}

@HiltViewModel
class WidgetsViewModel @Inject constructor(
    private val store: GlanceableStore,
    private val settings: WidgetSettings,
    private val widgets: PlacedWidgets,
) : ViewModel() {
    private val state = MutableStateFlow(WidgetsUiState(showCustomCharts = true))
    val uiState: StateFlow<WidgetsUiState> = state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val data = store.read()
        val chosen = settings.charts.first()
        val placed = runCatching { widgets.chartWidgets() }.getOrDefault(emptyMap())
            .map { (id, preset) -> id to (chosen[id] ?: ChartWidgetConfig(preset)) }
            .sortedBy { it.first }
        state.update {
            it.copy(
                data = data,
                placed = placed,
                tiles = settings.tiles.first(),
                savedCharts = settings.savedCharts.first(),
                widgetTheme = settings.widgetTheme.first(),
            )
        }
    }

    fun selectWidgetTheme(theme: WidgetTheme) = viewModelScope.launch {
        settings.setWidgetTheme(theme)
        state.update { it.copy(widgetTheme = theme, message = "Widget design set to ${theme.label}") }
        widgets.redraw()
    }

    fun chooseChart(id: Int?) = state.update { it.copy(choosingChart = id) }
    fun chooseTile(slot: Int?) = state.update { it.copy(choosingTile = slot) }

    fun startNewCustomChart() = state.update {
        it.copy(editingSavedChart = SavedChart(id = UUID.randomUUID().toString(), config = ChartWidgetConfig(ChartKind.Monthly), showOnDashboard = true))
    }

    fun editCustomChart(chart: SavedChart) = state.update { it.copy(editingSavedChart = chart) }
    fun dismissCustomChart() = state.update { it.copy(editingSavedChart = null) }

    fun saveCustomChart(config: ChartWidgetConfig, showOnDashboard: Boolean) = viewModelScope.launch {
        val editing = state.value.editingSavedChart ?: return@launch
        val saved = editing.copy(config = config, showOnDashboard = showOnDashboard)
        settings.saveCustomChart(saved)
        state.update {
            it.copy(
                editingSavedChart = null,
                message = if (showOnDashboard) "Custom chart saved and added to Home" else "Custom chart saved",
            )
        }
        load()
    }

    fun toggleCustomChartDashboard(chart: SavedChart, show: Boolean) = viewModelScope.launch {
        settings.saveCustomChart(chart.copy(showOnDashboard = show))
        load()
    }

    fun deleteCustomChart(chart: SavedChart) = viewModelScope.launch {
        settings.deleteCustomChart(chart.id)
        state.update { it.copy(message = "Custom chart removed") }
        load()
    }

    fun pinCustomChart(chart: SavedChart) {
        val asked = widgets.requestPin(chart.config)
        state.update {
            it.copy(
                message = if (asked) "Your home screen asks where to put it"
                else "Your home screen can't add widgets from apps. Long-press the home screen → Widgets → Centsible instead.",
            )
        }
    }

    fun saveChart(config: ChartWidgetConfig) = viewModelScope.launch {
        val id = state.value.choosingChart ?: return@launch
        state.update { it.copy(choosingChart = null) }
        if (id == WidgetsUiState.NEW) {
            val asked = widgets.requestPin(config)
            state.update {
                it.copy(message = if (asked) "Your home screen asks where to put it" else "Your home screen can't add widgets from apps. Long-press the home screen → Widgets → Centsible instead.")
            }
        } else {
            settings.setChart(id, config)
            widgets.redraw()
            state.update { it.copy(message = "Widget now shows ${(config.title?.takeIf { t -> t.isNotBlank() } ?: config.kind.title).lowercase()}") }
        }
        load()
    }

    fun saveTile(config: TileConfig) = viewModelScope.launch {
        val slot = state.value.choosingTile ?: return@launch
        settings.setTile(slot, config)
        widgets.redraw()
        state.update { it.copy(choosingTile = null, message = "Tile ${slot + 1} shows ${config.metric.title.lowercase()}") }
        load()
    }

    fun messageShown() = state.update { it.copy(message = null) }
}

@Composable
fun WidgetsRoute(onBack: () -> Unit, viewModel: WidgetsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Back from the home screen (a widget may have been added): look again.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { viewModel.load(); onPauseOrDispose { } }
    WidgetsScreen(
        state,
        onBack = onBack,
        chooseChart = viewModel::chooseChart,
        saveChart = viewModel::saveChart,
        chooseTile = viewModel::chooseTile,
        saveTile = viewModel::saveTile,
        messageShown = viewModel::messageShown,
        onNewCustomChart = viewModel::startNewCustomChart,
        onEditCustomChart = viewModel::editCustomChart,
        onDismissCustomChart = viewModel::dismissCustomChart,
        onSaveCustomChart = viewModel::saveCustomChart,
        onToggleCustomChartDashboard = viewModel::toggleCustomChartDashboard,
        onDeleteCustomChart = viewModel::deleteCustomChart,
        onPinCustomChart = viewModel::pinCustomChart,
        onSelectWidgetTheme = viewModel::selectWidgetTheme,
    )
}

@Composable
fun WidgetsScreen(
    state: WidgetsUiState,
    onBack: () -> Unit = {},
    chooseChart: (Int?) -> Unit = {},
    saveChart: (ChartWidgetConfig) -> Unit = {},
    chooseTile: (Int?) -> Unit = {},
    saveTile: (TileConfig) -> Unit = {},
    messageShown: () -> Unit = {},
    onNewCustomChart: () -> Unit = {},
    onEditCustomChart: (SavedChart) -> Unit = {},
    onDismissCustomChart: () -> Unit = {},
    onSaveCustomChart: (ChartWidgetConfig, Boolean) -> Unit = { _, _ -> },
    onToggleCustomChartDashboard: (SavedChart, Boolean) -> Unit = { _, _ -> },
    onDeleteCustomChart: (SavedChart) -> Unit = {},
    onPinCustomChart: (SavedChart) -> Unit = {},
    onSelectWidgetTheme: (WidgetTheme) -> Unit = {},
    today: LocalDate = LocalDate.now(),
) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); messageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Widgets & tiles", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Charts for your home screen and figures for Quick Settings, drawn from the copy of your budget kept on this phone. Tapping a chart opens it in Centsible.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (state.showCustomCharts) {
                item {
                    WidgetDesignCard(
                        selected = state.widgetTheme,
                        onSelect = onSelectWidgetTheme,
                    )
                }
                item {
                    SectionCard("Custom charts", action = "New", onAction = onNewCustomChart) {
                        if (state.savedCharts.isEmpty()) {
                            Text(
                                "Build custom charts with your choice of metric, style, colors, and range — then show them on your Home dashboard, pin them as a widget, or both.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                        state.savedCharts.forEachIndexed { idx, chart ->
                            if (idx > 0) {
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = colors.border)
                                Spacer(Modifier.height(10.dp))
                            }
                            ChartPreview(chart.config, state.data, today, height = 170.dp, onClick = { onEditCustomChart(chart) }, widgetTheme = state.widgetTheme)
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Show on Home dashboard", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = chart.showOnDashboard,
                                    onCheckedChange = { onToggleCustomChartDashboard(chart, it) },
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onPinCustomChart(chart) }) { Text("Pin widget") }
                                TextButton(onClick = { onEditCustomChart(chart) }) { Text("Customize") }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { onDeleteCustomChart(chart) }) { Text("Delete", color = colors.negative) }
                            }
                        }
                    }
                }
            }
            item {
                SectionCard("Chart widgets", action = "Add", onAction = { chooseChart(WidgetsUiState.NEW) }) {
                    if (state.placed.isEmpty()) {
                        Text("None on your home screen yet. Tap Add, or long-press the home screen → Widgets → Centsible.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                    state.placed.forEachIndexed { i, (id, config) ->
                        if (i > 0) Spacer(Modifier.height(12.dp))
                        val label = config.title?.takeIf { it.isNotBlank() } ?: (config.kind.title + (config.categoryName?.let { " · $it" } ?: ""))
                        Text("Widget ${i + 1} · $label", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        ChartPreview(config, state.data, today, height = 170.dp, onClick = { chooseChart(id) }, widgetTheme = state.widgetTheme)
                        TextButton(onClick = { chooseChart(id) }) { Text("Change what it shows") }
                    }
                }
            }
            item {
                SectionCard("Quick Settings tiles") {
                    Text(
                        "Pull the notification shade down twice, tap ✏️, and drag in Centsible's “Budget figure” tiles. Long-press a tile to come back here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    state.tiles.forEachIndexed { slot, config ->
                        Spacer(Modifier.height(10.dp))
                        TileRow(slot, config, state.data, today) { chooseTile(slot) }
                    }
                }
            }
        }
    }
    state.choosingChart?.let { id ->
        val current = state.placed.firstOrNull { it.first == id }?.second ?: ChartWidgetConfig(ChartKind.Pace)
        ChartPickerSheet(
            data = state.data,
            current = current,
            isNew = id == WidgetsUiState.NEW,
            showCustomization = state.showCustomCharts,
            widgetTheme = state.widgetTheme,
            today = today,
            onSave = saveChart,
            onDismiss = { chooseChart(null) },
        )
    }
    state.editingSavedChart?.let { editing ->
        CustomChartBuilderSheet(
            data = state.data,
            initial = editing,
            widgetTheme = state.widgetTheme,
            today = today,
            onSave = onSaveCustomChart,
            onDismiss = onDismissCustomChart,
        )
    }
    state.choosingTile?.let { slot ->
        TilePickerSheet(slot, state.tiles.getOrElse(slot) { DEFAULT_TILES[0] }, state.data, today, onSave = saveTile, onDismiss = { chooseTile(null) })
    }
}

@Composable
private fun WidgetDesignCard(selected: WidgetTheme, onSelect: (WidgetTheme) -> Unit) {
    val colors = CentsibleTheme.colors
    val m3 = rememberMaterialExpressiveTokens(darkTheme = colors.isDark)
    SectionCard("Widget design") {
        Text(
            "Choose how your home-screen widgets and custom dashboard charts look. You can also override individual charts when customizing.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WidgetTheme.entries.forEach { theme ->
                val isSelected = selected == theme
                val cardBg = if (theme == WidgetTheme.MaterialExpressive) m3.surfaceContainer else colors.cardMuted
                val titleColor = if (theme == WidgetTheme.MaterialExpressive) m3.colors.textPrimary else colors.textPrimary
                val descColor = if (theme == WidgetTheme.MaterialExpressive) m3.colors.textSecondary else colors.textSecondary
                val borderColor = if (isSelected) (if (theme == WidgetTheme.MaterialExpressive) m3.primary else colors.accent) else colors.border
                val swatches = if (theme == WidgetTheme.MaterialExpressive) {
                    listOf(m3.primary, m3.tertiary, m3.primaryContainer)
                } else {
                    listOf(colors.accent, colors.series1, colors.positive)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(if (theme == WidgetTheme.MaterialExpressive) 24.dp else 18.dp))
                        .background(cardBg)
                        .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(if (theme == WidgetTheme.MaterialExpressive) 24.dp else 18.dp))
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(theme) }
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        swatches.forEach { swatch ->
                            Box(Modifier.size(12.dp).background(swatch, CircleShape))
                        }
                        Spacer(Modifier.weight(1f))
                        if (isSelected) {
                            Text("Active", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = borderColor)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(theme.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = titleColor, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(theme.description, style = MaterialTheme.typography.labelSmall, color = descColor, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A tile as it looks in the shade: icon, figure name, the amount now. */
@Composable
private fun TileRow(slot: Int, config: TileConfig, data: Glanceable?, today: LocalDate, onClick: () -> Unit) {
    val colors = CentsibleTheme.colors
    val reading = data?.let { TileMetrics.read(config, it, today.dayOfMonth, today.lengthOfMonth()) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (reading?.negative == true) colors.cardMuted else colors.accentSoft).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(if (reading?.negative == true) colors.track else colors.accent, CircleShape), contentAlignment = Alignment.Center) {
            Text("$", color = if (reading?.negative == true) colors.textSecondary else colors.card, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(reading?.title ?: config.metric.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                reading?.amount?.let { MoneyFormat.format(it, showCents = false) } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = if (reading?.negative == true) colors.negative else colors.textSecondary,
            )
        }
        Text("Tile ${slot + 1}", style = MaterialTheme.typography.labelMedium, color = colors.accent)
    }
}

/**
 * A chart as the widget draws it (the same words, numbers and drawing), on a card. In the
 * app's light or dark palette, whichever the app is in.
 */
@Composable
fun ChartPreview(
    config: ChartWidgetConfig,
    data: Glanceable?,
    today: LocalDate,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
    widgetTheme: WidgetTheme = WidgetTheme.Classic,
) {
    ChartPreviewCard(config, data, today, height, modifier, onClick, compact, widgetTheme = widgetTheme)
}

/** Picking and customizing a chart: every kind with a live preview, plus style, color, range and toggles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartPickerContent(
    data: Glanceable?,
    initial: ChartWidgetConfig,
    onSave: (ChartWidgetConfig) -> Unit,
    onCancel: () -> Unit,
    isNew: Boolean = false,
    showCustomization: Boolean = false,
    today: LocalDate = LocalDate.now(),
    extraFooter: (@Composable () -> Unit)? = null,
    saveLabel: String? = null,
    widgetTheme: WidgetTheme = WidgetTheme.Classic,
) {
    val colors = CentsibleTheme.colors
    var kind by remember(initial) { mutableStateOf(initial.kind) }
    var category by remember(initial) { mutableStateOf(initial.categoryId?.let { it to (initial.categoryName ?: "") }) }
    var customTitle by remember(initial) { mutableStateOf(initial.title.orEmpty()) }
    var style by remember(initial) { mutableStateOf(initial.style) }
    var accent by remember(initial) { mutableStateOf(initial.accent) }
    var series by remember(initial) { mutableStateOf(initial.series) }
    var months by remember(initial) { mutableStateOf(initial.months) }
    var showReference by remember(initial) { mutableStateOf(initial.showReference) }
    var showComparison by remember(initial) { mutableStateOf(initial.showComparison) }
    var showLegend by remember(initial) { mutableStateOf(initial.showLegend) }
    var categoryIds by remember(initial) { mutableStateOf(initial.categoryIds) }
    var cardTheme by remember(initial) { mutableStateOf(initial.theme) }

    val categories = data?.categories.orEmpty().filter { it.id != null }
    val config = ChartWidgetConfig(
        kind = kind,
        categoryId = category?.first.takeIf { kind == ChartKind.Category },
        categoryName = category?.second.takeIf { kind == ChartKind.Category },
        title = customTitle.trim().takeIf { it.isNotEmpty() },
        style = style,
        accent = accent,
        series = series,
        months = months,
        showReference = showReference,
        showComparison = showComparison,
        showLegend = showLegend,
        categoryIds = if (kind == ChartKind.Categories) categoryIds else emptyList(),
        theme = cardTheme,
    )
    Column(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (isNew) "Add a chart" else "Choose a chart", style = MaterialTheme.typography.headlineSmall)
            ChartPreview(config, data, today, height = 180.dp, widgetTheme = widgetTheme)
            ChartKind.entries.forEach { k ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (k == kind) colors.accentSoft else colors.card)
                        .selectable(k == kind, role = Role.RadioButton) { kind = k }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = k == kind, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(k.title, style = MaterialTheme.typography.bodyLarge)
                        Text(k.description, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
            }
            if (kind == ChartKind.Category) {
                Text("Which category?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                if (categories.isEmpty()) Text("Open Centsible's Home once so the categories show here.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { c -> FilterChip(selected = category?.first == c.id, onClick = { category = c.id!! to c.name }, label = { Text(c.name) }) }
                }
            }

            if (showCustomization) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.border)
                Text("Customize appearance", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = customTitle,
                    onValueChange = { customTitle = it },
                    label = { Text("Custom title (optional)") },
                    placeholder = { Text(kind.title) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Card design", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = cardTheme == null, onClick = { cardTheme = null }, label = { Text("App default (${widgetTheme.label})") })
                    WidgetTheme.entries.forEach { t ->
                        FilterChip(selected = cardTheme == t, onClick = { cardTheme = t }, label = { Text(t.label) })
                    }
                }

                if (kind == ChartKind.Monthly) {
                    Text("Metric", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChartSeries.entries.forEach { s ->
                            FilterChip(selected = series == s, onClick = { series = s }, label = { Text(s.label) })
                        }
                    }
                }

                if (kind == ChartKind.Monthly || kind == ChartKind.NetWorth || kind == ChartKind.Week) {
                    Text("Chart style", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChartStyle.entries.forEach { st ->
                            FilterChip(selected = style == st, onClick = { style = st }, label = { Text(st.label) })
                        }
                    }
                }

                if (kind == ChartKind.Monthly || kind == ChartKind.CashFlow || kind == ChartKind.NetWorth) {
                    val defaultMonths = if (kind == ChartKind.NetWorth) 12 else 6
                    val activeMonths = months ?: defaultMonths
                    Text("Time range", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(3, 6, 12).forEach { m ->
                            FilterChip(selected = activeMonths == m, onClick = { months = m }, label = { Text("$m months") })
                        }
                    }
                }

                if (kind == ChartKind.Categories && categories.isNotEmpty()) {
                    Text("Filter categories (leave empty for all)", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        categories.forEach { c ->
                            val cid = c.id!!
                            val picked = cid in categoryIds
                            FilterChip(
                                selected = picked,
                                onClick = { categoryIds = if (picked) categoryIds - cid else categoryIds + cid },
                                label = { Text(c.name) },
                            )
                        }
                    }
                }

                if (kind != ChartKind.Categories && kind != ChartKind.CashFlow) {
                    Text("Color accent", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChartAccent.entries.forEach { a ->
                            FilterChip(selected = accent == a, onClick = { accent = a }, label = { Text(a.label) })
                        }
                    }
                }

                if (kind == ChartKind.Pace || kind == ChartKind.Week || kind == ChartKind.Monthly) {
                    ToggleRow("Reference / average line", showReference) { showReference = it }
                }
                if (kind == ChartKind.Pace) {
                    ToggleRow("Compare to last month", showComparison) { showComparison = it }
                }
                if (kind == ChartKind.Categories || kind == ChartKind.Category || kind == ChartKind.CashFlow) {
                    ToggleRow("Show legend", showLegend) { showLegend = it }
                }

                extraFooter?.invoke()
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = { onSave(config) }, enabled = kind != ChartKind.Category || category != null, modifier = Modifier.weight(1f)) {
                Text(saveLabel ?: if (isNew) "Add to home screen" else "Save")
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartPickerSheet(
    data: Glanceable?,
    current: ChartWidgetConfig,
    isNew: Boolean,
    showCustomization: Boolean,
    widgetTheme: WidgetTheme,
    today: LocalDate,
    onSave: (ChartWidgetConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CentsibleTheme.colors.canvas) {
        ChartPickerContent(data, current, onSave = onSave, onCancel = onDismiss, isNew = isNew, showCustomization = showCustomization, today = today, widgetTheme = widgetTheme)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomChartBuilderSheet(
    data: Glanceable?,
    initial: SavedChart,
    widgetTheme: WidgetTheme,
    today: LocalDate,
    onSave: (ChartWidgetConfig, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var showOnDashboard by remember(initial) { mutableStateOf(initial.showOnDashboard) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CentsibleTheme.colors.canvas) {
        ChartPickerContent(
            data = data,
            initial = initial.config,
            onSave = { cfg -> onSave(cfg, showOnDashboard) },
            onCancel = onDismiss,
            isNew = false,
            showCustomization = true,
            today = today,
            saveLabel = "Save chart",
            widgetTheme = widgetTheme,
            extraFooter = {
                ToggleRow("Show on app Home dashboard", showOnDashboard) { showOnDashboard = it }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TilePickerSheet(slot: Int, current: TileConfig, data: Glanceable?, today: LocalDate, onSave: (TileConfig) -> Unit, onDismiss: () -> Unit) {
    val colors = CentsibleTheme.colors
    var metric by remember { mutableStateOf(current.metric) }
    var category by remember { mutableStateOf(current.categoryId?.let { it to (current.categoryName ?: "") }) }
    val categories = data?.categories.orEmpty().filter { it.id != null }
    val config = TileConfig(metric, category?.first.takeIf { metric == TileMetric.Category }, category?.second.takeIf { metric == TileMetric.Category })
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tile ${slot + 1} shows…", style = MaterialTheme.typography.titleLarge)
            TileRow(slot, config, data, today) {}
            TileMetric.entries.forEach { m ->
                val value = data?.let { TileMetrics.read(TileConfig(m, category?.first, category?.second), it, today.dayOfMonth, today.lengthOfMonth()).amount }
                Row(Modifier.fillMaxWidth().selectable(m == metric, role = Role.RadioButton) { metric = m }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = m == metric, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.title, style = MaterialTheme.typography.bodyLarge)
                        Text(m.description, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                    if (m != TileMetric.Category && value != null) Text(MoneyFormat.format(value, showCents = false), style = MaterialTheme.typography.bodyMedium, color = if (value.isNegative) colors.negative else colors.textSecondary)
                }
            }
            if (metric == TileMetric.Category) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { c -> FilterChip(selected = category?.first == c.id, onClick = { category = c.id!! to c.name }, label = { Text(c.name) }) }
                }
            }
            Button(onClick = { onSave(config) }, enabled = metric != TileMetric.Category || category != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
