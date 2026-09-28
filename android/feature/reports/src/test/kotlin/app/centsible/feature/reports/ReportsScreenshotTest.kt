package app.centsible.feature.reports

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.YearMonth
import app.centsible.core.testing.SamplePlanning
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ReportsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val base = ReportsUiState(
        cashFlow = Loadable.Ready(SamplePlanning.cashFlow),
        spending = Loadable.Ready(SamplePlanning.spending),
        spendingMonth = YearMonth("2026-09"),
        netWorth = Loadable.Ready(SamplePlanning.netWorth),
    )

    private fun shot(state: ReportsUiState, name: String, dark: Boolean = false) {
        compose.setContent { CentsibleTheme(darkTheme = dark) { ReportsScreen(state, onBack = {}) } }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun cash_flow_light() = shot(base, "reports_cash_flow_light")
    @Test fun cash_flow_selected_dark() = shot(base.copy(selectedMonth = 2), "reports_cash_flow_selected_dark", dark = true)
    @Test fun spending_light() = shot(base.copy(tab = ReportTab.Spending), "reports_spending_light")
    @Test fun net_worth_light() = shot(base.copy(tab = ReportTab.NetWorth), "reports_net_worth_light")

    @Test fun `axis maximum rounds to a clean number`() {
        assertEquals(1_000_000.0, niceCeiling(812_300.0), 0.0)
        assertEquals(1_250_000.0, niceCeiling(1_190_400.0), 0.0)
        assertEquals(1.5, niceCeiling(1.3), 0.0)
        assertEquals(10_000.0, niceCeiling(8_500.0), 0.0)
    }
}
