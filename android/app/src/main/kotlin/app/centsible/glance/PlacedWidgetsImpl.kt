package app.centsible.glance

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.ChartKind
import app.centsible.core.domain.ChartWidgetConfig
import app.centsible.core.domain.GlanceableStore
import app.centsible.core.domain.PlacedWidgets
import app.centsible.core.domain.WidgetSettings
import app.centsible.feature.settings.ChartPickerContent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch

private val CHART_RECEIVERS = listOf(ChartWidgetReceiver::class.java, PaceWidgetReceiver::class.java, WeekWidgetReceiver::class.java, CategoriesWidgetReceiver::class.java)

@Singleton
class AndroidPlacedWidgets @Inject constructor(
    @ApplicationContext private val context: Context,
    private val refresher: GlanceRefresher,
) : PlacedWidgets {
    override suspend fun chartWidgets(): Map<Int, ChartKind> {
        val manager = AppWidgetManager.getInstance(context)
        return CHART_RECEIVERS.flatMap { r ->
            val preset = when (r) { WeekWidgetReceiver::class.java -> ChartKind.Week; CategoriesWidgetReceiver::class.java -> ChartKind.Categories; else -> ChartKind.Pace }
            manager.getAppWidgetIds(ComponentName(context, r)).map { it to preset }
        }.toMap()
    }

    override fun requestPin(config: ChartWidgetConfig): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        // When the launcher adds it, PinnedChartReceiver stores the choice for its new id.
        val callback = PendingIntent.getBroadcast(
            context, config.hashCode(),
            Intent(context, PinnedChartReceiver::class.java)
                .putExtra(EXTRA_KIND, config.kind.id)
                .putExtra(EXTRA_CATEGORY_ID, config.categoryId)
                .putExtra(EXTRA_CATEGORY_NAME, config.categoryName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return manager.requestPinAppWidget(ComponentName(context, ChartWidgetReceiver::class.java), null, callback)
    }

    override suspend fun redraw() {
        refresher.redraw()
    }

    companion object {
        const val EXTRA_KIND = "app.centsible.chart.kind"
        const val EXTRA_CATEGORY_ID = "app.centsible.chart.category_id"
        const val EXTRA_CATEGORY_NAME = "app.centsible.chart.category_name"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlacedWidgetsModule {
    @Binds abstract fun placed(impl: AndroidPlacedWidgets): PlacedWidgets
}

/** The launcher added a chart widget from Settings: remember which chart it shows. */
class PinnedChartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val kind = ChartKind.of(intent.getStringExtra(AndroidPlacedWidgets.EXTRA_KIND)) ?: return
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val deps = context.glanceDeps()
                deps.widgetSettings().setChart(id, ChartWidgetConfig(kind, intent.getStringExtra(AndroidPlacedWidgets.EXTRA_CATEGORY_ID), intent.getStringExtra(AndroidPlacedWidgets.EXTRA_CATEGORY_NAME)))
                ChartWidget().updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Opens when a "Centsible chart" widget is placed, and when one is long-pressed to
 * reconfigure: pick the chart, with live previews.
 */
@AndroidEntryPoint
class ChartConfigActivity : ComponentActivity() {
    @Inject lateinit var settings: WidgetSettings
    @Inject lateinit var store: GlanceableStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget unplaced (Android's rule for a configuration screen).
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        lifecycleScope.launch {
            val data = store.read()
            val current = settings.chart(widgetId) ?: defaultFor(widgetId)
            setContent {
                CentsibleTheme {
                    ChartPickerContent(
                        data = data,
                        initial = current,
                        onSave = { config ->
                            lifecycleScope.launch {
                                settings.setChart(widgetId, config)
                                ChartWidget().updateAll(this@ChartConfigActivity)
                                GlanceRefresher.schedule(this@ChartConfigActivity)
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }
                        },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    private fun defaultFor(widgetId: Int): ChartWidgetConfig {
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className
        return ChartWidgetConfig(
            when (provider) {
                WeekWidgetReceiver::class.java.name -> ChartKind.Week
                CategoriesWidgetReceiver::class.java.name -> ChartKind.Categories
                else -> ChartKind.Pace
            },
        )
    }
}
