package app.canopy.core.data

import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.HouseholdGateway
import app.canopy.core.domain.PairingGateway
import app.canopy.core.domain.SessionStore
import app.canopy.core.engine.bridge.BridgeBudgetEngine
import app.canopy.core.engine.bridge.BridgeHouseholdGateway
import app.canopy.core.engine.bridge.BridgePairingGateway
import app.canopy.core.network.BridgeApi
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
    @Binds abstract fun budgetEngine(impl: BridgeBudgetEngine): BudgetEngine
    @Binds abstract fun household(impl: BridgeHouseholdGateway): HouseholdGateway
    @Binds abstract fun pairing(impl: BridgePairingGateway): PairingGateway

    companion object {
        @Provides
        @Singleton
        fun bridgeClient(sessions: SessionStore): BridgeClient = BridgeClient(
            sessions,
            OkHttp.create {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    // Cloudflare cuts origin requests at ~100 s; fail a little earlier.
                    readTimeout(90, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            },
        )

        @Provides
        @Singleton
        fun bridgeApi(client: BridgeClient) = BridgeApi(client)
    }
}
