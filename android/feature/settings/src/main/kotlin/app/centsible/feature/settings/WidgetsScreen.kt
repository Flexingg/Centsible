package app.centsible.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.SectionCard
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.ChartKind
import app.centsible.core.domain.ChartWidgetConfig
import app.centsible.core.domain.DEFAULT_TILES
import app.centsible.core.domain.Glanceable
import app.centsible.core.domain.GlanceableStore
import app.centsible.core.domain.PlacedWidgets
import app.centsible.core.domain.TileConfig
import app.centsible.core.domain.TileMetric
import app.centsible.core.domain.TileMetrics
import app.centsible.core.domain.WidgetSettings
import app.centsible.core.ui.chart.ChartArt
import app.centsible.core.ui.chart.ChartFace
import app.centsible.core.ui.chart.ChartFaces
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
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
    private val state = MutableStateFlow(WidgetsUiState())
    val uiState: StateFlow<WidgetsUiState> = state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val data = store.read()
        val chosen = settings.charts.first()
        val placed = runCatching { widgets.chartWidgets() }.getOrDefault(emptyMap())
            .map { (id, preset) -> id to (chosen[id] ?: ChartWidgetConfig(preset)) }
            .sortedBy { it.first }
        state.update { it.copy(data = data, placed = placed, tiles = settings.tiles.first()) }
    }

    fun chooseChart(id: Int?) = state.update { it.copy(choosingChart = id) }
    fun chooseTile(slot: Int?) = state.update { it.copy(choosingTile = slot) }

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
            state.update { it.copy(message = "Widget now shows ${config.kind.title.lowercase()}") }
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
            item {
                SectionCard("Chart widgets", action = "Add", onAction = { chooseChart(WidgetsUiState.NEW) }) {
                    if (state.placed.isEmpty()) {
                        Text("None on your home screen yet. Tap Add, or long-press the home screen → Widgets → Centsible.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                    state.placed.forEachIndexed { i, (id, config) ->
                        if (i > 0) Spacer(Modifier.height(12.dp))
                        Text("Widget ${i + 1} · ${config.kind.title}" + (config.categoryName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        ChartPreview(config, state.data, today, height = 170.dp, onClick = { chooseChart(id) })
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
        ChartPickerSheet(state.data, current, isNew = id == WidgetsUiState.NEW, today = today, onSave = saveChart, onDismiss = { chooseChart(null) })
    }
    state.choosingTile?.let { slot ->
        TilePickerSheet(slot, state.tiles.getOrElse(slot) { DEFAULT_TILES[0] }, state.data, today, onSave = saveTile, onDismiss = { chooseTile(null) })
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
fun ChartPreview(config: ChartWidgetConfig, data: Glanceable?, today: LocalDate, height: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, compact: Boolean = false) {
    val colors = CentsibleTheme.colors
    val density = LocalDensity.current.density
    val art = remember(colors, density) { ChartArt(colors, density) }
    val face = data?.let { ChartFaces.face(config, it, today, art) }
    Column(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(24.dp)).background(colors.card).border(1.dp, colors.border, RoundedCornerShape(24.dp))
            .let { m -> onClick?.let { m.clickable(onClick = it) } ?: m }.padding(16.dp),
    ) {
        if (face == null) {
            Text(config.kind.title, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
            Text("Open the app's Home once to fill the preview.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            return@Column
        }
        Text(face.title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val draw = face.draw
        if (draw == null) {
            Text(face.emptyMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
            return@Column
        }
        val tone = when (face.tone) {
            ChartFace.Tone.Good -> colors.positive
            ChartFace.Tone.Bad -> colors.negative
            ChartFace.Tone.Neutral -> colors.textSecondary
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (face.square) {
                val ring = minOf(maxHeight, maxWidth * 0.45f)
                val ringPx = with(LocalDensity.current) { ring.toPx() }.toInt()
                val bmp = remember(face, ringPx, colors) { draw(art, ringPx, ringPx) }
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Image(bmp.asImageBitmap(), contentDescription = "${face.title}: ${face.headline}", modifier = Modifier.size(ring))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(face.headline, fontSize = if (compact) 16.sp else 22.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1)
                        Text(face.subtitle, style = MaterialTheme.typography.labelSmall, color = tone, maxLines = 2)
                        if (!compact) face.legend.take(4).forEach { item ->
                            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(item.color, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(item.label, style = MaterialTheme.typography.labelSmall, color = colors.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                                Text(item.value, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Text(face.headline, fontSize = if (compact) 16.sp else 22.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1)
                    Text(face.subtitle, style = MaterialTheme.typography.labelSmall, color = tone, maxLines = 1)
                    if (face.legend.isNotEmpty()) {
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            face.legend.forEach { item ->
                                Box(Modifier.size(7.dp).background(item.color, CircleShape))
                                Text(" ${item.label}   ", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        val cw = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceAtLeast(40)
                        val ch = with(LocalDensity.current) { maxHeight.toPx() }.toInt().coerceAtLeast(30)
                        val bmp = remember(face, cw, ch, colors) { draw(art, cw, ch) }
                        Image(bmp.asImageBitmap(), contentDescription = "${face.title}: ${face.headline}, ${face.subtitle}", modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** Picking a chart: every kind with a small live preview; a category for "One category". */
@Composable
fun ChartPickerContent(data: Glanceable?, initial: ChartWidgetConfig, onSave: (ChartWidgetConfig) -> Unit, onCancel: () -> Unit, isNew: Boolean = false, today: LocalDate = LocalDate.now()) {
    val colors = CentsibleTheme.colors
    var kind by remember { mutableStateOf(initial.kind) }
    var category by remember { mutableStateOf(initial.categoryId?.let { it to (initial.categoryName ?: "") }) }
    val categories = data?.categories.orEmpty().filter { it.id != null }
    val config = ChartWidgetConfig(kind, category?.first.takeIf { kind == ChartKind.Category }, category?.second.takeIf { kind == ChartKind.Category })
    Column(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (isNew) "Add a chart" else "Choose a chart", style = MaterialTheme.typography.headlineSmall)
            ChartPreview(config, data, today, height = 180.dp)
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
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = { onSave(config) }, enabled = kind != ChartKind.Category || category != null, modifier = Modifier.weight(1f)) {
                Text(if (isNew) "Add to home screen" else "Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartPickerSheet(data: Glanceable?, current: ChartWidgetConfig, isNew: Boolean, today: LocalDate, onSave: (ChartWidgetConfig) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CentsibleTheme.colors.canvas) {
        ChartPickerContent(data, current, onSave = onSave, onCancel = onDismiss, isNew = isNew, today = today)
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
