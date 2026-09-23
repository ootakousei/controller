package com.example.controller

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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

// ??????????Enum
enum class ScreenState {
    TEAM_SELECTION,
    CONTROLLER
}

enum class TurnTarget {
    HATA,
    HOJU,
    BAKETU
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

    private var posX by mutableStateOf(3300.0)
    private var posY by mutableStateOf(8645.0)
    private var posTheta by mutableStateOf(0.0)
    private var logList by mutableStateOf<List<String>>(emptyList())
    // robot??????hojustate:xxxxxxx??????????????????
    private var hojuState by mutableStateOf("")
    // turn???
    private var hata_turnx by mutableStateOf(0.0f)
    private var hata_turny by mutableStateOf(0.0f)
    private var hata_turntheta by mutableStateOf(0.0f)
    private var hoju_turnx by mutableStateOf(0.0f)
    private var hoju_turny by mutableStateOf(0.0f)
    private var hoju_turntheta by mutableStateOf(0.0f)
    // バケツ用の turn 調整値。MOVE TARGET で BS 系（bs1 / bs2 / bs3）を選んでいるときの対象。
    private var baketu_turnx by mutableStateOf(0.0f)
    private var baketu_turny by mutableStateOf(0.0f)
    private var baketu_turntheta by mutableStateOf(0.0f)

    // t0
    private var t0 by mutableStateOf(false)

    // 画面上の物体選択（バケツ / 旗 / デカ雑巾）3択排他。
    // 常にどれか1つが選択状態で、send() で "object" として送り続ける。
    // 未選択状態はなし（選択中のボタンを再タップしても解除されない）。
    private var selectedObject by mutableStateOf("baketu")

    // マップ横の一覧から選んだ移動先。
    // 基本は常にどれか1つが選択状態で、send() で "target" として送り続ける。
    // ボタンの再タップでは解除されず、Lスティック / Rスティックの押し込み（L3 / R3）でのみ "none" に戻る。
    private var selectedTarget by mutableStateOf("")

    // START / SELECT / PS ?????????????????????
    private var start by mutableStateOf(false)
    private var select by mutableStateOf(false)
    private var ps by mutableStateOf(false)

    private var hataLaser by mutableStateOf(false)
    private var hojuLaser by mutableStateOf(false)

    // デバッグログを間引くための前回出力時刻。
    private var lastLockDebugMs = 0L

    private var sendTime = 0L
    private val rttList = mutableStateListOf<Long>()

    // ????????????? String ("bluemap" ??? "redmap")???????????
    private var currentScreen by mutableStateOf(ScreenState.TEAM_SELECTION)
    private var selectedMapID by mutableStateOf("bluemap")
    private var isFlipped by mutableStateOf(true)

    // turn????
    private var selectedTurnTarget by mutableStateOf(TurnTarget.HATA)

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
    private val speedStep = 0.025f

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

            if (selectedTurnTarget == TurnTarget.HATA) {
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

                if (selectedTurnTarget != TurnTarget.HATA) break

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
                // SELECT ボタンで BS3 -> BS2 -> BS1 -> BS3 と切り替える
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    select = true
                    if (event.repeatCount == 0) {
                        cycleBsTarget()
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

    /**
     * サイドごとのキーを読む。"<mapId>_<name>" が無ければ旧バージョンの
     * サイド共用キー "<name>" を使う（それも無ければ default）。
     */
    private fun loadSideFloat(mapId: String, name: String, default: Float): Float =
        prefs.getFloat("${mapId}_$name", prefs.getFloat(name, default))

    /** 選んだサイドの turn 調整値と射出速度を読み込む。 */
    private fun loadSettings(mapId: String) {
        hata_turnx = loadSideFloat(mapId, "hata_turnx", 0.0f)
        hata_turny = loadSideFloat(mapId, "hata_turny", 0.0f)
        hata_turntheta = loadSideFloat(mapId, "hata_turntheta", 0.0f)
        hoju_turnx = loadSideFloat(mapId, "hoju_turnx", 0.0f)
        hoju_turny = loadSideFloat(mapId, "hoju_turny", 0.0f)
        hoju_turntheta = loadSideFloat(mapId, "hoju_turntheta", 0.0f)
        baketu_turnx = loadSideFloat(mapId, "baketu_turnx", 0.0f)
        baketu_turny = loadSideFloat(mapId, "baketu_turny", 0.0f)
        baketu_turntheta = loadSideFloat(mapId, "baketu_turntheta", 0.0f)
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
            .putFloat("${mapId}_hata_turnx", hata_turnx)
            .putFloat("${mapId}_hata_turny", hata_turny)
            .putFloat("${mapId}_hata_turntheta", hata_turntheta)
            .putFloat("${mapId}_hoju_turnx", hoju_turnx)
            .putFloat("${mapId}_hoju_turny", hoju_turny)
            .putFloat("${mapId}_hoju_turntheta", hoju_turntheta)
            .putFloat("${mapId}_baketu_turnx", baketu_turnx)
            .putFloat("${mapId}_baketu_turny", baketu_turny)
            .putFloat("${mapId}_baketu_turntheta", baketu_turntheta)
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

        // --- ???? ---
        // ???????????????????????????????????
        // ????????????????????
        // 移動先ボタンのロック判定は自己位置の受信にぶら下げず、一定間隔で回し続ける。
        // ネットワーク初期化の成否や受信の途切れに左右されないよう、networkJob とは別に持つ。
        lifecycleScope.launch {
            while (isActive) {
                if (currentScreen == ScreenState.CONTROLLER) {
                    updateMoveTargetLock()
                    logMoveTargetLockDebug()
                }
                delay(TARGET_LOCK_INTERVAL_MS)
            }
        }

        networkJob = lifecycleScope.launch(Dispatchers.IO) {
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
                        mapID = selectedMapID,
                        rttList = rttList,
                        posX = posX,
                        posY = posY,
                        posTheta = posTheta,
                        currentVx = if (lowGain) vy / 2f else vy,
                        currentVy = if (lowGain) vx / 2f else vx,
                        currentW = if (lowGain) w / 2f else w,
                        hataTurnX = hata_turnx, hataTurnY = hata_turny, hataTurnTheta = hata_turntheta,
                        hojuTurnX = hoju_turnx, hojuTurnY = hoju_turny, hojuTurnTheta = hoju_turntheta,
                        baketuTurnX = baketu_turnx, baketuTurnY = baketu_turny, baketuTurnTheta = baketu_turntheta,
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
                        t0locked = t0Locked
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
                    hata_turnx, hata_turny, hata_turntheta,
                    hoju_turnx, hoju_turny, hoju_turntheta,
                    baketu_turnx, baketu_turny, baketu_turntheta,
                    mode = "normal",
                    selectedObject, selectedTarget,
                    hataLaser, hojuLaser, t0,
                    left, right, up, down,
                    circle, triangle, square, cross,
                    l1, l2, r1, r2,
                    start, select, ps
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
        val buf = ByteArray(1024)
        while (isActive) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                logSocket.receive(packet) // ??????I/O?Dispatchers.IO???????

                val jsonString = String(packet.data, 0, packet.length)
                val json = JSONObject(jsonString)
                val receivedLog = json.optString("log", "")

                if (receivedLog.isNotBlank() && !receivedLog.equals("none", ignoreCase = true)) {
                    val normalLines = mutableListOf<String>()
                    var latestHojuState: String? = null

                    receivedLog.split("\n")
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
                        .forEach { line ->
                            if (line.startsWith("hojustate:")) {
                                // hojustate???UI????SYSTEM LOG???????
                                latestHojuState = line
                            } else {
                                normalLines += line
                            }
                        }

                    if (latestHojuState != null || normalLines.isNotEmpty()) {
                        runOnUiThread {
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
     * MOVE TARGET の選択。hata / hoju なら turn 調整の対象もそれに合わせる。
     * 選択中のボタンを再タップしても解除はしない（解除は L3 / R3 のみ）。
     * hata / hoju に切り替わったときは、対応するレーザーを1回だけ ON にする
     * （ON のまま固定はしないので、そのあと手動で OFF にできる）。
     */
    private fun selectMoveTarget(value: String) {
        // 付近に居ないと押せない移動先は、ここで弾く（画面のタップも SELECT の順送りも通る道）。
        val target = MOVE_TARGETS.firstOrNull { it.id == value }
        if (target != null && !isMoveTargetUnlocked(target, posX, posY, posTheta)) return
        val changed = selectedTarget != value
        selectedTarget = value
        // 選ばれていない方のレーザーは位置決めが解けたとみなして落とす。
        if (value != "hata") hataLaser = false
        if (value != "hoju") hojuLaser = false
        when (value) {
            "hata" -> {
                setTurnTarget(TurnTarget.HATA)
                // 選んだ瞬間に1回だけ点ける。以降は固定しないので手動で消せる。
                if (changed) hataLaser = true
            }
            "hoju" -> {
                setTurnTarget(TurnTarget.HOJU)
                if (changed) hojuLaser = true
            }
            "bs1", "bs2", "bs3" -> setTurnTarget(TurnTarget.BAKETU)
        }
    }

    /** L3 / R3 で MOVE TARGET を解除する。位置決めをやめるのでレーザーも両方消す。 */
    private fun clearMoveTarget() {
        selectedTarget = "none"
        hataLaser = false
        hojuLaser = false
    }

    /** 何も選んでいない状態かどうか。起動直後の "" も含む。 */
    private fun isMoveTargetCleared(): Boolean =
        selectedTarget.isEmpty() || selectedTarget == "none"

    /**
     * 選択中の移動先が押せる範囲から外れていたら none に戻す。
     * 付近でしか押せないボタンなのに、離れたあとも選択が残るのを防ぐ。
     * 自己位置の受信が止まっていても一定間隔で呼ばれる。
     */
    private fun updateMoveTargetLock() {
        if (isMoveTargetCleared()) return
        val target = MOVE_TARGETS.firstOrNull { it.id == selectedTarget } ?: return
        if (isMoveTargetUnlocked(target, posX, posY, posTheta)) return
        clearMoveTarget()
        appendLog("TARGET RELEASED: ${target.label}")
    }

    /**
     * どのボタンも押せない状態のときに、一番近い基準位置との差を出す。
     * * が付いた軸が許容幅を超えている。
     */
    private fun logMoveTargetLockDebug() {
        if (!TARGET_LOCK_DEBUG) return
        if (MOVE_TARGETS.any { isMoveTargetUnlocked(it, posX, posY, posTheta) }) return
        val now = System.currentTimeMillis()
        if (now - lastLockDebugMs < 1000) return
        lastLockDebugMs = now

        val nearest = MOVE_TARGETS
            .filter { it.unlockArea?.enabled == true }
            .minByOrNull { target ->
                val area = target.unlockArea!!
                Math.abs(posX - area.x) + Math.abs(posY - area.y)
            } ?: return
        val area = nearest.unlockArea!!
        val dx = Math.abs(posX - area.x)
        val dy = Math.abs(posY - area.y)
        val dth = Math.abs(Math.IEEEremainder(Math.toDegrees(posTheta) - area.theta, 360.0))
        val mark = { over: Boolean -> if (over) "*" else "" }
        appendLog(
            "LOCKED nearest=${nearest.label}" +
                " dx${"%.0f".format(dx)}${mark(dx > UNLOCK_TOLERANCE_X)}" +
                " dy${"%.0f".format(dy)}${mark(dy > UNLOCK_TOLERANCE_Y)}" +
                " dth${"%.0f".format(dth)}${mark(dth > UNLOCK_TOLERANCE_THETA)}"
        )
    }

    /** turn 調整の対象を直接切り替える。HATA の連続調整 Job の面倒もここで見る。 */
    private fun setTurnTarget(target: TurnTarget) {
        if (selectedTurnTarget == target) return
        selectedTurnTarget = target
        if (target == TurnTarget.HATA) {
            if (left || right || up || down || l2 || r2) {
                startHataTurnRepeat()
            }
        } else {
            stopHataTurnRepeat()
        }
    }

    /**
     * SELECT ボタンで MOVE TARGET を BS3 -> BS2 -> BS1 -> BS3 と順送りする。
     * BS 以外を選んでいるとき（none 含む）は何もしない。
     */
    private fun cycleBsTarget() {
        val index = BS_TARGET_CYCLE.indexOf(selectedTarget)
        if (index < 0) return
        // 押せない BS は飛ばして、次に押せるものまで送る。1周して戻ったら何もしない。
        for (step in 1 until BS_TARGET_CYCLE.size) {
            val next = BS_TARGET_CYCLE[(index + step) % BS_TARGET_CYCLE.size]
            val target = MOVE_TARGETS.firstOrNull { it.id == next } ?: continue
            if (isMoveTargetUnlocked(target, posX, posY, posTheta)) {
                selectMoveTarget(next)
                return
            }
        }
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
        when (selectedTurnTarget) {
            TurnTarget.HATA -> {
                hata_turnx += dx
                hata_turny += dy
                hata_turntheta += dtheta
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
        when (selectedObject) {
            "hata" -> {
                fun clamp(v: Float) = v.coerceIn(HATA_SPEED_MIN, HATA_SPEED_MAX)
                when (column) {
                    1 -> hataSpeed1 = clamp(hataSpeed1 + delta)
                    2 -> hataSpeed2 = clamp(hataSpeed2 + delta)
                    else -> hataSpeed3 = clamp(hataSpeed3 + delta)
                }
            }
            "dekazoukin" -> {
                fun clamp(v: Float) = v.coerceIn(DEKAZOUKIN_SPEED_MIN, DEKAZOUKIN_SPEED_MAX)
                when (column) {
                    1 -> dekazoukinSpeed1 = clamp(dekazoukinSpeed1 + delta)
                    2 -> dekazoukinSpeed2 = clamp(dekazoukinSpeed2 + delta)
                    else -> dekazoukinSpeed3 = clamp(dekazoukinSpeed3 + delta)
                }
            }
            else -> {
                fun clamp(v: Float) = v.coerceIn(BAKETU_SPEED_MIN, BAKETU_SPEED_MAX)
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
        hataTurnX: Float, hataTurnY: Float, hataTurnTheta: Float,
        hojuTurnX: Float, hojuTurnY: Float, hojuTurnTheta: Float,
        baketuTurnX: Float, baketuTurnY: Float, baketuTurnTheta: Float,
        mode: String,
        selectedObject: String, selectedTarget: String,
        hataLaser: Boolean, hojuLaser: Boolean, t0: Boolean,
        left: Boolean, right: Boolean, up: Boolean, down: Boolean,
        circle: Boolean, triangle: Boolean, square: Boolean, cross: Boolean,
        l1: Boolean, l2: Boolean, r1: Boolean, r2: Boolean,
        start: Boolean, select: Boolean, ps: Boolean
    ) {
        try {
            val msg = """{"map_id":"$mapId","vx":$vx,"vy":$vy,"w":$w,"hata_speed1":$hataSpeed1,"baketu_speed1":$baketuSpeed1,"hata_speed2":$hataSpeed2,"baketu_speed2":$baketuSpeed2,"hata_speed3":$hataSpeed3,"baketu_speed3":$baketuSpeed3,"dekazoukin_speed1":$dekazoukinSpeed1,"dekazoukin_speed2":$dekazoukinSpeed2,"dekazoukin_speed3":$dekazoukinSpeed3,"hata_turnx":$hataTurnX,"hata_turny":$hataTurnY,"hata_turntheta":$hataTurnTheta,"hoju_turnx":$hojuTurnX,"hoju_turny":$hojuTurnY,"hoju_turntheta":$hojuTurnTheta,"baketu_turnx":$baketuTurnX,"baketu_turny":$baketuTurnY,"baketu_turntheta":$baketuTurnTheta,"mode":"$mode","object":"$selectedObject","target":"$selectedTarget","hatalaser":$hataLaser,"hojulaser":$hojuLaser,"t0":$t0,"left":$left,"right":$right,"up":$up,"down":$down,"circle":$circle,"triangle":$triangle,"square":$square,"cross":$cross,"l1":$l1,"l2":$l2,"r1":$r1,"r2":$r2,"start":$start,"select":$select,"ps":$ps}"""
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

@Composable
private fun HudPanel(
    modifier: Modifier = Modifier,
    accent: Color = ControllerColors.BorderAccent,
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
            .padding(10.dp),
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
 */
@Composable
private fun SpeedAdjustRow(
    label: String,
    value: Float,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    accent: Color = ControllerColors.Accent
) {
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
        HudButton(
            text = "-",
            onClick = onDecrease,
            modifier = Modifier.width(38.dp),
            height = 44.dp,
            fontSize = 18.sp,
            accent = ControllerColors.Border
        )
        HudButton(
            text = "+",
            onClick = onIncrease,
            modifier = Modifier.width(38.dp),
            height = 44.dp,
            fontSize = 18.sp,
            accent = accent
        )
    }
}

/**
 * ???????????UI??
 */
@Composable
fun TeamSelectionScreen(
    isFlipped: Boolean,
    onToggleFlip: () -> Unit,
    onSelectTeam: (mapStr: String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .rotate(if (isFlipped) 180f else 0f)
            .background(ControllerColors.Background),
        contentAlignment = Alignment.Center
    ) {
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
 * 座標は赤 / 青で共通。画面描画と同じく、ロボットからは自サイド基準の値が来る前提。
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

// true の間、全ボタンがロックされているときに一番近い基準位置との差を SYSTEM LOG に出す。
private const val TARGET_LOCK_DEBUG = true

/**
 * その移動先のボタンをいま押せるか。
 * 範囲を持たない（null）移動先と enabled = false の移動先は、位置に関係なく常に押せる。
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

// 画面での並びをそのまま表す。内側の listOf 1つが 1 行分。
// SELECT ボタンで送る順番。BS3 -> BS2 -> BS1 -> BS3 と一周する。
private val BS_TARGET_CYCLE = listOf("bs3", "bs2", "bs1")

// unlockArea の x / y（mm）・theta（度）がそのボタンを押せる場所。enabled = false でロックなし。
private val MOVE_TARGET_ROWS = listOf(
    listOf(
        MoveTarget("tb1", "TB1", UnlockArea(3300f, 3000f, 0f, enabled = true)),
        MoveTarget("tb2", "TB2", UnlockArea(3300f, 8645f, 0f, enabled = true))
    ),
    listOf(
        MoveTarget("bs1", "BS1", UnlockArea(5000f, 4060f, 0f, enabled = true)),
        MoveTarget("bs2", "BS2", UnlockArea(5000f, 3980f, 0f, enabled = true)),
        MoveTarget("bs3", "BS3", UnlockArea(5000f, 3900f, 0f, enabled = true))
    ),
    listOf(
        MoveTarget("hata", "HATA", UnlockArea(4260f, 6100f, 0f, enabled = true)),
        MoveTarget("hoju", "HOJU", UnlockArea(4080f, 500f, 0f, enabled = true))
    )
)

// ローをターゲット id で引けるようにしたもの。毎回 flatten() しないため。
private val MOVE_TARGETS = MOVE_TARGET_ROWS.flatten()

@Composable
fun ControllerUI(
    isFlipped: Boolean,
    rttList: List<Long>,
    posX: Double,
    posY: Double,
    posTheta: Double,
    mapID: String,
    currentVx: Float,
    currentVy: Float,
    currentW: Float,
    hataTurnX: Float, hataTurnY: Float, hataTurnTheta: Float,
    hojuTurnX: Float, hojuTurnY: Float, hojuTurnTheta: Float,
    baketuTurnX: Float, baketuTurnY: Float, baketuTurnTheta: Float,
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
    t0locked: Boolean
) {
    val imageBitmap = ImageBitmap.imageResource(id = R.drawable.blackarrow)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .rotate(if (isFlipped) 180f else 0f)
            .background(ControllerColors.Background),
    ) {
        // 移動先の一覧。常に1つ点灯して "target" を送り続け、L3 / R3 でのみ "none" に戻る。
        HudPanel(modifier = Modifier.width(340.dp).offset(x=122.dp,).zIndex(1f), accent = ControllerColors.Accent) {
            HudSectionTitle("MOVE TARGET  /  ${if (selectedTarget == "none") "---" else selectedTarget.uppercase()}", ControllerColors.Accent, 12.sp)
            // 赤ゾーンはフィールドが左右反転するので、ボタンの並びも行ごと横方向に反転させる。
            val moveTargetRows = remember(mapID) {
                if (mapID == "redmap") MOVE_TARGET_ROWS.map { it.reversed() } else MOVE_TARGET_ROWS
            }
            Column(
                modifier = Modifier
                    .height(136.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                moveTargetRows.forEach { rowTargets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowTargets.forEach { target ->
                            MoveTargetButton(
                                label = target.label,
                                selected = selectedTarget == target.id,
                                // 付近に居ない間はロックして押せなくする。
                                enabled = isMoveTargetUnlocked(target, posX, posY, posTheta),
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
                    rotate(degrees = -angleDegrees + 180f, pivot = Offset.Zero)
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
                        .height(150.dp)
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

                HudSectionTitle("NETWORK RTT")
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(120.dp)
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
            }
        }

        // 右上：turn調整パネルと SPEED ADJUST。
        // 下端の LOW GAIN / レーザー列（約106dp）にかからないよう下に余白を取り、
        // 残りの高さを SPEED ADJUST に weight(1f) で全部使わせる。
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(1.dp)
                .width(205.dp)
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
            HudPanel(modifier = Modifier.fillMaxWidth().weight(1f), accent = speedAccent) {
                HudSectionTitle("SPEED ADJUST  /  ${objectDisplayName(selectedObject)}", speedAccent, 11.sp)
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    SpeedAdjustRow(
                        label = "C1",
                        value = speed1,
                        onIncrease = { onSpeedIncrease(1) },
                        onDecrease = { onSpeedDecrease(1) },
                        accent = speedAccent
                    )
                    SpeedAdjustRow(
                        label = "C2",
                        value = speed2,
                        onIncrease = { onSpeedIncrease(2) },
                        onDecrease = { onSpeedDecrease(2) },
                        accent = speedAccent
                    )
                    SpeedAdjustRow(
                        label = "C3",
                        value = speed3,
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
                .width(150.dp)
                .offset(463.dp, 0.dp),
            accent = ControllerColors.Accent
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
                .offset(498.dp, 182.dp),
            accent = ControllerColors.Warning
        ) {
            HudSectionTitle("TURN ADJUST  /  ${selectedTurnTarget.displayName()}", ControllerColors.Warning, 10.sp)
            Text("BAKETU X ${"%.2f".format(baketuTurnX)} Y ${"%.2f".format(baketuTurnY)} T ${"%.2f".format(baketuTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
            Text("HATA   X ${"%.2f".format(hataTurnX)} Y ${"%.2f".format(hataTurnY)} T ${"%.2f".format(hataTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
            Text("HOJU   X ${"%.2f".format(hojuTurnX)} Y ${"%.2f".format(hojuTurnY)} T ${"%.2f".format(hojuTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
        }

        // ロボットから受け取った hojustate 表示（旧 t0 ボタンの位置）
        HudPanel(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(100.dp)
                .width(130.dp).height(70.dp)
                .offset(390.dp, 70.dp),
            accent = ControllerColors.Success
        ) {
            Text(
                text = if (hojuState.isBlank()) "---" else hojuState,
                color = ControllerColors.TextPrimary,
                fontSize = 40.sp,
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
    TurnTarget.HATA -> "HATA"
    TurnTarget.HOJU -> "HOJU"
    TurnTarget.BAKETU -> "BAKETU"
}

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
        modifier = Modifier.width(128.dp),
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
    HudButton(
        text = label,
        onClick = onClick,
        modifier = Modifier.width(100.dp),
        enabled = enabled,
        containerColor = if (selected) Color(0xFF124D37) else ControllerColors.Surface2,
        contentColor = if (selected) ControllerColors.Success else ControllerColors.TextSecondary,
        height = 42.dp,
        fontSize = 12.sp,
        // ロック中は枠も落として、押せないことが一目で分かるようにする。
        accent = when {
            !enabled -> ControllerColors.Neutral
            selected -> ControllerColors.Success
            else -> ControllerColors.Border
        }
    )
}
