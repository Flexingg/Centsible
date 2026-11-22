package app.centsible.core.domain

import app.centsible.core.model.Money
import kotlinx.coroutines.flow.Flow

/** What a chart widget shows. [link] is the app screen a tap opens (centsible://open/…). */
enum class ChartKind(val id: String, val title: String, val description: String, val link: String) {
    Pace("pace", "Spending pace", "This month's spending day by day, against last month's", "trends"),
    Week("week", "This week", "Monday to Sunday, against your daily allowance", "transactions"),
    Categories("categories", "Where it went", "This month's spending by category, as a ring", "budget"),
    Category("category", "One category", "A category's budget: spent, left, and its pace", "budget"),
    Monthly("monthly", "Monthly spending", "The last six months of spending, with the average", "trends"),
    CashFlow("cashflow", "Income vs spending", "Money in and out for the last six months", "trends"),
    NetWorth("networth", "Net worth", "The last twelve months", "net-worth"),
    ;

    companion object {
        fun of(id: String?): ChartKind? = entries.firstOrNull { it.id == id }
    }
}

/** One chart widget's choice. [categoryId]/[categoryName] are for [ChartKind.Category]. */
data class ChartWidgetConfig(val kind: ChartKind, val categoryId: String? = null, val categoryName: String? = null)

/** What a configurable Quick Settings tile shows. */
enum class TileMetric(val id: String, val title: String, val description: String) {
    LeftToday("today", "Left today", "What you can spend today and stay on budget this month"),
    LeftMonth("month", "Left this month", "What's left in your budget's spending categories"),
    LeftWeek("week", "Left this week", "Your weekly allowance minus this week's spending"),
    SpentToday("spent-today", "Spent today", "Everything spent so far today"),
    ToBudget("to-budget", "To budget", "Money not given a job yet"),
    Category("category", "One category", "What's left in a category you pick"),
    ;

    companion object {
        fun of(id: String?): TileMetric? = entries.firstOrNull { it.id == id }
    }
}

data class TileConfig(val metric: TileMetric, val categoryId: String? = null, val categoryName: String? = null)

/** The two configurable tiles' defaults. */
val DEFAULT_TILES = listOf(TileConfig(TileMetric.LeftToday), TileConfig(TileMetric.LeftMonth))

/** Per-widget chart choices and the configurable tiles' choices, kept on the phone. */
interface WidgetSettings {
    /** Chart widgets by their Android widget id. */
    val charts: Flow<Map<Int, ChartWidgetConfig>>
    suspend fun chart(widgetId: Int): ChartWidgetConfig?
    suspend fun setChart(widgetId: Int, config: ChartWidgetConfig)
    suspend fun removeChart(widgetId: Int)

    /** Tile slots 0 and 1. */
    val tiles: Flow<List<TileConfig>>
    suspend fun setTile(slot: Int, config: TileConfig)
}

/** Chart widgets on the home screen, and asking the launcher to add one (Android side). */
interface PlacedWidgets {
    /** The chart widgets on the home screen now: id to the chart it shows until one is chosen. */
    suspend fun chartWidgets(): Map<Int, ChartKind>
    /** Asks the launcher to add a chart widget showing [config]; false if the launcher can't. */
    fun requestPin(config: ChartWidgetConfig): Boolean
    /** Redraws widgets and tiles after a change in settings. */
    suspend fun redraw()
}

/** A tile's figure: its title and amount (null amount: nothing to show yet). */
data class TileReading(val title: String, val amount: Money?, val negative: Boolean = amount?.isNegative == true)

object TileMetrics {
    /**
     * What [config] shows from [g] on [today] (1-based day of a month of [daysInMonth]):
     * - Left today: the budget left at the start of today spread over the days left
     *   (today included), minus what's been spent today.
     * - Left this month: the spending categories' balances.
     * - Left this week: the weekly allowance minus this week's spending.
     */
    fun read(config: TileConfig, g: Glanceable, dayOfMonth: Int, daysInMonth: Int): TileReading {
        val spentToday = g.spentToday
        return when (config.metric) {
            TileMetric.LeftToday -> {
                val today = spentToday?.minor ?: 0
                val startOfToday = g.leftInBudget.minor + today
                val daysLeft = (daysInMonth - dayOfMonth + 1).coerceAtLeast(1)
                TileReading("Left today", Money(startOfToday / daysLeft - today))
            }
            TileMetric.LeftMonth -> TileReading("Left this month", g.leftInBudget)
            TileMetric.LeftWeek -> {
                val t = g.trend
                val allowance = t?.weekAllowance
                TileReading("Left this week", if (t == null || allowance == null) null else Money(allowance - t.weekTotal))
            }
            TileMetric.SpentToday -> TileReading("Spent today", spentToday, negative = false)
            TileMetric.ToBudget -> TileReading("To budget", g.toBudget)
            TileMetric.Category -> {
                val c = g.categories.firstOrNull { it.id == config.categoryId }
                TileReading("${c?.name ?: config.categoryName ?: "Category"} left", c?.balance)
            }
        }
    }
}
