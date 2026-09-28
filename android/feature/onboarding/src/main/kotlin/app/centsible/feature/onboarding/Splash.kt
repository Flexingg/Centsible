package app.centsible.feature.onboarding

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlin.math.PI

/**
 * The launch animation ("dial turn"), after the design's Splash artboard: the budget
 * dial spins in from -150 degrees and settles with a small overshoot while its four
 * segments sweep in one after another, then the cent bar springs up through it and the
 * wordmark and tagline fade in. Same geometry as the launcher icon (1024-unit artwork).
 */
object SplashSpec {
    val Forest = Color(0xFF0E3B2E)
    val Cream = Color(0xFFF7F3EA)
    val Sage = Color(0xFFB9D8C8)
    /** Segment colors and their dash (start, length) along the ring, in artwork units. */
    val Segments = listOf(
        Triple(Color(0xFF7FD1A8), 0f, 520f),
        Triple(Color(0xFFF2B45C), 544f, 380f),
        Triple(Color(0xFFE8735E), 948f, 270f),
        Triple(Color(0xFF9FB8F0), 1242f, 171.72f),
    )
    const val RADIUS = 300f
    const val STROKE = 116f
    val CIRCUMFERENCE = (2 * PI * RADIUS).toFloat()

    /** The design's loop is 4.2s; the app plays it once, through the fade (96%). */
    const val DURATION_MS = 4200
    const val END = 0.96f
}

private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val DialEase = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)
private val BarEase = CubicBezierEasing(0.3f, 1.5f, 0.5f, 1f)

/** CSS-style keyframes: [stops] are (progress 0..1, value); [easing] runs within each interval. */
internal fun keyframes(t: Float, easing: Easing, vararg stops: Pair<Float, Float>): Float {
    if (t <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (p1, v1) = stops[i]
        if (t <= p1) {
            val (p0, v0) = stops[i - 1]
            val f = if (p1 == p0) 1f else easing.transform((t - p0) / (p1 - p0))
            return v0 + (v1 - v0) * f
        }
    }
    return stops.last().second
}

/** Everything the frame at progress [t] (0..1 of the 4.2s loop) needs. */
data class SplashFrame(
    val stage: Float,
    val dialDegrees: Float,
    val segments: List<Float>,
    val bar: Float,
    val word: Float,
    val wordOffset: Float,
    val tag: Float,
) {
    companion object {
        fun at(t: Float) = SplashFrame(
            stage = keyframes(t, EaseInOut, 0.86f to 1f, 0.96f to 0f),
            dialDegrees = keyframes(t, DialEase, 0.04f to -150f, 0.44f to 9f, 0.50f to -3f, 0.55f to 0f),
            segments = listOf(
                keyframes(t, EaseOut, 0.04f to 0f, 0.24f to 1f),
                keyframes(t, EaseOut, 0.13f to 0f, 0.31f to 1f),
                keyframes(t, EaseOut, 0.21f to 0f, 0.37f to 1f),
                keyframes(t, EaseOut, 0.28f to 0f, 0.42f to 1f),
            ),
            bar = keyframes(t, BarEase, 0.46f to 0f, 0.58f to 1f),
            word = keyframes(t, EaseOut, 0.56f to 0f, 0.68f to 1f),
            wordOffset = keyframes(t, EaseOut, 0.56f to 14f, 0.68f to 0f),
            tag = keyframes(t, EaseOut, 0.62f to 0f, 0.74f to 1f),
        )

        /** Settled, before the fade: what reduced motion shows. */
        val Settled = at(0.80f)
    }
}

private val Fraunces = FontFamily(Font(R.font.fraunces_semibold, FontWeight.SemiBold))
private val DmSans = FontFamily(Font(R.font.dm_sans_regular, FontWeight.Normal))

@Composable
fun AnimatedSplash(onFinished: () -> Unit) {
    val context = LocalContext.current
    // "Remove animations" in accessibility settings: show the logo still, briefly.
    val reduced = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) {
            kotlinx.coroutines.delay(700)
        } else {
            progress.animateTo(SplashSpec.END, tween((SplashSpec.DURATION_MS * SplashSpec.END).toInt(), easing = LinearEasing))
        }
        onFinished()
    }
    // Light status and navigation bar icons on the green, for as long as the splash shows.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars to controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            before.first?.let { controller?.isAppearanceLightStatusBars = it }
            before.second?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
    val frame = if (reduced) SplashFrame.Settled else SplashFrame.at(progress.value)
    SplashContent(
        frame,
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onFinished),
    )
}

/** One frame of the splash; also used by the screenshot tests. */
@Composable
fun SplashContent(frame: SplashFrame, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(SplashSpec.Forest).semantics { contentDescription = "Centsible" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(bottom = 64.dp).graphicsLayer { alpha = frame.stage },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(Modifier.size(200.dp)) { drawLogo(frame) }
            Spacer(Modifier.height(28.dp))
            Text(
                "Centsible",
                style = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, letterSpacing = (-0.5).sp, color = SplashSpec.Cream),
                modifier = Modifier.graphicsLayer {
                    alpha = frame.word
                    translationY = frame.wordOffset.dp.toPx()
                },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Where your money goes, down to the cent.",
                style = TextStyle(fontFamily = DmSans, fontSize = 15.sp, color = SplashSpec.Sage),
                modifier = Modifier.graphicsLayer { alpha = frame.tag },
            )
        }
    }
}

/** The logo in 1024-unit artwork space, scaled to the canvas. */
private fun DrawScope.drawLogo(frame: SplashFrame) {
    val unit = size.minDimension / 1024f
    scale(unit, pivot = Offset.Zero) {
        val center = Offset(512f, 512f)
        rotate(frame.dialDegrees, center) {
            // The ring starts at 3 o'clock, turned 45 degrees, and runs clockwise (as the SVG's dashes do).
            val box = Size(SplashSpec.RADIUS * 2, SplashSpec.RADIUS * 2)
            val topLeft = Offset(512f - SplashSpec.RADIUS, 512f - SplashSpec.RADIUS)
            SplashSpec.Segments.forEachIndexed { i, (color, start, length) ->
                val grown = length * frame.segments[i]
                if (grown <= 0f) return@forEachIndexed
                drawArc(
                    color = color,
                    startAngle = 45f + start / SplashSpec.CIRCUMFERENCE * 360f,
                    sweepAngle = grown / SplashSpec.CIRCUMFERENCE * 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = box,
                    style = Stroke(width = SplashSpec.STROKE, cap = StrokeCap.Butt),
                )
            }
        }
        if (frame.bar > 0f) {
            // The bar grows from the middle; a forest-colored halo (the SVG's 28-unit stroke) cuts the ring around it.
            scale(scaleX = 1f, scaleY = frame.bar, pivot = center) {
                drawRoundRect(SplashSpec.Forest, Offset(464f, 126f), Size(96f, 772f), CornerRadius(48f, 48f))
                drawRoundRect(SplashSpec.Cream, Offset(478f, 140f), Size(68f, 744f), CornerRadius(34f, 34f))
            }
        }
    }
}
