package com.kids.colors

import android.content.Context
import android.media.AudioTrack
import android.media.MediaPlayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Plays animal calls and character effects (chomp, burp, giggle). A real recording in res/raw named after the key (e.g. res/raw/cow.ogg)
 * takes priority; otherwise a synthesized call from [AnimalSynth] is used.
 */
class AnimalSounds(private val context: Context, keys: List<String>) {
    private val synth = HashMap<String, Pair<AudioTrack, Float>>()
    private val recorded = HashMap<String, MediaPlayer>()

    init {
        for (key in keys) {
            @Suppress("DiscouragedApi")
            val id = context.resources.getIdentifier(key, "raw", context.packageName)
            val player = if (id != 0) MediaPlayer.create(context, id) else null
            if (player != null) {
                recorded[key] = player
            } else {
                val pcm = AnimalSynth.render(key) ?: continue
                synth[key] = staticTrack(pcm, AnimalSynth.RATE) to pcm.size.toFloat() / AnimalSynth.RATE
            }
        }
    }

    /** Plays the call (stopping any other) and returns its duration in seconds. */
    fun play(key: String): Float {
        stopAll()
        recorded[key]?.let {
            it.seekTo(0)
            it.start()
            return it.duration / 1000f
        }
        val (track, duration) = synth[key] ?: return 0f
        try {
            track.reloadStaticData()
            track.play()
        } catch (_: IllegalStateException) {
        }
        return duration
    }

    fun stopAll() {
        recorded.values.forEach { if (it.isPlaying) it.pause() }
        synth.values.forEach { (t, _) ->
            try {
                t.stop()
            } catch (_: IllegalStateException) {
            }
        }
    }

    fun release() {
        recorded.values.forEach { it.release() }
        synth.values.forEach { it.first.release() }
    }
}

/**
 * Cartoon animal calls built from a sawtooth/pulse source shaped by two moving formant
 * filters (a crude vocal tract). Pure Kotlin, so it can also be rendered off-device.
 */
object AnimalSynth {
    const val RATE = 22050

    fun render(key: String): ShortArray? {
        val raw = when (key) {
            "cow" -> voice(
                1.4, pitch = curve(105.0, 125.0, 115.0, 90.0),
                f1 = curve(250.0, 550.0, 650.0, 600.0), f2 = curve(700.0, 950.0, 1000.0, 900.0),
                env = adsr(0.2, 0.3), noise = 0.05, q = 4.0,
            )
            "cat" -> voice(
                0.8, pitch = curve(500.0, 700.0, 780.0, 600.0, 420.0),
                f1 = curve(350.0, 800.0, 900.0, 600.0, 400.0), f2 = curve(1600.0, 2000.0, 1900.0, 1200.0, 900.0),
                env = adsr(0.08, 0.35), noise = 0.03, q = 5.0,
            )
            "dog" -> {
                val bark = voice(
                    0.2, pitch = curve(220.0, 170.0, 120.0),
                    f1 = curve(500.0, 700.0, 500.0), f2 = curve(1100.0, 1300.0, 1000.0),
                    env = adsr(0.01, 0.7), noise = 0.35, q = 3.0,
                )
                seq(bark, gap(0.12), bark)
            }
            "duck" -> {
                val quack = voice(
                    0.24, pitch = curve(330.0, 310.0, 260.0),
                    f1 = curve(1000.0, 900.0, 700.0), f2 = curve(1700.0, 1500.0, 1300.0),
                    env = adsr(0.02, 0.4), noise = 0.1, q = 8.0, pulse = true,
                )
                seq(quack, gap(0.1), quack)
            }
            "sheep" -> {
                val base = curve(300.0, 330.0, 290.0, 260.0)
                voice(
                    1.1, pitch = { t -> base(t) * (1 + 0.06 * sin(2 * PI * 7.0 * 1.1 * t)) },
                    f1 = curve(400.0, 850.0, 850.0, 800.0), f2 = curve(1100.0, 1300.0, 1300.0, 1200.0),
                    env = adsr(0.08, 0.3), noise = 0.12, q = 5.0, pulse = true,
                )
            }
            "chick" -> {
                val peep = chirp(0.12, curve(2800.0, 3600.0, 3200.0))
                seq(peep, gap(0.07), peep, gap(0.07), peep)
            }
            "frog" -> {
                val a = adsr(0.03, 0.3)
                val croak = voice(
                    0.28, pitch = curve(90.0, 80.0),
                    f1 = curve(450.0), f2 = curve(1200.0),
                    env = { t -> a(t) * (0.5 + 0.5 * sin(2 * PI * 28.0 * 0.28 * t)) },
                    noise = 0.1, q = 4.0, pulse = true,
                )
                seq(croak, gap(0.15), croak)
            }
            "lion" -> {
                val base = curve(70.0, 95.0, 85.0, 60.0)
                voice(
                    1.5, pitch = { t -> base(t) * (1 + 0.08 * (Random.nextDouble() - 0.5)) },
                    f1 = curve(350.0, 650.0, 550.0, 300.0), f2 = curve(800.0, 1100.0, 1000.0, 700.0),
                    env = adsr(0.25, 0.4), noise = 0.55, q = 3.0,
                )
            }
            "chomp" -> {
                val nom = voice(
                    0.13, pitch = curve(230.0, 180.0),
                    f1 = curve(300.0, 650.0, 350.0), f2 = curve(900.0, 1200.0, 900.0),
                    env = adsr(0.05, 0.4), noise = 0.1, q = 5.0,
                )
                seq(nom, gap(0.06), nom, gap(0.06), nom)
            }
            "burp" -> {
                val base = curve(85.0, 110.0, 75.0)
                voice(
                    0.65, pitch = { t -> base(t) * (1 + 0.15 * (Random.nextDouble() - 0.5)) },
                    f1 = curve(450.0, 700.0, 550.0), f2 = curve(900.0, 1100.0, 900.0),
                    env = adsr(0.04, 0.35), noise = 0.25, q = 4.0, pulse = true,
                )
            }
            "giggle" -> seq(
                *List(5) { i ->
                    listOf(
                        voice(
                            0.09, pitch = curve(480.0 + i * 30, 560.0 + i * 30),
                            f1 = curve(550.0, 380.0), f2 = curve(2300.0),
                            env = adsr(0.15, 0.5), noise = 0.15, q = 6.0,
                        ),
                        gap(0.05),
                    )
                }.flatten().toTypedArray()
            )
            else -> return null
        }
        return toPcm(raw, 0.8)
    }

    private fun voice(
        sec: Double,
        pitch: (Double) -> Double,
        f1: (Double) -> Double,
        f2: (Double) -> Double,
        env: (Double) -> Double,
        noise: Double = 0.0,
        q: Double = 5.0,
        pulse: Boolean = false,
    ): DoubleArray {
        val n = (RATE * sec).toInt()
        val out = DoubleArray(n)
        val bp1 = BandPass()
        val bp2 = BandPass()
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            phase += pitch(t) / RATE
            phase -= floor(phase)
            val src = if (pulse) (if (phase < 0.3) 1.0 else -0.43) else 2 * phase - 1
            val x = src * (1 - noise) + (Random.nextDouble() * 2 - 1) * noise
            out[i] = (bp1.next(x, f1(t), q) + 0.6 * bp2.next(x, f2(t), q)) * env(t)
        }
        return out
    }

    private fun chirp(sec: Double, freq: (Double) -> Double): DoubleArray {
        val n = (RATE * sec).toInt()
        val env = adsr(0.1, 0.4)
        var phase = 0.0
        return DoubleArray(n) { i ->
            val t = i.toDouble() / n
            phase += 2 * PI * freq(t) / RATE
            sin(phase) * env(t)
        }
    }

    private fun gap(sec: Double) = DoubleArray((RATE * sec).toInt())

    private fun seq(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    /** Smooth (cosine-interpolated) curve through evenly spaced points over t ∈ [0, 1]. */
    private fun curve(vararg v: Double): (Double) -> Double = { t ->
        if (v.size == 1) {
            v[0]
        } else {
            val x = t.coerceIn(0.0, 1.0) * (v.size - 1)
            val i = min(x.toInt(), v.size - 2)
            val s = (1 - cos(PI * (x - i))) / 2
            v[i] + (v[i + 1] - v[i]) * s
        }
    }

    /** Linear attack and release, as fractions of the sound's length. */
    private fun adsr(attack: Double, release: Double): (Double) -> Double = { t ->
        min(1.0, t / attack) * min(1.0, (1 - t) / release)
    }

    private fun toPcm(x: DoubleArray, peak: Double): ShortArray {
        val max = x.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        return ShortArray(x.size) { (x[it] / max * peak * Short.MAX_VALUE).toInt().toShort() }
    }

    /** RBJ band-pass biquad (0 dB peak) with per-sample center frequency. */
    private class BandPass {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun next(x: Double, freq: Double, q: Double): Double {
            val w0 = 2 * PI * freq / RATE
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            val y = (alpha * x - alpha * x2 + 2 * cos(w0) * y1 - (1 - alpha) * y2) / a0
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }
    }
}
