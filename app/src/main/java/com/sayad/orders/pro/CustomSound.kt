package com.sayad.orders.pro

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/** صوت القبول البديل (المقطع اللي اختاره المستخدم)، بديل عن صفارة الإنذار. */
object CustomSound {
    fun play(ctx: Context) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            val afd = ctx.resources.openRawResourceFd(R.raw.accept_custom)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.prepare()
            mp.start()
        } catch (_: Exception) {
        }
    }
}
