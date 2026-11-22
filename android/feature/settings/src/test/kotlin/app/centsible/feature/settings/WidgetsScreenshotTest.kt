package app.centsible.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.ChartKind
import app.centsible.core.domain.ChartWidgetConfig
import app.centsible.core.domain.Glanceable
import app.centsible.core.domain.TileConfig
import app.centsible.core.domain.TileMetric
import app.centsible.core.model.Money
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** A household's month, for the widget previews (realistic numbers, fixed date). */
internal object SampleGlance {
    val today: LocalDate = LocalDate.parse("2026-11-18")
    private fun running(vararg daily: Long) = daily.toList().runningReduce { a, b -> a + b }
    val data = Glanceable(
        budgetName = "Household", month = "November", envelope = true, toBudget = Money(12_300), spent = Money(214_380), budgeted = Money(420_000),
        reviewCount = 4, bills = emptyList(), updatedAt = 1_763_480_000_000,
        trend = Glanceable.Trend(
            monthToDate = running(4_120, 18_340, 9_210, 2_450, 31_200, 6_780, 12_900, 3_400, 21_560, 8_330, 5_120, 14_750, 9_980, 2_100, 26_430, 11_210, 7_650, 12_850),
            lastMonth = running(*LongArray(31) { i -> listOf(9_800L, 4_300, 15_600, 7_200, 22_100, 3_900, 11_400)[i % 7] }),
            daysInMonth = 30, week = listOf(26_430L, 11_210, 7_650, 0, 0, 0, 0), weekdayToday = 2, lastWeekTotal = 88_420, weekAllowance = 98_000,
        ),
        categories = listOf(
            Glanceable.CategorySlice("Groceries", Money(61_240), Money(80_000), "c1", Money(18_760)),
            Glanceable.CategorySlice("Dining Out", Money(38_900), Money(30_000), "c2", Money(-8_900)),
            Glanceable.CategorySlice("Gas", Money(24_310), Money(35_000), "c3", Money(10_690)),
            Glanceable.CategorySlice("Shopping", Money(21_870), Money(25_000), "c4", Money(3_130)),
            Glanceable.CategorySlice("Kids", Money(18_120), Money(30_000), "c5", Money(11_880)),
            Glanceable.CategorySlice("Utilities", Money(29_940), Money(32_000), "c6", Money(2_060)),
            Glanceable.CategorySlice("Fun", Money(20_000), Money(20_000), "c7", Money.Zero),
        ),
        leftInBudget = Money(205_620),
        months = listOf("2026-06" to 378_000L, "2026-07" to 412_500L, "2026-08" to 396_200L, "2026-09" to 441_900L, "2026-10" to 402_300L, "2026-11" to 214_380L)
            .map { (m, s) -> Glanceable.MonthFlow(m, Money(if (m == "2026-11") 310_000 else 520_000), Money(s)) },
        netWorth = listOf(18_210_000L, 18_390_000, 18_120_000, 18_640_000, 18_910_000, 19_020_000, 19_480_000, 19_350_000, 19_810_000, 20_060_000, 20_240_000, 20_610_000)
            .mapIndexed { i, v -> Glanceable.NetWorthPoint(java.time.YearMonth.of(2025, 12).plusMonths(i.toLong()).toString(), Money(v)) },
    )
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val state = WidgetsUiState(
        data = SampleGlance.data,
        placed = listOf(1 to ChartWidgetConfig(ChartKind.Pace), 2 to ChartWidgetConfig(ChartKind.Categories)),
        tiles = listOf(TileConfig(TileMetric.LeftToday), TileConfig(TileMetric.Category, "c2", "Dining Out")),
    )

    @Test fun widgets_settings() {
        compose.setContent { CentsibleTheme(darkTheme = false) { WidgetsScreen(state, today = SampleGlance.today) } }
        compose.onRoot().captureRoboImage("screenshots/widgets_settings.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    private fun gallery(dark: Boolean, name: String, kinds: List<ChartKind>) {
        compose.setContent {
            CentsibleTheme(darkTheme = dark) {
                Column(
                    Modifier.background(CentsibleTheme.colors.canvas).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    kinds.forEach { k ->
                        ChartPreview(ChartWidgetConfig(k, "c1", "Groceries"), SampleGlance.data, SampleGlance.today, height = 180.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/$name.png")
    }

    private val first = ChartKind.entries.take(4)
    private val rest = ChartKind.entries.drop(4)

    @Test fun widget_charts_light() = gallery(false, "widget_charts_light", first)
    @Test fun widget_charts_light_2() = gallery(false, "widget_charts_light_2", rest)
    @Test fun widget_charts_dark() = gallery(true, "widget_charts_dark", first)
    @Test fun widget_charts_dark_2() = gallery(true, "widget_charts_dark_2", rest)

    @Test fun widget_chart_picker() {
        compose.setContent { CentsibleTheme(darkTheme = false) { ChartPickerContent(SampleGlance.data, ChartWidgetConfig(ChartKind.Week), onSave = {}, onCancel = {}, isNew = true, today = SampleGlance.today) } }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/widget_chart_picker.png")
    }
}
