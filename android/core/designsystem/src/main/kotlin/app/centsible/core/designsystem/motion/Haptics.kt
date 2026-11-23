package app.centsible.core.designsystem.motion

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Expressive haptics & subtle tactile sounds that match the motion:
 * - [tap]: light crisp click for tabs, cards, chips, and buttons.
 * - [tick]: delicate mechanical notch when something settles or steps (dial, month stepper, card reorder).
 * - [toggle]: rising/falling pop for switches and filter toggles.
 * - [threshold]: warm thump when a swipe crosses its action threshold or pull-to-refresh arms.
 * - [confirm]: double-tick + soft 2-note chime when a save, rule, or review lands.
 * - [celebrate]: expressive ascending composition + 3-note chime for inbox zero / all caught up.
 * - [reject]: soft low double-bump for errors, overspent warnings, or deletes.
 *
 * Respects both Centsible's Settings → Haptics & sounds toggles and Android's own
 * touch-feedback / ringer-mode settings.
 */
class Haptics internal constructor(
    private val feedback: HapticFeedback,
    private val context: Context,
    private val prefs: FeedbackPreferences,
) {
    private val vibrator: Vibrator? by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
        }.getOrNull()
    }

    private val lowTickPrimitive: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK

    private val thudPrimitive: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_THUD else VibrationEffect.Composition.PRIMITIVE_CLICK

    fun tap() {
        haptic(HapticFeedbackType.SegmentTick) {
            composePrimitive(lowTickPrimitive to 0.55f)
        }
        sound(TactileSounds.Effect.Tap)
    }

    fun tick() {
        haptic(HapticFeedbackType.SegmentTick) {
            composePrimitive(VibrationEffect.Composition.PRIMITIVE_TICK to 0.45f)
        }
        sound(TactileSounds.Effect.Tick)
    }

    fun toggle(on: Boolean) {
        haptic(HapticFeedbackType.SegmentTick) {
            if (on) composePrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK to 0.7f)
            else composePrimitive(lowTickPrimitive to 0.65f)
        }
        sound(if (on) TactileSounds.Effect.ToggleOn else TactileSounds.Effect.ToggleOff)
    }

    fun threshold() {
        haptic(HapticFeedbackType.GestureThresholdActivate) {
            composePrimitive(thudPrimitive to 0.75f)
        }
        sound(TactileSounds.Effect.Threshold)
    }

    fun confirm() {
        haptic(HapticFeedbackType.Confirm) {
            composePrimitives(
                listOf(
                    Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, 0),
                    Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.85f, 45),
                ),
            )
        }
        sound(TactileSounds.Effect.Confirm)
    }

    fun celebrate() {
        haptic(HapticFeedbackType.Confirm) {
            composePrimitives(
                listOf(
                    Triple(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 0),
                    Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f, 40),
                    Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 1.0f, 55),
                ),
            )
        }
        sound(TactileSounds.Effect.Celebrate)
    }

    fun reject() {
        haptic(HapticFeedbackType.Reject) {
            composePrimitives(
                listOf(
                    Triple(thudPrimitive, 0.75f, 0),
                    Triple(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.65f, 50),
                ),
            )
        }
        sound(TactileSounds.Effect.Reject)
    }

    private fun sound(effect: TactileSounds.Effect) {
        if (prefs.sounds) TactileSounds.play(context, effect)
    }

    private fun haptic(fallback: HapticFeedbackType, expressive: () -> Boolean) {
        if (!prefs.haptics) return
        val played = runCatching { expressive() }.getOrDefault(false)
        if (!played) runCatching { feedback.performHapticFeedback(fallback) }
    }

    private fun composePrimitive(pair: Pair<Int, Float>): Boolean =
        composePrimitives(listOf(Triple(pair.first, pair.second, 0)))

    private fun composePrimitives(steps: List<Triple<Int, Float, Int>>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val v = vibrator ?: return false
        if (!v.hasVibrator()) return false
        val ids = steps.map { it.first }.distinct().toIntArray()
        val supported = runCatching { v.areAllPrimitivesSupported(*ids) }.getOrDefault(false)
        if (!supported) return false
        val comp = VibrationEffect.startComposition()
        for ((primitive, scale, delayMs) in steps) {
            comp.addPrimitive(primitive, scale.coerceIn(0f, 1f), delayMs)
        }
        v.vibrate(comp.compose())
        return true
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val prefs = LocalFeedbackPreferences.current
    return remember(feedback, context, prefs) { Haptics(feedback, context, prefs) }
}
