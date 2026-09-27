package app.canopy.feature.accounts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.testing.SampleHousehold
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage

import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AccountsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accounts_light() {
        compose.setContent { CanopyTheme(darkTheme = false) { AccountsScreen(AccountsUiState(Loadable.Ready(AccountsSummary.from(SampleHousehold.accounts)), canWrite = true), onRetry = {}) } }
        compose.onRoot().captureRoboImage("screenshots/accounts_light.png")
    }
}
