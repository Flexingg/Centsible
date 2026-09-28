package app.centsible.core.extensions

import androidx.compose.runtime.Composable
import app.centsible.core.model.Account
import app.centsible.core.model.BudgetId
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.Capabilities
import app.centsible.core.model.Feature
import app.centsible.core.model.Member
import app.centsible.core.model.Transaction
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Extension points. Core screens and add-on features plug in the same way, via Hilt
 * multibindings, so adding a feature never means editing a core screen:
 *
 *     @Module @InstallIn(SingletonComponent::class)
 *     abstract class MyWidgetModule {
 *         @Binds @IntoSet abstract fun widget(w: MyWidget): DashboardWidget
 *     }
 */
interface DashboardWidget {
    /** Stable id, also used to remember a person's widget order later. */
    val id: String

    /** Lower sorts first. Core widgets use multiples of 100. */
    val order: Int

    /** Hidden unless the bridge reports all of these. */
    val requires: Set<Feature> get() = emptySet()

    @Composable
    fun Content(context: DashboardContext)
}

/** Data the dashboard already loaded; widgets needing more can load their own. */
data class DashboardContext(
    val budget: BudgetId,
    val member: Member?,
    val capabilities: Capabilities,
    val month: BudgetMonth?,
    val accounts: List<Account>,
    val recentTransactions: List<Transaction>,
    val categoryNames: Map<String, String>,
    val navigate: (Destination) -> Unit,
)

/** Places a widget (or any screen) may send the person. */
sealed interface Destination {
    data object Accounts : Destination
    data object Transactions : Destination
    data object Budget : Destination
    data object Settings : Destination
    data object Recurring : Destination
    data object Reports : Destination
    data object NetWorth : Destination
    data object Trends : Destination
    data object YearInReview : Destination
    data class Account(val id: app.centsible.core.model.AccountId) : Destination
    /** Opens the editor; a null id starts a new transaction. */
    data class Transaction(val id: app.centsible.core.model.TransactionId?, val account: app.centsible.core.model.AccountId? = null) : Destination
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ExtensionPointsModule {
    // Declares the sets so they exist even when nothing contributes to them.
    @Multibinds abstract fun dashboardWidgets(): Set<DashboardWidget>
}
