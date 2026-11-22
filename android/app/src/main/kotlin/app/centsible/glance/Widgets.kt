package app.centsible.glance

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.compose.ui.graphics.toArgb
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.centsible.MainActivity
import java.time.LocalDate
import app.centsible.core.ui.chart.ChartFaces
import app.centsible.core.ui.chart.ChartFace
import app.centsible.core.ui.chart.ChartArt
import app.centsible.core.domain.ChartWidgetConfig
import app.centsible.core.domain.ChartKind
import androidx.glance.layout.size
import androidx.glance.appwidget.GlanceAppWidgetManager
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.domain.Glanceable
import java.text.DateFormat
import java.util.Date

/** Opens a screen in the app through its own centsible://open link. */
internal fun openIntent(context: Context, path: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("centsible://open/$path"), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** The widget's refresh button: reads the bridge now. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        GlanceRefresher.refreshSoon(context)
    }
}

private val SMALL = DpSize(110.dp, 110.dp)
private val WIDE = DpSize(250.dp, 110.dp)

/**
 * "This month": what's left to budget (or spent so far, for tracking budgets), how much of
 * the budget is spent, and how many transactions wait for review, with Add and Review.
 */
class BudgetWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = context.glanceDeps().store().read()
        provideContent { GlanceTheme { BudgetContent(context, data) } }
    }
}

class BudgetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BudgetWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        GlanceRefresher.schedule(context)
        GlanceRefresher.refreshSoon(context)
    }
}

/** The next bills from recurring schedules, over the coming two weeks. */
class BillsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = context.glanceDeps().store().read()
        provideContent { GlanceTheme { BillsContent(context, data) } }
    }
}

class BillsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BillsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        GlanceRefresher.schedule(context)
        GlanceRefresher.refreshSoon(context)
    }
}

@Composable
private fun Frame(context: Context, open: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(CG.card)
            .padding(16.dp).clickable(actionStartActivity(openIntent(context, open))),
    ) { content() }
}

@Composable
private fun Label(text: String) = Text(text, style = TextStyle(color = CG.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)

@Composable
private fun Big(text: String) = Text(text, style = TextStyle(color = CG.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold), maxLines = 1)

@Composable
private fun Small(text: String) = Text(text, style = TextStyle(color = CG.textSecondary, fontSize = 12.sp), maxLines = 2)

@Composable
private fun Chip(context: Context, text: String, open: String) {
    Text(
        text,
        style = TextStyle(color = CG.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        modifier = GlanceModifier.cornerRadius(14.dp).background(CG.accent).padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(actionStartActivity(openIntent(context, open))),
    )
}

/** Shown before the first refresh, signed out, or with the app lock on. */
@Composable
private fun Placeholder(context: Context, data: Glanceable?) {
    Frame(context, if (data?.locked == true) "budget" else "review") {
        Label("Centsible")
        Spacer(GlanceModifier.height(6.dp))
        Small(
            when {
                data?.locked == true -> "App lock is on, so amounts stay hidden. Tap to open Centsible."
                else -> "Open Centsible once to show your budget here."
            },
        )
    }
}

@Composable
internal fun BudgetContent(context: Context, data: Glanceable?) {
    if (data == null || data.locked) return Placeholder(context, data)
    val wide = LocalSize.current.width >= WIDE.width
    Frame(context, "budget") {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(data.month + (data.budgetName?.let { " · $it" } ?: ""))
            Spacer(GlanceModifier.defaultWeight())
            Text("↻", style = TextStyle(color = CG.textSecondary, fontSize = 14.sp), modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()))
        }
        Spacer(GlanceModifier.height(4.dp))
        if (data.envelope) {
            Big(MoneyFormat.format(data.toBudget, showCents = false))
            Small(if (data.toBudget.isNegative) "over-assigned" else "to budget")
        } else {
            Big(MoneyFormat.format(data.spent, showCents = false))
            Small("spent this month")
        }
        data.spentShare?.let { share ->
            Spacer(GlanceModifier.height(8.dp))
            LinearProgressIndicator(
                progress = share,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = if (share >= 1f) CG.negative else CG.accent,
                backgroundColor = CG.track,
            )
            Spacer(GlanceModifier.height(4.dp))
            Small("${MoneyFormat.format(data.spent, showCents = false)} of ${MoneyFormat.format(data.budgeted, showCents = false)} spent")
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Chip(context, "+ Add", "new-transaction")
            if (wide || data.reviewCount > 0) {
                Spacer(GlanceModifier.width(8.dp))
                Chip(context, if (data.reviewCount > 0) "Review ${data.reviewCount}" else "Review", "review")
            }
        }
    }
}

@Composable
internal fun BillsContent(context: Context, data: Glanceable?) {
    if (data == null || data.locked) return Placeholder(context, data)
    Frame(context, "recurring") {
        Label("Upcoming bills")
        Spacer(GlanceModifier.height(6.dp))
        if (data.bills.isEmpty()) {
            Small("Nothing due in the next two weeks.")
        }
        val rows = (LocalSize.current.height.value / 44).toInt().coerceIn(1, 5)
        data.bills.take(rows).forEach { bill ->
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(bill.title, style = TextStyle(color = CG.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    Small(bill.whenText)
                }
                Text(MoneyFormat.format(bill.amount), style = TextStyle(color = CG.textPrimary, fontSize = 14.sp), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Small("Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(data.updatedAt)))
    }
}

// ── Centsible colours ──

/** The app's palette for widgets, switching with the phone's dark mode (not the wallpaper's colours). */
internal object CG {
    private fun dn(l: androidx.compose.ui.graphics.Color, d: androidx.compose.ui.graphics.Color) = androidx.glance.color.ColorProvider(day = l, night = d)
    private val L = app.centsible.core.designsystem.theme.LightCentsibleColors
    private val D = app.centsible.core.designsystem.theme.DarkCentsibleColors
    val card = dn(L.card, D.card)
    val cardMuted = dn(L.cardMuted, D.cardMuted)
    val textPrimary = dn(L.textPrimary, D.textPrimary)
    val textSecondary = dn(L.textSecondary, D.textSecondary)
    val textTertiary = dn(L.textTertiary, D.textTertiary)
    val accent = dn(L.accent, D.accent)
    val accentSoft = dn(L.accentSoft, D.accentSoft)
    val onAccent = dn(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color(0xFF1A1C20))
    val positive = dn(L.positive, D.positive)
    val negative = dn(L.negative, D.negative)
    val track = dn(L.track, D.track)

    fun palette(context: Context) =
        if (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) D else L
}

// ── The configurable chart widget ──

/**
 * A chart widget: what it shows is chosen per widget (when it's placed, by long-pressing it
 * to reconfigure, or in Settings → Widgets & tiles). [preset] is the choice for a widget
 * that hasn't been configured (the widget picker's "Spending pace" etc.).
 */
class ChartWidget(private val preset: ChartKind = ChartKind.Pace) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = context.glanceDeps()
        val widgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrNull()
        val config = widgetId?.let { deps.widgetSettings().chart(it) } ?: ChartWidgetConfig(preset)
        val data = deps.store().read()
        provideContent { ChartContent(context, data, config, LocalDate.now()) }
    }
}

open class ChartReceiver(preset: ChartKind) : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChartWidget(preset)

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        GlanceRefresher.schedule(context)
        GlanceRefresher.refreshSoon(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val settings = context.glanceDeps().widgetSettings()
        kotlinx.coroutines.runBlocking { appWidgetIds.forEach { settings.removeChart(it) } }
    }
}

/** "Centsible chart": asks which chart when it's placed. */
class ChartWidgetReceiver : ChartReceiver(ChartKind.Pace)
// The first chart widgets, kept so ones already on home screens carry on (and can be reconfigured).
class PaceWidgetReceiver : ChartReceiver(ChartKind.Pace)
class WeekWidgetReceiver : ChartReceiver(ChartKind.Week)
class CategoriesWidgetReceiver : ChartReceiver(ChartKind.Categories)

@Composable
internal fun ChartContent(context: Context, data: Glanceable?, config: ChartWidgetConfig, today: LocalDate) {
    if (data == null || data.locked) return Placeholder(context, data)
    val art = ChartArt(CG.palette(context), context.resources.displayMetrics.density)
    val face = ChartFaces.face(config, data, today, art)
    val size = LocalSize.current
    val density = context.resources.displayMetrics.density
    Frame(context, face.link) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(face.title, style = TextStyle(color = CG.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Text(
                "↻",
                style = TextStyle(color = CG.textTertiary, fontSize = 14.sp),
                modifier = GlanceModifier.padding(start = 8.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }
        val draw = face.draw
        if (draw == null) {
            Spacer(GlanceModifier.height(8.dp))
            Small(face.emptyMessage.orEmpty())
            return@Frame
        }
        val tone = when (face.tone) {
            ChartFace.Tone.Good -> CG.positive
            ChartFace.Tone.Bad -> CG.negative
            ChartFace.Tone.Neutral -> CG.textSecondary
        }
        if (face.square) {
            // Ring or donut beside the numbers and the legend.
            val ringDp = (minOf(size.height.value - 50, size.width.value * 0.45f)).coerceAtLeast(56f)
            Row(GlanceModifier.fillMaxSize().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(draw(art, (ringDp * density).toInt(), (ringDp * density).toInt())),
                    contentDescription = "${face.title}: ${face.headline}",
                    modifier = GlanceModifier.size(ringDp.dp),
                )
                Spacer(GlanceModifier.width(14.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(face.headline, style = TextStyle(color = CG.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text(face.subtitle, style = TextStyle(color = tone, fontSize = 11.sp), maxLines = 2)
                    Spacer(GlanceModifier.height(6.dp))
                    val rows = ((size.height.value - 90) / 20).toInt().coerceIn(0, face.legend.size)
                    face.legend.take(rows).forEach { item -> LegendRow(context, item) }
                }
            }
        } else {
            Text(face.headline, style = TextStyle(color = CG.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text(face.subtitle, style = TextStyle(color = tone, fontSize = 12.sp), maxLines = 1)
            if (face.legend.isNotEmpty() && size.height.value > 150) {
                Row(GlanceModifier.padding(top = 4.dp)) {
                    face.legend.forEach { item ->
                        Text("● ", style = TextStyle(color = androidx.glance.unit.ColorProvider(item.color), fontSize = 10.sp))
                        Text(item.label + "   ", style = TextStyle(color = CG.textSecondary, fontSize = 10.sp))
                    }
                }
            }
            Spacer(GlanceModifier.height(6.dp))
            val reserved = if (face.legend.isNotEmpty() && size.height.value > 150) 86 else 70
            val w = ((size.width.value - 32) * density).toInt().coerceAtLeast(60)
            val h = ((size.height.value - 32 - reserved) * density).toInt().coerceAtLeast(40)
            Image(
                provider = ImageProvider(draw(art, w, h)),
                contentDescription = "${face.title}: ${face.headline}, ${face.subtitle}",
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            )
        }
    }
}

/** A legend row; tapping it opens that category's transactions when it has one. */
@Composable
private fun LegendRow(context: Context, item: ChartFace.LegendItem) {
    val modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp).let { m -> item.link?.let { m.clickable(actionStartActivity(openIntent(context, it))) } ?: m }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("●", style = TextStyle(color = androidx.glance.unit.ColorProvider(item.color), fontSize = 11.sp))
        Spacer(GlanceModifier.width(6.dp))
        Text(item.label, style = TextStyle(color = CG.textPrimary, fontSize = 12.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        if (item.value.isNotEmpty()) Text(item.value, style = TextStyle(color = CG.textSecondary, fontSize = 12.sp), maxLines = 1)
    }
}
