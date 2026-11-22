package app.centsible.core.ui.chart

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.domain.ChartKind
import app.centsible.core.domain.ChartWidgetConfig
import app.centsible.core.domain.Glanceable
import app.centsible.core.model.Money
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Everything a chart widget shows for one choice: its words (title, the big number, a line
 * under it), the legend, where a tap goes, and how to draw the chart. The widget and its
 * preview in Settings both use this, so they always match.
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
        return when (config.kind) {
            ChartKind.Pace -> {
                val t = g.trend
                if (t == null) return needsBridge(config.kind)
                val diff = t.spentThisMonth - t.lastMonthByNow
                val budget = g.budgeted.minor.takeIf { it > 0 }
                ChartFace(
                    title = "Spending · ${g.month}",
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
                            w, h, t.monthToDate, t.daysInMonth, compare = t.lastMonth,
                            reference = budget, referenceLabel = budget?.let { "Budget ${compact(it)}" },
                            xLabels = listOf(0 to "1", t.daysInMonth / 2 - 1 to "${t.daysInMonth / 2}", t.daysInMonth - 1 to "${t.daysInMonth}"),
                            over = budget != null && t.spentThisMonth > budget,
                        )
                    },
                )
            }
            ChartKind.Week -> {
                val t = g.trend ?: return needsBridge(config.kind)
                val allowance = t.weekAllowance
                val perDay = allowance?.let { it / 7 }
                val left = allowance?.let { it - t.weekTotal }
                ChartFace(
                    title = "This week",
                    headline = money(t.weekTotal),
                    subtitle = when {
                        left == null -> "last week: ${money(t.lastWeekTotal)}"
                        left < 0 -> "${money(-left)} over this week's ${money(allowance)}"
                        else -> "${money(left)} left of ${money(allowance)}"
                    },
                    tone = if (left != null && left < 0) ChartFace.Tone.Bad else if (left != null) ChartFace.Tone.Good else ChartFace.Tone.Neutral,
                    link = config.kind.link,
                    draw = { a, w, h ->
                        a.bars(
                            w, h, t.week, listOf("M", "T", "W", "T", "F", "S", "S"), highlight = t.weekdayToday,
                            future = ((t.weekdayToday + 1)..6).toSet(), reference = perDay, referenceLabel = perDay?.let { "${compact(it)}/day" },
                            valueLabel = compact(t.week.getOrElse(t.weekdayToday) { 0 }),
                        )
                    },
                )
            }
            ChartKind.Categories -> {
                val spent = g.categories.filter { it.spent.minor > 0 }
                if (spent.isEmpty()) return empty(config.kind, "Spending · ${g.month}", "Nothing spent yet this month.")
                val top = spent.take(5)
                val rest = spent.drop(5).sumOf { it.spent.minor }
                val colors = art.palette
                val slices = top.mapIndexed { i, c -> c.spent.minor to colors[i % colors.size] } + (if (rest > 0) listOf(rest to colors.last()) else emptyList())
                val total = spent.sumOf { it.spent.minor }
                ChartFace(
                    title = "Where it went · ${g.month}",
                    headline = money(total),
                    subtitle = "across ${spent.size} categor${if (spent.size == 1) "y" else "ies"}",
                    tone = ChartFace.Tone.Neutral,
                    link = config.kind.link,
                    square = true,
                    legend = top.mapIndexed { i, c -> ChartFace.LegendItem(c.name, compact(c.spent.minor), colors[i % colors.size], categoryLink(c.id, c.name, ym)) } +
                        (if (rest > 0) listOf(ChartFace.LegendItem("Everything else", compact(rest), colors.last(), null)) else emptyList()),
                    draw = { a, w, h -> a.donut(minOf(w, h), slices, compact(total), "spent") },
                )
            }
            ChartKind.Category -> {
                val c = g.categories.firstOrNull { it.id == config.categoryId }
                    ?: return empty(config.kind, config.categoryName ?: "One category", if (config.categoryId == null) "Pick a category in Settings → Widgets & tiles." else "This category isn't in the budget any more.")
                val budgeted = c.budgeted.minor
                val share = if (budgeted > 0) c.spent.minor.toFloat() / budgeted else if (c.spent.minor > 0) 1.01f else 0f
                val left = c.balance.minor
                // How far through the month we are, to judge the pace.
                val monthShare = today.dayOfMonth.toFloat() / today.lengthOfMonth()
                ChartFace(
                    title = "${c.name} · ${g.month}",
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
                    legend = listOf(
                        ChartFace.LegendItem("Spent", money(c.spent.minor), art.palette[0], null),
                        ChartFace.LegendItem("Budgeted", money(budgeted), art.palette[1], null),
                        ChartFace.LegendItem("Month gone", "${(monthShare * 100).toInt()}%", art.palette.last(), null),
                    ),
                    draw = { a, w, h -> a.ring(minOf(w, h), share, "${(share * 100).toInt()}%", "used") },
                )
            }
            ChartKind.Monthly -> {
                if (g.months.isEmpty()) return needsBridge(config.kind)
                val values = g.months.map { it.spending.minor }
                val past = values.dropLast(1).filter { it > 0 }
                val avg = if (past.isEmpty()) null else past.sum() / past.size
                val now = values.last()
                ChartFace(
                    title = "Monthly spending",
                    headline = money(now),
                    subtitle = avg?.let { if (now <= it) "so far this month · average ${money(it)}" else "${money(now - it)} over the ${past.size}-month average" } ?: "so far this month",
                    tone = if (avg != null && now > avg) ChartFace.Tone.Bad else ChartFace.Tone.Neutral,
                    link = config.kind.link,
                    draw = { a, w, h -> a.bars(w, h, values, g.months.map { monthShort(it.month) }, highlight = values.size - 1, reference = avg, referenceLabel = avg?.let { "avg ${compact(it)}" }, overOnlyHighlight = true) },
                )
            }
            ChartKind.CashFlow -> {
                if (g.months.isEmpty()) return needsBridge(config.kind)
                val m = g.months.last()
                val net = m.income.minor - m.spending.minor
                ChartFace(
                    title = "Income vs spending",
                    headline = (if (net >= 0) "+" else "−") + money(kotlin.math.abs(net)),
                    subtitle = "${monthShort(m.month)}: ${compact(m.income.minor)} in · ${compact(m.spending.minor)} out",
                    tone = if (net >= 0) ChartFace.Tone.Good else ChartFace.Tone.Bad,
                    link = config.kind.link,
                    legend = listOf(
                        ChartFace.LegendItem("Income", "", art.palette[2], null),
                        ChartFace.LegendItem("Spending", "", art.palette[0], null),
                    ),
                    draw = { a, w, h -> a.pairedBars(w, h, g.months.map { it.income.minor }, g.months.map { it.spending.minor }, g.months.map { monthShort(it.month) }) },
                )
            }
            ChartKind.NetWorth -> {
                if (g.netWorth.size < 2) return needsBridge(config.kind)
                val values = g.netWorth.map { it.value.minor }
                val change = values.last() - values.first()
                // Shift so the line uses the chart's height even when net worth is far from zero.
                val floor = values.min()
                val span = (values.max() - floor).coerceAtLeast(1)
                val shifted = values.map { it - floor + span / 6 }
                ChartFace(
                    title = "Net worth",
                    headline = money(values.last()),
                    subtitle = "${if (change >= 0) "up" else "down"} ${money(kotlin.math.abs(change))} in ${values.size - 1} months",
                    tone = if (change >= 0) ChartFace.Tone.Good else ChartFace.Tone.Bad,
                    link = config.kind.link,
                    draw = { a, w, h ->
                        a.area(
                            w, h, shifted, shifted.size, tone = if (change >= 0) a.palette[2] else a.palette[0],
                            xLabels = listOf(0 to monthShort(g.netWorth.first().month), shifted.size - 1 to monthShort(g.netWorth.last().month)),
                        )
                    },
                )
            }
        }
    }

    private fun needsBridge(kind: ChartKind) = empty(kind, kind.title, "Update Centsible on your server (Home Assistant) to show this chart.")

    private fun empty(kind: ChartKind, title: String, message: String) =
        ChartFace(title = title, headline = "", subtitle = "", tone = ChartFace.Tone.Neutral, link = kind.link, draw = null, emptyMessage = message)
}
