package app.centsible.feature.planning

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AccountId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Forecast
import app.centsible.core.model.ForecastDay
import app.centsible.core.model.ForecastEvent
import app.centsible.core.model.Goal
import app.centsible.core.model.Money
import app.centsible.core.model.YearMonth
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PlanAheadScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 9, 27)

    private fun goal(id: String, name: String, kind: Goal.Kind, target: Long, balance: Long, month: String?, needed: Long?, avg: Long, projected: String?, status: Goal.Status) = Goal(
        CategoryId(id), name, "Savings", kind, Money(target), month?.let(::YearMonth), Money(balance), Money(avg),
        (balance.toFloat() / target).coerceIn(0f, 1f), Money((target - balance).coerceAtLeast(0)), needed?.let(::Money), Money(avg), projected?.let(::YearMonth), status,
    )

    private val goals = listOf(
        goal("c-emergency", "Emergency fund", Goal.Kind.Balance, 1_500_000, 940_000, null, null, 50_000, "2027-09", Goal.Status.OnTrack),
        goal("c-japan", "Japan trip", Goal.Kind.By, 600_000, 180_000, "2027-04", 52_500, 30_000, "2027-11", Goal.Status.Behind),
        goal("c-car", "New tires", Goal.Kind.By, 90_000, 90_000, "2026-11", 0, 15_000, "2026-09", Goal.Status.Reached),
        goal("c-couch", "Couch", Goal.Kind.Balance, 200_000, 20_000, null, null, 0, null, Goal.Status.Stalled),
    )
    private val categories = listOf(GoalCategory(CategoryId("c-gifts"), "Gifts", "Savings"))

    @Test fun goals_list() {
        compose.setContent { CentsibleTheme(darkTheme = false) { GoalsScreen(GoalsUiState(Loadable.Ready(goals), categories, canEdit = true), GoalsActions()) } }
        compose.onRoot().captureRoboImage("screenshots/goals.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun goals_dark() {
        compose.setContent { CentsibleTheme(darkTheme = true) { GoalsScreen(GoalsUiState(Loadable.Ready(goals), categories, canEdit = true), GoalsActions()) } }
        compose.onRoot().captureRoboImage("screenshots/goals_dark.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun goals_empty() {
        compose.setContent { CentsibleTheme(darkTheme = false) { GoalsScreen(GoalsUiState(Loadable.Ready(emptyList()), categories, canEdit = true), GoalsActions()) } }
        compose.onRoot().captureRoboImage("screenshots/goals_empty.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun goals_editor() {
        val g = goals[0]
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                GoalsScreen(GoalsUiState(Loadable.Ready(goals), categories, canEdit = true, editor = GoalEditor(g, GoalCategory(g.categoryId, g.name, g.groupName))), GoalsActions())
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/goals_editor.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    // A checking account: paid on the 1st and 15th, rent on the 1st, a few bills, everyday spending in between.
    private val forecast: Forecast = run {
        val events = mutableListOf<ForecastEvent>()
        fun add(date: LocalDate, name: String, amount: Long, overdue: Boolean = false) =
            events.add(ForecastEvent(date.toString(), name, name, "Joint Checking", Money(amount), false, overdue))
        add(today.minusDays(2).let { today }, "Internet", -6_500, overdue = true)
        var d = today
        while (d <= today.plusDays(90)) {
            if (d.dayOfMonth == 1) { add(d, "Paycheck", 310_000); add(d, "Rent", -185_000) }
            if (d.dayOfMonth == 15) add(d, "Paycheck", 310_000)
            if (d.dayOfMonth == 5) add(d, "Car loan", -42_000)
            if (d.dayOfMonth == 20) add(d, "Phone", -8_500)
            if (d.dayOfMonth == 28) add(d, "Streaming", -1_599)
            d = d.plusDays(1)
        }
        val typical = -14_000L
        var balance = 212_000L
        val days = (0..90).map { i ->
            val date = today.plusDays(i.toLong()).toString()
            val scheduled = events.filter { it.date == date }.sumOf { it.amount.minor }
            balance += scheduled + if (i == 0) 0 else typical
            ForecastDay(date, Money(balance), Money(scheduled), Money(if (i == 0) 0 else typical))
        }
        val low = days.minBy { it.balance.minor }
        val paid = listOf(1 to "Rent", 5 to "Car loan", 20 to "Phone").map { (day, name) ->
            app.centsible.core.model.PaidBill(today.withDayOfMonth(day).toString(), name, name, Money(-10_000))
        }
        Forecast(today.toString(), today.plusDays(90).toString(), listOf(AccountId("a")), Money(212_000), Money(typical), events, days, low, paid)
    }

    @Test fun forecast() {
        compose.setContent { CentsibleTheme(darkTheme = false) { ForecastScreen(ForecastUiState(today = today, data = Loadable.Ready(forecast)), ForecastActions()) } }
        compose.onRoot().captureRoboImage("screenshots/forecast.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun forecast_dark_selected() {
        compose.setContent { CentsibleTheme(darkTheme = true) { ForecastScreen(ForecastUiState(today = today, data = Loadable.Ready(forecast), selectedIndex = 34), ForecastActions()) } }
        compose.onRoot().captureRoboImage("screenshots/forecast_dark.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    @Test fun bill_calendar() {
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                BillCalendarScreen(ForecastUiState(today = today, data = Loadable.Ready(forecast), calendarMonth = YearMonth("2026-10")), CalendarActions())
            }
        }
        compose.onRoot().captureRoboImage("screenshots/bill_calendar.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    @Test fun bill_calendar_day() {
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                BillCalendarScreen(ForecastUiState(today = today, data = Loadable.Ready(forecast), calendarMonth = YearMonth("2026-10"), selectedDate = "2026-10-01"), CalendarActions())
            }
        }
        compose.onRoot().captureRoboImage("screenshots/bill_calendar_day.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    @Test fun bill_calendar_this_month() {
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                BillCalendarScreen(ForecastUiState(today = today, data = Loadable.Ready(forecast), calendarMonth = YearMonth("2026-09")), CalendarActions())
            }
        }
        compose.onRoot().captureRoboImage("screenshots/bill_calendar_this_month.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
