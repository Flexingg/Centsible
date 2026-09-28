package app.centsible.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.SectionCard
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.InsightsGateway
import app.centsible.core.extensions.DashboardContext
import app.centsible.core.extensions.DashboardWidget
import app.centsible.core.extensions.Destination
import app.centsible.core.model.Feature
import app.centsible.core.model.Insight
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.time.LocalDate
import javax.inject.Inject

/** The two most important alerts this month; hidden when nothing stands out. */
class InsightsWidget @Inject constructor(private val insights: InsightsGateway) : DashboardWidget {
    override val id = "reports.insights"
    override val order = 150
    override val requires = setOf(Feature.ReportsSpending)

    @Composable
    override fun Content(context: DashboardContext) {
        val colors = CentsibleTheme.colors
        val alerts by produceState<List<Insight>?>(null, context.budget) {
            value = runCatching { insights.insights(context.budget).alerts.filter { it.severity != Insight.Severity.Info } }.getOrNull()
        }
        val list = alerts?.takeIf { it.isNotEmpty() } ?: return
        SectionCard("Worth a look", action = "Trends", onAction = { context.navigate(Destination.Trends) }, contentPadding = PaddingValues(bottom = 4.dp)) {
            list.take(2).forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.border)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).padding(vertical = 2.dp).background(if (a.severity == Insight.Severity.Good) colors.positive else colors.warning, RoundedCornerShape(2.dp)).padding(vertical = 16.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.title, style = MaterialTheme.typography.bodyLarge)
                        Text(a.detail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 2)
                    }
                }
            }
        }
    }
}

/** Shows in December and January: the year, wrapped. */
class YearInReviewWidget @Inject constructor() : DashboardWidget {
    override val id = "reports.year-in-review"
    override val order = 50
    override val requires = setOf(Feature.ReportsSpending)

    @Composable
    override fun Content(context: DashboardContext) {
        val today = LocalDate.now()
        val year = when (today.monthValue) {
            12 -> today.year
            1 -> today.year - 1
            else -> return
        }
        CentsibleCard(onClick = { context.navigate(Destination.YearInReview) }, contentPadding = PaddingValues(0.dp)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(listOf(Color(0xFF3B1F6B), Color(0xFFB0306A), Color(0xFFE8663D))))
                    .padding(20.dp),
            ) {
                Text("✨ Your $year in money", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("Top categories, favorite spots, no-spend streaks and more.", style = MaterialTheme.typography.bodyMedium, color = Color(0xE6FFFFFF))
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class InsightWidgetsModule {
    @Binds @IntoSet abstract fun insights(w: InsightsWidget): DashboardWidget
    @Binds @IntoSet abstract fun yearInReview(w: YearInReviewWidget): DashboardWidget
}
