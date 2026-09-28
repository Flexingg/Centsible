package app.centsible.feature.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class SplashScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun frame(t: Float, name: String) {
        compose.setContent { SplashContent(SplashFrame.at(t)) }
        compose.onRoot().captureRoboImage("screenshots/splash_$name.png")
    }

    // Key moments of the design's 4.2s timeline.
    @Test fun spinning_in() = frame(0.20f, "1_spinning")
    @Test fun overshoot() = frame(0.44f, "2_overshoot")
    @Test fun bar_springs() = frame(0.54f, "3_bar")
    @Test fun settled() = frame(0.80f, "4_settled")

    @Test fun timeline_matches_the_design() {
        assertEquals(-150f, SplashFrame.at(0f).dialDegrees)
        assertEquals(9f, SplashFrame.at(0.44f).dialDegrees, 0.01f)
        assertEquals(0f, SplashFrame.at(0.60f).dialDegrees)
        assertEquals(listOf(1f, 1f, 1f, 1f), SplashFrame.at(0.45f).segments)
        assertEquals(0f, SplashFrame.at(0.46f).bar)
        assertEquals(1f, SplashFrame.at(0.58f).bar)
        assertEquals(0f, SplashFrame.at(0.56f).word)
        assertEquals(1f, SplashFrame.at(0.74f).tag)
        assertEquals(1f, SplashFrame.at(0.86f).stage)
        assertEquals(0f, SplashFrame.at(0.96f).stage)
    }

    /** The launcher icon as the system masks it: circle, rounded square, and themed (monochrome). */
    @Config(qualifiers = "w420dp-h180dp-xxhdpi")
    @Test fun launcher_icon() {
        compose.setContent {
            Row(Modifier.background(Color(0xFFE8E4DA)).padding(16.dp)) {
                listOf(CircleShape, RoundedCornerShape(28.dp)).forEach { shape ->
                    Box(Modifier.padding(8.dp).size(108.dp).clip(shape).background(SplashSpec.Forest)) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(108.dp))
                    }
                }
                Box(Modifier.padding(8.dp).size(108.dp).clip(CircleShape).background(Color(0xFFD7E3DC))) {
                    Image(
                        painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.size(108.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF2B4A3F)),
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/launcher_icon.png")
    }
}
