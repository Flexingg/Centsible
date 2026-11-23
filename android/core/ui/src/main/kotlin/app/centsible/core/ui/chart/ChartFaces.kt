package app.centsible.core.ui.chart

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.domain.ChartKind
import app.centsible.core.domain.ChartSeries
import app.centsible.core.domain.ChartStyle
import app.centsible.core.domain.ChartWidgetConfig
import app.centsible.core.domain.Glanceable
import app.centsible.core.model.Money
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Everything a chart widget or dashboard card shows for one choice: its words (title, the
 * big number, a line under it), the legend, where a tap goes, and how to draw the chart.
 * Widgets, the in-app dashboard, and Settings previews all use this, so they always match.
 */
data class ChartFace(
    val title: String,
    val headline: String,
    val subtitle: String,
    val tone: Tone,
    /** centsible://open/… path a tap opens. */
    val link: String,
    val legend: List<LegendItem> = emptyList(),
    /** The chart is square (ring, donut) and sits beside the legend rather than under the words. */
    val square: Boolean = false,
    /** Null: no data for this chart yet (an older bridge, or nothing spent). */
    val draw: ((ChartArt, Int, Int) -> Bitmap)?,
    val emptyMessage: String? = null,
) {
    enum class Tone { Neutral, Good, Bad }

    /** A legend row; [link] opens it (a category's transactions). */
    data class LegendItem(val label: String, val value: String, val color: Color, val link: String?)
}

object ChartFaces {
    private fun money(cents: Long, cents2: Boolean = false) = MoneyFormat.format(Money(cents), showCents = cents2)
    private fun compact(cents: Long) = MoneyFormat.compact(Money(cents))
    private fun monthShort(ym: String) = runCatching {
        java.time.YearMonth.parse(ym).month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }.getOrDefault(ym)

    private fun categoryLink(id: String?, name: String, ym: String): String? =
        id?.let { "category/$it?name=${URLEncoder.encode(name, "UTF-8")}&month=$ym" }

    fun face(config: ChartWidgetConfig, g: Glanceable, today: LocalDate, art: ChartArt): ChartFace {
        val ym = today.toString().take(7)
        val customTitle = config.title?.trim()?.takeIf { it.isNotEmpty() }
        val customTone = art.accentColor(config.accent)
        val base = when (config.kind) {
            ChartKind.Pace -> {
                val t = g.trend
                if (t == null) return needsBridge(config.kind, customTitle)
                val diff = t.spentThisMonth - t.lastMonthByNow
                val budget = g.budgeted.minor.takeIf { it > 0 && config.showReference }
                val compare = t.lastMonth.takeIf { config.showComparison } ?: emptyList()
                ChartFace(
                    title = customTitle ?: "Spending · ${g.month}",
                    headline = money(t.spentThisMonth),
                    subtitle = when {
                        t.lastMonthByNow == 0L -> "spent so far this month"
                        diff > 0 -> "${money(diff)} more than last month by now"
                        diff < 0 -> "${money(-diff)} less than last month by now"
                        else -> "right on last month's pace"
                    },
                    tone = if (diff > 0) ChartFace.Tone.Bad else if (diff < 0) ChartFace.Tone.Good else ChartFace.Tone.Neutral,
                    link = config.kind.link,
                    draw = { a, w, h ->
                        a.area(
                            w, h, t.monthToDate, t.daysInMonth, compare = compare,
                            reference = budget, referenceLabel = budget?.let { "Budget ${compact(it)}" },
                            xLabels = listOf(0 to "1", t.daysInMonth / 2 - 1 to "${t.daysInMonth / 2}", t.daysInMonth - 1 to "${t.daysInMonth}"),
                            tone = customTone ?: a.palette[0],
                            over = budget != null && t.spentThisMonth > budget,
                        )
                    },
                )
            }
            ChartKind.Week -> {
                val t = g.trend ?: return needsBridge(config.kind, customTitle)
                val allowance = t.weekAllowance
                val perDay = allowance?.takeIf { config.showReference }?.let { it / 7 }
                val left = allowance?.let { it - t.weekTotal }
                val labels = listOf("M", "T", "W", "T", "F", "S", "S")
                ChartFace(
                    title = customTitle ?: "This week",
                    headline = money(t.weekTotal),
                    subtitle = when {
                        left == null -> "last week: ${money(t.lastWeekTotal)}"
                        left < 0 -> "${money(-left)} over this week's ${money(allowance)}"
                        else -> "${money(left)} left of ${money(allowance)}"
                    },
                    tone = if (left != null && left < 0) ChartFace.Tone.Bad else if (left != null) ChartFace.Tone.Good else ChartFace.Tone.Neutral,
                    link = config.kind.link,
                    draw = { a, w, h ->
                        val tone = customTone ?: a.palette[0]
                        if (config.style == ChartStyle.Area) {
                            a.area(
                                w, h, t.week.take(t.weekdayToday + 1), 7,
                                reference = perDay, referenceLabel = perDay?.let { "${compact(it)}/day" },
                                xLabels = listOf(0 to "M", 3 to "T", 6 to "S"),
                                tone = tone,
                                over = left != null && left < 0,
                            )
                        } else {
                            a.bars(
                                w, h, t.week, labels, highlight = t.weekdayToday,
                                future = ((t.weekdayToday + 1)..6).toSet(), reference = perDay, referenceLabel = perDay?.let { "${compact(it)}/day" },
                                valueLabel = compact(t.week.getOrElse(t.weekdayToday) { 0 }),
                                tone = tone,
                            )
                        }
                    },
                )
            }
            ChartKind.Categories -> {
                val filterIds = config.categoryIds.toSet()
                val spent = g.categories.filter { it.spent.minor > 0 && (filterIds.isEmpty() || it.id in filterIds) }
                if (spent.isEmpty()) return empty(config.kind, customTitle ?: "Spending · ${g.month}", "Nothing spent yet this month.")
                val top = spent.take(5)
                val rest = spent.drop(5).sumOf { it.spent.minor }
                val colors = art.palette
                val slices = top.mapIndexed { i, c -> c.spent.minor to colors[i % colors.size] } + (if (rest > 0) listOf(rest to colors.last()) else emptyList())
                val total = spent.sumOf { it.spent.minor }
                ChartFace(
                    title = customTitle ?: "Where it went · ${g.month}",
                    headline = money(total),
                    subtitle = "across ${spent.size} categor${if (spent.size == 1) "y" else "ies"}",
                    tone = ChartFace.Tone.Neutral,
                    link = config.kind.link,
                    square = true,
                    legend = if (!config.showLegend) emptyList() else {
                        top.mapIndexed { i, c -> ChartFace.LegendItem(c.name, compact(c.spent.minor), colors[i % colors.size], categoryLink(c.id, c.name, ym)) } +
                            (if (rest > 0) listOf(ChartFace.LegendItem("Everything else", compact(rest), colors.last(), null)) else emptyList())
                    },
                    draw = { a, w, h -> a.donut(minOf(w, h), slices, compact(total), "spent") },
                )
            }
            ChartKind.Category -> {
                val c = g.categories.firstOrNull { it.id == config.categoryId }
                    ?: return empty(config.kind, customTitle ?: config.categoryName ?: "One category", if (config.categoryId == null) "Pick a category in Settings → Widgets & tiles." else "This category isn't in the budget any more.")
                val budgeted = c.budgeted.minor
                val share = if (budgeted > 0) c.spent.minor.toFloat() / budgeted else if (c.spent.minor > 0) 1.01f else 0f
                val left = c.balance.minor
                val monthShare = today.dayOfMonth.toFloat() / today.lengthOfMonth()
                ChartFace(
                    title = customTitle ?: "${c.name} · ${g.month}",
                    headline = money(left),
                    subtitle = when {
                        left < 0 -> "over by ${money(-left)} · ${money(c.spent.minor)} spent"
                        budgeted <= 0 -> "${money(c.spent.minor)} spent · nothing budgeted"
                        share > monthShare + 0.1f -> "left · spending faster than the month"
                        else -> "left of ${money(budgeted)} · on pace"
                    },
                    tone = if (left < 0 || share > monthShare + 0.1f) ChartFace.Tone.Bad else ChartFace.Tone.Good,
                    link = categoryLink(c.id, c.name, ym) ?: config.kind.link,
                    square = true,
                    legend = if (!config.showLegend) emptyList() else listOf(
                        ChartFace.LegendItem("Spent", money(c.spent.minor), customTone ?: art.palette[0], null),
                        ChartFace.LegendItem("Budgeted", money(budgeted), art.palette[1], null),
                        ChartFace.LegendItem("Month gone", "${(monthShare * 100).toInt()}%", art.palette.last(), null),
                    ),
                    draw = { a, w, h -> a.ring(minOf(w, h), share, "${(share * 100).toInt()}%", "used", tone = customTone ?: a.palette[0]) },
                )
            }
            ChartKind.Monthly -> {
                val slice = g.months.takeLast((config.months ?: 6).coerceIn(2, 12))
                if (slice.isEmpty()) return needsBridge(config.kind, customTitle)
                val rawValues = when (config.series) {
                    ChartSeries.Spending -> slice.map { it.spending.minor }
                    ChartSeries.Income -> slice.map { it.income.minor }
                    ChartSeries.Savings -> slice.map { it.income.minor - it.spending.minor }
                }
                val plotValues = rawValues.map { it.coerceAtLeast(0L) }
                val past = rawValues.dropLast(1).filter { if (config.series == ChartSeries.Savings) true else it > 0 }
                val avg = if (past.isEmpty()) null else past.sum() / past.size
                val refAvg = avg?.takeIf { it > 0 && config.showReference }
                val now = rawValues.last()
                val defaultTitle = when (config.series) {
                    ChartSeries.Spending -> "Monthly spending"
                    ChartSeries.Income -> "Monthly income"
                    ChartSeries.Savings -> "Monthly net savings"
                }
                val headline = if (config.series == ChartSeries.Savings && now != 0L) {
                    (if (now > 0) "+" else "−") + money(kotlin.math.abs(now))
                } else {
                    money(now)
                }
                val subtitle = when (config.series) {
                    ChartSeries.Spending -> avg?.let { if (now <= it) "so far this month · average ${money(it)}" else "${money(now - it)} over the ${past.size}-month average" } ?: "so far this month"
                    ChartSeries.Income -> avg?.let { "average ${money(it)} over ${past.size} months" } ?: "this month"
                    ChartSeries.Savings -> avg?.let { "average ${(if (it >= 0) "+" else "−")}${money(kotlin.math.abs(it))}/mo" } ?: "income minus spending"
                }
                val faceTone = when (config.series) {
                    ChartSeries.Spending -> if (avg != null && now > avg) ChartFace.Tone.Bad else ChartFace.Tone.Neutral
                    ChartSeries.Income -> if (avg != null && now >= avg) ChartFace.Tone.Good else ChartFace.Tone.Neutral
                    ChartSeries.Savings -> if (now >= 0) ChartFace.Tone.Good else ChartFace.Tone.Bad
                }
                val labels = slice.map { monthShort(it.month) }
                val seriesTone = customTone ?: if (config.series == ChartSeries.Spending) art.palette[0] else art.palette[2]
                ChartFace(
                    title = customTitle ?: defaultTitle,
                    headline = headline,
                    subtitle = subtitle,
                    tone = faceTone,
                    link = config.kind.link,
                    draw = { a, w, h ->
                        if (config.style == ChartStyle.Area) {
                            a.area(
                                w, h, plotValues, plotValues.size,
                                reference = refAvg, referenceLabel = refAvg?.let { "avg ${compact(it)}" },
                                xLabels = listOf(0 to labels.first(), plotValues.lastIndex to labels.last()),
                                tone = seriesTone,
                                over = config.series == ChartSeries.Spending && refAvg != null && now > refAvg,
                            )
                        } else {
                            a.bars(
                                w, h, plotValues, labels,
                                highlight = plotValues.size - 1,
                                reference = refAvg, referenceLabel = refAvg?.let { "avg ${compact(it)}" },
                                tone = seriesTone,
                                overOnlyHighlight = config.series == ChartSeries.Spending,
                            )
                        }
                    },
                )
            }
            ChartKind.CashFlow -> {
                val slice = g.months.takeLast((config.months ?: 6).coerceIn(2, 12))
                if (slice.isEmpty()) return needsBridge(config.kind, customTitle)
                val m = slice.last()
                val net = m.income.minor - m.spending.minor
                ChartFace(
                    title = customTitle ?: "Income vs spending",
                    headline = (if (net >= 0) "+" else "−") + money(kotlin.math.abs(net)),
                    subtitle = "${monthShort(m.month)}: ${compact(m.income.minor)} in · ${compact(m.spending.minor)} out",
                    tone = if (net >= 0) ChartFace.Tone.Good else ChartFace.Tone.Bad,
                    link = config.kind.link,
                    legend = if (!config.showLegend) emptyList() else listOf(
                        ChartFace.LegendItem("Income", "", art.palette[2], null),
                        ChartFace.LegendItem("Spending", "", art.palette[0], null),
                    ),
                    draw = { a, w, h -> a.pairedBars(w, h, slice.map { it.income.minor }, slice.map { it.spending.minor }, slice.map { monthShort(it.month) }) },
                )
            }
            ChartKind.NetWorth -> {
                val slice = g.netWorth.takeLast((config.months ?: 12).coerceIn(2, 12))
                if (slice.size < 2) return needsBridge(config.kind, customTitle)
                val values = slice.map { it.value.minor }
                val change = values.last() - values.first()
                val floor = values.min()
                val span = (values.max() - floor).coerceAtLeast(1)
                val shifted = values.map { it - floor + span / 6 }
                val seriesTone = customTone ?: if (change >= 0) art.palette[2] else art.palette[0]
                ChartFace(
                    title = customTitle ?: "Net worth",
                    headline = money(values.last()),
                    subtitle = "${if (change >= 0) "up" else "down"} ${money(kotlin.math.abs(change))} in ${values.size - 1} months",
                    tone = if (change >= 0) ChartFace.Tone.Good else ChartFace.Tone.Bad,
                    link = config.kind.link,
                    draw = { a, w, h ->
                        if (config.style == ChartStyle.Bars) {
                            a.bars(w, h, shifted, slice.map { monthShort(it.month) }, highlight = shifted.lastIndex, tone = seriesTone)
                        } else {
                            a.area(
                                w, h, shifted, shifted.size, tone = seriesTone,
                                xLabels = listOf(0 to monthShort(slice.first().month), shifted.size - 1 to monthShort(slice.last().month)),
                            )
                        }
                    },
                )
            }
            ChartKind.Sankey -> {
                val full = g.sankey ?: return needsBridge(config.kind, customTitle)
                if (full.isEmpty) return empty(config.kind, customTitle ?: "Money flow · ${g.month}", "Nothing came in or went out yet this month.")
                val graph = full.limitDepth(config.depth ?: app.centsible.core.model.MAX_CATEGORY_LEVEL)
                val income = graph.nodes.filter { it.column == 0 && it.kind == app.centsible.core.model.SankeyNode.Kind.Income }.sumOf { it.value }
                val saved = graph.nodes.firstOrNull { it.kind == app.centsible.core.model.SankeyNode.Kind.Saved }?.value
                val drawn = graph.nodes.firstOrNull { it.kind == app.centsible.core.model.SankeyNode.Kind.FromSavings }?.value
                val groups = graph.nodes.filter { it.column == 2 && it.kind == app.centsible.core.model.SankeyNode.Kind.Group }
                val colorOf = art.sankeyColors(graph)
                ChartFace(
                    title = customTitle ?: "Money flow · ${g.month}",
                    headline = money(income),
                    subtitle = when {
                        saved != null -> "came in · ${money(saved)} saved"
                        drawn != null -> "came in · ${money(drawn)} from savings"
                        else -> "came in, all spent"
                    },
                    tone = if (drawn != null) ChartFace.Tone.Bad else ChartFace.Tone.Good,
                    link = config.kind.link,
                    legend = if (!config.showLegend) emptyList() else groups.take(3).map { n ->
                        ChartFace.LegendItem(n.name, compact(n.value), colorOf[n.key] ?: art.palette[0], null)
                    },
                    draw = { a, w, h -> a.sankey(w, h, graph, a.sankeyColors(graph)) { compact(it) } },
                )
            }
        }
        return base
    }

    private fun needsBridge(kind: ChartKind, customTitle: String? = null) =
        empty(kind, customTitle ?: kind.title, "Update Centsible on your server (Home Assistant) to show this chart.")

    private fun empty(kind: ChartKind, title: String, message: String) =
        ChartFace(title = title, headline = "", subtitle = "", tone = ChartFace.Tone.Neutral, link = kind.link, draw = null, emptyMessage = message)
}
