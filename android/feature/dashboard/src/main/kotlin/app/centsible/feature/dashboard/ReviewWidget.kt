package app.centsible.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.MerchantAvatar
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.extensions.DashboardContext
import app.centsible.core.extensions.DashboardWidget
import app.centsible.core.extensions.Destination
import app.centsible.core.model.Feature
import javax.inject.Inject

/** "7 transactions to review": this person's inbox, when there's anything in it. */
class ReviewWidget @Inject constructor() : DashboardWidget {
    override val id = "core.review"
    override val order = 15
    override val requires = setOf(Feature.TransactionsRead)

    @Composable
    override fun Content(context: DashboardContext) {
        val count = context.reviewCount ?: return
        if (count == 0) return
        val colors = CentsibleTheme.colors
        CentsibleCard(onClick = { context.navigate(Destination.Review) }, contentPadding = PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The first few merchants, fanned out.
                Box(Modifier.width((32 + 20 * (context.reviewPreview.size.coerceAtMost(3) - 1).coerceAtLeast(0)).dp).clearAndSetSemantics { }) {
                    context.reviewPreview.take(3).forEachIndexed { i, t ->
                        // A ring in the card's color keeps overlapping avatars apart.
                        Box(Modifier.offset(x = (20 * i).dp).background(colors.card, CircleShape).padding(2.dp)) {
                            MerchantAvatar(t.payeeName, size = 36.dp, modifier = Modifier.background(colors.card, CircleShape))
                        }
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (count >= 500) "500+ to review" else if (count == 1) "1 transaction to review" else "$count transactions to review",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("New and uncategorized, since you last looked", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                Text("Review", style = MaterialTheme.typography.labelLarge, color = colors.accent)
            }
        }
    }
}
