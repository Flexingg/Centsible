package app.centsible.glance

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.centsible.MainActivity
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.domain.Glanceable
import java.text.DateFormat
import java.util.Date

/** Opens a screen in the app through its own centsible://open link. */
internal fun openIntent(context: Context, path: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("centsible://open/$path"), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** The widget's refresh button: reads the bridge now. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        GlanceRefresher.refreshSoon(context)
    }
}

private val SMALL = DpSize(110.dp, 110.dp)
private val WIDE = DpSize(250.dp, 110.dp)

/**
 * "This month": what's left to budget (or spent so far, for tracking budgets), how much of
 * the budget is spent, and how many transactions wait for review, with Add and Review.
 */
class BudgetWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = context.glanceDeps().store().read()
        provideContent { GlanceTheme { BudgetContent(context, data) } }
    }
}

class BudgetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BudgetWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        GlanceRefresher.schedule(context)
        GlanceRefresher.refreshSoon(context)
    }
}

/** The next bills from recurring schedules, over the coming two weeks. */
class BillsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = context.glanceDeps().store().read()
        provideContent { GlanceTheme { BillsContent(context, data) } }
    }
}

class BillsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BillsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        GlanceRefresher.schedule(context)
        GlanceRefresher.refreshSoon(context)
    }
}

@Composable
private fun Frame(context: Context, open: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(GlanceTheme.colors.widgetBackground)
            .padding(14.dp).clickable(actionStartActivity(openIntent(context, open))),
    ) { content() }
}

@Composable
private fun Label(text: String) = Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)

@Composable
private fun Big(text: String) = Text(text, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 26.sp, fontWeight = FontWeight.Bold), maxLines = 1)

@Composable
private fun Small(text: String) = Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 2)

@Composable
private fun Chip(context: Context, text: String, open: String) {
    Text(
        text,
        style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        modifier = GlanceModifier.cornerRadius(14.dp).background(GlanceTheme.colors.primary).padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(actionStartActivity(openIntent(context, open))),
    )
}

/** Shown before the first refresh, signed out, or with the app lock on. */
@Composable
private fun Placeholder(context: Context, data: Glanceable?) {
    Frame(context, if (data?.locked == true) "budget" else "review") {
        Label("Centsible")
        Spacer(GlanceModifier.height(6.dp))
        Small(
            when {
                data?.locked == true -> "App lock is on, so amounts stay hidden. Tap to open Centsible."
                else -> "Open Centsible once to show your budget here."
            },
        )
    }
}

@Composable
internal fun BudgetContent(context: Context, data: Glanceable?) {
    if (data == null || data.locked) return Placeholder(context, data)
    val wide = LocalSize.current.width >= WIDE.width
    Frame(context, "budget") {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(data.month + (data.budgetName?.let { " · $it" } ?: ""))
            Spacer(GlanceModifier.defaultWeight())
            Text("↻", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp), modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()))
        }
        Spacer(GlanceModifier.height(4.dp))
        if (data.envelope) {
            Big(MoneyFormat.format(data.toBudget, showCents = false))
            Small(if (data.toBudget.isNegative) "over-assigned" else "to budget")
        } else {
            Big(MoneyFormat.format(data.spent, showCents = false))
            Small("spent this month")
        }
        data.spentShare?.let { share ->
            Spacer(GlanceModifier.height(8.dp))
            LinearProgressIndicator(
                progress = share,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = if (share >= 1f) GlanceTheme.colors.error else GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.secondaryContainer,
            )
            Spacer(GlanceModifier.height(4.dp))
            Small("${MoneyFormat.format(data.spent, showCents = false)} of ${MoneyFormat.format(data.budgeted, showCents = false)} spent")
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Chip(context, "+ Add", "new-transaction")
            if (wide || data.reviewCount > 0) {
                Spacer(GlanceModifier.width(8.dp))
                Chip(context, if (data.reviewCount > 0) "Review ${data.reviewCount}" else "Review", "review")
            }
        }
    }
}

@Composable
internal fun BillsContent(context: Context, data: Glanceable?) {
    if (data == null || data.locked) return Placeholder(context, data)
    Frame(context, "recurring") {
        Label("Upcoming bills")
        Spacer(GlanceModifier.height(6.dp))
        if (data.bills.isEmpty()) {
            Small("Nothing due in the next two weeks.")
        }
        val rows = (LocalSize.current.height.value / 44).toInt().coerceIn(1, 5)
        data.bills.take(rows).forEach { bill ->
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(bill.title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    Small(bill.whenText)
                }
                Text(MoneyFormat.format(bill.amount), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Small("Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(data.updatedAt)))
    }
}
