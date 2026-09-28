package app.centsible.core.designsystem.motion

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The app's motion language, taken from the splash ("dial turn"): quick ease-outs,
 * a settling overshoot for things that turn, a springy overshoot for things that
 * grow. Every helper here respects "Remove animations": with it on, values simply
 * appear at their final state.
 */
object Motion {
    /** The dial's turn: fast start, long settle (the splash's cubic-bezier(.2,.7,.2,1)). */
    val Dial = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)
    /** The cent bar's spring: overshoots and comes back (cubic-bezier(.3,1.5,.5,1)). */
    val Spring = CubicBezierEasing(0.3f, 1.5f, 0.5f, 1f)
    val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    const val SHORT = 220
    const val MEDIUM = 450
    const val LONG = 900
    /** Between staggered items (list rows, legend entries, story lines). */
    const val STAGGER = 60

    /** The icon's segment colors, in its order, then more for longer lists. */
    val Segments = listOf(
        Color(0xFF7FD1A8), Color(0xFFF2B45C), Color(0xFFE8735E), Color(0xFF9FB8F0),
        Color(0xFFB79CE8), Color(0xFF6CC3C9), Color(0xFFEFA3C1), Color(0xFFCDB38B),
    )
    val Forest = Color(0xFF0E3B2E)
    val Cream = Color(0xFFF7F3EA)
}

/** True when the person turned animations off (or they're scaled to zero). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotionSetting(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

val reducedMotion: Boolean
    @Composable @ReadOnlyComposable get() = LocalReducedMotion.current

/**
 * 0 to 1, once, when first shown (after [delayMillis]). Keyed on [key], so new content
 * plays again. 1 straight away with reduced motion.
 */
@Composable
fun rememberEntrance(key: Any? = Unit, delayMillis: Int = 0, durationMillis: Int = Motion.MEDIUM, easing: androidx.compose.animation.core.Easing = Motion.EaseOut): Float {
    val reduced = reducedMotion
    val anim = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key) {
        if (!reduced) anim.animateTo(1f, tween(durationMillis, delayMillis, easing))
    }
    return anim.value
}

/**
 * A number that counts: from 0 the first time it's shown, then from the old value to
 * the new one whenever it changes. Minor units (cents), so no Float rounding.
 */
@Composable
fun animateMinorUnits(target: Long, countUp: Boolean = true, durationMillis: Int = Motion.LONG): Long {
    val reduced = reducedMotion
    var from by remember { mutableLongStateOf(if (countUp && !reduced) 0L else target) }
    var to by remember { mutableLongStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (reduced) {
            from = target; to = target
            return@LaunchedEffect
        }
        // Continue from wherever the number is right now.
        from = from + ((to - from) * progress.value.toDouble()).toLong()
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis, easing = Motion.EaseOut))
    }
    return from + ((to - from) * progress.value.toDouble()).toLong()
}

/** Rises and fades in, [index] steps after its siblings (lists, legends, story lines). */
fun Modifier.staggeredEntrance(index: Int, key: Any? = Unit, distance: Int = 16): Modifier = composed {
    val t = rememberEntrance(key, delayMillis = index * Motion.STAGGER, durationMillis = Motion.MEDIUM)
    val px = distance.dp
    graphicsLayer {
        alpha = t
        translationY = (1f - t) * px.toPx()
    }
}

/** A gentle shimmer for placeholders while content loads. */
@Composable
fun rememberShimmer(): Float {
    if (reducedMotion) return 0.5f
    val transition = rememberInfiniteTransition(label = "shimmer")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "shimmer")
    return t
}

/** Something just added (a saved transaction): drops into place with a little spring. */
fun Modifier.landing(key: Any? = Unit): Modifier = composed {
    val t = rememberEntrance(key, durationMillis = Motion.MEDIUM + 150, easing = Motion.Spring)
    graphicsLayer {
        alpha = t.coerceIn(0f, 1f)
        translationY = (1f - t) * -20.dp.toPx()
        val s = 0.96f + 0.04f * t
        scaleX = s
        scaleY = s
    }
}

/** A small hop when something becomes selected (bottom bar icons). Not on first show. */
fun Modifier.bounceOnSelect(selected: Boolean): Modifier = composed {
    val reduced = reducedMotion
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (first) { first = false; return@LaunchedEffect }
        if (!selected || reduced) return@LaunchedEffect
        scale.animateTo(1.22f, tween(120, easing = Motion.EaseOut))
        scale.animateTo(1f, tween(360, easing = Motion.Spring))
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        translationY = (1f - scale.value) * 18f
    }
}
