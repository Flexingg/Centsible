package app.centsible.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.ActualSetup
import app.centsible.core.model.Budget
import com.github.takahirom.roborazzi.captureRoboImage
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

    private fun render(state: OnboardingUiState, name: String) {
        compose.setContent { CentsibleTheme(darkTheme = false) { OnboardingScreen(state, OnboardingActions()) } }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun onboarding_address() = render(OnboardingUiState(url = "budget-api.example.com", deviceName = "Pixel 9"), "onboarding_address")

    @Test fun onboarding_access_needed() = render(
        OnboardingUiState(url = "budget-api.example.com", showAccess = true, error = "This address is protected by Cloudflare Access. Add a service token to connect."),
        "onboarding_access",
    )

    @Test fun onboarding_setup_new_actual() = render(
        OnboardingUiState(
            step = OnboardingStep.Setup, url = "budget-api.example.com", actual = ActualSetup.NeedsPassword,
            setupCode = "K7QM-2WXP", displayName = "Jo", deviceName = "Pixel 9", actualPassword = "correct-horse", actualPasswordAgain = "correct-horse",
        ),
        "onboarding_setup",
    )

    @Test fun onboarding_setup_unreachable() = render(
        OnboardingUiState(step = OnboardingStep.Setup, url = "budget-api.example.com", actual = ActualSetup.Unreachable, deviceName = "Pixel 9"),
        "onboarding_setup_unreachable",
    )

    @Test fun onboarding_join() = render(
        OnboardingUiState(step = OnboardingStep.Join, url = "budget-api.example.com", inviteCode = "YWJ5-P8PK", deviceName = "Pixel 9"),
        "onboarding_join",
    )

    @Test fun first_budget() {
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                BudgetPickerScreen(Result.success(emptyList<Budget>()), isOwner = true, creating = false, error = null, {}, {}, {}, {})
            }
        }
        compose.onRoot().captureRoboImage("screenshots/onboarding_first_budget.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
