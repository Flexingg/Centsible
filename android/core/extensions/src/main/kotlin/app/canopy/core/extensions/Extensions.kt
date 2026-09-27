package app.canopy.core.extensions

import androidx.compose.runtime.Composable
import app.canopy.core.model.Account
import app.canopy.core.model.BudgetId
import app.canopy.core.model.BudgetMonth
import app.canopy.core.model.Capabilities
import app.canopy.core.model.Feature
import app.canopy.core.model.Member
import app.canopy.core.model.Transaction
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

/** Top-level places a widget may send the person. */
enum class Destination { Accounts, Transactions, Budget, Settings }

@Module
@InstallIn(SingletonComponent::class)
abstract class ExtensionPointsModule {
    // Declares the sets so they exist even when nothing contributes to them.
    @Multibinds abstract fun dashboardWidgets(): Set<DashboardWidget>
}
