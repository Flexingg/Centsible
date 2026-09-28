package app.centsible.feature.onboarding

import app.centsible.core.model.ActualSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {
    private val ready = OnboardingUiState(url = "budget-api.example.com", setupCode = "K7QM-2WXP", displayName = "Jo", deviceName = "Pixel 9")

    @Test fun `a bare host becomes an https address`() = assertEquals("https://budget-api.example.com", ready.address?.url)

    @Test fun `a new Actual server needs a confirmed password of 8+`() {
        val s = ready.copy(actual = ActualSetup.NeedsPassword)
        assertFalse(s.copy(actualPassword = "short", actualPasswordAgain = "short").canClaim)
        assertEquals("At least 8 characters", s.copy(actualPassword = "short").passwordProblem)
        assertFalse(s.copy(actualPassword = "long-enough", actualPasswordAgain = "different!").canClaim)
        assertTrue(s.copy(actualPassword = "long-enough", actualPasswordAgain = "long-enough").canClaim)
    }

    @Test fun `an existing Actual server just needs its password`() {
        assertFalse(ready.copy(actual = ActualSetup.NeedsLogin).canClaim)
        assertTrue(ready.copy(actual = ActualSetup.NeedsLogin, actualPassword = "whatever").canClaim)
    }

    @Test fun `nothing to type when the bridge is already signed in to Actual`() {
        assertTrue(ready.copy(actual = ActualSetup.Ready).canClaim)
        assertNull(ready.copy(actual = ActualSetup.Ready).passwordProblem)
    }

    @Test fun `can't set up while Actual is unreachable`() = assertFalse(ready.copy(actual = ActualSetup.Unreachable).canClaim)

    @Test fun `the Cloudflare token rides along`() {
        val a = ready.copy(cfId = " id.access ", cfSecret = "secret").address!!
        assertEquals("id.access", a.cfAccessClientId)
        assertEquals("secret", a.cfAccessClientSecret)
    }
}
