package app.centsible.core.data

import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.ConnectionStatus
import app.centsible.core.domain.PendingChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.HouseholdGateway
import app.centsible.core.domain.PairingGateway
import app.centsible.core.domain.SessionStore
import app.centsible.core.engine.bridge.BridgeBudgetEngine
import app.centsible.core.engine.bridge.BridgeHouseholdGateway
import app.centsible.core.engine.bridge.BridgePairingGateway
import app.centsible.core.network.BridgeApi
import app.centsible.core.network.PlanningApi
import app.centsible.core.domain.AccountServices
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.ReportsGateway
import app.centsible.core.engine.bridge.BridgeAccountServices
import app.centsible.core.engine.bridge.BridgePlanningGateway
import app.centsible.core.engine.bridge.BridgeReports
import app.centsible.core.network.BridgeClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun sessionStore(impl: EncryptedSessionStore): SessionStore
    @Binds abstract fun appLock(impl: DataStoreAppLockSettings): app.centsible.core.domain.AppLockSettings
    @Binds abstract fun reminders(impl: DataStoreReminderSettings): app.centsible.core.domain.ReminderSettings

    // The engine seam: swap this binding for an on-device engine later.
    @Binds abstract fun budgetEngine(impl: NotifyingBudgetEngine): BudgetEngine
    @Binds abstract fun budgetChanges(impl: NotifyingBudgetEngine): BudgetChanges
    @Binds abstract fun pendingChanges(impl: OutboxSync): PendingChanges
    @Binds abstract fun household(impl: BridgeHouseholdGateway): HouseholdGateway
    @Binds abstract fun pairing(impl: BridgePairingGateway): PairingGateway
    @Binds abstract fun setup(impl: app.centsible.core.engine.bridge.BridgeSetupGateway): app.centsible.core.domain.SetupGateway

    companion object {
        @Provides
        @Singleton
        fun bridgeClient(sessions: SessionStore, cache: RoomResponseCache): BridgeClient = BridgeClient(
            sessions,
            OkHttp.create {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    // Cloudflare cuts origin requests at ~100 s; fail a little earlier.
                    readTimeout(90, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            },
            cache,
        )

        @Provides
        fun connectionStatus(client: BridgeClient): ConnectionStatus = object : ConnectionStatus {
            override val offline = client.offline
        }

        @Provides
        @Singleton
        fun bridgeApi(client: BridgeClient, outbox: RoomOutbox) = BridgeApi(client, outbox)

        @Provides
        @Singleton
        fun bridgeEngine(api: BridgeApi) = BridgeBudgetEngine(api)

        @Provides
        @Singleton
        fun planningApi(client: BridgeClient) = PlanningApi(client)

        @Provides
        @Singleton
        fun bankSync(api: PlanningApi, changes: NotifyingBudgetEngine): app.centsible.core.domain.BankSyncGateway =
            app.centsible.core.engine.bridge.BridgeBankSync(api, changes::notifyChanged)

        @Provides
        @Singleton
        fun planning(api: PlanningApi, changes: NotifyingBudgetEngine): PlanningGateway = BridgePlanningGateway(api, changes::notifyChanged)

        @Provides
        @Singleton
        fun accountServices(api: PlanningApi, changes: NotifyingBudgetEngine): AccountServices = BridgeAccountServices(api, changes::notifyChanged)

        @Provides
        @Singleton
        fun reports(api: PlanningApi): ReportsGateway = BridgeReports(api)

        @Provides
        @Singleton
        fun planAhead(api: PlanningApi, changes: NotifyingBudgetEngine): app.centsible.core.domain.PlanAheadGateway =
            app.centsible.core.engine.bridge.BridgePlanAhead(api, changes::notifyChanged)

        @Provides
        @Singleton
        fun insights(api: PlanningApi): app.centsible.core.domain.InsightsGateway = app.centsible.core.engine.bridge.BridgeInsights(api)

        @Provides
        @Singleton
        fun personal(client: BridgeClient, changes: NotifyingBudgetEngine): app.centsible.core.domain.PersonalGateway =
            app.centsible.core.engine.bridge.BridgePersonal(app.centsible.core.network.PersonalApi(client), changes::notifyChanged)

        @Provides
        @Singleton
        fun ruleTools(client: BridgeClient, changes: NotifyingBudgetEngine): app.centsible.core.domain.RuleTools =
            app.centsible.core.engine.bridge.BridgeRuleTools(app.centsible.core.network.RuleToolsApi(client), changes::notifyChanged)

        @Provides
        @Singleton
        fun automations(client: BridgeClient, changes: NotifyingBudgetEngine): app.centsible.core.domain.AutomationsGateway =
            app.centsible.core.engine.bridge.BridgeAutomations(app.centsible.core.network.AutomationsApi(client), changes::notifyChanged)

        @Provides
        @Singleton
        fun transactionTools(client: BridgeClient, changes: NotifyingBudgetEngine): app.centsible.core.domain.TransactionTools =
            app.centsible.core.engine.bridge.BridgeTransactionTools(app.centsible.core.network.TransactionToolsApi(client), changes::notifyChanged)

        @Provides
        @Singleton
        fun server(client: BridgeClient): app.centsible.core.domain.ServerGateway =
            app.centsible.core.engine.bridge.BridgeServer(app.centsible.core.network.ServerApi(client))
    }
}
