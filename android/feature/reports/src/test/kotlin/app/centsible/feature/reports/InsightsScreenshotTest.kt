package app.centsible.feature.reports

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AccountId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.CategoryTrend
import app.centsible.core.model.Insight
import app.centsible.core.model.Insights
import app.centsible.core.model.Money
import app.centsible.core.model.NetWorth
import app.centsible.core.model.NetWorthAccount
import app.centsible.core.model.NetWorthPoint
import app.centsible.core.model.PayeeId
import app.centsible.core.model.TransactionId
import app.centsible.core.model.YearInReview
import app.centsible.core.model.YearMonth
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class InsightsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun trend(name: String, spent: Long, typical: Long, projected: Long) =
        CategoryTrend(CategoryId(name), name, Money(spent), Money(typical), Money(projected), if (typical == 0L) null else ((projected - typical) * 100 / typical).toInt())

    private fun alert(id: String, kind: Insight.Kind, severity: Insight.Severity, title: String, detail: String) =
        Insight(id, kind, severity, title, detail, Money(0), "2026-09-20", null, null, null)

    private val insights = Insights(
        YearMonth("2026-09"), 20, 30, Money(412_300), Money(520_000), Money(618_450),
        listOf(
            trend("Groceries", 71_245, 64_000, 106_868),
            trend("Dining Out", 18_950, 26_100, 28_425),
            trend("Gas", 11_420, 17_650, 17_130),
            trend("Home Improvement", 42_000, 8_000, 63_000),
            trend("Pets", 6_499, 0, 9_749),
        ),
        listOf(
            alert("a1", Insight.Kind.CategoryPace, Insight.Severity.Warning, "Home Improvement is 688% above usual", "$420 so far, on pace for $630. You usually spend about $80."),
            alert("a2", Insight.Kind.UnusualTransaction, Insight.Severity.Warning, "Bigger than usual at Costco", "$389.12 on 2026-09-14. It's usually about $142."),
            alert("a3", Insight.Kind.PriceChange, Insight.Severity.Warning, "Spotify went up", "$12.99 now, was $11.99 (+8%)."),
            alert("a4", Insight.Kind.NewMerchant, Insight.Severity.Info, "First time at Harbor Freight", "$214.50 on 2026-09-09. Worth a look if you don't recognize it."),
            alert("a5", Insight.Kind.CategoryPace, Insight.Severity.Good, "Dining Out is 42% below usual", "$189.50 so far, against about $261 normally. Nice."),
        ),
    )

    @Test fun trends() {
        compose.setContent { CentsibleTheme(darkTheme = false) { TrendsScreen(TrendsUiState(YearMonth("2026-09"), Loadable.Ready(insights))) } }
        compose.onRoot().captureRoboImage("screenshots/trends.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun trends_dark() {
        compose.setContent { CentsibleTheme(darkTheme = true) { TrendsScreen(TrendsUiState(YearMonth("2026-09"), Loadable.Ready(insights))) } }
        compose.onRoot().captureRoboImage("screenshots/trends_dark.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    private val months = (0 until 12).map { YearMonth("2025-10").plus(it) }
    private fun series(start: Long, step: Long, wobble: Long = 0) = months.indices.map { Money(start + step * it + if (it % 3 == 1) wobble else 0) }
    private val accounts = listOf(
        NetWorthAccount(AccountId("a1"), "Joint Checking", false, false, series(420_000, 8_000, -60_000)),
        NetWorthAccount(AccountId("a2"), "High-Yield Savings", false, false, series(1_800_000, 45_000)),
        NetWorthAccount(AccountId("a3"), "Brokerage", true, false, series(4_210_000, 95_000, -120_000)),
        NetWorthAccount(AccountId("a4"), "Visa Signature", false, false, series(-210_000, 9_000, -40_000)),
        NetWorthAccount(AccountId("a5"), "Car Loan", true, false, series(-1_450_000, 38_000)),
    )
    private val netWorth = NetWorth(
        months.indices.map { i ->
            val bal = accounts.map { it.balances[i].minor }
            NetWorthPoint(months[i], Money(bal.filter { it >= 0 }.sum()), Money(bal.filter { it < 0 }.sum()), Money(bal.sum()))
        },
        accounts,
    )

    @Test fun net_worth() {
        compose.setContent { CentsibleTheme(darkTheme = false) { NetWorthScreen(NetWorthUiState(12, Loadable.Ready(netWorth))) } }
        compose.onRoot().captureRoboImage("screenshots/net_worth.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    private val review = YearInReview(
        year = 2025, complete = true, availableYears = listOf(2026, 2025, 2024), empty = false,
        income = Money(12_640_000), spending = Money(9_871_245), saved = Money(2_768_755), savingsRate = 22, purchases = 1_284,
        dailyAverage = Money(27_044),
        topCategories = listOf(
            YearInReview.CategoryShare(CategoryId("c1"), "Mortgage", Money(2_940_000), 0.30f),
            YearInReview.CategoryShare(CategoryId("c2"), "Groceries", Money(1_182_340), 0.12f),
            YearInReview.CategoryShare(CategoryId("c3"), "Travel", Money(640_210), 0.065f),
            YearInReview.CategoryShare(CategoryId("c4"), "Dining Out", Money(512_880), 0.052f),
            YearInReview.CategoryShare(CategoryId("c5"), "Utilities", Money(401_500), 0.041f),
        ),
        topMerchants = listOf(
            YearInReview.MerchantTotal(PayeeId("p1"), "Oak Street Mortgage", Money(2_940_000), 12),
            YearInReview.MerchantTotal(PayeeId("p2"), "Costco", Money(612_004), 31),
            YearInReview.MerchantTotal(PayeeId("p3"), "Delta Air Lines", Money(301_220), 4),
            YearInReview.MerchantTotal(PayeeId("p4"), "Trader Joe's", Money(288_410), 52),
            YearInReview.MerchantTotal(PayeeId("p5"), "Target", Money(201_990), 27),
        ),
        mostVisited = YearInReview.MerchantTotal(PayeeId("p6"), "Blue Bottle Coffee", Money(71_450), 104),
        biggestPurchase = YearInReview.BiggestPurchase(TransactionId("t1"), "2025-06-14", "Delta Air Lines", "Travel", Money(186_420)),
        months = (1..12).map { m -> YearInReview.MonthTotal(YearMonth.of(2025, m), Money(700_000L + (m * 37_000L % 260_000L) + if (m == 12) 320_000 else 0), Money(1_053_333)) },
        biggestMonth = YearInReview.MonthTotal(YearMonth("2025-12"), Money(1_164_000), Money(1_053_333)),
        smallestMonth = YearInReview.MonthTotal(YearMonth("2025-07"), Money(719_000), Money(1_053_333)),
        noSpendDays = 83,
        longestNoSpendStreak = YearInReview.Streak(6, "2025-02-09", "2025-02-14"),
        newMerchants = 47, merchantsVisited = 212,
        previousYear = YearInReview.PreviousYear(Money(10_402_000), Money(12_100_000), -5),
    )

    private fun page(i: Int, name: String) {
        compose.setContent { CentsibleTheme(darkTheme = false) { YearInReviewScreen(YearInReviewUiState(Loadable.Ready(review)), initialPage = i) } }
        compose.onRoot().captureRoboImage("screenshots/year_$name.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun year_intro() = page(0, "intro")
    @Test fun year_spent() = page(1, "spent")
    @Test fun year_categories() = page(2, "categories")
    @Test fun year_merchants() = page(3, "merchants")
    @Test fun year_visited() = page(4, "visited")
    @Test fun year_months() = page(6, "months")
    @Test fun year_nospend() = page(7, "nospend")
    @Test fun year_saved() = page(8, "saved")
    @Test fun year_summary() = page(9, "summary")
}
