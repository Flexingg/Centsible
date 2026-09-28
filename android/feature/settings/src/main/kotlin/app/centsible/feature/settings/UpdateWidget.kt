package app.centsible.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.ServerGateway
import app.centsible.core.extensions.DashboardContext
import app.centsible.core.extensions.DashboardWidget
import app.centsible.core.extensions.Destination
import app.centsible.core.model.Role
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

/** Tops the dashboard when a newer app (anyone) or server (owners) is out. */
class UpdateWidget @Inject constructor(private val server: ServerGateway) : DashboardWidget {
    override val id = "settings.update"
    override val order = 10

    @Composable
    override fun Content(context: DashboardContext) {
        val android = LocalContext.current
        val text by produceState<String?>(null, context.budget) {
            val s = runCatching { server.status() }.getOrNull() ?: return@produceState
            val app = s.app?.takeIf { (it.versionCode ?: 0) > AppUpdater.installedVersionCode(android) && it.apkUrl != null }
            val owner = context.member?.role == Role.Owner
            value = when {
                app != null && owner && s.bridgeUpdateAvailable -> "Centsible ${app.version} is out, for the app and the server."
                app != null -> "Centsible ${app.version} is out. Tap to update the app."
                owner && s.bridgeUpdateAvailable -> "A server update (${s.bridgeLatest}) is ready."
                else -> null
            }
        }
        val message = text ?: return
        CentsibleCard(onClick = { context.navigate(Destination.Server) }, contentPadding = PaddingValues(16.dp)) {
            Column {
                Text("✨ Update available", style = MaterialTheme.typography.titleSmall, color = CentsibleTheme.colors.accent)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsWidgetsModule {
    @Binds @IntoSet abstract fun update(w: UpdateWidget): DashboardWidget
}
