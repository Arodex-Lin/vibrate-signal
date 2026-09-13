package io.github.arodexlin.vibsignal

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 把信号翻译成振动:A=1 下 … D=4 下,字母之间停顿 gap 毫秒;重置为急促连振。 */
object Vibe {
    const val PULSE_ON = 300L
    const val PULSE_OFF = 250L
    const val DEFAULT_GAP = 1500L
    private const val RESET_ON = 100L
    private const val RESET_OFF = 80L
    private const val RESET_PULSES = 8

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

    fun resetTimings(): LongArray {
        val out = mutableListOf(0L)
        repeat(RESET_PULSES) { i ->
            if (i > 0) out.add(RESET_OFF)
            out.add(RESET_ON)
        }
        return out.toLongArray()
    }

    fun play(context: Context, letters: String, gap: Long) = vibrate(context, timings(letters, gap))

    /** 新的振动会打断正在进行的振动 */
    fun playReset(context: Context) = vibrate(context, resetTimings())

    /** 重置提示音:两声低沉的"嘟",跟随通知音量 */
    fun errorTone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            val handler = Handler(Looper.getMainLooper())
            tone.startTone(ToneGenerator.TONE_PROP_NACK, 400)
            handler.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_NACK, 400) }, 550)
            handler.postDelayed({ tone.release() }, 1_200)
        } catch (e: Exception) {
            // 音频资源被占用时创建会失败,不影响振动
        }
    }

    private fun vibrate(context: Context, timings: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val effect = VibrationEffect.createWaveform(timings, -1)
        // 用"闹钟"用途振动,尽量避免熄屏、后台或静音时被系统过滤
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
