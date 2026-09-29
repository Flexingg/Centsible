package app.centsible.feature.planning

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.MerchantAvatar
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.MoneyTone
import app.centsible.core.designsystem.component.SectionCard
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.extensions.DashboardContext
import app.centsible.core.extensions.DashboardWidget
import app.centsible.core.model.Feature
import app.centsible.core.model.Schedule
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.time.LocalDate
import javax.inject.Inject

/**
 * Contributed from outside the dashboard module and loads its own data: the same shape
 * any add-on widget will have.
 */
class UpcomingBillsWidget @Inject constructor(
    private val planning: PlanningGateway,
    private val engine: BudgetEngine,
) : DashboardWidget {
    override val id = "planning.upcoming-bills"
    override val title = "Upcoming bills"
    override val order = 250
    override val requires = setOf(Feature.SchedulesRead)

    @Composable
    override fun Content(context: DashboardContext) {
        val colors = CentsibleTheme.colors
        val today = LocalDate.now()
        val data by produceState<Pair<List<Schedule>, Map<String, String>>?>(null, context.budget) {
            value = runCatching {
                val due = planning.schedules(context.budget).filter { s ->
                    !s.completed && s.nextDate?.let { LocalDate.parse(it) <= today.plusDays(14) } == true
                }.sortedBy { it.nextDate }
                due to engine.payees(context.budget).associate { it.id.raw to it.name }
            }.getOrNull()
        }
        val (due, names) = data ?: return
        if (due.isEmpty()) return
        SectionCard("Coming up", action = "All", onAction = { context.navigate(app.centsible.core.extensions.Destination.Recurring) }, contentPadding = PaddingValues(bottom = 4.dp)) {
            due.take(4).forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = colors.border)
                val title = Describe.scheduleTitle(s, names)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MerchantAvatar(title)
                    Spacer(Modifier.width(12.dp))
                    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(Describe.due(s.nextDate, today), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Spacer(Modifier.width(12.dp))
                    MoneyText(s.amount, tone = MoneyTone.Signed, signed = s.amount.minor > 0, style = MaterialTheme.typography.bodyLarge, showCents = false)
                }
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlanningWidgetsModule {
    @Binds @IntoSet abstract fun upcoming(w: UpcomingBillsWidget): DashboardWidget
}
