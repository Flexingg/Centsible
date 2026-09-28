package app.centsible.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AppRelease
import app.centsible.core.model.Backup
import app.centsible.core.model.BackupBudget
import app.centsible.core.model.BackupOverview
import app.centsible.core.model.BudgetId
import app.centsible.core.model.ServerStatus
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi")
class ServerScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private val status = ServerStatus(
        "0.2.21", "0.2.25", true, 3 * 86_400, "26.9.0", "26.9.0", "ok", "26.10.0", true,
        AppRelease("0.2.25", 25, "2026-09-28T12:00:00Z", "https://github.com/Flexingg/Centsible/releases/tag/v0.2.25", "https://x/app.apk", null),
        true, null, null, 48_300_000_000, 250_000_000_000,
    )
    private fun backup(id: String, at: String, scheduled: Boolean) = Backup(
        id, at, scheduled, 3_412_998,
        listOf(BackupBudget(BudgetId("b1"), "Household", "household-b1.zip", 2_981_004), BackupBudget(BudgetId("b2"), "Side business", "side-business-b2.zip", 401_220)),
        emptyList(), true,
    )
    private val backups = BackupOverview(
        24, 14, listOf(0, 6, 12, 24, 168), "2026-09-28T03:00:00Z", null, "2026-09-29T03:00:00Z", 10_238_994,
        listOf(backup("20260928T030000Z", "2026-09-28T03:00:00Z", true), backup("20260927T201500Z", "2026-09-27T20:15:00Z", false), backup("20260927T030000Z", "2026-09-27T03:00:00Z", true)),
    )
    private val state = ServerUiState(Loadable.Ready(status), Loadable.Ready(backups), isOwner = true, installedVersion = "0.2.21", installedCode = 21)

    @Test fun server() {
        compose.setContent { CentsibleTheme(darkTheme = false) { ServerScreen(state, ServerActions()) } }
        compose.onRoot().captureRoboImage("screenshots/server.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun server_updating() {
        compose.setContent {
            CentsibleTheme(darkTheme = true) {
                ServerScreen(state.copy(updatingFrom = "0.2.21", download = AppDownload.Running(0.42f)), ServerActions())
            }
        }
        compose.onRoot().captureRoboImage("screenshots/server_updating_dark.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun backup_sheet() {
        compose.setContent { CentsibleTheme(darkTheme = false) { ServerScreen(state.copy(openBackup = backups.items[0]), ServerActions()) } }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/server_backup_sheet.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
