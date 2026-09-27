package app.centsible.core.engine.bridge

import app.centsible.core.domain.PairingLinks
import app.centsible.core.domain.recreate
import app.centsible.core.model.AccountId
import app.centsible.core.model.AmountOp
import app.centsible.core.model.BudgetPot
import app.centsible.core.model.CsvMapping
import app.centsible.core.model.Feature
import app.centsible.core.model.Frequency
import app.centsible.core.model.ImportOptions
import app.centsible.core.model.JobStatus
import app.centsible.core.model.Money
import app.centsible.core.model.NewTransaction
import app.centsible.core.model.Recurrence
import app.centsible.core.model.Role
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.RuleValue
import app.centsible.core.model.ScheduleDraft
import app.centsible.core.model.TransactionId
import app.centsible.core.model.TransactionPatch
import app.centsible.core.model.Update
import app.centsible.core.model.YearMonth
import app.centsible.core.network.BridgeApi
import app.centsible.core.network.BridgeClient
import app.centsible.core.network.PlanningApi
import app.centsible.core.testing.InMemorySessionStore
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * The app's real data layer (gateways → BridgeApi → HTTP) against a real bridge and a
 * real Actual server: the path a phone takes, minus the UI.
 *
 * Start the server, then pass its pairing link:
 *   cd bridge && npx tsx test/support/e2e-server.ts 8787
 *   E2E_PAIRING_URI='actualbridge://pair?...' ./gradlew :core:engine-bridge:test --tests '*EndToEndTest*'
 * Skipped when E2E_PAIRING_URI isn't set. CI runs it on every push.
 */
class EndToEndTest {
    private val failures = mutableListOf<String>()

    private suspend fun <T> step(name: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        failures += "$name: ${e::class.simpleName}: ${e.message}"
        null
    }

    private fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
        if (!ok) failures += "$name: ${detail()}"
    }

    @Test
    fun `a paired phone can use every feature against a real bridge`() = runBlocking {
        val uri = System.getProperty("e2e.pairingUri")
        assumeTrue("E2E_PAIRING_URI not set; skipping end-to-end test", uri != null)

        val sessions = InMemorySessionStore()
        val client = BridgeClient(sessions, OkHttp.create())
        val api = BridgeApi(client)
        val planningApi = PlanningApi(client)
        val engine = BridgeBudgetEngine(api)
        val household = BridgeHouseholdGateway(api)
        val planning = BridgePlanningGateway(planningApi) {}
        val accounts = BridgeAccountServices(planningApi) {}
        val reports = BridgeReports(planningApi)

        // Pairing: exactly what scanning the QR code does.
        val link = checkNotNull(PairingLinks.parse(uri!!)) { "Unparseable pairing link: $uri" }
        val session = step("pair") { BridgePairingGateway(api).pair(link, "Pixel 9") }
        check(session != null) { "Pairing failed, so nothing else can run: $failures" }
        sessions.save(session!!)

        val caps = step("capabilities") { engine.capabilities() }
        check("capabilities cover envelope budgets", caps?.has(Feature.BudgetEnvelope) == true) { "$caps" }
        step("me") { household.me() }
        val budget = step("budgets") { engine.budgets().single { it.name == "Household" }.id }!!
        sessions.selectBudget(budget)

        val accountList = step("accounts") { engine.accounts(budget) }.orEmpty()
        val checking = accountList.firstOrNull { it.name == "Joint Checking" }?.id
        val visa = accountList.firstOrNull { it.name == "Visa" }?.id
        check("seeded accounts", checking != null && visa != null) { accountList.map { it.name }.toString() }
        val groups = step("category groups") { engine.categoryGroups(budget) }.orEmpty()
        val categories = groups.flatMap { it.categories }.associateBy { it.name }
        val food = categories["Food"]?.id
        val general = categories["General"]?.id
        step("payees") { engine.payees(budget) }
        step("preferences") { engine.preferences(budget) }
        val months = step("budget months") { engine.budgetMonths(budget) }.orEmpty()
        val month = months.maxOrNull() ?: YearMonth.of(LocalDate.now().year, LocalDate.now().monthValue)
        step("budget month") { engine.budgetMonth(budget, month) }
        step("transactions page") { engine.transactions(budget) }

        // Transactions: create, edit, split, delete.
        val txId = TransactionId(UUID.randomUUID().toString())
        step("create transaction") {
            engine.createTransaction(budget, NewTransaction(txId, checking!!, LocalDate.now().toString(), Money(-2345), payeeName = "Blue Bottle", categoryId = general))
        }
        step("edit transaction") { engine.updateTransaction(budget, txId, TransactionPatch(notes = Update.Set("latte"), amount = Update.Set(Money(-2500)))) }
        step("clear a field") { engine.updateTransaction(budget, txId, TransactionPatch(notes = Update.Set(null))) }
            ?.let { check("notes cleared", it.notes == null) { "notes=${it.notes}" } }
        val splitId = TransactionId(UUID.randomUUID().toString())
        step("create split") {
            engine.createTransaction(budget, NewTransaction(splitId, visa!!, LocalDate.now().toString(), Money(-5000), payeeName = "Target",
                splits = listOf(NewTransaction.Split(Money(-3000), food), NewTransaction.Split(Money(-2000), general))))
        }
        step("delete transaction") { engine.deleteTransaction(budget, txId) }
        // Undo: delete the split, then bring it back the way the snackbar does.
        val split = step("read split") { engine.transaction(budget, splitId) }
        if (split != null) {
            step("delete split") { engine.deleteTransaction(budget, splitId) }
            val again = split.recreate()
            step("undo delete") { engine.createTransaction(budget, again) }
            step("read restored") { engine.transaction(budget, again.id) }?.let { restored ->
                check("restored split keeps its parts", restored.subtransactions.map { it.amount } == listOf(Money(-3000), Money(-2000))) { "${restored.subtransactions}" }
                check("restored split keeps its payee", restored.payeeId == split.payeeId) { "${restored.payeeId} vs ${split.payeeId}" }
            }
        }

        // Envelope budgeting.
        if (food != null) {
            step("set budgeted") { engine.setBudgeted(budget, month, food, Money(65000)) }
            step("carryover") { engine.setCarryover(budget, month, food, true) }
            step("move money") { engine.moveMoney(budget, month, BudgetPot.ToBudget, BudgetPot.Envelope(food), Money(1000), UUID.randomUUID().toString()) }
        }
        step("hold for next month") { engine.holdForNextMonth(budget, month, Money(1000)) }
        step("release hold") { engine.holdForNextMonth(budget, month, null) }

        // Structure.
        val newAccount = step("create account") { engine.createAccount(budget, "E2E Savings", offBudget = false, initialBalance = Money(10000)) }
        newAccount?.let { a -> step("rename account") { engine.renameAccount(budget, a.id, "E2E Vault") } }
        val groupId = groups.firstOrNull { !it.isIncome }?.id
        val newCategory = groupId?.let { g -> step("create category") { engine.createCategory(budget, "E2E Pets", g) } }
        newCategory?.let { c -> step("update category") { engine.updateCategory(budget, c.id, name = "E2E Pet Care") } }

        // Recurring.
        val schedule = step("create schedule") {
            planning.createSchedule(budget, ScheduleDraft(name = "E2E Rent", payeeName = "Landlord", accountId = checking, amount = Money(-150000), amountOp = AmountOp.Is,
                recurrence = Recurrence(Frequency.Monthly, start = LocalDate.now().plusDays(3).toString())))
        }
        step("list schedules") { planning.schedules(budget) }
        schedule?.let { s ->
            step("skip schedule") { planning.skipSchedule(budget, s.id) }
            step("delete schedule") { planning.deleteSchedule(budget, s.id) }
        }

        // Rules ("Always use this category").
        val payee = step("payees for rule") { engine.payees(budget).first { it.name == "Blue Bottle" } }
        val rule = if (payee != null && general != null) step("create rule") {
            planning.createRule(budget, RuleDraft(
                conditions = listOf(RuleClause("payee", "is", RuleValue.Text(payee.id.raw), "id")),
                actions = listOf(RuleClause("category", "set", RuleValue.Text(general.raw), "id")),
            ))
        } else null
        step("list rules") { planning.rules(budget) }
        rule?.let { r -> step("delete rule") { planning.deleteRule(budget, r.id) } }

        // Merchants, tags, notes, goals.
        step("payee stats") { planning.payeeStats(budget) }
        payee?.let { p -> step("rename payee") { planning.renamePayee(budget, p.id, "Blue Bottle Coffee") } }
        val tag = step("create tag") { planning.createTag(budget, "e2e", "#2A78D6") }
        tag?.let { t -> step("delete tag") { planning.deleteTag(budget, t.id) } }
        if (food != null) {
            step("set note") { planning.setCategoryNote(budget, food, "Weekly shop\n#template 600") }
            step("read note") { planning.categoryNote(budget, food) }?.let { check("note saved", it.contains("#template")) { it } }
            step("apply goals") { planning.applyTemplates(budget, month, overwrite = false) }
            step("clear note") { planning.setCategoryNote(budget, food, null) }
        }

        // Accounts: import, reconcile, bank sync.
        if (visa != null) {
            val csv = "Date,Payee,Amount\n${LocalDate.now()},Farmers Market,-45.67\n${LocalDate.now()},Parking,-8.00\n".toByteArray()
            val options = ImportOptions(csvMapping = CsvMapping(date = "Date", payee = "Payee", amount = "Amount"))
            step("import preview") { accounts.previewImport(budget, visa, "visa.csv", csv, options) }
                ?.let { check("preview rows", it.rows.size == 2) { "${it.rows} ${it.errors}" } }
            step("import") { accounts.importFile(budget, visa, "visa.csv", csv, options) }
            val status = step("reconcile status") { accounts.reconcileStatus(budget, visa) }
            status?.let { s -> step("reconcile") { accounts.reconcile(budget, visa, s.cleared, createAdjustment = false) } }
        }
        val job = step("start bank sync") { accounts.startBankSync(budget, checking) }
        job?.let { j ->
            // Nothing is linked to a bank here, so the job should end, not hang.
            var current = j
            repeat(40) { if (current.status == JobStatus.Running) { delay(500); current = accounts.job(j.id) } }
            check("bank sync job finishes", current.status != JobStatus.Running) { "still running" }
        }

        // Reports.
        val start = months.minOrNull() ?: month
        step("cash flow") { reports.cashFlow(budget, start, month) }
        step("spending") { reports.spending(budget, start, month) }
        step("net worth") { reports.netWorth(budget, 6) }

        // Household.
        step("members") { household.members() }
        val sam = step("add member") { household.addMember("Sam", Role.Member, listOf(budget)) }
        sam?.let { m -> step("invite member") { household.invite(m.id) } }

        assertEquals("End-to-end steps that failed", emptyList<String>(), failures)
    }
}
