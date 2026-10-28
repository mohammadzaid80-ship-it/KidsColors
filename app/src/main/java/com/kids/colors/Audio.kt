package com.kids.colors

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Arabic voice via the device's built-in TTS engine. Silent if Arabic data is unavailable. */
class Voice(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val result = tts.setLanguage(Locale.forLanguageTag("ar"))
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setSpeechRate(0.85f)
        tts.setPitch(1.15f)
    }

    fun say(text: String, interrupt: Boolean = true) {
        if (!ready) return
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text)
    }

    fun stop() = tts.stop()

    fun release() {
        tts.stop()
        tts.shutdown()
    }
}

/** All sound effects are synthesized at startup — no audio asset files needed. */
class SoundFx {
    private val rate = 22050

    private val pops = List(3) { i -> track(synthPop(700.0 + i * 180.0)) }
    private var popIndex = 0
    private val success = track(notes(listOf(523.25, 659.25, 783.99, 1046.5), 0.11, 0.45))
    private val gentle = track(notes(listOf(392.0, 329.63), 0.18, 0.25))
    private val tap = track(notes(listOf(880.0), 0.06, 0.3))

    fun pop() {
        play(pops[popIndex])
        popIndex = (popIndex + 1) % pops.size
    }

    fun success() = play(success)
    fun gentle() = play(gentle)
    fun tap() = play(tap)

    fun release() {
        (pops + listOf(success, gentle, tap)).forEach { it.release() }
    }

    private fun play(t: AudioTrack) {
        try {
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (_: IllegalStateException) {
        }
    }

    private fun track(pcm: ShortArray) = staticTrack(pcm, rate)

    /** Short "bloop": falling sine sweep with a touch of noise and fast decay. */
    private fun synthPop(startHz: Double): ShortArray {
        val n = (rate * 0.14).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val freq = startHz * (1.0 - 0.75 * t)
            phase += 2 * PI * freq / rate
            val env = exp(-t * 6.0)
            val s = (0.8 * sin(phase) + 0.2 * (Random.nextDouble() * 2 - 1) * (1 - t)) * env
            out[i] = (s * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Sequence of soft bell-like notes. */
    private fun notes(freqs: List<Double>, noteSec: Double, volume: Double): ShortArray {
        val per = (rate * noteSec).toInt()
        val tail = (rate * 0.15).toInt()
        val out = DoubleArray(per * freqs.size + tail)
        freqs.forEachIndexed { k, f ->
            val len = per + tail
            for (i in 0 until len) {
                val idx = k * per + i
                if (idx >= out.size) break
                val t = i.toDouble() / rate
                val attack = (i / (rate * 0.005)).coerceAtMost(1.0)
                val env = attack * exp(-t * 9.0)
                out[idx] += (sin(2 * PI * f * t) + 0.3 * sin(4 * PI * f * t)) * env
            }
        }
        return ShortArray(out.size) { i ->
            (out[i].coerceIn(-1.3, 1.3) / 1.3 * volume * Short.MAX_VALUE).toInt().toShort()
        }
    }
}

internal fun staticTrack(pcm: ShortArray, rate: Int): AudioTrack {
    val t = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(pcm.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    t.write(pcm, 0, pcm.size)
    return t
}
