package app.centsible.core.network

import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge's contract suite records these fixtures from a real Actual server
 * (`npm run fixtures`). If the contract drifts from these DTOs, this fails first.
 */
class FixtureDecodingTest {
    private val json = BridgeJson
    private val dir = File(System.getProperty("contract.fixtures") ?: "../../../contract/fixtures")

    private val decoders: Map<String, KSerializer<*>> = mapOf(
        "capabilities" to CapabilitiesDto.serializer(),
        "token-response" to TokenResponseDto.serializer(),
        "pairing-code" to PairingCodeDto.serializer(),
        "budgets" to ItemsDto.serializer(BudgetDto.serializer()),
        "accounts" to ItemsDto.serializer(AccountDto.serializer()),
        "category-groups" to ItemsDto.serializer(CategoryGroupDto.serializer()),
        "transactions-page" to TransactionPageDto.serializer(),
        "transfer-matches" to TransferMatchesDto.serializer(),
        "transfer-candidates" to TransferCandidatesDto.serializer(),
        "targets" to TargetsDto.serializer(),
        "mortgage" to MortgageDto.serializer(),
        "annual-budgets" to AnnualBudgetsDto.serializer(),
        "home-layout" to HomeLayoutDto.serializer(),
        "appearance" to AppearanceListDto.serializer(),
        "rule-preview" to RulePreviewDto.serializer(),
        "automations" to AutomationListDto.serializer(),
        "category-automations" to CategoryAutomationsDto.serializer(),
        "transactions-running-balance" to TransactionPageDto.serializer(),
        "review-inbox" to ReviewInboxDto.serializer(),
        "transaction" to TransactionDto.serializer(),
        "budget-month" to BudgetMonthDto.serializer(),
        "problem-unauthorized" to ProblemDto.serializer(),
        "problem-budget-encrypted" to ProblemDto.serializer(),
        "setup-status" to SetupStatusDto.serializer(),
        "budget-created" to BudgetDto.serializer(),
        "bank-sync-overview" to BankSyncOverviewDto.serializer(),
        "bank-sync-backfill" to BackfillDto.serializer(),
        "autopilot" to AutopilotDto.serializer(),
        "goals" to GoalsDto.serializer(),
        "forecast" to ForecastDto.serializer(),
        "insights" to InsightsDto.serializer(),
        "subscriptions" to SubscriptionsDto.serializer(),
        "year-in-review" to YearInReviewDto.serializer(),
        "review-month" to YearInReviewDto.serializer(),
        "server-status" to ServerStatusDto.serializer(),
        "backups" to BackupOverviewDto.serializer(),
        "bank-sync-summary" to SyncSummaryDto.serializer(),
        "bank-sync-settings" to BankSyncSettingsDto.serializer(),
        "simplefin-accounts" to ItemsDto.serializer(ExternalAccountDto.serializer()),
        "transaction-updated" to TransactionDto.serializer(),
        "preferences" to PreferencesDto.serializer(),
        "schedule" to ScheduleDto.serializer(),
        "rule" to RuleDto.serializer(),
        "payee-stats" to ItemsDto.serializer(PayeeStatDto.serializer()),
        "job" to JobDto.serializer(),
        "import-preview" to ImportPreviewDto.serializer(),
        "reconcile-status" to ReconcileStatusDto.serializer(),
        "report-cash-flow" to CashFlowDto.serializer(),
        "report-spending" to SpendingDto.serializer(),
        "report-net-worth" to NetWorthDto.serializer(),
    )

    @Test
    fun `every recorded fixture has a decoder`() {
        val names = dir.listFiles { f -> f.extension == "json" }!!.map { it.nameWithoutExtension }.toSet()
        assertTrue("fixtures missing in $dir", names.isNotEmpty())
        assertEquals("fixtures without a decoder", emptySet<String>(), names - decoders.keys)
    }

    @Test
    fun `all fixtures decode`() {
        for ((name, serializer) in decoders) {
            val text = File(dir, "$name.json").readText()
            json.decodeFromString(serializer, text)
        }
    }

    @Test
    fun `budget month fixture keeps envelope semantics`() {
        val m = json.decodeFromString(BudgetMonthDto.serializer(), File(dir, "budget-month.json").readText())
        assertEquals("envelope", m.budgetType)
        assertTrue("totalBudgeted is normalized to positive", m.totalBudgeted >= 0)
        assertTrue(m.groups.any { it.isIncome })
    }

    @Test
    fun `newer bridges may add fields`() {
        val withExtra = """{"id":"a","name":"Checking","offBudget":false,"closed":false,"balance":1,"brandNew":{"x":1}}"""
        assertEquals("Checking", json.decodeFromString(AccountDto.serializer(), withExtra).name)
    }
}
