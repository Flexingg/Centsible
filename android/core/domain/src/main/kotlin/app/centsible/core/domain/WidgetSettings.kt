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
    Sankey("sankey", "Money flow", "Where this month's income went, group by group and down each level", "money-flow"),
    ;

    companion object {
        fun of(id: String?): ChartKind? = entries.firstOrNull { it.id == id }
    }
}

/** Visual style override for customizable charts. */
enum class ChartStyle(val id: String, val label: String) {
    Auto("auto", "Auto"),
    Area("area", "Area line"),
    Bars("bars", "Bars"),
    ;

    companion object {
        fun of(id: String?): ChartStyle = entries.firstOrNull { it.id == id } ?: Auto
    }
}

/** Accent color palette override for customizable charts. */
enum class ChartAccent(val id: String, val label: String) {
    Default("default", "Default"),
    Brand("brand", "Brand"),
    Green("green", "Green"),
    Coral("coral", "Coral"),
    Ocean("ocean", "Ocean"),
    Plum("plum", "Plum"),
    Amber("amber", "Amber"),
    ;

    companion object {
        fun of(id: String?): ChartAccent = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** Which metric series [ChartKind.Monthly] plots. */
enum class ChartSeries(val id: String, val label: String) {
    Spending("spending", "Spending"),
    Income("income", "Income"),
    Savings("savings", "Net savings"),
    ;

    companion object {
        fun of(id: String?): ChartSeries = entries.firstOrNull { it.id == id } ?: Spending
    }
}

/** Visual design system for home-screen widgets and custom chart cards. */
enum class WidgetTheme(val id: String, val label: String, val description: String) {
    Classic("classic", "Classic", "Calm neutral card with Centsible's signature warm palette"),
    MaterialExpressive("material-expressive", "Material Expressive", "Dynamic Material You wallpaper colors, expressive shapes & tonal pills"),
    ;

    companion object {
        fun of(id: String?): WidgetTheme = entries.firstOrNull { it.id == id } ?: Classic
    }
}

/**
 * Configuration for a chart shown on a home-screen widget or the in-app dashboard.
 * All customization fields default to the classic preset behavior.
 */
data class ChartWidgetConfig(
    val kind: ChartKind,
    val categoryId: String? = null,
    val categoryName: String? = null,
    /** Optional custom title override (null/blank uses the automatic title). */
    val title: String? = null,
    /** Visual style override (Area line vs Bars, or Auto for the kind's default). */
    val style: ChartStyle = ChartStyle.Auto,
    /** Accent color override. */
    val accent: ChartAccent = ChartAccent.Default,
    /** Metric plotted by [ChartKind.Monthly] (Spending, Income, or Net savings). */
    val series: ChartSeries = ChartSeries.Spending,
    /** Number of months (e.g. 3, 6, 12) for multi-month charts. */
    val months: Int? = null,
    /** Show reference / average / budget target line. */
    val showReference: Boolean = true,
    /** Show comparison line (e.g. last month's spending pace). */
    val showComparison: Boolean = true,
    /** Show legend rows. */
    val showLegend: Boolean = true,
    /** Optional subset of category IDs for [ChartKind.Categories]. */
    val categoryIds: List<String> = emptyList(),
    /** Optional per-chart theme override; null follows the global [WidgetSettings.widgetTheme]. */
    val theme: WidgetTheme? = null,
    /** [ChartKind.Sankey]: the deepest level shown (1 = groups only, up to 5); null shows them all. */
    val depth: Int? = null,
)

/** A user-created custom chart that can be shown on the Home dashboard, pinned as a widget, or both. */
data class SavedChart(
    val id: String,
    val config: ChartWidgetConfig,
    val showOnDashboard: Boolean = true,
)

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

/** Per-widget chart choices, saved custom charts, and the configurable tiles' choices, kept on the phone. */
interface WidgetSettings {
    /** Chart widgets by their Android widget id. */
    val charts: Flow<Map<Int, ChartWidgetConfig>>
    suspend fun chart(widgetId: Int): ChartWidgetConfig?
    suspend fun setChart(widgetId: Int, config: ChartWidgetConfig)
    suspend fun removeChart(widgetId: Int)

    /** User-created custom charts (for app dashboard and/or pinning as widgets). */
    val savedCharts: Flow<List<SavedChart>>
    suspend fun saveCustomChart(chart: SavedChart)
    suspend fun deleteCustomChart(id: String)

    /** Pending config saved right before requestPinAppWidget so launchers that open ChartConfigActivity pick it up. */
    suspend fun setPendingPin(config: ChartWidgetConfig?)
    suspend fun consumePendingPin(): ChartWidgetConfig?

    /** Global design system for home-screen widgets and custom chart cards (Classic vs Material Expressive). */
    val widgetTheme: Flow<WidgetTheme>
    suspend fun setWidgetTheme(theme: WidgetTheme)

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
