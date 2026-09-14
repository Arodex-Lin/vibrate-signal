package io.github.arodexlin.vibsignal

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** 与网页端一致的消息协议:通过 ntfy.sh 的公共频道中转。 */
object Protocol {
    const val BASE = "https://ntfy.sh/"
    private const val TOPIC_PREFIX = "vibsig-q7k2-"

    const val PREFS = "vibsig"
    const val PREF_ROOM = "room"
    const val PREF_RUNNING = "running"
    const val PREF_RESET_SOUND = "reset_sound"
    const val PREF_STRENGTH = "strength"
    const val PREF_PULSE_MS = "pulse_ms"

    fun isValidRoom(room: String) = Regex("\\d{6}").matches(room)

    fun topic(room: String) = TOPIC_PREFIX + room

    fun publish(room: String, payload: JSONObject) {
        thread(isDaemon = true, name = "publish") {
            var c: HttpURLConnection? = null
            try {
                c = URL(BASE + topic(room)).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.outputStream.use { it.write(payload.toString().toByteArray()) }
                c.responseCode
            } catch (e: Exception) {
                // 回执发不出去不影响振动
            } finally {
                c?.disconnect()
            }
        }
    }
}
