package com.example.controller

import android.R.attr.translateX
import android.R.attr.translateY
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Xml
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
import kotlin.math.sqrt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.zIndex

// ??????????Enum
enum class ScreenState {
    TEAM_SELECTION,
    CONTROLLER,
    RECOVERY
}

data class TargetThreshold(
    val x: Double,
    val y: Double,
    val theta: Double,
    val distErr: Double = 1000.0,
    val angleErr: Double = 40.0
)

enum class TurnTarget {
    HATA,
    HOJU
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

    private var posX by mutableStateOf(3900.0)
    private var posY by mutableStateOf(0.0)
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

    // t0
    private var t0 by mutableStateOf(false)

    // ???????????
    private var column1 by mutableStateOf("baketu")
    private var column2 by mutableStateOf("hata")
    private var column3 by mutableStateOf("hata")

    // ?????????
    private var execute by mutableStateOf(false)
    private var refill by mutableStateOf(false)
    private var reload1 by mutableStateOf(false)
    private var reload2 by mutableStateOf(false)
    private var reload3 by mutableStateOf(false)
    private var release by mutableStateOf(false)

    private var firehata by mutableStateOf(false)
    private var firebaketu by mutableStateOf(false)
    private var hataLaser by mutableStateOf(false)
    private var hojuLaser by mutableStateOf(false)
    private var interrupt by mutableStateOf(false)

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

    private var isHojuPositioningEnabled by mutableStateOf(false)
    // ?????1????????
    private val speedStep = 0.025f

    // ---- ?????????????? ?????????????? ----
    // ?????????????????????????????????
    // ???????????????????????????
    // ??????????? cooldownSeconds ?????????????????
    private enum class RecoveryAction(val cooldownSeconds: Float) {
        REFILL(2.0f),       // ??
        FIRE_HATA(1.5f),    // ???
        FIRE_BAKETU(1.5f),  // ?????
        RELOAD1(1.0f),      // RL1
        RELOAD2(1.0f),      // RL2
        RELOAD3(1.0f),      // RL3
        RELEASE(1.0f)       // RELEASE
    }
    private val t0CooldownSeconds = 2.0f   // ?????(?)???????????????
    private var t0Locked by mutableStateOf(false)  // true ??????????
    private var t0LockJob: Job? = null     // ????????????????
    // true ??????????????????????????
    private var recoveryActionsLocked by mutableStateOf(false)
    private var recoveryLockJob: Job? = null
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
    /**
     * ???????????????????????????
     * ?????????????????????????????????
     * ????????????????????????
     * ?????????????????????????
     */
    private fun triggerRecoveryAction(action: RecoveryAction) {
        if (recoveryActionsLocked) return
        when (action) {
            RecoveryAction.REFILL -> pulseRefill()
            RecoveryAction.FIRE_HATA -> pulsefirehata()
            RecoveryAction.FIRE_BAKETU -> pulsefirebaketu()
            RecoveryAction.RELOAD1 -> pulseReload1()
            RecoveryAction.RELOAD2 -> pulseReload2()
            RecoveryAction.RELOAD3 -> pulseReload3()
            RecoveryAction.RELEASE -> pulseRelease()
        }
        recoveryActionsLocked = true
        recoveryLockJob?.cancel()
        recoveryLockJob = lifecycleScope.launch {
            delay((action.cooldownSeconds * 1000).toLong())
            recoveryActionsLocked = false
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
        if (currentScreen != ScreenState.CONTROLLER && currentScreen != ScreenState.RECOVERY) {
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
        if ((currentScreen == ScreenState.CONTROLLER || currentScreen == ScreenState.RECOVERY) &&
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        ) {
            when (keyCode) {
                // PS????Turn????? HATA -> HOJU -> HATA ????????
                KeyEvent.KEYCODE_BUTTON_MODE -> {
                    if (event.repeatCount == 0) {
                        cycleTurnTarget()
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
        if ((currentScreen == ScreenState.CONTROLLER || currentScreen == ScreenState.RECOVERY) &&
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        ) {
            when (keyCode) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hata_turnx = prefs.getFloat("hata_turnx", 0.0f)
        hata_turny = prefs.getFloat("hata_turny", 0.0f)
        hata_turntheta = prefs.getFloat("hata_turntheta", 0.0f)
        hoju_turnx = prefs.getFloat("hoju_turnx", 0.0f)
        hoju_turny = prefs.getFloat("hoju_turny", 0.0f)
        hoju_turntheta = prefs.getFloat("hoju_turntheta", 0.0f)
        hataSpeed1 = prefs.getFloat("hata_speed1", 10.2f)
        baketuSpeed1 = prefs.getFloat("baketu_speed1", 4.5f)
        hataSpeed2 = prefs.getFloat("hata_speed2", 10.2f)
        baketuSpeed2 = prefs.getFloat("baketu_speed2", 4.5f)
        hataSpeed3 = prefs.getFloat("hata_speed3", 10.2f)
        baketuSpeed3 = prefs.getFloat("baketu_speed3", 4.5f)
        hideSystemUI()
        startLockTaskMode()

        // --- ???? ---
        // ???????????????????????????????????
        // ????????????????????
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
                        selectedTurnTarget = selectedTurnTarget,
                        t0 = t0,
                        onToggleT0 = { toggleT0() },
                        interrupt = interrupt,
                        onInterrupt = { pulseInterrupt() },
                        left = left, right = right, up = up, down = down,
                        circle = circle, square = square, cross = cross, triangle = triangle,
                        l1 = l1, l2 = l2, r1 = r1, r2 = r2,
                        column1 = column1,
                        column2 = column2,
                        column3 = column3,
                        onColumn1Change = {
                            column1 = nextColumnfor1(column1)
                            if (column1 == "hata") {
                                if (column2 == "baketu") column2 = "hata"
                                if (column3 == "baketu") column3 = "hata"
                            }
                        },
                        onColumn2Change = {
                            column2 = if (column1 == "hata" && column2 != "nothing") {
                                nextColumnforhata(column2)
                            } else {
                                nextColumn(column2)
                            }
                            if (column2 == "nothing") column3 = "nothing"
                        },
                        onColumn3Change = {
                            column3 = if ((column1 == "hata" || column2 == "hata") && column2 != "nothing") {
                                nextColumnforhata(column3)
                            } else if (column2 == "nothing") {
                                "nothing"
                            } else {
                                nextColumn(column3)
                            }
                        },
                        onExecute = { pulseExecute() },
                        onEnterRecovery = { currentScreen = ScreenState.RECOVERY },
                        execute = execute,
                        pulexecute = { pulseExecute() },
                        hataSpeed1 = hataSpeed1,
                        baketuSpeed1 = baketuSpeed1,
                        hataSpeed2 = hataSpeed2,
                        baketuSpeed2 = baketuSpeed2,
                        hataSpeed3 = hataSpeed3,
                        baketuSpeed3 = baketuSpeed3,
                        onHataSpeed1Increase = { hataSpeed1 = (hataSpeed1 + speedStep).coerceAtMost(11.2f) },
                        onHataSpeed1Decrease = { hataSpeed1 = (hataSpeed1 - speedStep).coerceAtLeast(9.0f) },
                        onBaketuSpeed1Increase = { baketuSpeed1 = (baketuSpeed1 + speedStep).coerceAtMost(5.0f) },
                        onBaketuSpeed1Decrease = { baketuSpeed1 = (baketuSpeed1 - speedStep).coerceAtLeast(4.0f) },
                        onHataSpeed2Increase = { hataSpeed2 = (hataSpeed2 + speedStep).coerceAtMost(11.2f) },
                        onHataSpeed2Decrease = { hataSpeed2 = (hataSpeed2 - speedStep).coerceAtLeast(9.0f) },
                        onBaketuSpeed2Increase = { baketuSpeed2 = (baketuSpeed2 + speedStep).coerceAtMost(5.0f) },
                        onBaketuSpeed2Decrease = { baketuSpeed2 = (baketuSpeed2 - speedStep).coerceAtLeast(4.0f) },
                        onHataSpeed3Increase = { hataSpeed3 = (hataSpeed3 + speedStep).coerceAtMost(11.2f) },
                        onHataSpeed3Decrease = { hataSpeed3 = (hataSpeed3 - speedStep).coerceAtLeast(9.0f) },
                        onBaketuSpeed3Increase = { baketuSpeed3 = (baketuSpeed3 + speedStep).coerceAtMost(5.0f) },
                        onBaketuSpeed3Decrease = { baketuSpeed3 = (baketuSpeed3 - speedStep).coerceAtLeast(4.0f) },
                        onNavigateTo = { targetScreen -> currentScreen = targetScreen },
                        isHojuPositioningEnabled = isHojuPositioningEnabled,
                        onToggleHojuPositioning = {
                            updateHojuPositioningEnabled(!isHojuPositioningEnabled)
                        },
                        logList = logList,
                        hojuState = hojuState,
                        t0locked = t0Locked
                    )
                }

                ScreenState.RECOVERY -> {
                    RecoveryUI(
                        isFlipped = isFlipped,
                        mapID = selectedMapID,
                        rttList = rttList,
                        posX = posX,
                        posY = posY,
                        posTheta = posTheta,
                        currentVx = if (lowGain) vy / 2f else vy,
                        currentVy = if (lowGain) vx / 2f else vx,
                        currentW = if (lowGain) w / 2f else w,
                        left = left, right = right, up = up, down = down,
                        circle = circle, square = square, cross = cross, triangle = triangle,
                        l1 = l1, l2 = l2, r1 = r1, r2 = r2,
                        onRefill = { triggerRecoveryAction(RecoveryAction.REFILL) },
                        onfirehata = { triggerRecoveryAction(RecoveryAction.FIRE_HATA) },
                        refill = refill,
                        reload1 = reload1,
                        reload2 = reload2,
                        reload3 = reload3,
                        onReload1 = { triggerRecoveryAction(RecoveryAction.RELOAD1) },
                        onReload2 = { triggerRecoveryAction(RecoveryAction.RELOAD2) },
                        onReload3 = { triggerRecoveryAction(RecoveryAction.RELOAD3) },
                        release = release,
                        onRelease = { triggerRecoveryAction(RecoveryAction.RELEASE) },
                        firehata = firehata,onfirebaketu = { triggerRecoveryAction(RecoveryAction.FIRE_BAKETU) }, // ??
                        firebaketu = firebaketu,             // ??
                        pulfirebaketu = { triggerRecoveryAction(RecoveryAction.FIRE_BAKETU) },
                        pulrefill = { triggerRecoveryAction(RecoveryAction.REFILL) },
                        pulfirehata = { triggerRecoveryAction(RecoveryAction.FIRE_HATA) },
                        hataLaser = hataLaser,
                        onHataLaser = { hataLaser = !hataLaser },
                        hojuLaser = hojuLaser,
                        onHojuLaser = { hojuLaser = !hojuLaser },
                        lowGain = lowGain,
                        onToggleLowGain = { lowGain = !lowGain },onNavigateTo = { targetScreen -> currentScreen = targetScreen },
                        actionsLocked = recoveryActionsLocked,
                        logList = logList,
                        baketuSpeed1 = baketuSpeed1,
                        baketuSpeed2 = baketuSpeed2,
                        baketuSpeed3 = baketuSpeed3,
                        hataSpeed1 = hataSpeed1,
                        hataSpeed2 = hataSpeed2,
                        hataSpeed3 = hataSpeed3,
                        onHataSpeed1Increase = { hataSpeed1 = (hataSpeed1 + speedStep).coerceAtMost(11.2f) },
                        onHataSpeed1Decrease = { hataSpeed1 = (hataSpeed1 - speedStep).coerceAtLeast(9.0f) },
                        onBaketuSpeed1Increase = { baketuSpeed1 = (baketuSpeed1 + speedStep).coerceAtMost(5.0f) },
                        onBaketuSpeed1Decrease = { baketuSpeed1 = (baketuSpeed1 - speedStep).coerceAtLeast(4.0f) },
                        onHataSpeed2Increase = { hataSpeed2 = (hataSpeed2 + speedStep).coerceAtMost(11.2f) },
                        onHataSpeed2Decrease = { hataSpeed2 = (hataSpeed2 - speedStep).coerceAtLeast(9.0f) },
                        onBaketuSpeed2Increase = { baketuSpeed2 = (baketuSpeed2 + speedStep).coerceAtMost(5.0f) },
                        onBaketuSpeed2Decrease = { baketuSpeed2 = (baketuSpeed2 - speedStep).coerceAtLeast(4.0f) },
                        onHataSpeed3Increase = { hataSpeed3 = (hataSpeed3 + speedStep).coerceAtMost(11.2f) },
                        onHataSpeed3Decrease = { hataSpeed3 = (hataSpeed3 - speedStep).coerceAtLeast(9.0f) },
                        onBaketuSpeed3Increase = { baketuSpeed3 = (baketuSpeed3 + speedStep).coerceAtMost(5.0f) },
                        onBaketuSpeed3Decrease = { baketuSpeed3 = (baketuSpeed3 - speedStep).coerceAtLeast(4.0f) },
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
            if (currentScreen == ScreenState.CONTROLLER || currentScreen == ScreenState.RECOVERY) {
                send(
                    selectedMapID,
                    if (lowGain) vy / 2f else vy,
                    if (lowGain) vx / 2f else vx,
                    if (lowGain) w / 2f else w,
                    hataSpeed1, baketuSpeed1,
                    hataSpeed2, baketuSpeed2,
                    hataSpeed3, baketuSpeed3,
                    hata_turnx, hata_turny, hata_turntheta,
                    hoju_turnx, hoju_turny, hoju_turntheta,
                    mode = if (currentScreen == ScreenState.RECOVERY) "recovery" else "normal",
                    column1, column2, column3,
                    execute, refill, reload1, reload2, reload3, release, firehata, firebaketu, hataLaser, hojuLaser, t0, interrupt, isHojuPositioningEnabled,
                    left, right, up, down,
                    circle, triangle, square, cross,
                    l1, l2, r1, r2
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

    private fun updateHojuPositioningEnabled(enabled: Boolean) {
        isHojuPositioningEnabled = enabled
        if (enabled) {
            selectedTurnTarget = TurnTarget.HOJU
        }
    }

    private fun cycleTurnTarget() {
        if (isHojuPositioningEnabled) {
            selectedTurnTarget = TurnTarget.HOJU
            stopHataTurnRepeat()
            return
        }

        selectedTurnTarget = when (selectedTurnTarget) {
            TurnTarget.HATA -> TurnTarget.HOJU
            TurnTarget.HOJU -> TurnTarget.HATA
        }

        if (selectedTurnTarget == TurnTarget.HATA &&
            (left || right || up || down || l2 || r2)
        ) {
            startHataTurnRepeat()
        } else if (selectedTurnTarget != TurnTarget.HATA) {
            stopHataTurnRepeat()
        }

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
        }
    }

    private fun nextColumn(current: String): String {
        return when (current) {
            "hata" -> "baketu"
            "baketu" -> "nothing"
            else -> "hata"
        }
    }
    private fun nextColumnfor1(current: String): String {
        return when (current) {
            "hata" -> "baketu"
            else -> "hata"
        }
    }
    private fun nextColumnforhata(current: String): String {
        return when (current) {
            "hata" -> "nothing"
            else -> "hata"
        }
    }

    private fun pulseExecute() {
        if (execute) return
        selectedTurnTarget = TurnTarget.HATA
        if (left || right || up || down || l2 || r2) {
            startHataTurnRepeat()
        }
        execute = true
        lifecycleScope.launch {
            delay(150)
            execute = false
        }
    }

    private fun pulseRefill() {
        if (refill) return
        refill = true
        lifecycleScope.launch {
            delay(150)
            refill = false
        }
    }

    private fun pulsefirehata() {
        if (firehata) return
        firehata = true
        lifecycleScope.launch {
            delay(150)
            firehata = false
        }
    }
    private fun pulsefirebaketu() {
        if (firebaketu) return
        firebaketu = true
        lifecycleScope.launch {
            delay(150)
            firebaketu = false
        }
    }

    private fun pulseInterrupt() {
        if (interrupt) return
        interrupt = true
        lifecycleScope.launch {
            delay(150)
            interrupt = false
        }
    }
    private fun pulseReload1() {
        if (reload1) return
        reload1 = true
        lifecycleScope.launch {
            delay(150)
            reload1 = false
        }
    }

    private fun pulseReload2() {
        if (reload2) return
        reload2 = true
        lifecycleScope.launch {
            delay(150)
            reload2 = false
        }
    }

    private fun pulseReload3() {
        if (reload3) return
        reload3 = true
        lifecycleScope.launch {
            delay(150)
            reload3 = false
        }
    }

    private fun pulseRelease() {
        if (release) return
        release = true
        lifecycleScope.launch {
            delay(150)
            release = false
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
        hataTurnX: Float, hataTurnY: Float, hataTurnTheta: Float,
        hojuTurnX: Float, hojuTurnY: Float, hojuTurnTheta: Float,
        mode: String,
        column1: String, column2: String, column3: String,
        execute: Boolean, refill: Boolean, reload1: Boolean, reload2: Boolean, reload3: Boolean, release: Boolean, firehata: Boolean, firebaketu: Boolean, hataLaser: Boolean, hojuLaser: Boolean, t0: Boolean, interrupt: Boolean,
        isHojuPositioningEnabled: Boolean,
        left: Boolean, right: Boolean, up: Boolean, down: Boolean,
        circle: Boolean, triangle: Boolean, square: Boolean, cross: Boolean,
        l1: Boolean, l2: Boolean, r1: Boolean, r2: Boolean
    ) {
        try {
            val msg = """{"map_id":"$mapId","vx":$vx,"vy":$vy,"w":$w,"hata_speed1":$hataSpeed1,"baketu_speed1":$baketuSpeed1,"hata_speed2":$hataSpeed2,"baketu_speed2":$baketuSpeed2,"hata_speed3":$hataSpeed3,"baketu_speed3":$baketuSpeed3,"hata_turnx":$hataTurnX,"hata_turny":$hataTurnY,"hata_turntheta":$hataTurnTheta,"hoju_turnx":$hojuTurnX,"hoju_turny":$hojuTurnY,"hoju_turntheta":$hojuTurnTheta,"mode":"$mode","column1":"$column1","column2":"$column2","column3":"$column3","execute":$execute,"refill":$refill,"reload1":$reload1,"reload2":$reload2,"reload3":$reload3,"release":$release,"firehata":$firehata,"firebaketu":$firebaketu,"hatalaser":$hataLaser,"hojulaser":$hojuLaser,"t0":$t0,"interrupt":$interrupt,"HojuPosition":$isHojuPositioningEnabled,"left":$left,"right":$right,"up":$up,"down":$down,"circle":$circle,"triangle":$triangle,"square":$square,"cross":$cross,"l1":$l1,"l2":$l2,"r1":$r1,"r2":$r2}"""
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
        prefs.edit()
            .putFloat("hata_turnx", hata_turnx)
            .putFloat("hata_turny", hata_turny)
            .putFloat("hata_turntheta", hata_turntheta)
            .putFloat("hoju_turnx", hoju_turnx)
            .putFloat("hoju_turny", hoju_turny)
            .putFloat("hoju_turntheta", hoju_turntheta)
            .putFloat("hata_speed1", hataSpeed1)
            .putFloat("baketu_speed1", baketuSpeed1)
            .putFloat("hata_speed2", hataSpeed2)
            .putFloat("baketu_speed2", baketuSpeed2)
            .putFloat("hata_speed3", hataSpeed3)
            .putFloat("baketu_speed3", baketuSpeed3)
            .commit()
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
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        HudButton(
            text = "?",
            onClick = onDecrease,
            modifier = Modifier.width(40.dp),
            height = 36.dp,
            fontSize = 16.sp,
            accent = ControllerColors.Border
        )
        HudButton(
            text = "?",
            onClick = onIncrease,
            modifier = Modifier.width(40.dp),
            height = 36.dp,
            fontSize = 16.sp,
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
    selectedTurnTarget: TurnTarget,
    t0: Boolean,
    onToggleT0: () -> Unit,
    interrupt: Boolean,
    onInterrupt: () -> Unit,
    left: Boolean, right: Boolean, up: Boolean, down: Boolean,
    circle: Boolean, square: Boolean, cross: Boolean, triangle: Boolean,
    l1: Boolean, l2: Boolean, r1: Boolean, r2: Boolean,
    column1: String,
    column2: String,
    column3: String,
    onColumn1Change: () -> Unit,
    onColumn2Change: () -> Unit,
    onColumn3Change: () -> Unit,
    onExecute: () -> Unit,
    onNavigateTo: (ScreenState) -> Unit,
    onEnterRecovery: () -> Unit,
    execute: Boolean,
    pulexecute: () -> Unit,
    hataSpeed1: Float,
    baketuSpeed1: Float,
    hataSpeed2: Float,
    baketuSpeed2: Float,
    hataSpeed3: Float,
    baketuSpeed3: Float,
    onHataSpeed1Increase: () -> Unit,
    onHataSpeed1Decrease: () -> Unit,
    onBaketuSpeed1Increase: () -> Unit,
    onBaketuSpeed1Decrease: () -> Unit,
    onHataSpeed2Increase: () -> Unit,
    onHataSpeed2Decrease: () -> Unit,
    onBaketuSpeed2Increase: () -> Unit,
    onBaketuSpeed2Decrease: () -> Unit,
    onHataSpeed3Increase: () -> Unit,
    onHataSpeed3Decrease: () -> Unit,
    onBaketuSpeed3Increase: () -> Unit,
    onBaketuSpeed3Decrease: () -> Unit,
    isHojuPositioningEnabled: Boolean,
    onToggleHojuPositioning: () -> Unit,
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
        HudPanel(modifier = Modifier.width(368.dp).height(174.dp).offset(x=122.dp,).zIndex(1f), accent = ControllerColors.Success) {
            HudSectionTitle("SPEED ADJUST", ControllerColors.Success)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SpeedAdjustRow(
                        label = "C1 BAKETU",
                        value = baketuSpeed1,
                        onIncrease = onBaketuSpeed1Increase,
                        onDecrease = onBaketuSpeed1Decrease,
                        accent = ControllerColors.Warning
                    )

                    SpeedAdjustRow(
                        label = "C2 BAKETU",
                        value = baketuSpeed2,
                        onIncrease = onBaketuSpeed2Increase,
                        onDecrease = onBaketuSpeed2Decrease,
                        accent = ControllerColors.Warning
                    )

                    SpeedAdjustRow(
                        label = "C3 BAKETU",
                        value = baketuSpeed3,
                        onIncrease = onBaketuSpeed3Increase,
                        onDecrease = onBaketuSpeed3Decrease,
                        accent = ControllerColors.Warning
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SpeedAdjustRow(
                        label = "C1 HATA",
                        value = hataSpeed1,
                        onIncrease = onHataSpeed1Increase,
                        onDecrease = onHataSpeed1Decrease,
                        accent = ControllerColors.Accent
                    )

                    SpeedAdjustRow(
                        label = "C2 HATA",
                        value = hataSpeed2,
                        onIncrease = onHataSpeed2Increase,
                        onDecrease = onHataSpeed2Decrease,
                        accent = ControllerColors.Accent
                    )

                    SpeedAdjustRow(
                        label = "C3 HATA",
                        value = hataSpeed3,
                        onIncrease = onHataSpeed3Increase,
                        onDecrease = onHataSpeed3Decrease,
                        accent = ControllerColors.Accent
                    )
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

        val targetHoju = TargetThreshold(x = 4000.0, y = 441.0, theta = 0.0)
        fun isInRange(target: TargetThreshold, curX: Double, curY: Double, curTheta: Double): Boolean {
            val dx = target.x - curX
            val dy = if (mapID == "redmap") -target.y - curY else target.y - curY
            val dist = sqrt(dx * dx + dy * dy)
            val targetNorm = (target.theta % 360.0 + 360.0) % 360.0
            val currentDeg = Math.toDegrees(curTheta)
            val currentNorm = (currentDeg % 360.0 + 360.0) % 360.0
            val diffTheta = Math.abs(targetNorm - currentNorm)
            val angleDist = if (diffTheta > 180.0) 360.0 - diffTheta else diffTheta
            return dist < target.distErr && angleDist < target.angleErr
        }
        val hojuInRange = isInRange(targetHoju, posX, posY, posTheta)
        val canExecute = isHojuPositioningEnabled && hojuInRange

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
                Text("?   ${"%.2f".format(posTheta)}�", color = ControllerColors.TextPrimary, fontSize = 13.sp)

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

        // ???turn?????????????????????
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(1.dp)
                .width(195.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End
        ) {
            HudPanel(modifier = Modifier.fillMaxWidth(), accent = ControllerColors.Warning) {
                HudSectionTitle("TURN ADJUST  /  ${selectedTurnTarget.displayName()}", ControllerColors.Warning)
                Text("HATA   X ${"%.2f".format(hataTurnX)}  Y ${"%.2f".format(hataTurnY)}  ? ${"%.2f".format(hataTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 10.sp)
                Text("HOJU   X ${"%.2f".format(hojuTurnX)}  Y ${"%.2f".format(hojuTurnY)}  ? ${"%.2f".format(hojuTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 10.sp)
            }



            HudPanel(modifier = Modifier.fillMaxWidth(), accent = ControllerColors.Accent) {
                HudSectionTitle("NORMAL MODE", ControllerColors.Accent, 16.sp)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ColumnButton(label = "COLUMN 1  /  $column1", onClick = onColumn1Change)
                    ColumnButton(label = "COLUMN 2  /  $column2", onClick = onColumn2Change)
                    ColumnButton(label = "COLUMN 3  /  $column3", onClick = onColumn3Change)
                }

                LaunchedEffect(square, canExecute) {
                    if (square && canExecute) {
                        pulexecute()
                        if (isHojuPositioningEnabled) onToggleHojuPositioning()
                    }
                }

                HudButton(
                    text = when {
                        execute -> "EXECUTING"
                        !isHojuPositioningEnabled -> "EXECUTE  /  LOCKED"
                        else -> "EXECUTE"
                    },
                    onClick = {
                        if (canExecute) {
                            onExecute()
                            if (isHojuPositioningEnabled) onToggleHojuPositioning()
                        }
                    },
                    modifier = Modifier.width(180.dp),
                    containerColor = when {
                        execute -> Color(0xFF124D37)
                        canExecute -> ControllerColors.Surface2
                        else -> ControllerColors.Neutral
                    },
                    contentColor = if (canExecute || execute) ControllerColors.TextPrimary else ControllerColors.TextMuted,
                    enabled = canExecute,
                    height = 60.dp,
                    fontSize = 16.sp,
                    accent = when {
                        execute -> ControllerColors.Success
                        canExecute -> ControllerColors.Accent
                        else -> ControllerColors.Border
                    }
                )
            }
        }

        // ????????????????
        HudButton(
            text = when {
                !hojuInRange -> "??????  /  OUT"
                isHojuPositioningEnabled -> "??????  /  ON"
                else -> "??????  /  OFF"
            },
            onClick = { if (hojuInRange) onToggleHojuPositioning() },
            modifier = Modifier
                .width(130.dp)
                .offset(505.dp, 120.dp),
            containerColor = if (isHojuPositioningEnabled) Color(0xFF124D37) else ControllerColors.Surface2,
            contentColor = if (!hojuInRange) ControllerColors.TextMuted else if (isHojuPositioningEnabled) ControllerColors.Success else ControllerColors.TextPrimary,
            enabled = hojuInRange,
            height = 60.dp,
            fontSize = 11.sp,
            accent = if (isHojuPositioningEnabled) ControllerColors.Success else ControllerColors.Border
        )

        // ????????????hojustate????
        HudPanel(
            modifier = Modifier
                .width(130.dp).height(70.dp)
                .offset(505.dp, 182.dp),
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

        // t0
        Button(
            onClick = onToggleT0,
            enabled = !t0locked,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(100.dp)
                .size(80.dp)
                .offset(390.dp, 70.dp)
                .border(1.dp, if (t0) ControllerColors.Success else ControllerColors.Danger, androidx.compose.foundation.shape.CircleShape),
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (t0) Color(0xFF124D37) else Color(0xFF521A22)
            ),
            elevation = ButtonDefaults.buttonElevation(0.dp)
        ) {
            Text(if (t0) "ON" else "OFF", fontWeight = FontWeight.Bold, color = ControllerColors.TextPrimary)
        }

        // ????????????
        Button(
            onClick = onInterrupt,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(100.dp)
                .size(80.dp)
                .offset(470.dp, 70.dp)
                .border(1.dp, ControllerColors.Danger, androidx.compose.foundation.shape.CircleShape),
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (interrupt) Color(0xFF7A1F2A) else Color(0xFF521A22)
            ),
            elevation = ButtonDefaults.buttonElevation(0.dp)
        ) {
            Text("??", fontWeight = FontWeight.Bold, color = ControllerColors.TextPrimary)
        }

        ModeSwitchButtons(
            currentScreen = ScreenState.CONTROLLER,
            onNavigate = onNavigateTo,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 200.dp)
        )
    }
}

fun TurnTarget.displayName(): String = when (this) {
    TurnTarget.HATA -> "HATA"
    TurnTarget.HOJU -> "HOJU"
}

@Composable
fun ColumnButton(
    label: String,
    onClick: () -> Unit
) {
    val isHata = label.contains("hata", ignoreCase = true)
    val isBaketu = label.contains("baketu", ignoreCase = true)
    val accent = when {
        isHata -> ControllerColors.Accent
        isBaketu -> ControllerColors.Warning
        else -> ControllerColors.Border
    }
    val fill = when {
        isHata -> Color(0xFF123B4A)
        isBaketu -> Color(0xFF4A351A)
        else -> ControllerColors.Surface2
    }

    HudButton(
        text = label,
        onClick = onClick,
        modifier = Modifier.width(180.dp),
        containerColor = fill,
        contentColor = ControllerColors.TextPrimary,
        height = 40.dp,
        fontSize = 12.sp,
        accent = accent
    )
}

@Composable
fun RecoveryUI(
    isFlipped: Boolean,
    lowGain: Boolean,
    onToggleLowGain: () -> Unit,
    rttList: List<Long>,
    posX: Double,
    posY: Double,
    posTheta: Double,
    mapID: String,
    currentVx: Float,
    currentVy: Float,
    currentW: Float,
    left: Boolean, right: Boolean, up: Boolean, down: Boolean,
    circle: Boolean, square: Boolean, cross: Boolean, triangle: Boolean,
    l1: Boolean, l2: Boolean, r1: Boolean, r2: Boolean,
    onRefill: () -> Unit,
    onfirehata: () -> Unit,
    onNavigateTo: (ScreenState) -> Unit,
    refill: Boolean,
    reload1: Boolean,
    reload2: Boolean,
    reload3: Boolean,
    onReload1: () -> Unit,
    onReload2: () -> Unit,
    onReload3: () -> Unit,
    release: Boolean,
    onRelease: () -> Unit,
    firehata: Boolean,
    onfirebaketu: () -> Unit,
    firebaketu: Boolean,
    pulfirebaketu: () -> Unit,
    pulrefill: () -> Unit,
    pulfirehata: () -> Unit,
    hataLaser: Boolean,
    onHataLaser: () -> Unit,
    hojuLaser: Boolean,
    onHojuLaser: () -> Unit,
    actionsLocked: Boolean,
    logList: List<String>,
    hataSpeed1: Float,
    baketuSpeed1: Float,
    hataSpeed2: Float,
    baketuSpeed2: Float,
    hataSpeed3: Float,
    baketuSpeed3: Float,
    onHataSpeed1Increase: () -> Unit,
    onHataSpeed1Decrease: () -> Unit,
    onBaketuSpeed1Increase: () -> Unit,
    onBaketuSpeed1Decrease: () -> Unit,
    onHataSpeed2Increase: () -> Unit,
    onHataSpeed2Decrease: () -> Unit,
    onBaketuSpeed2Increase: () -> Unit,
    onBaketuSpeed2Decrease: () -> Unit,
    onHataSpeed3Increase: () -> Unit,
    onHataSpeed3Decrease: () -> Unit,
    onBaketuSpeed3Increase: () -> Unit,
    onBaketuSpeed3Decrease: () -> Unit,
) {
    val imageBitmap = ImageBitmap.imageResource(id = R.drawable.blackarrow)
    LaunchedEffect(circle, cross) {
        if (circle) pulfirehata() else if (cross) pulrefill()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .rotate(if (isFlipped) 180f else 0f)
            .background(ControllerColors.Background),
    ) {
        HudPanel(modifier = Modifier.width(368.dp).height(174.dp).offset(x=122.dp,).zIndex(1f), accent = ControllerColors.Success) {
            HudSectionTitle("SPEED ADJUST", ControllerColors.Success)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SpeedAdjustRow(
                        label = "C1 BAKETU",
                        value = baketuSpeed1,
                        onIncrease = onBaketuSpeed1Increase,
                        onDecrease = onBaketuSpeed1Decrease,
                        accent = ControllerColors.Warning
                    )

                    SpeedAdjustRow(
                        label = "C2 BAKETU",
                        value = baketuSpeed2,
                        onIncrease = onBaketuSpeed2Increase,
                        onDecrease = onBaketuSpeed2Decrease,
                        accent = ControllerColors.Warning
                    )

                    SpeedAdjustRow(
                        label = "C3 BAKETU",
                        value = baketuSpeed3,
                        onIncrease = onBaketuSpeed3Increase,
                        onDecrease = onBaketuSpeed3Decrease,
                        accent = ControllerColors.Warning
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SpeedAdjustRow(
                        label = "C1 HATA",
                        value = hataSpeed1,
                        onIncrease = onHataSpeed1Increase,
                        onDecrease = onHataSpeed1Decrease,
                        accent = ControllerColors.Accent
                    )

                    SpeedAdjustRow(
                        label = "C2 HATA",
                        value = hataSpeed2,
                        onIncrease = onHataSpeed2Increase,
                        onDecrease = onHataSpeed2Decrease,
                        accent = ControllerColors.Accent
                    )

                    SpeedAdjustRow(
                        label = "C3 HATA",
                        value = hataSpeed3,
                        onIncrease = onHataSpeed3Increase,
                        onDecrease = onHataSpeed3Decrease,
                        accent = ControllerColors.Accent
                    )
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
                Text("?   ${"%.2f".format(posTheta)}�", color = ControllerColors.TextPrimary, fontSize = 13.sp)
                HudSectionTitle("SYSTEM LOG", ControllerColors.TextSecondary, 10.sp)

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
                            Text(logLine, color = ControllerColors.TextSecondary, fontSize = 10.sp, maxLines = 1)
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

        HudPanel(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .width(205.dp).offset(30.dp),
            accent = ControllerColors.Warning
        ) {
            HudSectionTitle("RECOVERY MODE", ControllerColors.Warning, 20.sp)
            Text("???????", color = ControllerColors.TextSecondary, fontSize = 11.sp)
        }

        Column(
            modifier = Modifier.offset(540.dp, 150.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HudButton(
                text = if (reload1) "RL 1  /  ACTIVE" else "RL 1",
                onClick = onReload1,
                modifier = Modifier.width(100.dp),
                containerColor = if (reload1) Color(0xFF124D37) else ControllerColors.Surface2,
                contentColor = if (reload1) ControllerColors.Success else ControllerColors.TextPrimary,
                enabled = !actionsLocked,
                height = 50.dp,
                fontSize = 11.sp,
                accent = if (reload1) ControllerColors.Success else ControllerColors.Border
            )
            HudButton(
                text = if (reload2) "RL 2  /  ACTIVE" else "RL 2",
                onClick = onReload2,
                modifier = Modifier.width(100.dp),
                containerColor = if (reload2) Color(0xFF124D37) else ControllerColors.Surface2,
                contentColor = if (reload2) ControllerColors.Success else ControllerColors.TextPrimary,
                enabled = !actionsLocked,
                height = 50.dp,
                fontSize = 11.sp,
                accent = if (reload2) ControllerColors.Success else ControllerColors.Border
            )
            HudButton(
                text = if (reload3) "RL 3  /  ACTIVE" else "RL 3",
                onClick = onReload3,
                modifier = Modifier.width(100.dp),
                containerColor = if (reload3) Color(0xFF124D37) else ControllerColors.Surface2,
                contentColor = if (reload3) ControllerColors.Success else ControllerColors.TextPrimary,
                enabled = !actionsLocked,
                height = 50.dp,
                fontSize = 11.sp,
                accent = if (reload3) ControllerColors.Success else ControllerColors.Border
            )
            HudButton(
                text = if (release) "RELEASE  /  ACTIVE" else "RELEASE",
                onClick = onRelease,
                modifier = Modifier.width(100.dp),
                containerColor = if (release) Color(0xFF124D37) else ControllerColors.Surface2,
                contentColor = if (release) ControllerColors.Success else ControllerColors.TextPrimary,
                enabled = !actionsLocked,
                height = 50.dp,
                fontSize = 11.sp,
                accent = if (release) ControllerColors.Success else ControllerColors.Border
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(1.dp)
                .offset(-9.dp,y=-2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End
        ) {
            HudButton(
                text = if (refill) "??  /  ACTIVE" else "??",
                onClick = onRefill,
                modifier = Modifier.width(180.dp),
                containerColor = if (refill) Color(0xFF124D37) else ControllerColors.Surface2,
                contentColor = if (refill) ControllerColors.Success else ControllerColors.TextPrimary,
                enabled = !actionsLocked,
                height = 47.dp,
                fontSize = 20.sp,
                accent = if (refill) ControllerColors.Success else ControllerColors.Accent
            )
            HudButton(
                text = if (firebaketu) "?????  /  ACTIVE" else "?????",
                onClick = onfirebaketu,
                modifier = Modifier.width(180.dp),
                containerColor = if (firebaketu) Color(0xFF124D37) else Color(0xFF4A2A0D),
                contentColor = if (firebaketu) ControllerColors.Success else ControllerColors.Warning,
                enabled = !actionsLocked,
                height = 47.dp,
                fontSize = 20.sp,
                accent = ControllerColors.Warning
            )
            HudButton(
                text = if (firehata) "???  /  ACTIVE" else "???",
                onClick = onfirehata,
                modifier = Modifier.width(180.dp),
                containerColor = if (firehata) Color(0xFF124D37) else Color(0xFF4E171E),
                contentColor = if (firehata) ControllerColors.Success else ControllerColors.Danger,
                enabled = !actionsLocked,
                height = 47.dp,
                fontSize = 20.sp,
                accent = ControllerColors.Danger
            )
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
            Row(){
                HudButton(
                    text = if (hataLaser) "HATA LASER  /  ACTIVE" else "HATA LASER",
                    onClick = onHataLaser,
                    modifier = Modifier.width(90.dp),
                    containerColor = if (hataLaser) Color(0xFF124D37) else ControllerColors.Surface2,
                    contentColor = if (hataLaser) ControllerColors.Success else ControllerColors.TextPrimary,
                    height = 55.dp,
                    fontSize = 9.sp,
                    accent = if (hataLaser) ControllerColors.Success else ControllerColors.Warning
                )
                HudButton(
                    text = if (hojuLaser) "HOJU LASER  /  ACTIVE" else "HOJU LASER",
                    onClick = onHojuLaser,
                    modifier = Modifier.width(90.dp),
                    containerColor = if (hojuLaser) Color(0xFF124D37) else ControllerColors.Surface2,
                    contentColor = if (hojuLaser) ControllerColors.Success else ControllerColors.TextPrimary,
                    height = 55.dp,
                    fontSize = 9.sp,
                    accent = if (hojuLaser) ControllerColors.Success else ControllerColors.Warning
                )
            }

        }

        ModeSwitchButtons(
            currentScreen = ScreenState.RECOVERY,
            onNavigate = onNavigateTo,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 200.dp)
        )
    }
}


@Composable
fun ModeSwitchButtons(
    currentScreen: ScreenState,
    onNavigate: (ScreenState) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.End
    ) {
        val screens = listOf(
            ScreenState.CONTROLLER to "?????",
            ScreenState.RECOVERY to "?????"
        )

        screens.forEach { (screen, label) ->
            val selected = currentScreen == screen
            HudButton(
                text = if (selected) "?  $label" else "?  $label",
                onClick = { if (!selected) onNavigate(screen) },
                modifier = Modifier.width(130.dp),
                containerColor = if (selected) Color(0xFF123D46) else ControllerColors.Surface2,
                contentColor = if (selected) ControllerColors.Accent else ControllerColors.TextSecondary,
                height = 38.dp,
                fontSize = 11.sp,
                accent = if (selected) ControllerColors.Accent else ControllerColors.Border
            )
        }
    }
}