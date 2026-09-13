package io.github.arodexlin.vibsignal

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 把字母序列翻译成振动:A=1 下 … D=4 下,字母之间停顿 gap 毫秒。 */
object Vibe {
    const val PULSE_ON = 300L
    const val PULSE_OFF = 250L
    const val DEFAULT_GAP = 1500L

    fun count(letter: Char) = letter - 'A' + 1

    fun label(letters: String) = letters.toList().joinToString(" → ")

    fun counts(letters: String) = letters.map { count(it) }.joinToString(" + ")

    /** createWaveform 的时长数组,依次为 关、开、关、开…… */
    fun timings(letters: String, gap: Long): LongArray {
        val out = mutableListOf(0L)
        letters.forEachIndexed { index, letter ->
            if (index > 0) out.add(gap)
            repeat(count(letter)) { i ->
                if (i > 0) out.add(PULSE_OFF)
                out.add(PULSE_ON)
            }
        }
        return out.toLongArray()
    }

    fun play(context: Context, letters: String, gap: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val effect = VibrationEffect.createWaveform(timings(letters, gap), -1)
        // 用"闹钟"用途振动,尽量避免熄屏、后台或静音时被系统过滤
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
