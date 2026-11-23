package app.centsible.core.designsystem.motion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** User preferences for expressive haptics and subtle tactile UI sounds. */
@Immutable
data class FeedbackPreferences(
    val haptics: Boolean = true,
    val sounds: Boolean = true,
)

val LocalFeedbackPreferences = staticCompositionLocalOf { FeedbackPreferences() }

/**
 * Lightweight procedural UI sound synthesizer: generates ultra-short (12–180ms), warm,
 * non-intrusive tactile tones in memory with zero external audio assets. Automatically stays
 * silent when the device is in Silent or Vibrate mode.
 */
internal object TactileSounds {
    enum class Effect {
        Tap,
        Tick,
        ToggleOn,
        ToggleOff,
        Threshold,
        Confirm,
        Celebrate,
        Reject,
    }

    private const val SAMPLE_RATE = 24_000
    private val cache = mutableMapOf<Effect, ShortArray>()

    fun play(context: Context, effect: Effect) {
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val pcm = synchronized(cache) { cache.getOrPut(effect) { synthesize(effect) } }
            val bytes = pcm.size * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return
            }
            track.write(pcm, 0, pcm.size)
            track.setVolume(0.28f)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) {
                        runCatching { t.release() }
                    }
                    override fun onPeriodicNotification(t: AudioTrack) = Unit
                },
            )
            track.play()
        }
    }

    private fun synthesize(effect: Effect): ShortArray = when (effect) {
        Effect.Tap -> sweep(ms = 18, f0 = 760.0, f1 = 480.0, decay = 9.0, gain = 0.28)
        Effect.Tick -> sweep(ms = 12, f0 = 1280.0, f1 = 920.0, decay = 14.0, gain = 0.22)
        Effect.ToggleOn -> notes(listOf(Note(587.33, 0, 26, 0.24), Note(880.0, 18, 34, 0.26)))
        Effect.ToggleOff -> notes(listOf(Note(739.99, 0, 24, 0.22), Note(523.25, 16, 34, 0.20)))
        Effect.Threshold -> sweep(ms = 34, f0 = 260.0, f1 = 165.0, decay = 7.5, gain = 0.34)
        Effect.Confirm -> notes(listOf(Note(587.33, 0, 52, 0.24), Note(880.0, 42, 78, 0.28)))
        Effect.Celebrate -> notes(
            listOf(
                Note(523.25, 0, 55, 0.22),
                Note(659.25, 42, 60, 0.24),
                Note(783.99, 84, 65, 0.26),
                Note(1046.50, 126, 95, 0.28),
            ),
        )
        Effect.Reject -> notes(listOf(Note(230.0, 0, 38, 0.26), Note(175.0, 44, 48, 0.24)))
    }

    private data class Note(val freq: Double, val startMs: Int, val durMs: Int, val gain: Double)

    private fun sweep(ms: Int, f0: Double, f1: Double, decay: Double, gain: Double): ShortArray {
        val n = SAMPLE_RATE * ms / 1000
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val freq = f0 + (f1 - f0) * t
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val attack = (i / (SAMPLE_RATE * 0.002)).coerceAtMost(1.0)
            val env = attack * exp(-decay * t)
            out[i] = (sin(phase) * env * gain * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun notes(list: List<Note>): ShortArray {
        val totalMs = list.maxOf { it.startMs + it.durMs }
        val total = SAMPLE_RATE * totalMs / 1000
        val mix = DoubleArray(total)
        for (note in list) {
            val start = SAMPLE_RATE * note.startMs / 1000
            val len = SAMPLE_RATE * note.durMs / 1000
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= total) break
                val t = i.toDouble() / len
                val attack = (i / (SAMPLE_RATE * 0.003)).coerceAtMost(1.0)
                val env = attack * exp(-4.8 * t)
                val angle = 2.0 * PI * note.freq * i / SAMPLE_RATE
                // Fundamental + gentle 2nd harmonic for a warm marimba-like timbre.
                val sample = (sin(angle) + 0.22 * sin(2.0 * angle)) * env * note.gain
                mix[idx] += sample
            }
        }
        return ShortArray(total) { i ->
            (mix[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
