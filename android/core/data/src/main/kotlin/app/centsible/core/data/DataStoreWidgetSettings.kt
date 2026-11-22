package app.centsible.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.centsible.core.domain.ChartKind
import app.centsible.core.domain.ChartWidgetConfig
import app.centsible.core.domain.DEFAULT_TILES
import app.centsible.core.domain.TileConfig
import app.centsible.core.domain.TileMetric
import app.centsible.core.domain.WidgetSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val CHART_PREFIX = "widget_chart_"
private fun tileKey(slot: Int) = stringPreferencesKey("tile_config_$slot")

/** Each value is "kind|categoryId|categoryName" (empty parts for none). */
private fun encode(kind: String, id: String?, name: String?) = listOf(kind, id.orEmpty(), name.orEmpty().replace("|", "/")).joinToString("|")
private fun parts(raw: String): Triple<String, String?, String?> {
    val p = raw.split('|')
    return Triple(p.getOrElse(0) { "" }, p.getOrNull(1)?.ifEmpty { null }, p.getOrNull(2)?.ifEmpty { null })
}

@Singleton
class DataStoreWidgetSettings @Inject constructor(@ApplicationContext private val context: Context) : WidgetSettings {
    private val store get() = context.settingsDataStore

    private fun chartsOf(prefs: Preferences): Map<Int, ChartWidgetConfig> = prefs.asMap().mapNotNull { (k, v) ->
        val id = k.name.removePrefix(CHART_PREFIX).takeIf { k.name.startsWith(CHART_PREFIX) }?.toIntOrNull() ?: return@mapNotNull null
        val (kind, cid, cname) = parts(v as? String ?: return@mapNotNull null)
        id to ChartWidgetConfig(ChartKind.of(kind) ?: return@mapNotNull null, cid, cname)
    }.toMap()

    override val charts: Flow<Map<Int, ChartWidgetConfig>> = store.data.map(::chartsOf)
    override suspend fun chart(widgetId: Int): ChartWidgetConfig? = chartsOf(store.data.first())[widgetId]
    override suspend fun setChart(widgetId: Int, config: ChartWidgetConfig) {
        store.edit { it[stringPreferencesKey("$CHART_PREFIX$widgetId")] = encode(config.kind.id, config.categoryId, config.categoryName) }
    }
    override suspend fun removeChart(widgetId: Int) {
        store.edit { it.remove(stringPreferencesKey("$CHART_PREFIX$widgetId")) }
    }

    override val tiles: Flow<List<TileConfig>> = store.data.map { prefs ->
        DEFAULT_TILES.indices.map { slot ->
            prefs[tileKey(slot)]?.let { raw ->
                val (metric, cid, cname) = parts(raw)
                TileMetric.of(metric)?.let { TileConfig(it, cid, cname) }
            } ?: DEFAULT_TILES[slot]
        }
    }
    override suspend fun setTile(slot: Int, config: TileConfig) {
        store.edit { it[tileKey(slot)] = encode(config.metric.id, config.categoryId, config.categoryName) }
    }
}
