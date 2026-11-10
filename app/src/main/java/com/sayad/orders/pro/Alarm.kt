package com.sayad.orders.pro

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator

/** صوت إنذار (صفارة بنغمتين) + اهتزاز، يتولّد بالكود بدون ملفات صوت. */
object Alarm {
    private const val SR = 22050
    private const val BEEPS = 8

    private val buffer: ShortArray by lazy {
        val beepN = (SR * 0.11).toInt()
        val gapN = (SR * 0.02).toInt()
        val buf = ShortArray(BEEPS * (beepN + gapN))
        var pos = 0
        for (b in 0 until BEEPS) {
            val f = if (b % 2 == 0) 1600.0 else 1050.0
            for (i in 0 until beepN) {
                val env = minOf(1.0, minOf(i, beepN - i) / (SR * 0.004))
                val v = Math.sin(2.0 * Math.PI * f * i / SR) * env * 32000.0
                buf[pos + i] = v.toInt().toShort()
            }
            pos += beepN + gapN
        }
        buf
    }

    fun play(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                val data = buffer
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(data.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(data, 0, data.size)
                track.play()
                try {
                    @Suppress("DEPRECATION")
                    val vib = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    vib?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (_: Exception) {
                }
                Thread.sleep(1300)
                track.release()
            } catch (_: Exception) {
            }
        }.start()
    }
}
