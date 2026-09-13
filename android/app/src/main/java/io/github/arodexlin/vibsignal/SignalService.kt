package io.github.arodexlin.vibsignal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/** 前台服务:熄屏后保持与 ntfy.sh 的长连接,收到信号就振动并回执给发送端。 */
class SignalService : Service() {

    companion object {
        const val EXTRA_ROOM = "room"
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile var running = false
        @Volatile var status = "未启动"
        @Volatile var lastLetters = ""
        @Volatile var lastTime = ""

        /** 界面在前台时注册,状态变化时在主线程回调。 */
        var listener: (() -> Unit)? = null

        private fun changed() {
            mainHandler.post { listener?.invoke() }
        }
    }

    private val prefs by lazy { getSharedPreferences(Protocol.PREFS, MODE_PRIVATE) }
    private val seen = LinkedHashSet<String>()
    @Volatile private var generation = 0
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var room: String? = null
    @Volatile private var workerStartedAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Wi-Fi 与流量切换后旧连接可能僵死,直接重连
            val r = room ?: return
            if (System.currentTimeMillis() - workerStartedAt > 5_000) {
                mainHandler.post { if (running) startWorker(r) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // 拿不到网络回调时仍可依靠读超时重连
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newRoom = intent?.getStringExtra(EXTRA_ROOM) ?: prefs.getString(Protocol.PREF_ROOM, null)
        if (newRoom == null || !Protocol.isValidRoom(newRoom)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat("正在连接房间 $newRoom…")
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        acquireWakeLock()
        if (newRoom != room) startWorker(newRoom)
        changed()
        return START_STICKY
    }

    override fun onDestroy() {
        generation++
        closeConnection()
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        running = false
        status = "未启动"
        changed()
        super.onDestroy()
    }

    private fun startWorker(newRoom: String) {
        room = newRoom
        val gen = ++generation
        workerStartedAt = System.currentTimeMillis()
        closeConnection()
        thread(isDaemon = true, name = "signal-listener") { listen(newRoom, gen) }
    }

    private fun closeConnection() {
        val c = connection ?: return
        connection = null
        // 关闭 TLS 连接会产生网络 IO,不能放在主线程
        thread(isDaemon = true) {
            try {
                c.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    private fun listen(room: String, gen: Int) {
        var backoff = 1_000L
        var lastId: String? = null
        var lastAlive = 0L
        while (gen == generation) {
            setStatus(gen, "正在连接房间 $room…")
            var c: HttpURLConnection? = null
            try {
                // 短暂断线后用 since 补收漏掉的消息
                val since = if (lastId != null && System.currentTimeMillis() - lastAlive < 60_000) "?since=$lastId" else ""
                c = URL(Protocol.BASE + Protocol.topic(room) + "/json" + since).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 75_000 // ntfy 每 45 秒发一次 keepalive
                connection = c
                if (gen != generation) break
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                    while (gen == generation) {
                        val line = reader.readLine() ?: break
                        lastAlive = System.currentTimeMillis()
                        if (line.isBlank()) continue
                        val event = try {
                            JSONObject(line)
                        } catch (e: Exception) {
                            continue
                        }
                        when (event.optString("event")) {
                            "open" -> {
                                backoff = 1_000L
                                setStatus(gen, "已连接房间 $room")
                            }
                            "message" -> {
                                lastId = event.optString("id")
                                onMessage(room, event)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // 断网、超时等,下面统一重连
            } finally {
                c?.disconnect()
            }
            if (gen != generation) break
            setStatus(gen, "连接断开,${backoff / 1000} 秒后重连…")
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun onMessage(room: String, event: JSONObject) {
        val msg = try {
            JSONObject(event.optString("message"))
        } catch (e: Exception) {
            return
        }
        if (msg.optString("t") != "sig") return
        val letters = msg.optString("k").filter { it in 'A'..'D' }
        val nonce = msg.optString("n")
        if (letters.isEmpty() || nonce.isEmpty()) return
        synchronized(seen) {
            if (!seen.add(nonce)) return
            if (seen.size > 200) seen.remove(seen.first())
        }
        // 断线补收时跳过太旧的信号
        val sentAt = event.optLong("time")
        if (sentAt > 0 && abs(System.currentTimeMillis() / 1000 - sentAt) > 120) return

        val gap = msg.optLong("gap", Vibe.DEFAULT_GAP).coerceIn(300L, 5_000L)
        Vibe.play(this, letters, gap)

        lastLetters = letters
        lastTime = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        changed()
        updateNotification("收到 ${Vibe.label(letters)} · $lastTime")
        Protocol.publish(room, JSONObject().put("t", "ack").put("n", nonce).put("k", letters))
    }

    private fun setStatus(gen: Int, text: String) {
        if (gen != generation) return
        status = text
        changed()
        updateNotification(text)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("振动信号 · 接收中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "接收状态", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        // 熄屏后保持 CPU 运行,长连接和振动节奏才不会被打断
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibsig:receiver")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }
}
