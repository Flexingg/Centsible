package app.canopy.feature.onboarding

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
class PairingScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pairing_light() {
        compose.setContent {
            CanopyTheme(darkTheme = false) {
                PairingScreen(PairingUiState(bridgeUrl = "https://budget-api.example.com", code = "YWJ5-P8PK", deviceName = "Pixel 9", cfId = "abc.access"), {}, {}, {}, {}, {})
            }
        }
        compose.onRoot().captureRoboImage("screenshots/pairing_light.png")
    }
}
