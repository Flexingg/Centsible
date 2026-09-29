package app.centsible.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.BrandDial
import app.centsible.core.designsystem.component.CardShape
import app.centsible.core.designsystem.component.DialSegment
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.motion.Motion
import app.centsible.core.designsystem.motion.staggeredEntrance
import app.centsible.core.extensions.DashboardContext
import app.centsible.core.extensions.DashboardWidget
import app.centsible.core.extensions.Destination
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.Feature
import app.centsible.core.model.Money
import java.time.LocalDate
import javax.inject.Inject

/**
 * The app icon, as the month's budget: each colored segment is a group's spending, as a
 * share of what's budgeted (the whole arc); the needle is how far through the month we
 * are. Spending segments that stop short of the needle mean you're under pace.
 */
class BudgetDialWidget @Inject constructor() : DashboardWidget {
    override val id = "core.budget-dial"
    override val order = 20
    override val requires = setOf(Feature.BudgetEnvelope)

    @Composable
    override fun Content(context: DashboardContext) {
        val month = context.month ?: return
        val dial = BudgetDial.of(month, context.today)
        val cream = Motion.Cream
        val soft = cream.copy(alpha = 0.72f)
        Surface(
            onClick = { context.navigate(Destination.Budget) },
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            color = Motion.Forest,
            contentColor = cream,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandDial(
                        segments = dial.slices.map { DialSegment(it.fraction, it.color) },
                        needle = dial.pace,
                        size = 136.dp,
                        key = month.month,
                        description = dial.description,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("This month", style = MaterialTheme.typography.labelMedium, color = soft)
                        MoneyText(dial.spent, style = MaterialTheme.typography.headlineMedium, showCents = false, color = cream, animate = true)
                        Text("spent of ${MoneyFormat.format(dial.budgeted, showCents = false)}", style = MaterialTheme.typography.bodyMedium, color = soft)
                        Spacer(Modifier.height(10.dp))
                        Text(dial.status, style = MaterialTheme.typography.labelLarge, color = cream)
                        dial.day?.let { (d, of) -> Text("Day $d of $of", style = MaterialTheme.typography.bodySmall, color = soft) }
                    }
                }
                if (dial.slices.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    dial.slices.forEachIndexed { i, s ->
                        // A group opens its transactions this month; "Other" opens the budget.
                        val open = s.groupId?.let { g -> { context.navigate(Destination.TransactionsFor.month(s.name, month.month, groupId = g)) } }
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (open != null) Modifier.clickable(onClickLabel = "See ${s.name} transactions", onClick = open) else Modifier)
                                .padding(vertical = 4.dp)
                                .staggeredEntrance(i + 4, key = month.month),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(10.dp).background(s.color, CircleShape))
                            Text(s.name, style = MaterialTheme.typography.bodyMedium, color = cream, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(MoneyFormat.format(s.amount, showCents = false), style = MaterialTheme.typography.labelLarge, color = soft)
                        }
                    }
                }
            }
        }
    }
}

/** What the dial shows, worked out from Actual's month (display arithmetic only). */
internal data class BudgetDial(
    val spent: Money,
    val budgeted: Money,
    val slices: List<Slice>,
    val pace: Float?,
    val day: Pair<Int, Int>?,
    val status: String,
) {
    /** [groupId] is null for "Other". */
    data class Slice(val name: String, val amount: Money, val fraction: Float, val color: Color, val groupId: app.centsible.core.model.CategoryGroupId? = null)

    val description: String
        get() = "Budget dial: ${MoneyFormat.format(spent, showCents = false)} spent of ${MoneyFormat.format(budgeted, showCents = false)}. $status."

    companion object {
        private const val TOP = 4

        fun of(month: BudgetMonth, today: LocalDate): BudgetDial {
            val spent = month.totalSpent.abs()
            val budgeted = month.totalBudgeted
            val groups = month.expenseGroups.filter { !it.hidden && it.spent.minor < 0 }
                .map { Triple(it.name, it.spent.abs(), it.id) }
                .sortedByDescending { it.second.minor }
            // The whole arc is the budget; if nothing is budgeted (or spending passed it), it's what was spent.
            val whole = maxOf(budgeted.minor, spent.minor).toFloat()
            val top = groups.take(TOP)
            val rest = groups.drop(TOP)
            val slices = buildList {
                top.forEachIndexed { i, (name, amount, id) -> add(Slice(name, amount, amount.minor / whole, Motion.Segments[i], id)) }
                if (rest.isNotEmpty()) {
                    val other = Money(rest.sumOf { it.second.minor })
                    add(Slice("Other", other, other.minor / whole, Motion.Cream.copy(alpha = 0.55f)))
                }
            }.takeIf { whole > 0f } ?: emptyList()

            val ym = java.time.YearMonth.of(month.month.year, month.month.month)
            val days = ym.lengthOfMonth()
            val (pace, day) = when {
                java.time.YearMonth.from(today) == ym -> Pair(today.dayOfMonth.toFloat() / days, today.dayOfMonth to days)
                ym.atDay(1).isBefore(today) -> Pair(1f, null)
                else -> Pair(0f, null)
            }
            val share = if (budgeted.minor > 0) spent.minor.toFloat() / budgeted.minor else null
            val status = when {
                share == null -> if (spent.isZero) "Nothing budgeted yet" else "Nothing budgeted"
                share > 1f -> "${MoneyFormat.format(Money(spent.minor - budgeted.minor), showCents = false)} over budget"
                pace >= 1f -> "Under budget"
                share <= pace + 0.02f -> "On pace"
                else -> "Ahead of pace"
            }
            return BudgetDial(spent, budgeted, slices, pace, day, status)
        }
    }
}
