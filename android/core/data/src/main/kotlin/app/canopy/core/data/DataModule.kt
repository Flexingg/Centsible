package app.canopy.core.data

import app.canopy.core.domain.BudgetChanges
import app.canopy.core.domain.ConnectionStatus
import app.canopy.core.domain.PendingChanges
import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.HouseholdGateway
import app.canopy.core.domain.PairingGateway
import app.canopy.core.domain.SessionStore
import app.canopy.core.engine.bridge.BridgeBudgetEngine
import app.canopy.core.engine.bridge.BridgeHouseholdGateway
import app.canopy.core.engine.bridge.BridgePairingGateway
import app.canopy.core.network.BridgeApi
import app.canopy.core.network.PlanningApi
import app.canopy.core.domain.AccountServices
import app.canopy.core.domain.PlanningGateway
import app.canopy.core.domain.ReportsGateway
import app.canopy.core.engine.bridge.BridgeAccountServices
import app.canopy.core.engine.bridge.BridgePlanningGateway
import app.canopy.core.engine.bridge.BridgeReports
import app.canopy.core.network.BridgeClient
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

    // The engine seam: swap this binding for an on-device engine later.
    @Binds abstract fun budgetEngine(impl: NotifyingBudgetEngine): BudgetEngine
    @Binds abstract fun budgetChanges(impl: NotifyingBudgetEngine): BudgetChanges
    @Binds abstract fun pendingChanges(impl: OutboxSync): PendingChanges
    @Binds abstract fun household(impl: BridgeHouseholdGateway): HouseholdGateway
    @Binds abstract fun pairing(impl: BridgePairingGateway): PairingGateway

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
        fun planning(api: PlanningApi, changes: NotifyingBudgetEngine): PlanningGateway = BridgePlanningGateway(api, changes::notifyChanged)

        @Provides
        @Singleton
        fun accountServices(api: PlanningApi, changes: NotifyingBudgetEngine): AccountServices = BridgeAccountServices(api, changes::notifyChanged)

        @Provides
        @Singleton
        fun reports(api: PlanningApi): ReportsGateway = BridgeReports(api)
    }
}
