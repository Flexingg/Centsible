package app.centsible.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.centsible.core.domain.ChartAccent
import app.centsible.core.domain.ChartKind
import app.centsible.core.domain.ChartSeries
import app.centsible.core.domain.ChartStyle
import app.centsible.core.domain.ChartWidgetConfig
import app.centsible.core.domain.DEFAULT_TILES
import app.centsible.core.domain.SavedChart
import app.centsible.core.domain.TileConfig
import app.centsible.core.domain.TileMetric
import app.centsible.core.domain.WidgetSettings
import app.centsible.core.domain.WidgetTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val CHART_PREFIX = "widget_chart_"
private val SAVED_CHARTS_KEY = stringPreferencesKey("custom_saved_charts_v1")
private val PENDING_PIN_KEY = stringPreferencesKey("widget_pending_pin_v1")
private val WIDGET_THEME_KEY = stringPreferencesKey("widget_theme_v1")
private fun tileKey(slot: Int) = stringPreferencesKey("tile_config_$slot")

private val codecJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class ChartConfigWire(
    val kind: String,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val title: String? = null,
    val style: String = "auto",
    val accent: String = "default",
    val series: String = "spending",
    val months: Int? = null,
    val showReference: Boolean = true,
    val showComparison: Boolean = true,
    val showLegend: Boolean = true,
    val categoryIds: List<String> = emptyList(),
    val theme: String? = null,
) {
    fun toModel(): ChartWidgetConfig? = ChartKind.of(kind)?.let { k ->
        ChartWidgetConfig(
            kind = k,
            categoryId = categoryId,
            categoryName = categoryName,
            title = title?.takeIf { it.isNotBlank() },
            style = ChartStyle.of(style),
            accent = ChartAccent.of(accent),
            series = ChartSeries.of(series),
            months = months,
            showReference = showReference,
            showComparison = showComparison,
            showLegend = showLegend,
            categoryIds = categoryIds,
            theme = theme?.let(WidgetTheme::of),
        )
    }

    companion object {
        fun from(c: ChartWidgetConfig) = ChartConfigWire(
            kind = c.kind.id,
            categoryId = c.categoryId,
            categoryName = c.categoryName,
            title = c.title?.trim()?.takeIf { it.isNotEmpty() },
            style = c.style.id,
            accent = c.accent.id,
            series = c.series.id,
            months = c.months,
            showReference = c.showReference,
            showComparison = c.showComparison,
            showLegend = c.showLegend,
            categoryIds = c.categoryIds,
            theme = c.theme?.id,
        )
    }
}

@Serializable
private data class SavedChartWire(
    val id: String,
    val config: ChartConfigWire,
    val showOnDashboard: Boolean = true,
)

@Serializable
private data class PendingPinWire(
    val config: ChartConfigWire,
    val createdAt: Long,
)

/** Shared encoder/decoder for [ChartWidgetConfig] (supports both JSON and legacy pipe format). */
object ChartConfigCodec {
    fun encode(config: ChartWidgetConfig): String = codecJson.encodeToString(ChartConfigWire.from(config))

    fun decode(raw: String): ChartWidgetConfig? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { codecJson.decodeFromString<ChartConfigWire>(trimmed).toModel() }.getOrNull()
        }
        val p = trimmed.split('|')
        val kind = ChartKind.of(p.getOrElse(0) { "" }) ?: return null
        return ChartWidgetConfig(kind, p.getOrNull(1)?.ifEmpty { null }, p.getOrNull(2)?.ifEmpty { null })
    }
}

private fun encodeTile(kind: String, id: String?, name: String?) = listOf(kind, id.orEmpty(), name.orEmpty().replace("|", "/")).joinToString("|")
private fun tileParts(raw: String): Triple<String, String?, String?> {
    val p = raw.split('|')
    return Triple(p.getOrElse(0) { "" }, p.getOrNull(1)?.ifEmpty { null }, p.getOrNull(2)?.ifEmpty { null })
}

@Singleton
class DataStoreWidgetSettings @Inject constructor(@ApplicationContext private val context: Context) : WidgetSettings {
    private val store get() = context.settingsDataStore

    private fun chartsOf(prefs: Preferences): Map<Int, ChartWidgetConfig> = prefs.asMap().mapNotNull { (k, v) ->
        val id = k.name.removePrefix(CHART_PREFIX).takeIf { k.name.startsWith(CHART_PREFIX) }?.toIntOrNull() ?: return@mapNotNull null
        val config = ChartConfigCodec.decode(v as? String ?: return@mapNotNull null) ?: return@mapNotNull null
        id to config
    }.toMap()

    private fun savedChartsOf(prefs: Preferences): List<SavedChart> {
        val raw = prefs[SAVED_CHARTS_KEY] ?: return emptyList()
        return runCatching {
            codecJson.decodeFromString<List<SavedChartWire>>(raw).mapNotNull { w ->
                w.config.toModel()?.let { SavedChart(w.id, it, w.showOnDashboard) }
            }
        }.getOrDefault(emptyList())
    }

    override val charts: Flow<Map<Int, ChartWidgetConfig>> = store.data.map(::chartsOf)
    override suspend fun chart(widgetId: Int): ChartWidgetConfig? = chartsOf(store.data.first())[widgetId]
    override suspend fun setChart(widgetId: Int, config: ChartWidgetConfig) {
        store.edit { it[stringPreferencesKey("$CHART_PREFIX$widgetId")] = ChartConfigCodec.encode(config) }
    }
    override suspend fun removeChart(widgetId: Int) {
        store.edit { it.remove(stringPreferencesKey("$CHART_PREFIX$widgetId")) }
    }

    override val savedCharts: Flow<List<SavedChart>> = store.data.map(::savedChartsOf)

    override suspend fun saveCustomChart(chart: SavedChart) {
        store.edit { prefs ->
            val current = savedChartsOf(prefs).toMutableList()
            val idx = current.indexOfFirst { it.id == chart.id }
            if (idx >= 0) current[idx] = chart else current.add(0, chart)
            prefs[SAVED_CHARTS_KEY] = codecJson.encodeToString(current.map { SavedChartWire(it.id, ChartConfigWire.from(it.config), it.showOnDashboard) })
        }
    }

    override suspend fun deleteCustomChart(id: String) {
        store.edit { prefs ->
            val next = savedChartsOf(prefs).filterNot { it.id == id }
            prefs[SAVED_CHARTS_KEY] = codecJson.encodeToString(next.map { SavedChartWire(it.id, ChartConfigWire.from(it.config), it.showOnDashboard) })
        }
    }

    override suspend fun setPendingPin(config: ChartWidgetConfig?) {
        store.edit { prefs ->
            if (config == null) prefs.remove(PENDING_PIN_KEY)
            else prefs[PENDING_PIN_KEY] = codecJson.encodeToString(PendingPinWire(ChartConfigWire.from(config), System.currentTimeMillis()))
        }
    }

    override suspend fun consumePendingPin(): ChartWidgetConfig? {
        var found: ChartWidgetConfig? = null
        store.edit { prefs ->
            val raw = prefs[PENDING_PIN_KEY] ?: return@edit
            prefs.remove(PENDING_PIN_KEY)
            val wire = runCatching { codecJson.decodeFromString<PendingPinWire>(raw) }.getOrNull() ?: return@edit
            // Only honor a pending pin created within the last 2 minutes.
            if (System.currentTimeMillis() - wire.createdAt <= 120_000L) {
                found = wire.config.toModel()
            }
        }
        return found
    }

    override val widgetTheme: Flow<WidgetTheme> = store.data.map { prefs ->
        WidgetTheme.of(prefs[WIDGET_THEME_KEY])
    }
    override suspend fun setWidgetTheme(theme: WidgetTheme) {
        store.edit { it[WIDGET_THEME_KEY] = theme.id }
    }

    override val tiles: Flow<List<TileConfig>> = store.data.map { prefs ->
        DEFAULT_TILES.indices.map { slot ->
            prefs[tileKey(slot)]?.let { raw ->
                val (metric, cid, cname) = tileParts(raw)
                TileMetric.of(metric)?.let { TileConfig(it, cid, cname) }
            } ?: DEFAULT_TILES[slot]
        }
    }
    override suspend fun setTile(slot: Int, config: TileConfig) {
        store.edit { it[tileKey(slot)] = encodeTile(config.metric.id, config.categoryId, config.categoryName) }
    }
}
