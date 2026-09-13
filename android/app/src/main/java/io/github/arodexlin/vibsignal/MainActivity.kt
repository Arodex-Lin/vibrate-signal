package io.github.arodexlin.vibsignal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(Protocol.PREFS, MODE_PRIVATE) }
    private lateinit var roomInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var lastText: TextView
    private lateinit var lastMeta: TextView
    private lateinit var batteryButton: Button
    private lateinit var defaultTextColors: ColorStateList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        fun add(view: View, top: Int = 0) {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.topMargin = dp(top)
            column.addView(view, params)
        }

        fun textView(size: Float, value: String = "", bold: Boolean = false, muted: Boolean = false) =
            TextView(this).apply {
                textSize = size
                text = value
                if (bold) typeface = Typeface.DEFAULT_BOLD
                if (muted) alpha = 0.65f
            }

        add(textView(26f, "振动信号", bold = true))
        add(textView(14f, "安卓接收端 · 熄屏也能振动", muted = true), top = 2)

        add(textView(13f, "房间号(和发送端网页填同一个)", muted = true), top = 24)
        roomInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            textSize = 30f
            letterSpacing = 0.2f
            typeface = Typeface.MONOSPACE
            hint = "000000"
            setText(prefs.getString(Protocol.PREF_ROOM, ""))
        }
        add(roomInput, top = 4)

        toggleButton = Button(this).apply {
            textSize = 18f
            setOnClickListener { toggle() }
        }
        add(toggleButton, top = 12)

        statusText = textView(14f, muted = true)
        add(statusText, top = 8)

        lastText = textView(56f, bold = true).apply { gravity = Gravity.CENTER }
        defaultTextColors = lastText.textColors
        add(lastText, top = 28)
        lastMeta = textView(14f, muted = true).apply { gravity = Gravity.CENTER }
        add(lastMeta, top = 4)

        add(Button(this).apply {
            text = "测试振动(A → C)"
            setOnClickListener { Vibe.play(this@MainActivity, "AC", Vibe.DEFAULT_GAP) }
        }, top = 28)

        add(Button(this).apply {
            text = "测试重置提示"
            setOnClickListener {
                Vibe.playReset(this@MainActivity)
                if (prefs.getBoolean(Protocol.PREF_RESET_SOUND, false)) Vibe.errorTone()
            }
        }, top = 8)

        add(CheckBox(this).apply {
            text = "收到重置时同时响提示音(跟随通知音量)"
            isChecked = prefs.getBoolean(Protocol.PREF_RESET_SOUND, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Protocol.PREF_RESET_SOUND, checked).apply()
            }
        }, top = 8)

        batteryButton = Button(this).apply {
            text = "允许后台运行(关闭电池优化)"
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        }
        add(batteryButton, top = 8)

        add(textView(13f, HINT, muted = true), top = 16)

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        SignalService.listener = { render() }
        // 之前在接收但进程被系统清理了:打开 App 时自动恢复
        if (prefs.getBoolean(Protocol.PREF_RUNNING, false) && !SignalService.running) {
            startReceiving(prefs.getString(Protocol.PREF_ROOM, null))
        }
        render()
    }

    override fun onPause() {
        SignalService.listener = null
        super.onPause()
    }

    private fun toggle() {
        if (SignalService.running) {
            prefs.edit().putBoolean(Protocol.PREF_RUNNING, false).apply()
            stopService(Intent(this, SignalService::class.java))
        } else {
            val room = roomInput.text.toString()
            if (!Protocol.isValidRoom(room)) {
                roomInput.error = "需要 6 位数字"
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            startReceiving(room)
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(roomInput.windowToken, 0)
        }
        render()
    }

    private fun startReceiving(room: String?) {
        if (room == null || !Protocol.isValidRoom(room)) return
        prefs.edit().putString(Protocol.PREF_ROOM, room).putBoolean(Protocol.PREF_RUNNING, true).apply()
        startForegroundService(Intent(this, SignalService::class.java).putExtra(SignalService.EXTRA_ROOM, room))
    }

    private fun render() {
        val running = SignalService.running
        toggleButton.text = if (running) "停止接收" else "开始接收"
        roomInput.isEnabled = !running
        statusText.text = if (running) SignalService.status else "未在接收"

        val headline = SignalService.lastHeadline
        lastText.text = headline.ifEmpty { "–" }
        if (headline == "✕") lastText.setTextColor(0xFFD64545.toInt()) else lastText.setTextColor(defaultTextColors)
        lastMeta.text = SignalService.lastDetail.ifEmpty { "还没收到信号" }

        val power = getSystemService(PowerManager::class.java)
        batteryButton.visibility = if (power.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private companion object {
        const val HINT = "熄屏后能不能持续收到,取决于系统是否放行本 App 后台运行:\n" +
            "· 点上面的按钮关闭电池优化\n" +
            "· 小米 / 红米:应用设置里把省电策略设为「无限制」,并允许自启动\n" +
            "· 华为 / 荣耀:应用启动管理里改为手动管理,全部允许\n" +
            "· OPPO / 一加 / vivo:耗电管理里允许后台运行\n" +
            "· 在最近任务里把本 App 锁定,避免一键清理时被关掉"
    }
}
