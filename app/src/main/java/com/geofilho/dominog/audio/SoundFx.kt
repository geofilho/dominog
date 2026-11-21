package com.geofilho.dominog.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sons de pedra de dominó gerados por síntese (sem arquivos de áudio no APK):
 *  - [playTile]: "clack" de uma pedra batendo na mesa.
 *  - [playShuffle]: pedras sendo embaralhadas/rolando na mesa.
 * Os PCMs são gerados uma única vez, em background, e tocados com AudioTrack estático.
 */
class SoundFx {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var clack: ShortArray? = null
    @Volatile private var shuffle: ShortArray? = null
    @Volatile private var draw: ShortArray? = null
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    init {
        scope.launch {
            val rnd = Random(7)
            clack = synthClack(rnd, 1f, 2900f)
            draw = synthClack(rnd, 0.45f, 2300f)
            shuffle = synthShuffle(rnd)
        }
    }

    fun playTile() = play(clack)
    fun playDraw() = play(draw)
    fun playShuffle() = play(shuffle)

    private fun play(pcm: ShortArray?) {
        pcm ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.setNotificationMarkerPosition(pcm.size - 1)
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) { t.release() }
                    override fun onPeriodicNotification(t: AudioTrack) {}
                })
                track.play()
            }
        }
    }

    fun release() { /* tracks se liberam sozinhas ao terminar */ }

    private companion object {
        const val RATE = 22050

        /** Impacto curto: ruído + duas ressonâncias amortecidas (som de osso/resina na madeira). */
        fun synthClack(rnd: Random, gain: Float, freq: Float): ShortArray {
            val n = (RATE * 0.12).toInt()
            val out = ShortArray(n)
            val f2 = freq * 1.73f
            val low = 180f
            for (i in 0 until n) {
                val t = i / RATE.toFloat()
                val noise = (rnd.nextFloat() * 2 - 1) * exp(-t * 260f)
                val ring = sin(2 * PI * freq * t).toFloat() * exp(-t * 70f) * 0.55f +
                    sin(2 * PI * f2 * t).toFloat() * exp(-t * 110f) * 0.3f
                val thump = sin(2 * PI * low * t).toFloat() * exp(-t * 40f) * 0.35f
                val v = (noise * 0.6f + ring + thump) * gain
                out[i] = (v.coerceIn(-1f, 1f) * 26000).toInt().toShort()
            }
            return out
        }

        /** ~1,6 s de vários cliques aleatórios mais um "arrastar" de fundo. */
        fun synthShuffle(rnd: Random): ShortArray {
            val n = (RATE * 1.6).toInt()
            val mix = FloatArray(n)
            // arrasto: ruído grave filtrado
            var lp = 0f
            for (i in 0 until n) {
                val env = sin(PI * i / n).toFloat()
                lp += 0.08f * ((rnd.nextFloat() * 2 - 1) - lp)
                mix[i] += lp * 0.5f * env
            }
            repeat(70) {
                val start = (rnd.nextFloat() * (n - RATE * 0.12f)).toInt()
                val c = synthClack(rnd, 0.25f + rnd.nextFloat() * 0.45f, 2000f + rnd.nextFloat() * 1800f)
                for (j in c.indices) {
                    val k = start + j
                    if (k < n) mix[k] += c[j] / 26000f
                }
            }
            return ShortArray(n) { (mix[it].coerceIn(-1f, 1f) * 24000).toInt().toShort() }
        }
    }
}
