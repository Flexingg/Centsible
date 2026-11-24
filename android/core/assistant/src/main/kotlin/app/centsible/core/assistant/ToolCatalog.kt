package app.centsible.core.assistant

import app.centsible.core.domain.AutomationsGateway
import app.centsible.core.domain.AccountServices
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.HouseholdGateway
import app.centsible.core.domain.InsightsGateway
import app.centsible.core.domain.PlanAheadGateway
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.ReportsGateway
import app.centsible.core.domain.RuleTools
import app.centsible.core.domain.ServerGateway
import app.centsible.core.domain.TransactionTools
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Every app gateway the tools reach (bank sync left out on purpose). */
class Gateways @Inject constructor(
    val engine: BudgetEngine,
    val planning: PlanningGateway,
    val reports: ReportsGateway,
    val insights: InsightsGateway,
    val planAhead: PlanAheadGateway,
    val automations: AutomationsGateway,
    val rules: RuleTools,
    val transactions: TransactionTools,
    val household: HouseholdGateway,
    val server: ServerGateway,
    val accounts: AccountServices,
)

/** Collects tool definitions: `read(...)` runs straight away, `write(...)` needs a group and approval. */
class ToolsBuilder {
    val tools = mutableListOf<AssistantTool>()

    fun read(name: String, description: String, schema: JsonObject = schema(), run: suspend (Args, ToolContext) -> JsonElement) {
        tools += AssistantTool(name, description, schema, group = null) { a, c -> ToolOutput(run(a, c)) }
    }

    fun write(
        group: ToolGroup,
        name: String,
        description: String,
        schema: JsonObject,
        preview: suspend (Args, ToolContext) -> Preview,
        run: suspend (Args, ToolContext) -> ToolOutput,
    ) {
        tools += AssistantTool(name, description, schema, group, preview, run)
    }
}

/** All of the assistant's app tools, in a fixed order (a stable list keeps the prompt cache warm). */
@Singleton
class ToolCatalog @Inject constructor(private val gw: Gateways) {
    val tools: List<AssistantTool> by lazy {
        ToolsBuilder().apply {
            readTools(gw)
            transactionWrites(gw)
            budgetWrites(gw)
            categoryWrites(gw)
            ruleWrites(gw)
            recurringWrites(gw)
            planWrites(gw)
            accountWrites(gw)
        }.tools.also { list ->
            val dupes = list.groupBy { it.name }.filterValues { it.size > 1 }.keys
            check(dupes.isEmpty()) { "Duplicate tool names: $dupes" }
        }
    }

    private val byName by lazy { tools.associateBy { it.name } }

    fun find(name: String): AssistantTool? = byName[name]

    fun names(budget: app.centsible.core.model.BudgetId) = Names(gw.engine, gw.planAhead, budget)
}
