package com.example.controller

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.zIndex
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState

// ??????????Enum
enum class ScreenState {
    TEAM_SELECTION,
    CONTROLLER
}

enum class TurnTarget {
    HATAT,
    HATAO,
    HOJU,
    BAKETU,
    DEKAHATA
}

// ???????????????? SPEED ADJUST ????????
// ※ デカ雑巾の値は仮置き（バケツと同じ範囲）。実機に合わせてここを書き換える。
private const val BAKETU_SPEED_MIN = 4.0f
private const val BAKETU_SPEED_MAX = 5.0f
private const val HATA_SPEED_MIN = 9.0f
private const val HATA_SPEED_MAX = 11.2f
private const val DEKAZOUKIN_SPEED_DEFAULT = 9.0f
private const val DEKAZOUKIN_SPEED_MIN = 8.0f
private const val DEKAZOUKIN_SPEED_MAX = 10.0f

// SPEED ADJUST の +/- を押しっぱなしにしたときの連続調整の間隔。HATA の turn 調整と同じ値。
private const val SPEED_REPEAT_INITIAL_DELAY_MS = 160L
private const val SPEED_REPEAT_INTERVAL_MS = 100L

/** OBJECT ごとの射出速度の可変範囲。 */
private fun speedRangeFor(objectId: String): ClosedFloatingPointRange<Float> = when (objectId) {
    "hata" -> HATA_SPEED_MIN..HATA_SPEED_MAX
    "dekazoukin" -> DEKAZOUKIN_SPEED_MIN..DEKAZOUKIN_SPEED_MAX
    else -> BAKETU_SPEED_MIN..BAKETU_SPEED_MAX
}

class MainActivity : ComponentActivity() {

    private val ip = "192.168.11.7"
    private val port = 5005

    private lateinit var socket: DatagramSocket
    private lateinit var address: InetAddress
    private lateinit var logSocket: DatagramSocket

    // ?????????????????Job?Activity????????????????????
    private var networkJob: Job? = null

    private val prefs by lazy { getSharedPreferences("controller_prefs", Context.MODE_PRIVATE) }

    private var vx by mutableStateOf(0f)
    private var vy by mutableStateOf(0f)
    private var w by mutableStateOf(0f)
    private var left by mutableStateOf(false)
    private var right by mutableStateOf(false)
    private var up by mutableStateOf(false)
    private var down by mutableStateOf(false)
    private var circle by mutableStateOf(false)
    private var square by mutableStateOf(false)
    private var cross by mutableStateOf(false)
    private var triangle by mutableStateOf(false)
    private var l1 by mutableStateOf(false)
    private var l2 by mutableStateOf(false)
    private var r1 by mutableStateOf(false)
    private var r2 by mutableStateOf(false)

    private var posX by mutableStateOf(3800.0)
    private var posY by mutableStateOf(445.0)
    private var posTheta by mutableStateOf(0.0)
    private var logList by mutableStateOf<List<String>>(emptyList())
    // robot??????hojustate:xxxxxxx??????????????????
    private var hojuState by mutableStateOf("")
    // turn???
    // 旗用の turn 調整値。MOVE TARGET の hatat / hatao それぞれで別に持つ。
    private var hatat_turnx by mutableStateOf(0.0f)
    private var hatat_turny by mutableStateOf(0.0f)
    private var hatat_turntheta by mutableStateOf(0.0f)
    private var hatao_turnx by mutableStateOf(0.0f)
    private var hatao_turny by mutableStateOf(0.0f)
    private var hatao_turntheta by mutableStateOf(0.0f)
    private var hoju_turnx by mutableStateOf(0.0f)
    private var hoju_turny by mutableStateOf(0.0f)
    private var hoju_turntheta by mutableStateOf(0.0f)
    // バケツ用の turn 調整値。MOVE TARGET で BS 系（bs1 / bs2 / bs3）を選んでいるときの対象。
    private var baketu_turnx by mutableStateOf(0.0f)
    private var baketu_turny by mutableStateOf(0.0f)
    private var baketu_turntheta by mutableStateOf(0.0f)
    // デカ雑巾を旗に撃つとき用の turn 調整値。MOVE TARGET で dekahata を選んでいるときの対象。
    private var dekahata_turnx by mutableStateOf(0.0f)
    private var dekahata_turny by mutableStateOf(0.0f)
    private var dekahata_turntheta by mutableStateOf(0.0f)

    // t0
    private var t0 by mutableStateOf(false)

    // 画面上の物体選択（バケツ / 旗 / デカ雑巾）3択排他。
    // 常にどれか1つが選択状態で、send() で "object" として送り続ける。
    // 未選択状態はなし（選択中のボタンを再タップしても解除されない）。
    private var selectedObject by mutableStateOf("baketu")

    // マップ横の一覧から選んだ移動先。
    // 基本は常にどれか1つが選択状態で、send() で "target" として送り続ける。
    // 選択中のボタンをもう一度押すか、Lスティック / Rスティックの押し込み（L3 / R3）で "none" に戻る。
    private var selectedTarget by mutableStateOf("")

    // START / SELECT / PS ?????????????????????
    private var start by mutableStateOf(false)
    private var select by mutableStateOf(false)
    private var ps by mutableStateOf(false)

    private var hataLaser by mutableStateOf(false)
    private var hojuLaser by mutableStateOf(false)

    // いま自己位置的に押せる移動先の id。受信が止まっていても一定間隔で作り直す。
    private var unlockedTargets by mutableStateOf<Set<String>>(emptySet())


    private var sendTime = 0L
    private val rttList = mutableStateListOf<Long>()

    // PING_HOST への ping の最新結果。画面の NETWORK RTT の下に出す。
    // pingOk = false の間（未実行 / タイムアウト / エラー）は赤で表示する。
    private var pingText by mutableStateOf("---")
    private var pingOk by mutableStateOf(false)

    // 起動時に Wi-Fi / モバイル回線がつながっていたら true。途中では判定し直さない。
    // つながっていると Android がそちらをデフォルトにし、ロボット宛ての UDP がそちらへ流れて届かなくなるので、
    // 画面の背景を点滅させて警告を出す。
    private var otherNetworkUp by mutableStateOf(false)

    // ????????????? String ("bluemap" ??? "redmap")???????????
    private var currentScreen by mutableStateOf(ScreenState.TEAM_SELECTION)
    private var selectedMapID by mutableStateOf("bluemap")
    private var isFlipped by mutableStateOf(true)

    // turn????
    private var selectedTurnTarget by mutableStateOf(TurnTarget.HATAT)

    // turn???1????????
    private val turnXYStep = 2f
    private val turnThetaStep = 0.1f
    private var prevDpadLeft = false
    private var prevDpadRight = false
    private var prevDpadUp = false
    private var prevDpadDown = false
    private var prevL2 = false
    private var prevR2 = false

    // HATA????????????????????????????????????
    private var hataTurnRepeatJob: Job? = null
    private val hataTurnRepeatInitialDelayMs = 160L
    private val hataTurnRepeatIntervalMs = 100L
    private var lowGain by mutableStateOf(true)
    private var baketuSpeed1 by mutableStateOf(4.5f)
    private var hataSpeed1 by mutableStateOf(10.2f)
    private var baketuSpeed2 by mutableStateOf(4.5f)
    private var hataSpeed2 by mutableStateOf(10.2f)
    private var baketuSpeed3 by mutableStateOf(4.5f)
    private var hataSpeed3 by mutableStateOf(10.2f)

    // デカ雑巾の射出速度。初期値・可変範囲は下の DEKAZOUKIN_SPEED_* で定義する。
    private var dekazoukinSpeed1 by mutableStateOf(DEKAZOUKIN_SPEED_DEFAULT)
    private var dekazoukinSpeed2 by mutableStateOf(DEKAZOUKIN_SPEED_DEFAULT)
    private var dekazoukinSpeed3 by mutableStateOf(DEKAZOUKIN_SPEED_DEFAULT)

    // ?????1????????
    private val speedStep = 0.01f

    private val t0CooldownSeconds = 2.0f   // ?????(?)???????????????
    private var t0Locked by mutableStateOf(false)  // true ??????????
    private var t0LockJob: Job? = null     // ????????????????
    private fun toggleT0() {
        if (t0Locked) return          // ???????????(????)
        t0 = !t0                      // ???ON/OFF????
        t0Locked = true                // ?????????
        t0LockJob?.cancel()            // ????????????????????????
        t0LockJob = lifecycleScope.launch {
            delay((t0CooldownSeconds * 1000).toLong())  // 2000ms??
            t0Locked = false            // ?????
        }
    }
    // ?????logList?????????
    // appendLog ?????????
    /** インターネット用の Wi-Fi / モバイル回線が1本でもつながっているか。 */
    @Suppress("DEPRECATION") // allNetworks は API 31 で非推奨だが、一度きりの判定には十分
    private fun isOtherNetworkConnected(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val last = logList.lastOrNull()
            val lastBase = last?.substringBeforeLast(" (x").let {
                if (last?.contains(" (x") == true) it else last
            }

            if (lastBase == msg) {
                // ??????? ? ?????????????
                val countMatch = Regex(""" \(x(\d+)\)$""").find(last ?: "")
                val newCount = (countMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1) + 1
                val updated = "$msg (x$newCount)"
                logList = (logList.dropLast(1) + updated)
            } else {
                logList = (logList + msg).takeLast(50)
            }
        }
        try {
            openFileOutput("debug_log.txt", MODE_APPEND).use {
                it.write("${System.currentTimeMillis()}: $msg\n".toByteArray())
            }
        } catch (_: Exception) {}
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // ????????????????????
        if (currentScreen != ScreenState.CONTROLLER) {
            return super.onGenericMotionEvent(event)
        }

        val source = event.source
        if (source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val rawVx = -event.getAxisValue(MotionEvent.AXIS_Y)
            val rawVy = event.getAxisValue(MotionEvent.AXIS_X)
            val rawW = event.getAxisValue(MotionEvent.AXIS_Z)
            val rawHori = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val rawVer = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val rawL2 = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
            val rawR2 = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)

            vx = if (Math.abs(rawVx) > 0.1f) rawVx else 0f
            vy = if (Math.abs(rawVy) > 0.1f) rawVy else 0f
            w = if (Math.abs(rawW) > 0.1f) rawW else 0f

            left = rawHori < -0.5f
            right = rawHori > 0.5f
            up = rawVer < -0.5f
            down = rawVer > 0.5f
            l2 = rawL2 > 0.5f
            r2 = rawR2 > 0.5f

            // turn?????
            // HATA???: ?????1??????????????????????
            // 100ms??????????????repeat job??????
            // HOJU: ???????1????????
            val currentDpadLeft = rawHori < -0.5f
            val currentDpadRight = rawHori > 0.5f
            val currentDpadUp = rawVer < -0.5f
            val currentDpadDown = rawVer > 0.5f
            val currentL2 = rawL2 > 0.5f
            val currentR2 = rawR2 > 0.5f

            if (selectedTurnTarget.usesHoldRepeat()) {
                // ?????????????1????????????Job?????
                val pressedNow = currentDpadLeft || currentDpadRight || currentDpadUp ||
                        currentDpadDown || currentL2 || currentR2
                val wasPressed = prevDpadLeft || prevDpadRight || prevDpadUp ||
                        prevDpadDown || prevL2 || prevR2

                if (pressedNow && !wasPressed) {
                    adjustHataTurnOnce(
                        currentDpadLeft, currentDpadRight,
                        currentDpadUp, currentDpadDown,
                        currentL2, currentR2
                    )
                }

                if (pressedNow) {
                    startHataTurnRepeat()
                } else {
                    stopHataTurnRepeat()
                }
            } else {
                // HOJU???????????????1????
                stopHataTurnRepeat()

                if (currentDpadLeft && !prevDpadLeft) {
                    adjustSelectedTurn(dx = -turnXYStep)
                }
                if (currentDpadRight && !prevDpadRight) {
                    adjustSelectedTurn(dx = turnXYStep)
                }
                if (currentDpadUp && !prevDpadUp) {
                    adjustSelectedTurn(dy = turnXYStep)
                }
                if (currentDpadDown && !prevDpadDown) {
                    adjustSelectedTurn(dy = -turnXYStep)
                }
                if (currentL2 && !prevL2) {
                    adjustSelectedTurn(dtheta = turnThetaStep)
                }
                if (currentR2 && !prevR2) {
                    adjustSelectedTurn(dtheta = -turnThetaStep)
                }
            }

            // ???????
            prevDpadLeft = currentDpadLeft
            prevDpadRight = currentDpadRight
            prevDpadUp = currentDpadUp
            prevDpadDown = currentDpadDown
            prevL2 = currentL2
            prevR2 = currentR2
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** HATA turn??????1???????? */
    private fun adjustHataTurnOnce(
        dpadLeft: Boolean,
        dpadRight: Boolean,
        dpadUp: Boolean,
        dpadDown: Boolean,
        l2Pressed: Boolean,
        r2Pressed: Boolean
    ) {
        if (dpadLeft) adjustSelectedTurn(dx = -turnXYStep)
        if (dpadRight) adjustSelectedTurn(dx = turnXYStep)
        if (dpadUp) adjustSelectedTurn(dy = turnXYStep)
        if (dpadDown) adjustSelectedTurn(dy = -turnXYStep)
        if (l2Pressed) adjustSelectedTurn(dtheta = turnThetaStep)
        if (r2Pressed) adjustSelectedTurn(dtheta = -turnThetaStep)
    }

    /**
     * HATA turn??????MotionEvent?repeat???????????
     * ???????1? + ??????1?????????????????
     * ???????100ms?????????
     */
    private fun startHataTurnRepeat() {
        if (hataTurnRepeatJob?.isActive == true) return

        hataTurnRepeatJob = lifecycleScope.launch {
            // ??????300ms?1??????????????????????
            delay(hataTurnRepeatInitialDelayMs)

            while (isActive) {
                delay(hataTurnRepeatIntervalMs)

                if (!selectedTurnTarget.usesHoldRepeat()) break

                val dpadLeft = left
                val dpadRight = right
                val dpadUp = up
                val dpadDown = down
                val l2Pressed = l2
                val r2Pressed = r2

                if (!dpadLeft && !dpadRight && !dpadUp &&
                    !dpadDown && !l2Pressed && !r2Pressed) {
                    break
                }

                adjustHataTurnOnce(
                    dpadLeft, dpadRight, dpadUp, dpadDown,
                    l2Pressed, r2Pressed
                )
            }
        }
    }

    private fun stopHataTurnRepeat() {
        hataTurnRepeatJob?.cancel()
        hataTurnRepeatJob = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentScreen == ScreenState.CONTROLLER &&
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        ) {
            when (keyCode) {
                // PS ボタンで OBJECT を バケツ -> 旗 -> デカ雑巾 -> バケツ と切り替える
                KeyEvent.KEYCODE_BUTTON_MODE -> {
                    ps = true
                    if (event.repeatCount == 0) {
                        cycleSelectedObject()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_START -> { start = true; return true }
                // SELECT ボタンで、選択中の移動先に応じて BS1 -> BS2 -> BS3 -> BS1 か HATAT <-> HATAO を切り替える
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    select = true
                    if (event.repeatCount == 0) {
                        cycleSelectTarget()
                    }
                    return true
                }
                // L???????/R???????????????MOVE TARGET?????
                KeyEvent.KEYCODE_BUTTON_THUMBL,
                KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                    if (event.repeatCount == 0) {
                        clearMoveTarget()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A -> { cross = true; return true }
                KeyEvent.KEYCODE_BUTTON_B -> { circle = true; return true }
                KeyEvent.KEYCODE_BUTTON_X -> { square = true; return true }
                KeyEvent.KEYCODE_BUTTON_Y -> { triangle = true; return true }
                KeyEvent.KEYCODE_BUTTON_L1 -> { l1 = true; return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { r1 = true; return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (currentScreen == ScreenState.CONTROLLER &&
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        ) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_MODE -> { ps = false; return true }
                KeyEvent.KEYCODE_BUTTON_START -> { start = false; return true }
                KeyEvent.KEYCODE_BUTTON_SELECT -> { select = false; return true }
                KeyEvent.KEYCODE_BUTTON_THUMBL,
                KeyEvent.KEYCODE_BUTTON_THUMBR -> { return true }
                KeyEvent.KEYCODE_BUTTON_A -> { cross = false; return true }
                KeyEvent.KEYCODE_BUTTON_B -> { circle = false; return true }
                KeyEvent.KEYCODE_BUTTON_X -> { square = false; return true }
                KeyEvent.KEYCODE_BUTTON_Y -> { triangle = false; return true }
                KeyEvent.KEYCODE_BUTTON_L1 -> { l1 = false; return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { r1 = false; return true }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    fun startLockTaskMode() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            startLockTask()
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // 読み込み済みのサイド。チーム未選択の間は null で、その間は保存もしない
    // （初期値のまま保存して既存の調整値を潰すのを防ぐ）。
    private var loadedSettingsMapID: String? = null

    // turn 調整値と射出速度は con.py 側が正本を持つ。起動直後は false で、con.py から
    // 設定値（ログ用ポート 5006 の "settings"）を受け取って反映したら true になる。
    // false の間は send() で "synced":false を送り、con.py は自分の保存値を使いつつ送り返してくる。
    // 受け取る前に調整しても上書きされるだけなので、false の間は調整を受け付けない。
    private var settingsSynced by mutableStateOf(false)

    /** con.py から届いた設定値を反映する。UI スレッドで呼ぶ。 */
    private fun applyRemoteSettings(settings: JSONObject) {
        val mapId = settings.optString("map_id", "")
        // チーム選択前や別サイドの値は使わない。同期済みなら手元の値が最新なので無視する。
        if (mapId != loadedSettingsMapID || settingsSynced) return
        fun f(name: String, current: Float): Float =
            settings.optDouble(name, current.toDouble()).toFloat()
        // hatat / hatao のキーがまだ無い（分ける前の）設定値なら、共用だった hata_turn* を両方に入れる。
        hatat_turnx = f("hatat_turnx", f("hata_turnx", hatat_turnx))
        hatat_turny = f("hatat_turny", f("hata_turny", hatat_turny))
        hatat_turntheta = f("hatat_turntheta", f("hata_turntheta", hatat_turntheta))
        hatao_turnx = f("hatao_turnx", f("hata_turnx", hatao_turnx))
        hatao_turny = f("hatao_turny", f("hata_turny", hatao_turny))
        hatao_turntheta = f("hatao_turntheta", f("hata_turntheta", hatao_turntheta))
        hoju_turnx = f("hoju_turnx", hoju_turnx)
        hoju_turny = f("hoju_turny", hoju_turny)
        hoju_turntheta = f("hoju_turntheta", hoju_turntheta)
        baketu_turnx = f("baketu_turnx", baketu_turnx)
        baketu_turny = f("baketu_turny", baketu_turny)
        baketu_turntheta = f("baketu_turntheta", baketu_turntheta)
        dekahata_turnx = f("dekahata_turnx", dekahata_turnx)
        dekahata_turny = f("dekahata_turny", dekahata_turny)
        dekahata_turntheta = f("dekahata_turntheta", dekahata_turntheta)
        hataSpeed1 = f("hata_speed1", hataSpeed1)
        hataSpeed2 = f("hata_speed2", hataSpeed2)
        hataSpeed3 = f("hata_speed3", hataSpeed3)
        baketuSpeed1 = f("baketu_speed1", baketuSpeed1)
        baketuSpeed2 = f("baketu_speed2", baketuSpeed2)
        baketuSpeed3 = f("baketu_speed3", baketuSpeed3)
        dekazoukinSpeed1 = f("dekazoukin_speed1", dekazoukinSpeed1)
        dekazoukinSpeed2 = f("dekazoukin_speed2", dekazoukinSpeed2)
        dekazoukinSpeed3 = f("dekazoukin_speed3", dekazoukinSpeed3)
        // 値を入れ終わってから同期済みにする（sendLoop が古い値を synced=true で送らないように）。
        settingsSynced = true
        // 手元の SharedPreferences はキャッシュとして更新しておく。
        saveSettings()
        appendLog("SETTINGS SYNCED: $mapId")
    }

    /**
     * サイドごとのキーを読む。"<mapId>_<name>" が無ければ旧バージョンの
     * サイド共用キー "<name>" を使う（それも無ければ default）。
     */
    private fun loadSideFloat(mapId: String, name: String, default: Float): Float =
        prefs.getFloat("${mapId}_$name", prefs.getFloat(name, default))

    /** 選んだサイドの turn 調整値と射出速度を読み込む。 */
    private fun loadSettings(mapId: String) {
        // hatat / hatao に分ける前は hata_turn* を共用していたので、新しいキーが無ければその値を引き継ぐ。
        val oldHataTurnX = loadSideFloat(mapId, "hata_turnx", 0.0f)
        val oldHataTurnY = loadSideFloat(mapId, "hata_turny", 0.0f)
        val oldHataTurnTheta = loadSideFloat(mapId, "hata_turntheta", 0.0f)
        hatat_turnx = loadSideFloat(mapId, "hatat_turnx", oldHataTurnX)
        hatat_turny = loadSideFloat(mapId, "hatat_turny", oldHataTurnY)
        hatat_turntheta = loadSideFloat(mapId, "hatat_turntheta", oldHataTurnTheta)
        hatao_turnx = loadSideFloat(mapId, "hatao_turnx", oldHataTurnX)
        hatao_turny = loadSideFloat(mapId, "hatao_turny", oldHataTurnY)
        hatao_turntheta = loadSideFloat(mapId, "hatao_turntheta", oldHataTurnTheta)
        hoju_turnx = loadSideFloat(mapId, "hoju_turnx", 0.0f)
        hoju_turny = loadSideFloat(mapId, "hoju_turny", 0.0f)
        hoju_turntheta = loadSideFloat(mapId, "hoju_turntheta", 0.0f)
        baketu_turnx = loadSideFloat(mapId, "baketu_turnx", 0.0f)
        baketu_turny = loadSideFloat(mapId, "baketu_turny", 0.0f)
        baketu_turntheta = loadSideFloat(mapId, "baketu_turntheta", 0.0f)
        dekahata_turnx = loadSideFloat(mapId, "dekahata_turnx", 0.0f)
        dekahata_turny = loadSideFloat(mapId, "dekahata_turny", 0.0f)
        dekahata_turntheta = loadSideFloat(mapId, "dekahata_turntheta", 0.0f)
        hataSpeed1 = loadSideFloat(mapId, "hata_speed1", 10.2f)
        hataSpeed2 = loadSideFloat(mapId, "hata_speed2", 10.2f)
        hataSpeed3 = loadSideFloat(mapId, "hata_speed3", 10.2f)
        baketuSpeed1 = loadSideFloat(mapId, "baketu_speed1", 4.5f)
        baketuSpeed2 = loadSideFloat(mapId, "baketu_speed2", 4.5f)
        baketuSpeed3 = loadSideFloat(mapId, "baketu_speed3", 4.5f)
        dekazoukinSpeed1 = loadSideFloat(mapId, "dekazoukin_speed1", DEKAZOUKIN_SPEED_DEFAULT)
        dekazoukinSpeed2 = loadSideFloat(mapId, "dekazoukin_speed2", DEKAZOUKIN_SPEED_DEFAULT)
        dekazoukinSpeed3 = loadSideFloat(mapId, "dekazoukin_speed3", DEKAZOUKIN_SPEED_DEFAULT)
        loadedSettingsMapID = mapId
        appendLog("SETTINGS LOADED: $mapId")
    }

    /** 読み込んだサイドのキーで書き戻す。チーム未選択なら何もしない。 */
    private fun saveSettings() {
        val mapId = loadedSettingsMapID ?: return
        prefs.edit()
            .putFloat("${mapId}_hatat_turnx", hatat_turnx)
            .putFloat("${mapId}_hatat_turny", hatat_turny)
            .putFloat("${mapId}_hatat_turntheta", hatat_turntheta)
            .putFloat("${mapId}_hatao_turnx", hatao_turnx)
            .putFloat("${mapId}_hatao_turny", hatao_turny)
            .putFloat("${mapId}_hatao_turntheta", hatao_turntheta)
            .putFloat("${mapId}_hoju_turnx", hoju_turnx)
            .putFloat("${mapId}_hoju_turny", hoju_turny)
            .putFloat("${mapId}_hoju_turntheta", hoju_turntheta)
            .putFloat("${mapId}_baketu_turnx", baketu_turnx)
            .putFloat("${mapId}_baketu_turny", baketu_turny)
            .putFloat("${mapId}_baketu_turntheta", baketu_turntheta)
            .putFloat("${mapId}_dekahata_turnx", dekahata_turnx)
            .putFloat("${mapId}_dekahata_turny", dekahata_turny)
            .putFloat("${mapId}_dekahata_turntheta", dekahata_turntheta)
            .putFloat("${mapId}_hata_speed1", hataSpeed1)
            .putFloat("${mapId}_hata_speed2", hataSpeed2)
            .putFloat("${mapId}_hata_speed3", hataSpeed3)
            .putFloat("${mapId}_baketu_speed1", baketuSpeed1)
            .putFloat("${mapId}_baketu_speed2", baketuSpeed2)
            .putFloat("${mapId}_baketu_speed3", baketuSpeed3)
            .putFloat("${mapId}_dekazoukin_speed1", dekazoukinSpeed1)
            .putFloat("${mapId}_dekazoukin_speed2", dekazoukinSpeed2)
            .putFloat("${mapId}_dekazoukin_speed3", dekazoukinSpeed3)
            .commit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // turn 調整値と射出速度は赤ゾーン / 青ゾーンで別に保存するので、
        // ここでは読まない。サイドが決まった時点（onSelectTeam）で loadSettings() を呼ぶ。
        hideSystemUI()
        startLockTaskMode()

        // Wi-Fi / モバイル回線の判定は起動時の1回だけ。
        otherNetworkUp = isOtherNetworkConnected()
        if (otherNetworkUp) appendLog("WIFI / MOBILE DATA ON: turn them off")

        // --- ???? ---
        // ???????????????????????????????????
        // ????????????????????
        // 移動先ボタンのロック判定は自己位置の受信にぶら下げず、一定間隔で回し続ける。
        // ネットワーク初期化の成否や受信の途切れに左右されないよう、networkJob とは別に持つ。
        lifecycleScope.launch {
            while (isActive) {
                if (currentScreen == ScreenState.CONTROLLER) {
                    refreshUnlockedTargets()
                }
                delay(TARGET_LOCK_INTERVAL_MS)
            }
        }

        // ルーターへの ping。UDP ソケットとは無関係に回したいので networkJob とは別に持つ。
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                pingOnce()
                delay(PING_INTERVAL_MS)
            }
        }

        networkJob =lifecycleScope.launch(Dispatchers.IO) {
            if (!initializeNetworkWithRetry()) {
                appendLog("NETWORK INIT FAILED: restarting app")
                withContext(Dispatchers.Main) {
                    restartApp()
                }
                return@launch
            }

            // ????????????????????????
            launch { sendLoop() }
            launch { receiveLoop() }
            launch { logReceiveLoop() }
        }
        setContent {
            when (currentScreen) {
                ScreenState.TEAM_SELECTION -> {
                    TeamSelectionScreen(
                        isFlipped = isFlipped,
                        networkWarning = otherNetworkUp,
                        onToggleFlip = { isFlipped = !isFlipped },
                        onSelectTeam = { mapStr ->
                            selectedMapID = mapStr
                            loadSettings(mapStr)
                            currentScreen = ScreenState.CONTROLLER
                        }
                    )
                }

                ScreenState.CONTROLLER -> {
                    ControllerUI(
                        isFlipped = isFlipped,
                        networkWarning = otherNetworkUp,
                        mapID = selectedMapID,
                        rttList = rttList,
                        pingText = pingText,
                        pingOk = pingOk,
                        posX = posX,
                        posY = posY,
                        posTheta = posTheta,
                        currentVx = if (lowGain) vy / 2f else vy,
                        currentVy = if (lowGain) vx / 2f else vx,
                        currentW = if (lowGain) w / 2f else w,
                        hatatTurnX = hatat_turnx, hatatTurnY = hatat_turny, hatatTurnTheta = hatat_turntheta,
                        hataoTurnX = hatao_turnx, hataoTurnY = hatao_turny, hataoTurnTheta = hatao_turntheta,
                        hojuTurnX = hoju_turnx, hojuTurnY = hoju_turny, hojuTurnTheta = hoju_turntheta,
                        baketuTurnX = baketu_turnx, baketuTurnY = baketu_turny, baketuTurnTheta = baketu_turntheta,
                        dekahataTurnX = dekahata_turnx, dekahataTurnY = dekahata_turny, dekahataTurnTheta = dekahata_turntheta,
                        selectedTurnTarget = selectedTurnTarget,
                        t0 = t0,
                        onToggleT0 = { toggleT0() },
                        left = left, right = right, up = up, down = down,
                        circle = circle, square = square, cross = cross, triangle = triangle,
                        l1 = l1, l2 = l2, r1 = r1, r2 = r2,
                        selectedObject = selectedObject,
                        onSelectObject = { value ->
                            // ?3?????????????1?????????????????????
                            selectedObject = value
                        },
                        selectedTarget = selectedTarget,
                        onSelectTarget = { value -> selectMoveTarget(value) },
                        unlockedTargets = unlockedTargets,
                        hataLaser = hataLaser,
                        onHataLaser = { hataLaser = !hataLaser },
                        hojuLaser = hojuLaser,
                        onHojuLaser = { hojuLaser = !hojuLaser },
                        lowGain = lowGain,
                        onToggleLowGain = { lowGain = !lowGain },
                        // SPEED ADJUST ??????? OBJECT ?3?????
                        speed1 = selectedObjectSpeed(1),
                        speed2 = selectedObjectSpeed(2),
                        speed3 = selectedObjectSpeed(3),
                        onSpeedIncrease = { column -> adjustSelectedObjectSpeed(column, speedStep) },
                        onSpeedDecrease = { column -> adjustSelectedObjectSpeed(column, -speedStep) },
                        logList = logList,
                        hojuState = hojuState,
                        t0locked = t0Locked,
                        settingsSynced = settingsSynced
                    )
                }
            }
        }
    }

    /**
     * ??????????????????????????????????????????
     */
    private suspend fun initializeNetworkWithRetry(maxRetries: Int = 5): Boolean {
        repeat(maxRetries) { attempt ->
            var newSocket: DatagramSocket? = null
            var newLogSocket: DatagramSocket? = null

            try {
                newSocket = DatagramSocket()
                val newAddress = InetAddress.getByName(ip)
                newLogSocket = DatagramSocket(5006)

                socket = newSocket
                address = newAddress
                logSocket = newLogSocket

                appendLog("SOCKET INIT OK (attempt ${attempt + 1}/$maxRetries)")
                return true
            } catch (e: Exception) {
                try {
                    newSocket?.close()
                } catch (_: Exception) {
                }
                try {
                    newLogSocket?.close()
                } catch (_: Exception) {
                }

                // ?????????????????????????????
                closeSockets()

                appendLog(
                    "SOCKET INIT ERROR " +
                            "(${attempt + 1}/$maxRetries): " +
                            "${e.javaClass.simpleName} ${e.message}"
                )

                if (attempt < maxRetries - 1) {
                    delay(1000)
                }
            }
        }

        return false
    }

    /**
     * ???Activity??????????Activity?1?????????
     * onPause() ????????????????????????
     * ?????????????????????????
     */
    private fun restartApp() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                appendLog("APP RESTART ERROR: launch intent not found")
                return
            }

            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
            )

            val pendingIntent = PendingIntent.getActivity(
                this,
                1001,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000L,
                pendingIntent
            )

            networkJob?.cancel()
            closeSockets()
            finishAndRemoveTask()

            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) {
            appendLog("APP RESTART ERROR: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * 端末の ping コマンドで PING_HOST に1回だけ ping し、結果を pingText / pingOk に入れる。
     * Java からは ICMP を直接送れないので、コマンドの出力の "time=12.3 ms" を読む。
     * ブロッキングするので Dispatchers.IO から呼ぶ。
     */
    private fun pingOnce() {
        var process: Process? = null
        try {
            process = ProcessBuilder("ping", "-c", "1", "-W", "1", PING_HOST)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            val timeMs = Regex("""time[=<]([\d.]+)\s*ms""").find(output)
                ?.groupValues?.get(1)?.toFloatOrNull()
            if (timeMs != null) {
                pingText = "%.1f ms".format(timeMs)
                pingOk = true
            } else {
                pingText = "TIMEOUT"
                pingOk = false
            }
        } catch (e: Exception) {
            pingText = "ERROR"
            pingOk = false
            appendLog("PING ERROR: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            process?.destroy()
        }
    }

    /**
     * ??????10ms???????????UDP??????
     * lifecycleScope.launch(Dispatchers.IO) ??????????????????
     */
    private suspend fun CoroutineScope.sendLoop() {
        while (isActive) {
            if (currentScreen == ScreenState.CONTROLLER) {
                send(
                    selectedMapID,
                    if (lowGain) vy / 2f else vy,
                    if (lowGain) vx / 2f else vx,
                    if (lowGain) w / 2f else w,
                    hataSpeed1, baketuSpeed1,
                    hataSpeed2, baketuSpeed2,
                    hataSpeed3, baketuSpeed3,
                    dekazoukinSpeed1, dekazoukinSpeed2, dekazoukinSpeed3,
                    hatat_turnx, hatat_turny, hatat_turntheta,
                    hatao_turnx, hatao_turny, hatao_turntheta,
                    hoju_turnx, hoju_turny, hoju_turntheta,
                    baketu_turnx, baketu_turny, baketu_turntheta,
                    dekahata_turnx, dekahata_turny, dekahata_turntheta,
                    mode = "normal",
                    selectedObject, selectedTarget,
                    hataLaser, hojuLaser, t0,
                    left, right, up, down,
                    circle, triangle, square, cross,
                    l1, l2, r1, r2,
                    start, select, ps,
                    settingsSynced
                )
            }
            delay(10)
        }
    }

    /**
     * ??????????????????????????????
     */
    private suspend fun CoroutineScope.receiveLoop() {
        val buf = ByteArray(1024)
        while (isActive) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet) // ??????I/O?Dispatchers.IO???????

                val jsonString = String(packet.data, 0, packet.length)
                try {
                    val json = JSONObject(jsonString)

                    posX = json.optDouble("x", 0.0)
                    posY = json.optDouble("y", 0.0)
                    posTheta = json.optDouble("theta", 0.0)
                } catch (e: Exception) {
                    appendLog("RECV ERROR: ${e.javaClass.simpleName} ${e.message}")
                }
                val rtt = (System.nanoTime() - sendTime) / 1_000_000
                if (rttList.size > 50) rttList.removeAt(0)
                rttList.add(rtt)
            } catch (e: Exception) {
                // close()???SocketException?Activity???????????????????
                if (isActive) {
                    appendLog("RECV ERROR: ${e.javaClass.simpleName} ${e.message}")
                }
            }
        }
    }

    /**
     * ?????????????????????????????????
     */
    private suspend fun CoroutineScope.logReceiveLoop() {
        // 設定値の同期パケットも来るので余裕を持たせる
        val buf = ByteArray(4096)
        while (isActive) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                logSocket.receive(packet) // ??????I/O?Dispatchers.IO???????

                val jsonString = String(packet.data, 0, packet.length)
                val json = JSONObject(jsonString)

                // con.py が保存している turn / speed。同期待ちの間だけ送られてくる。
                json.optJSONObject("settings")?.let { settings ->
                    runOnUiThread { applyRemoteSettings(settings) }
                }

                val receivedLog = json.optString("log", "")

                if (receivedLog.isNotBlank() && !receivedLog.equals("none", ignoreCase = true)) {
                    val normalLines = mutableListOf<String>()
                    var latestHojuState: String? = null
                    var invalidTarget = false

                    receivedLog.split("\n")
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
                        .forEach { line ->
                            // ロボットが移動先を受け付けなかった合図。ログにも残す。
                            if (line.contains("invalid_target", ignoreCase = true)) {
                                invalidTarget = true
                            }
                            if (line.startsWith("hojustate:")) {
                                // 画面には "hojustate:" を外した中身だけを出す。
                                // hojustate???UI????SYSTEM LOG???????
                                latestHojuState = line.removePrefix("hojustate:").trim()
                            } else {
                                normalLines += line
                            }
                        }

                    if (latestHojuState != null || normalLines.isNotEmpty() || invalidTarget) {
                        runOnUiThread {
                            // invalid_target が来たら、何を選んでいても MOVE TARGET を解除する。
                            if (invalidTarget) clearMoveTarget()
                            latestHojuState?.let { hojuState = it }
                            if (normalLines.isNotEmpty()) {
                                logList = (logList + normalLines).takeLast(50)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    appendLog("RECV ERROR: ${e.javaClass.simpleName} ${e.message}")
                }
            }
        }
    }

    /**
     * MOVE TARGET で hata / hoju を選んでいる間は、turn 調整の対象をそれに固定する。
     * （L3 / R3 で "none" に戻すか、他の移動先を選めば固定は解ける）
     */
    /**
     * MOVE TARGET の選択。hatat・hatao / hoju なら turn 調整の対象もそれに合わせる。
     * 選択中のボタンをもう一度押すと解除する（L3 / R3 と同じ扱い）。
     * hatat・hatao / hoju に切り替わったときは、対応するレーザーを1回だけ ON にする
     * （ON のまま固定はしないので、そのあと手動で OFF にできる）。
     * OBJECT も移動先に合わせて切り替える（BS 系 -> バケツ / hatat・hatao -> 旗 / dekahoju・dekahata -> デカ雑巾）。
     * ただし OBJECT がデカ雑巾のとき（デカ補充のあと）は hatat・hatao を選んでも旗にせずデカ雑巾のままにする。
     * hatat と hatao は turn 調整値を別々に持つ（HATAT / HATAO）。レーザーと OBJECT の扱いは共通。
     * こちらも選んだ瞬間に1回だけなので、そのあと手動や PS ボタンで変えられる。
     */
    private fun selectMoveTarget(value: String) {
        // 同じボタンをもう一度押したら解除する。選択中なのでロック判定は見るまでもない。
        if (selectedTarget == value) {
            clearMoveTarget()
            return
        }
        // 押せない移動先はここで弾く（画面のタップも SELECT の順送りも通る道）。
        if (!canPressMoveTarget(value, selectedTarget, unlockedTargets)) return
        selectedTarget = value
        // 選ばれていない方のレーザーは位置決めが解けたとみなして落とす。
        if (value !in HATA_TARGETS && value != "dekahata") hataLaser = false
        if (value != "hoju") hojuLaser = false
        when (value) {
            "hatat", "hatao" -> {
                setTurnTarget(if (value == "hatat") TurnTarget.HATAT else TurnTarget.HATAO)
                // 選んだ瞬間に1回だけ点ける。以降は固定しないので手動で消せる。
                hataLaser = true
                // デカ補充のあとに旗へ向かうときはデカ雑巾を撃つので、OBJECT はデカ雑巾のまま残す。
                if (selectedObject != "dekazoukin") selectedObject = "hata"
            }
            // デカ雑巾を旗に撃ちに行く。turn は専用の DEKAHATA、レーザーは hata と同じで、OBJECT は必ずデカ雑巾。
            "dekahata" -> {
                setTurnTarget(TurnTarget.DEKAHATA)
                hataLaser = true
                selectedObject = "dekazoukin"
            }
            "hoju" -> {
                setTurnTarget(TurnTarget.HOJU)
                hojuLaser = true
            }
            "dekahoju" -> selectedObject = "dekazoukin"
            "bs1", "bs2", "bs3" -> {
                setTurnTarget(TurnTarget.BAKETU)
                selectedObject = "baketu"
            }
        }
    }

    /** L3 / R3 で MOVE TARGET を解除する。位置決めをやめるのでレーザーも両方消す。 */
    private fun clearMoveTarget() {
        selectedTarget = "none"
        hataLaser = false
        hojuLaser = false
    }

    /** 自己位置から、いま押せる移動先を洗い直す。自己位置の受信が止まっていても一定間隔で呼ばれる。 */
    private fun refreshUnlockedTargets() {
        unlockedTargets = moveTargetsFor(selectedMapID)
            .filter { isMoveTargetUnlocked(it, posX, posY, posTheta) }
            .map { it.id }
            .toSet()
    }

    /** turn 調整の対象を直接切り替える。HATA の連続調整 Job の面倒もここで見る。 */
    private fun setTurnTarget(target: TurnTarget) {
        if (selectedTurnTarget == target) return
        selectedTurnTarget = target
        if (target.usesHoldRepeat()) {
            if (left || right || up || down || l2 || r2) {
                startHataTurnRepeat()
            }
        } else {
            stopHataTurnRepeat()
        }
    }

    /**
     * SELECT ボタンで MOVE TARGET を順送りする。どの組を送るかは選択中の移動先で決まる。
     * BS 系を選んでいれば BS1 -> BS2 -> BS3 -> BS1、HATAT / HATAO を選んでいればもう片方へ。
     * どの組にも入っていない移動先を選んでいるとき（none 含む）は何もしない。
     */
    private fun cycleSelectTarget() {
        val cycle = SELECT_TARGET_CYCLES.firstOrNull { selectedTarget in it } ?: return
        // 組の中のどれかを選んでいる時点で何か選択中なので、ロックは掛からず必ず送れる。
        selectMoveTarget(cycle[(cycle.indexOf(selectedTarget) + 1) % cycle.size])
    }

    /**
     * PS ボタンで OBJECT を バケツ -> 旗 -> デカ雑巾 -> バケツ と順送りする。
     * 並びは FIELD_OBJECTS の順番に従うので、画面のボタンの並びと一致する。
     */
    private fun cycleSelectedObject() {
        val ids = FIELD_OBJECTS.map { it.id }
        if (ids.isEmpty()) return
        val nextIndex = (ids.indexOf(selectedObject) + 1) % ids.size
        selectedObject = ids[nextIndex]
    }

    private fun adjustSelectedTurn(
        dx: Float = 0f,
        dy: Float = 0f,
        dtheta: Float = 0f
    ) {
        // con.py の保存値を受け取るまでは調整しない（受け取った時点で上書きされるため）。
        if (!settingsSynced) return
        when (selectedTurnTarget) {
            TurnTarget.HATAT -> {
                hatat_turnx += dx
                hatat_turny += dy
                hatat_turntheta += dtheta
            }
            TurnTarget.HATAO -> {
                hatao_turnx += dx
                hatao_turny += dy
                hatao_turntheta += dtheta
            }
            TurnTarget.HOJU -> {
                hoju_turnx += dx
                hoju_turny += dy
                hoju_turntheta += dtheta
            }
            TurnTarget.BAKETU -> {
                baketu_turnx += dx
                baketu_turny += dy
                baketu_turntheta += dtheta
            }
            TurnTarget.DEKAHATA -> {
                dekahata_turnx += dx
                dekahata_turny += dy
                dekahata_turntheta += dtheta
            }
        }
    }

    /**
     * 選択中の OBJECT に対応する column（1..3）の射出速度を返す。
     * SPEED ADJUST はこの値を表示するので、OBJECT を切り替えると調整対象も切り替わる。
     */
    private fun selectedObjectSpeed(column: Int): Float = when (selectedObject) {
        "hata" -> when (column) {
            1 -> hataSpeed1
            2 -> hataSpeed2
            else -> hataSpeed3
        }
        "dekazoukin" -> when (column) {
            1 -> dekazoukinSpeed1
            2 -> dekazoukinSpeed2
            else -> dekazoukinSpeed3
        }
        else -> when (column) {
            1 -> baketuSpeed1
            2 -> baketuSpeed2
            else -> baketuSpeed3
        }
    }

    /** 選択中の OBJECT の column（1..3）の速度を delta 分動かす。範囲は物体ごと。 */
    private fun adjustSelectedObjectSpeed(column: Int, delta: Float) {
        if (!settingsSynced) return
        fun clamp(v: Float) = v.coerceIn(speedRangeFor(selectedObject))
        when (selectedObject) {
            "hata" -> {
                when (column) {
                    1 -> hataSpeed1 = clamp(hataSpeed1 + delta)
                    2 -> hataSpeed2 = clamp(hataSpeed2 + delta)
                    else -> hataSpeed3 = clamp(hataSpeed3 + delta)
                }
            }
            "dekazoukin" -> {
                when (column) {
                    1 -> dekazoukinSpeed1 = clamp(dekazoukinSpeed1 + delta)
                    2 -> dekazoukinSpeed2 = clamp(dekazoukinSpeed2 + delta)
                    else -> dekazoukinSpeed3 = clamp(dekazoukinSpeed3 + delta)
                }
            }
            else -> {
                when (column) {
                    1 -> baketuSpeed1 = clamp(baketuSpeed1 + delta)
                    2 -> baketuSpeed2 = clamp(baketuSpeed2 + delta)
                    else -> baketuSpeed3 = clamp(baketuSpeed3 + delta)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                window.decorView.requestPointerCapture()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                window.decorView.postDelayed({ hideSystemUI() }, 5000)
            }
            return super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    private fun send(
        mapId: String,
        vx: Float, vy: Float, w: Float,
        hataSpeed1: Float, baketuSpeed1: Float,
        hataSpeed2: Float, baketuSpeed2: Float,
        hataSpeed3: Float, baketuSpeed3: Float,
        dekazoukinSpeed1: Float, dekazoukinSpeed2: Float, dekazoukinSpeed3: Float,
        hatatTurnX: Float, hatatTurnY: Float, hatatTurnTheta: Float,
        hataoTurnX: Float, hataoTurnY: Float, hataoTurnTheta: Float,
        hojuTurnX: Float, hojuTurnY: Float, hojuTurnTheta: Float,
        baketuTurnX: Float, baketuTurnY: Float, baketuTurnTheta: Float,
        dekahataTurnX: Float, dekahataTurnY: Float, dekahataTurnTheta: Float,
        mode: String,
        selectedObject: String, selectedTarget: String,
        hataLaser: Boolean, hojuLaser: Boolean, t0: Boolean,
        left: Boolean, right: Boolean, up: Boolean, down: Boolean,
        circle: Boolean, triangle: Boolean, square: Boolean, cross: Boolean,
        l1: Boolean, l2: Boolean, r1: Boolean, r2: Boolean,
        start: Boolean, select: Boolean, ps: Boolean,
        synced: Boolean
    ) {
        try {
            val msg = """{"map_id":"$mapId","vx":$vx,"vy":$vy,"w":$w,"hata_speed1":$hataSpeed1,"baketu_speed1":$baketuSpeed1,"hata_speed2":$hataSpeed2,"baketu_speed2":$baketuSpeed2,"hata_speed3":$hataSpeed3,"baketu_speed3":$baketuSpeed3,"dekazoukin_speed1":$dekazoukinSpeed1,"dekazoukin_speed2":$dekazoukinSpeed2,"dekazoukin_speed3":$dekazoukinSpeed3,"hatat_turnx":$hatatTurnX,"hatat_turny":$hatatTurnY,"hatat_turntheta":$hatatTurnTheta,"hatao_turnx":$hataoTurnX,"hatao_turny":$hataoTurnY,"hatao_turntheta":$hataoTurnTheta,"hoju_turnx":$hojuTurnX,"hoju_turny":$hojuTurnY,"hoju_turntheta":$hojuTurnTheta,"baketu_turnx":$baketuTurnX,"baketu_turny":$baketuTurnY,"baketu_turntheta":$baketuTurnTheta,"dekahata_turnx":$dekahataTurnX,"dekahata_turny":$dekahataTurnY,"dekahata_turntheta":$dekahataTurnTheta,"mode":"$mode","object":"$selectedObject","target":"$selectedTarget","hatalaser":$hataLaser,"hojulaser":$hojuLaser,"t0":$t0,"left":$left,"right":$right,"up":$up,"down":$down,"circle":$circle,"triangle":$triangle,"square":$square,"cross":$cross,"l1":$l1,"l2":$l2,"r1":$r1,"r2":$r2,"start":$start,"select":$select,"ps":$ps,"synced":$synced}"""
            val buf = msg.toByteArray()
            val packet = DatagramPacket(buf, buf.size, address, port)
            sendTime = System.nanoTime()
            socket.send(packet)
        } catch (e: Exception) {
            appendLog("SEND ERROR: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * ???????????????DatagramSocket#close()??????????????
     * onPause() / onDestroy() ????????????????
     * close()?????????????????socket.receive()????
     * SocketException????????????????????????????????
     */
    private fun closeSockets() {
        try {
            if (::socket.isInitialized) socket.close()
        } catch (e: Exception) {
            appendLog("SOCKET CLOSE ERROR: ${e.javaClass.simpleName} ${e.message}")
        }
        try {
            if (::logSocket.isInitialized) logSocket.close()
        } catch (e: Exception) {
            appendLog("SOCKET CLOSE ERROR: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    override fun onDestroy() {
        // Activity????turn????????????????????????????????
        stopHataTurnRepeat()
        networkJob?.cancel()
        closeSockets()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        stopHataTurnRepeat()
        saveSettings()
        // ??????????????????????????????????
        networkJob?.cancel()
        closeSockets()
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/**
 * ============================================================
 * UI THEME
 * ============================================================
 * ???????????????????????????????
 */
private object ControllerColors {
    val Background = Color(0xFF0B0F12)
    val Surface = Color(0xFF11181D)
    val Surface2 = Color(0xFF172128)
    val Border = Color(0xFF293840)
    val BorderAccent = Color(0xFF00D9FF)

    val TextPrimary = Color(0xFFEAF3F7)
    val TextSecondary = Color(0xFF8C9AA2)
    val TextMuted = Color(0xFF5C6970)

    val Accent = Color(0xFF00D9FF)
    val Success = Color(0xFF39D98A)
    val Warning = Color(0xFFFFB547)
    val Danger = Color(0xFFFF4D5A)

    val Red = Color(0xFF8F2530)
    val Blue = Color(0xFF175F8A)
    val Neutral = Color(0xFF2A353B)
}

/**
 * 画面の背景色。networkWarning の間は Background と Danger の間で点滅させる。
 */
@Composable
private fun screenBackground(networkWarning: Boolean): Color {
    if (!networkWarning) return ControllerColors.Background
    val fraction by rememberInfiniteTransition(label = "networkWarning").animateFloat(
        initialValue = 0f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse),
        label = "networkWarningFraction"
    )
    return lerp(ControllerColors.Background, ControllerColors.Danger, fraction)
}

/**
 * 起動時に Wi-Fi / モバイル回線がつながっていたときに画面上部へ出す警告。
 * クリック処理を持たないので、下にあるボタンへのタップはそのまま通る。
 */
@Composable
private fun NetworkWarningBanner(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .zIndex(10f)
            .padding(top = 8.dp)
            .background(ControllerColors.Danger, RoundedCornerShape(8.dp))
            .border(2.dp, ControllerColors.TextPrimary, RoundedCornerShape(8.dp))
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "⚠ Wi-Fi / モバイルデータを OFF にしてください",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = ControllerColors.TextPrimary
        )
        Text(
            text = "ON のままだとロボットと通信できません",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = ControllerColors.TextPrimary
        )
    }
}

@Composable
private fun HudPanel(
    modifier: Modifier = Modifier,
    accent: Color = ControllerColors.BorderAccent,
    // 枠と中身の間の余白。横幅が足りないパネルはここを詰める。
    contentPadding: androidx.compose.ui.unit.Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .background(
                color = ControllerColors.Surface,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = ControllerColors.Border,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            )
            content()
        }
    )
}

@Composable
private fun HudSectionTitle(
    text: String,
    color: Color = ControllerColors.Accent,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

@Composable
private fun HudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    containerColor: Color = ControllerColors.Surface2,
    contentColor: Color = ControllerColors.TextPrimary,
    enabled: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 50.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    accent: Color = ControllerColors.Border
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(7.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = ControllerColors.Neutral.copy(alpha = 0.55f),
            disabledContentColor = ControllerColors.TextMuted
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, accent, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

/**
 * ??????1????? + ??? + ?/?????
 * 上限 / 下限に達した側のボタンは無効（灰色）にする。
 */
@Composable
private fun SpeedAdjustRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    accent: Color = ControllerColors.Accent,
    enabled: Boolean = true
) {
    // 0.01 刻みの足し引きで誤差が乗るので、端との比較は少し余裕を持たせる。
    val canDecrease = enabled && value > range.start + 1e-4f
    val canIncrease = enabled && value < range.endInclusive - 1e-4f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "$label ${"%.3f".format(value)}",
            color = ControllerColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        SpeedStepButton(text = "-", enabled = canDecrease, onStep = onDecrease, accent = accent)
        SpeedStepButton(text = "+", enabled = canIncrease, onStep = onIncrease, accent = accent)
    }
}

/**
 * SPEED ADJUST の +/- ボタン。押した瞬間に1回動かし、押しっぱなしなら
 * HATA の turn 調整と同じく SPEED_REPEAT_INITIAL_DELAY_MS 後から SPEED_REPEAT_INTERVAL_MS ごとに動かし続ける。
 * 有効なときは OBJECT の色、無効（上限 / 下限）のときは灰色で表示する。
 */
@Composable
private fun SpeedStepButton(
    text: String,
    enabled: Boolean,
    onStep: () -> Unit,
    accent: Color
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // OBJECT を切り替えると onStep の中身も変わるので、常に最新を呼ぶ。
    val currentOnStep by rememberUpdatedState(onStep)
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        currentOnStep()
        delay(SPEED_REPEAT_INITIAL_DELAY_MS)
        while (true) {
            delay(SPEED_REPEAT_INTERVAL_MS)
            currentOnStep()
        }
    }

    val shape = RoundedCornerShape(7.dp)
    val containerColor = when {
        !enabled -> ControllerColors.Neutral.copy(alpha = 0.35f)
        pressed -> accent.copy(alpha = 0.45f)
        else -> accent.copy(alpha = 0.18f)
    }
    val contentColor = if (enabled) accent else ControllerColors.TextMuted
    val borderColor = if (enabled) accent else ControllerColors.Border
    Button(
        // 調整は押下で行うので、離したときの onClick では何もしない。
        onClick = {},
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier.width(38.dp).height(44.dp),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, borderColor, shape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

/**
 * ???????????UI??
 */
@Composable
fun TeamSelectionScreen(
    isFlipped: Boolean,
    // 起動時に Wi-Fi / モバイル回線がつながっていたら true。背景の点滅と警告を出す。
    networkWarning: Boolean,
    onToggleFlip: () -> Unit,
    onSelectTeam: (mapStr: String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .rotate(if (isFlipped) 180f else 0f)
            .background(screenBackground(networkWarning)),
        contentAlignment = Alignment.Center
    ) {
        if (networkWarning) NetworkWarningBanner(Modifier.align(Alignment.TopCenter))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Text(
                text = "SELECT FIELD SIDE",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = ControllerColors.TextPrimary,
                letterSpacing = 2.sp
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(40.dp)
            ) {
                Button(
                    onClick = { onSelectTeam("redmap") },
                    modifier = Modifier
                        .size(width = 180.dp, height = 120.dp)
                        .border(1.dp, ControllerColors.Danger.copy(alpha = 0.65f), RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF531920)
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Text(
                        text = "RED",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = ControllerColors.TextPrimary,
                        letterSpacing = 2.sp
                    )
                }

                Button(
                    onClick = { onSelectTeam("bluemap") },
                    modifier = Modifier
                        .size(width = 180.dp, height = 120.dp)
                        .border(1.dp, ControllerColors.Accent.copy(alpha = 0.65f), RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF123D59)
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Text(
                        text = "BLUE",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = ControllerColors.TextPrimary,
                        letterSpacing = 2.sp
                    )
                }
            }

            HudButton(
                text = if (isFlipped) "FLIPPED  /  180�" else "ROTATE SCREEN  /  180�",
                onClick = onToggleFlip,
                modifier = Modifier.width(220.dp),
                containerColor = if (isFlipped) Color(0xFF5A431B) else ControllerColors.Surface2,
                contentColor = if (isFlipped) ControllerColors.Warning else ControllerColors.TextPrimary,
                height = 50.dp,
                accent = if (isFlipped) ControllerColors.Warning else ControllerColors.Border
            )
        }
    }
}

/**
 * ????????????????
 * ??????????????? send() ? "object" ????????
 */
private data class FieldObject(val id: String, val label: String)

private val FIELD_OBJECTS = listOf(
    FieldObject("baketu", "バケツ"),
    FieldObject("hata", "旗"),
    FieldObject("dekazoukin", "デカ雑巾")
)

/**
 * ?????????????????????
 * ??? id ? send() ? "target" ?????????
 *
 * ?????????????? 9 ??????????????????????
 * ????????????????????????????????????
 * ??????????????????????????????
 */
private data class MoveTarget(
    val id: String,
    val label: String,
    // このボタンが押せるようになる範囲。null なら位置に関係なくいつでも押せる。
    val unlockArea: UnlockArea? = null
)

/**
 * MOVE TARGET のボタンが押せるようになる基準位置。
 * 自己位置がここを中心とした許容幅の中に居る間だけ、そのボタンを押せる。
 * 付近以外に居る間はボタンがロックされ、押しても選べない。
 *
 * x / y は mm、theta は度（画面の ROBOT POSE の θ 表示と同じ単位）。
 * ロボットからは theta がラジアンで届くので、判定の直前で度に直して比べる。
 * 座標は赤 / 青で別々に定義する（RED_ / BLUE_MOVE_TARGET_ROWS）。ロボットからは自サイド基準の値が来る前提。
 *
 * enabled = false にすると、座標を残したままその移動先のロックだけを止められる
 * （= いつでも押せる）。
 */
private data class UnlockArea(
    val x: Float,
    val y: Float,
    val theta: Float,
    val enabled: Boolean = true
)

// 全ターゲット共通の許容幅。基準位置との差がこの値以内ならボタンを押せる。
private const val UNLOCK_TOLERANCE_X = 500f       // mm
private const val UNLOCK_TOLERANCE_Y = 500f       // mm
private const val UNLOCK_TOLERANCE_THETA = 34f    // 度

// ロック判定の間隔。自己位置の受信が途切れていてもこの間隔で評価し続ける。
private const val TARGET_LOCK_INTERVAL_MS = 50L

// ping の宛先と間隔。結果は NETWORK RTT の下に出す。
private const val PING_HOST = "192.168.11.1"
private const val PING_INTERVAL_MS = 1000L

/** 何も選んでいない状態かどうか。起動直後の "" も含む。 */
private fun isMoveTargetCleared(selectedTarget: String): Boolean =
    selectedTarget.isEmpty() || selectedTarget == "none"

/**
 * その移動先のボタンをいま押せるか。
 * 何か選択している間は、どのボタンも押せる（選び直しを妨げないため）。
 * 何も選んでいないときだけ、自己位置がそのボタンの範囲に入っているかで決まる。
 */
private fun canPressMoveTarget(
    targetId: String,
    selectedTarget: String,
    unlockedTargets: Set<String>
): Boolean =
    !isMoveTargetCleared(selectedTarget) || targetId in unlockedTargets

/**
 * 自己位置がその移動先の範囲に入っているか。
 * 範囲を持たない（null）移動先と enabled = false の移動先は、位置に関係なく常に範囲内とみなす。
 */
private fun isMoveTargetUnlocked(
    target: MoveTarget,
    posX: Double,
    posY: Double,
    posTheta: Double
): Boolean {
    val area = target.unlockArea ?: return true
    if (!area.enabled) return true
    if (Math.abs(posX - area.x) > UNLOCK_TOLERANCE_X) return false
    if (Math.abs(posY - area.y) > UNLOCK_TOLERANCE_Y) return false
    // 基準値も許容幅も度なので、自己位置のラジアンを度に直してから比べる。
    // ±180° をまたいでも正しく比べられるよう正規化する。
    val dTheta = Math.IEEEremainder(Math.toDegrees(posTheta) - area.theta, 360.0)
    return Math.abs(dTheta) <= UNLOCK_TOLERANCE_THETA
}

// 旗へ撃ちに行く移動先。turn 調整値は別々に持つが、レーザー・OBJECT の扱いは共通。
private val HATA_TARGETS = listOf("hatat", "hatao")

// SELECT ボタンで順送りする組。選択中の移動先が入っている組の中で、次へ送って一周する。
// BS1 -> BS2 -> BS3 -> BS1 / HATAT <-> HATAO。
private val SELECT_TARGET_CYCLES = listOf(
    listOf("bs1", "bs2", "bs3"),
    HATA_TARGETS
)

// 画面での並びをそのまま表す。内側の listOf 1つが 1 行分で、1 行に最大 4 つ並ぶ。
// unlockArea の x / y（mm）・theta（度）がそのボタンを押せる場所。enabled = false でロックなし。
// 座標はロボット側のマップのポイントに合わせてある。向きはあちらが rad なので度に直して書く
// （0.22 rad = 12.6° / -1.57 rad = -90°）。
// 青ゾーン用。画面での並びもそのまま使う（各行とも 1 が右端に来るよう逆順、右詰めで表示）。
private val BLUE_MOVE_TARGET_ROWS = listOf(
    listOf(
        MoveTarget("tb2", "TB2", UnlockArea(2900f, 8645f, 0f, enabled = true)),
        MoveTarget("tb1", "TB1", UnlockArea(2900f, 2955f, 0f, enabled = true)),
        MoveTarget("dekahata", "DEKAHATA", UnlockArea(4260f, 6000f, 0f, enabled = true))
    ),
    listOf(
        MoveTarget("bs3", "BS3", UnlockArea(5080f, 4096f, 0f, enabled = true)),
        MoveTarget("bs2", "BS2", UnlockArea(5080f, 3976f, 0f, enabled = true)),
        MoveTarget("bs1", "BS1", UnlockArea(5080f, 3844f, 0f, enabled = true))
    ),
    listOf(
        MoveTarget("dekahoju", "DEKAHOJU", UnlockArea(3900f, 500f, -90f, enabled = true)),
        MoveTarget("hoju", "HOJU", UnlockArea(4050f, 500f, 0f, enabled = true)),
        MoveTarget("hatao", "HATAO", UnlockArea(4930f, 6700f, -12.6f, enabled = true)),
        MoveTarget("hatat", "HATAT", UnlockArea(4930f, 5080f, 12.6f, enabled = true))
    )
)

// 赤ゾーン用。フィールドが左右反転するので、各行を青の逆順（1 が左端）に並べ、左詰めで表示する。
// 座標はロボット側と同じく、青の y と向きの符号を反転したもの。
// 並び・ラベル・座標とも青とは独立に書き換えられる。
private val RED_MOVE_TARGET_ROWS = listOf(
    listOf(
        MoveTarget("tb1", "TB1", UnlockArea(2900f, -2955f, 0f, enabled = true)),
        MoveTarget("tb2", "TB2", UnlockArea(2900f, -8645f, 0f, enabled = true)),
        MoveTarget("dekahata", "DEKAHATA", UnlockArea(4260f, -6000f, 0f, enabled = true))
    ),
    listOf(
        MoveTarget("bs1", "BS1", UnlockArea(5080f, -3844f, 0f, enabled = true)),
        MoveTarget("bs2", "BS2", UnlockArea(5080f, -3976f, 0f, enabled = true)),
        MoveTarget("bs3", "BS3", UnlockArea(5080f, -4096f, 0f, enabled = true))
    ),
    listOf(
        MoveTarget("hatat", "HATAT", UnlockArea(4930f, -5080f, -12.6f, enabled = true)),
        MoveTarget("hatao", "HATAO", UnlockArea(4930f, -6700f, 12.6f, enabled = true)),
        MoveTarget("hoju", "HOJU", UnlockArea(4050f, -500f, 0f, enabled = true)),
        MoveTarget("dekahoju", "DEKAHOJU", UnlockArea(3900f, -500f, 90f, enabled = true))
    )
)

/** サイド（"redmap" / "bluemap"）に応じた移動先の並び。 */
private fun moveTargetRowsFor(mapId: String): List<List<MoveTarget>> =
    if (mapId == "redmap") RED_MOVE_TARGET_ROWS else BLUE_MOVE_TARGET_ROWS

// ローをターゲット id で引けるようにしたもの。毎回 flatten() しないため。
private val RED_MOVE_TARGETS = RED_MOVE_TARGET_ROWS.flatten()
private val BLUE_MOVE_TARGETS = BLUE_MOVE_TARGET_ROWS.flatten()

private fun moveTargetsFor(mapId: String): List<MoveTarget> =
    if (mapId == "redmap") RED_MOVE_TARGETS else BLUE_MOVE_TARGETS

@Composable
fun ControllerUI(
    isFlipped: Boolean,
    // 起動時に Wi-Fi / モバイル回線がつながっていたら true。背景の点滅と警告を出す。
    networkWarning: Boolean,
    rttList: List<Long>,
    // PING_HOST への ping の最新結果（"12.3 ms" / "TIMEOUT" など）。pingOk = false なら赤。
    pingText: String,
    pingOk: Boolean,
    posX: Double,
    posY: Double,
    posTheta: Double,
    mapID: String,
    currentVx: Float,
    currentVy: Float,
    currentW: Float,
    hatatTurnX: Float, hatatTurnY: Float, hatatTurnTheta: Float,
    hataoTurnX: Float, hataoTurnY: Float, hataoTurnTheta: Float,
    hojuTurnX: Float, hojuTurnY: Float, hojuTurnTheta: Float,
    baketuTurnX: Float, baketuTurnY: Float, baketuTurnTheta: Float,
    dekahataTurnX: Float, dekahataTurnY: Float, dekahataTurnTheta: Float,
    selectedTurnTarget: TurnTarget,
    t0: Boolean,
    onToggleT0: () -> Unit,
    left: Boolean, right: Boolean, up: Boolean, down: Boolean,
    circle: Boolean, square: Boolean, cross: Boolean, triangle: Boolean,
    l1: Boolean, l2: Boolean, r1: Boolean, r2: Boolean,
    selectedObject: String,
    onSelectObject: (String) -> Unit,
    selectedTarget: String,
    onSelectTarget: (String) -> Unit,
    unlockedTargets: Set<String>,
    hataLaser: Boolean,
    onHataLaser: () -> Unit,
    hojuLaser: Boolean,
    onHojuLaser: () -> Unit,
    lowGain: Boolean,
    onToggleLowGain: () -> Unit,
    // 選択中の OBJECT の column1..3 の射出速度。OBJECT を切り替えると中身ごと入れ替わる。
    speed1: Float,
    speed2: Float,
    speed3: Float,
    onSpeedIncrease: (column: Int) -> Unit,
    onSpeedDecrease: (column: Int) -> Unit,
    logList: List<String>,
    hojuState: String,
    t0locked: Boolean,
    // con.py から turn / speed を受け取るまでは false。その間は調整ボタンを無効にする。
    settingsSynced: Boolean
) {
    val imageBitmap = ImageBitmap.imageResource(id = R.drawable.blackarrow)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .rotate(if (isFlipped) 180f else 0f)
            .background(screenBackground(networkWarning)),
    ) {
        if (networkWarning) NetworkWarningBanner(Modifier.align(Alignment.TopCenter))
        // 移動先の一覧。常に1つ点灯して "target" を送り続け、L3 / R3 でのみ "none" に戻る。
        // 1 行に 4 つ並べるので、OBJECT / SPEED ADJUST の余白を詰めて空けたぶんまで右へ広げてある
        // （96dp x 4 + 間隔 4dp x 3 + 余白 4dp x 2 = 404dp）。
        HudPanel(
            modifier = Modifier.width(404.dp).offset(x=122.dp,).zIndex(1f),
            accent = ControllerColors.Accent,
            contentPadding = 4.dp
        ) {
            HudSectionTitle("MOVE TARGET  /  ${if (selectedTarget == "none") "---" else selectedTarget.uppercase()}", ControllerColors.Accent, 12.sp)
            // 並びと座標は赤 / 青それぞれの定義（RED_ / BLUE_MOVE_TARGET_ROWS）を使う。
            val moveTargetRows = moveTargetRowsFor(mapID)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(136.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // 青は 1 が右端に来るよう右詰め、赤はその左右反転で左詰め。行の中身の順番は定義側で決めてある
                // （ボタン数の少ない行も赤青で鏡写しの位置に来る）。
                horizontalAlignment = if (mapID == "redmap") Alignment.Start else Alignment.End
            ) {
                moveTargetRows.forEach { rowTargets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowTargets.forEach { target ->
                            MoveTargetButton(
                                label = target.label,
                                selected = selectedTarget == target.id,
                                // 何も選んでいない間だけ、付近に居ないボタンをロックする。
                                enabled = canPressMoveTarget(target.id, selectedTarget, unlockedTargets),
                                onClick = { onSelectTarget(target.id) }
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .size(370.dp)
                .offset(x = 120.dp,y=90.dp)
                .background(ControllerColors.Surface, RoundedCornerShape(6.dp))
                .border(1.dp, ControllerColors.Border, RoundedCornerShape(6.dp))
                .graphicsLayer { scaleX = if (mapID == "redmap") -1f else 1f }
        ) {

            Image(
                painter = if (mapID == "redmap")painterResource(id = R.drawable.robocon_mapred) else painterResource(id = R.drawable.robocon_mapblue),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = 90f
                    scaleX = if (mapID == "redmap") -1f else 1f
                },
                contentScale = ContentScale.Fit
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val mapWidth = 11000
                val mapHeight = 11000
                val scaleX = size.width / mapWidth
                val scaleY = size.height / mapHeight
                var tempPx = 0.0
                var tempPy = 0.0
                if (mapID == "bluemap") {
                    tempPx = posX
                    tempPy = posY
                } else {
                    tempPx = posX
                    tempPy = posY
                }

                val px = size.height - (tempPx * scaleX).toFloat()
                val py = (-tempPy * scaleY).toFloat()
                val robotPos = Offset(px, py)

                val angleDegrees = Math.toDegrees(posTheta).toFloat()
                val translateX: Float
                val translateY: Float

                if (mapID == "bluemap") {
                    translateX = robotPos.y + 1182f
                    translateY = robotPos.x - 317f
                } else {
                    translateX = -robotPos.y + 1182f
                    translateY = robotPos.x -317f
                }

                withTransform({
                    translate(translateX, translateY)
                    rotate(degrees = -angleDegrees - 90f, pivot = Offset.Zero)
                    scale(scaleX = 0.15f, scaleY = 0.15f, pivot = Offset.Zero)
                }) {
                    drawImage(
                        image = imageBitmap,
                        topLeft = Offset(-imageBitmap.width / 2f, -imageBitmap.height / 2f)
                    )
                }
            }
        }

        // ?????????????????
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(1.dp)
                .width(120.dp)
        ) {
            HudPanel(
                modifier = Modifier.fillMaxWidth(),
                accent = if (mapID == "redmap") ControllerColors.Danger else ControllerColors.Accent
            ) {
                Text(
                    text = if (mapID == "redmap") "RED SIDE" else "BLUE SIDE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (mapID == "redmap") ControllerColors.Danger else ControllerColors.Accent,
                    letterSpacing = 1.sp
                )

                HudSectionTitle("ROBOT POSE")
                Text("X   ${"%.2f".format(posX)} mm", color = ControllerColors.TextPrimary, fontSize = 13.sp)
                Text("Y   ${"%.2f".format(posY)} mm", color = ControllerColors.TextPrimary, fontSize = 13.sp)
                // posTheta はラジアンで届くので、表示は度に直してから出す。
                Text("θ   ${"%.2f".format(Math.toDegrees(posTheta))}°", color = ControllerColors.TextPrimary, fontSize = 13.sp)

                Text("SYSTEM LOG", color = ControllerColors.TextSecondary, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)

                val logScrollState = rememberLazyListState()
                LaunchedEffect(logList.size) {
                    if (logList.isNotEmpty()) logScrollState.animateScrollToItem(logList.lastIndex)
                }

                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(130.dp)
                        .background(ControllerColors.Background, RoundedCornerShape(5.dp))
                        .border(1.dp, ControllerColors.Border, RoundedCornerShape(5.dp))
                        .padding(5.dp)
                ) {
                    LazyColumn(state = logScrollState, modifier = Modifier.fillMaxSize()) {
                        items(logList) { logLine ->
                            Text(
                                text = logLine,
                                color = ControllerColors.TextSecondary,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                // PING の欄を入れるぶん、NETWORK RTT の枠を 120dp から縮めてある。
                HudSectionTitle("NETWORK RTT")
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(30.dp)
                        .background(ControllerColors.Background, RoundedCornerShape(5.dp))
                        .border(1.dp, ControllerColors.Border, RoundedCornerShape(5.dp))
                        .padding(5.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        rttList.asReversed().forEach { rtt ->
                            Text("$rtt ms", color = ControllerColors.Success, fontSize = 10.sp)
                        }
                    }
                }

                // 宛先の下2つ（"192.168.11.1" -> "11.1"）だけ出す。
                HudSectionTitle("PING ${PING_HOST.split('.').takeLast(2).joinToString(".")}")
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(30.dp)
                        .background(ControllerColors.Background, RoundedCornerShape(5.dp))
                        .border(1.dp, if (pingOk) ControllerColors.Border else ControllerColors.Danger, RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = pingText,
                        color = if (pingOk) ControllerColors.Success else ControllerColors.Danger,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }

        // 右上：turn調整パネルと SPEED ADJUST。
        // 下端の LOW GAIN / レーザー列（約106dp）にかからないよう下に余白を取り、
        // 残りの高さを SPEED ADJUST に weight(1f) で全部使わせる。
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(1.dp)
                .width(168.dp)
                .fillMaxHeight()
                .padding(bottom = 116.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End
        ) {
            // 射出速度調整。選択中の OBJECT の column1..3 だけを表示するので3行。
            // t0 と入れ替えてカラム先頭。weight(1f) で t0 を除いた残りの高さを使う。
            val speedAccent = when (selectedObject) {
                "hata" -> ControllerColors.Accent
                "dekazoukin" -> ControllerColors.Success
                else -> ControllerColors.Warning
            }
            HudPanel(modifier = Modifier.fillMaxWidth().weight(1f), accent = speedAccent, contentPadding = 4.dp) {
                HudSectionTitle(
                    if (settingsSynced) "SPEED ADJUST  /  ${objectDisplayName(selectedObject)}" else "SPEED ADJUST  /  SYNC...",
                    if (settingsSynced) speedAccent else ControllerColors.Danger,
                    11.sp
                )
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    val speedRange = speedRangeFor(selectedObject)
                    SpeedAdjustRow(
                        label = "C1",
                        value = speed1,
                        range = speedRange,
                        enabled = settingsSynced,
                        onIncrease = { onSpeedIncrease(1) },
                        onDecrease = { onSpeedDecrease(1) },
                        accent = speedAccent
                    )
                    SpeedAdjustRow(
                        label = "C2",
                        value = speed2,
                        range = speedRange,
                        enabled = settingsSynced,
                        onIncrease = { onSpeedIncrease(2) },
                        onDecrease = { onSpeedDecrease(2) },
                        accent = speedAccent
                    )
                    SpeedAdjustRow(
                        label = "C3",
                        value = speed3,
                        range = speedRange,
                        enabled = settingsSynced,
                        onIncrease = { onSpeedIncrease(3) },
                        onDecrease = { onSpeedDecrease(3) },
                        accent = speedAccent
                    )
                }
            }

            // t0（SPEED ADJUST と入れ替えてカラム下端へ）
            Button(
                onClick = onToggleT0,
                enabled = !t0locked,
                modifier = Modifier
                    .size(80.dp)
                    .border(1.dp, if (t0) ControllerColors.Success else ControllerColors.Danger, androidx.compose.foundation.shape.CircleShape),
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (t0) Color(0xFF124D37) else Color(0xFF521A22)
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) {
                Text(if (t0) "ON" else "OFF", fontWeight = FontWeight.Bold, color = ControllerColors.TextPrimary)
            }
        }

        // 物体選択（バケツ / 旗 / デカ雑巾）3択排他。
        // 常に1つ点灯して "object" を送り続ける。
        HudPanel(
            modifier = Modifier
                .width(134.dp)
                .offset(527.dp, 0.dp),
            accent = ControllerColors.Accent,
            contentPadding = 4.dp
        ) {
            HudSectionTitle("OBJECT  /  ${objectDisplayName(selectedObject)}", ControllerColors.Accent, 12.sp)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FIELD_OBJECTS.forEach { fieldObject ->
                    ObjectSelectButton(
                        label = fieldObject.label,
                        selected = selectedObject == fieldObject.id,
                        onClick = { onSelectObject(fieldObject.id) }
                    )
                }
            }
        }

        // turn 調整値の表示（旧 t0 ボタンの位置）。操作は D-pad / L2 R2。
        HudPanel(
            modifier = Modifier
                .width(165.dp)
                .offset(498.dp, 174.dp),
            accent = ControllerColors.Warning,
            // HATAT / HATAO で1行増えたぶん、下の hojustate 表示にかからないよう余白を詰めて少し上げてある。
            contentPadding = 4.dp
        ) {
            HudSectionTitle(
                if (settingsSynced) "TURN ADJUST  /  ${selectedTurnTarget.displayName()}" else "TURN ADJUST  /  SYNC...",
                if (settingsSynced) ControllerColors.Warning else ControllerColors.Danger,
                10.sp
            )
            Text("BAKETU X ${"%.2f".format(baketuTurnX)} Y ${"%.2f".format(baketuTurnY)} T ${"%.2f".format(baketuTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
            Text("HATAT  X ${"%.2f".format(hatatTurnX)} Y ${"%.2f".format(hatatTurnY)} T ${"%.2f".format(hatatTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
            Text("HATAO  X ${"%.2f".format(hataoTurnX)} Y ${"%.2f".format(hataoTurnY)} T ${"%.2f".format(hataoTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
            Text("HOJU   X ${"%.2f".format(hojuTurnX)} Y ${"%.2f".format(hojuTurnY)} T ${"%.2f".format(hojuTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
            Text("DKHATA X ${"%.2f".format(dekahataTurnX)} Y ${"%.2f".format(dekahataTurnY)} T ${"%.2f".format(dekahataTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
        }

        // ロボットから受け取った hojustate 表示（旧 t0 ボタンの位置）
        HudPanel(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(100.dp)
                .width(145.dp).height(70.dp)
                .offset(395.dp, 70.dp),
            accent = ControllerColors.Success
        ) {
            Text(
                text = if (hojuState.isBlank()) "---" else hojuState,
                color = ControllerColors.TextPrimary,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        // ??????????????????? LOW GAIN / HATA LASER / HOJU LASER
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(1.dp)
                .offset(-9.dp, y = -2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End
        ) {
            HudButton(
                text = if (lowGain) "LOW GAIN  /  ON" else "LOW GAIN  /  OFF",
                onClick = onToggleLowGain,
                modifier = Modifier.width(180.dp),
                containerColor = if (lowGain) Color(0xFF124D37) else ControllerColors.Surface2,
                contentColor = if (lowGain) ControllerColors.Success else ControllerColors.TextSecondary,
                height = 47.dp,
                fontSize = 17.sp,
                accent = if (lowGain) ControllerColors.Success else ControllerColors.Border
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HudButton(
                    text = if (hataLaser) "HATA LASER  /  ACTIVE" else "HATA LASER",
                    onClick = onHataLaser,
                    modifier = Modifier.width(88.dp),
                    containerColor = if (hataLaser) Color(0xFF124D37) else ControllerColors.Surface2,
                    contentColor = if (hataLaser) ControllerColors.Success else ControllerColors.TextPrimary,
                    height = 55.dp,
                    fontSize = 9.sp,
                    accent = if (hataLaser) ControllerColors.Success else ControllerColors.Warning
                )
                HudButton(
                    text = if (hojuLaser) "HOJU LASER  /  ACTIVE" else "HOJU LASER",
                    onClick = onHojuLaser,
                    modifier = Modifier.width(88.dp),
                    containerColor = if (hojuLaser) Color(0xFF124D37) else ControllerColors.Surface2,
                    contentColor = if (hojuLaser) ControllerColors.Success else ControllerColors.TextPrimary,
                    height = 55.dp,
                    fontSize = 9.sp,
                    accent = if (hojuLaser) ControllerColors.Success else ControllerColors.Warning
                )
            }
        }
    }
}

fun TurnTarget.displayName(): String = when (this) {
    TurnTarget.HATAT -> "HATAT"
    TurnTarget.HATAO -> "HATAO"
    TurnTarget.HOJU -> "HOJU"
    TurnTarget.BAKETU -> "BAKETU"
    TurnTarget.DEKAHATA -> "DEKAHATA"
}

/** 押しっぱなしで連続調整する対象か。旗を狙う HATAT / HATAO / DEKAHATA が対象。 */
private fun TurnTarget.usesHoldRepeat(): Boolean =
    this == TurnTarget.HATAT || this == TurnTarget.HATAO || this == TurnTarget.DEKAHATA

/** send() ?????? "object" ?????????????? */
private fun objectDisplayName(id: String): String =
    FIELD_OBJECTS.firstOrNull { it.id == id }?.label ?: "---"

/** ???????3?????1?? */
@Composable
private fun ObjectSelectButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    HudButton(
        text = if (selected) "▶  $label" else label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (selected) Color(0xFF123B4A) else ControllerColors.Surface2,
        contentColor = if (selected) ControllerColors.Accent else ControllerColors.TextSecondary,
        height = 40.dp,
        fontSize = 14.sp,
        accent = if (selected) ControllerColors.Accent else ControllerColors.Border
    )
}

/** ??????????1?? */
@Composable
private fun MoveTargetButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    // 選択中 = 緑ベタ塗り + 白枠 / 押せる = 水色ベタ塗り / ロック中 = 黒く沈めて取り消し線 + LOCKED。
    // 押せる / 押せないは塗りの有無で遠目にも分かるようにしてある。
    val shape = RoundedCornerShape(7.dp)
    val containerColor = when {
        selected -> ControllerColors.Success
        enabled -> ControllerColors.Accent
        else -> ControllerColors.Background
    }
    val contentColor = if (enabled) ControllerColors.Background else ControllerColors.TextMuted.copy(alpha = 0.5f)
    val borderColor = when {
        selected -> ControllerColors.TextPrimary
        enabled -> ControllerColors.Accent
        else -> ControllerColors.Surface2
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(96.dp).height(42.dp),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .border(if (selected) 3.dp else 1.dp, borderColor, shape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = contentColor,
                textDecoration = if (enabled) null else TextDecoration.LineThrough
            )
            if (!enabled) {
                Text(
                    text = "LOCKED",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = ControllerColors.Danger.copy(alpha = 0.7f),
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
