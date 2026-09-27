package app.canopy.core.network

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
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val dir = File(System.getProperty("contract.fixtures") ?: "../../../contract/fixtures")

    private val decoders: Map<String, KSerializer<*>> = mapOf(
        "capabilities" to CapabilitiesDto.serializer(),
        "token-response" to TokenResponseDto.serializer(),
        "pairing-code" to PairingCodeDto.serializer(),
        "budgets" to ItemsDto.serializer(BudgetDto.serializer()),
        "accounts" to ItemsDto.serializer(AccountDto.serializer()),
        "category-groups" to ItemsDto.serializer(CategoryGroupDto.serializer()),
        "transactions-page" to TransactionPageDto.serializer(),
        "transaction" to TransactionDto.serializer(),
        "budget-month" to BudgetMonthDto.serializer(),
        "problem-unauthorized" to ProblemDto.serializer(),
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
