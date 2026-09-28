package app.centsible.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.testing.SampleHousehold
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import app.centsible.core.domain.Me
import app.centsible.core.model.Device
import app.centsible.core.model.DeviceId
import app.centsible.core.model.Member
import app.centsible.core.model.MemberId
import app.centsible.core.model.PairingInvite
import app.centsible.core.model.Role
import app.centsible.core.testing.FakeBudgetEngine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val jo = Member(MemberId("m1"), "Jo", Role.Owner, false, emptyList())
    private val sam = Member(MemberId("m2"), "Sam", Role.Member, false, listOf(SampleHousehold.budget.id))
    private val kid = Member(MemberId("m3"), "Riley", Role.Viewer, false, emptyList())
    private val device = Device(DeviceId("d1"), "Pixel 9", "android", "2026-09-27T12:00:00Z")
    private val data = SettingsData(
        me = Me(jo, device, listOf(device, Device(DeviceId("d2"), "Galaxy Tab", "android", "2026-09-20T08:00:00Z"))),
        members = listOf(jo, sam, kid),
        capabilities = FakeBudgetEngine().capabilities,
        bridgeUrl = "https://budget-api.example.com",
    )

    @Test fun settings_light() {
        compose.setContent { CentsibleTheme(darkTheme = false) { SettingsScreen(SettingsUiState(Loadable.Ready(data), reminders = true, reminderDays = 1), SettingsActions()) } }
        compose.onRoot().captureRoboImage("screenshots/settings_light.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun settings_invite() {
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                SettingsScreen(
                    SettingsUiState(Loadable.Ready(data), invite = sam to PairingInvite("K7QM-3XTP", "2026-09-27T12:10:00Z", "actualbridge://pair?u=https%3A%2F%2Fbudget-api.example.com&c=K7QM-3XTP")),
                    SettingsActions(),
                )
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/settings_invite.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
