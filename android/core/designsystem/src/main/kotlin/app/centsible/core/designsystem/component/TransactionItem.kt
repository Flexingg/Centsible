package app.centsible.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Transaction
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** "Today", "Yesterday", "Thursday, Sep 24", or "Sep 24, 2025" for other years. */
fun dayLabel(date: String, today: LocalDate): String {
    val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEEE, MMM d" else "MMM d, yyyy"))
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun TransactionRow(
    t: Transaction,
    categoryNames: Map<String, String>,
    accountNames: Map<String, String>,
    onClick: (() -> Unit)? = null,
    /** The account's balance after this transaction (one account's list). */
    balance: app.centsible.core.model.Money? = null,
    onLongClick: (() -> Unit)? = null,
    /** In multi-select: a check replaces the avatar. */
    selected: Boolean? = null,
) {
    val colors = CentsibleTheme.colors
    val category = when {
        t.isParent -> "Split · ${t.subtransactions.size}"
        t.isTransfer -> "Transfer"
        else -> t.categoryId?.let { categoryNames[it.raw] }
    }
    Row(
        Modifier.fillMaxWidth()
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick, onLongClickLabel = "Select")
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                },
            )
            .then(if (selected == true) Modifier.background(colors.accentSoft) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            androidx.compose.animation.Crossfade(selected, label = "select") { on ->
                if (on) {
                    AnimatedCheck(size = 40.dp, color = colors.accent, description = "Selected")
                } else {
                    Box(Modifier.size(40.dp).border(2.dp, colors.border, CircleShape))
                }
            }
        } else {
            MerchantAvatar(t.payeeName)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.payeeName ?: "No payee", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (category != null) {
                    Text(
                        "${categoryEmoji(category)} $category",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.background(colors.cardMuted, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                } else {
                    Text(
                        "Uncategorized",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier.background(colors.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                accountNames[t.accountId.raw]?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(t.amount, tone = MoneyTone.Signed, signed = t.amount.minor > 0, style = MaterialTheme.typography.bodyLarge)
            balance?.let {
                Text(
                    MoneyFormat.format(it),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = colors.textTertiary,
                    modifier = Modifier.semantics { contentDescription = "Balance after: ${MoneyFormat.format(it)}" },
                )
            }
        }
    }
}
