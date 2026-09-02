package com.example.controller

import android.R.attr.translateX
import android.R.attr.translateY
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.math.sqrt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
// 画面切り替え管理用のEnum
enum class ScreenState {
    TEAM_SELECTION,
    CONTROLLER,
    RECOVERY,
    ADJUSTMENT
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
    BAKETU,
    HOJU
}

class MainActivity : ComponentActivity() {

    private val ip = "192.168.11.7"
    private val port = 5005

    private lateinit var socket: DatagramSocket
    private lateinit var address: InetAddress

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

    private var posX by mutableStateOf(2200.0)
    private var posY by mutableStateOf(500.0)
    private var posTheta by mutableStateOf(0.0)
    private var logList by mutableStateOf<List<String>>(emptyList())
    // turn調整値
    private var hata_turnx by mutableStateOf(0.0f)
    private var hata_turny by mutableStateOf(0.0f)
    private var hata_turntheta by mutableStateOf(0.0f)
    private var baketu_turnx by mutableStateOf(0.0f)
    private var baketu_turny by mutableStateOf(0.0f)
    private var baketu_turntheta by mutableStateOf(0.0f)
    private var hoju_turnx by mutableStateOf(0.0f)
    private var hoju_turny by mutableStateOf(0.0f)
    private var hoju_turntheta by mutableStateOf(0.0f)

    // t0
    private var t0 by mutableStateOf(false)

    // 通常モードで送信する列
    private var column1 by mutableStateOf("baketu")
    private var column2 by mutableStateOf("hata")
    private var column3 by mutableStateOf("hata")

    // ワンショット送信用
    private var execute by mutableStateOf(false)
    private var refill by mutableStateOf(false)
    private var reload1 by mutableStateOf(false)
    private var reload2 by mutableStateOf(false)
    private var reload3 by mutableStateOf(false)

    private var firehata by mutableStateOf(false)
    private var firebaketu by mutableStateOf(false)

    private var sendTime = 0L
    private val rttList = mutableStateListOf<Long>()

    // 画面状態、選択されたマップ String ("bluemap" または "redmap")、および画面反転フラグ
    private var currentScreen by mutableStateOf(ScreenState.TEAM_SELECTION)
    private var selectedMapID by mutableStateOf("bluemap")
    private var isFlipped by mutableStateOf(false)

    // turn調整対象
    private var selectedTurnTarget by mutableStateOf(TurnTarget.HATA)

    // turn調整の1回あたりの変化量
    private val turnXYStep = 10f
    private val turnThetaStep = 2f
    private var prevDpadLeft = false
    private var prevDpadRight = false
    private var prevDpadUp = false
    private var prevDpadDown = false
    private var prevL2 = false
    private var prevR2 = false
    private var lowGain by mutableStateOf(false)
    private var baketuSpeed by mutableStateOf(4.5f)
    private var hataSpeed by mutableStateOf(10.2f)

    // 速度調整の1回あたりの変化量
    private val speedStep = 0.1f
    // クラス内、logListの定義の近くに追加
    // appendLog を以下のように変更
    private fun appendLog(msg: String) {
        runOnUiThread {
            val last = logList.lastOrNull()
            val lastBase = last?.substringBeforeLast(" (x").let {
                if (last?.contains(" (x") == true) it else last
            }

            if (lastBase == msg) {
                // 直前と同じ内容 → 件数だけ更新して置き換える
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
        // コントローラー画面表示時のみ入力を有効化
        if (currentScreen != ScreenState.CONTROLLER && currentScreen != ScreenState.RECOVERY && currentScreen != ScreenState.ADJUSTMENT) {
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

            // D-Padで選択中のturnのX/Yを調整
            // ← X減少 / → X増加 / ↑ Y増加 / ↓ Y減少
            // 現在のボタン状態
            val currentDpadLeft = rawHori < -0.5f
            val currentDpadRight = rawHori > 0.5f
            val currentDpadUp = rawVer < -0.5f
            val currentDpadDown = rawVer > 0.5f
            val currentL2 = rawL2 > 0.5f
            val currentR2 = rawR2 > 0.5f

            // D-Pad 「押された瞬間」だけ1回調整
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

            // L2 / R2 「押された瞬間」だけ1回調整
            if (currentL2 && !prevL2) {
                adjustSelectedTurn(dtheta = -turnThetaStep)
            }

            if (currentR2 && !prevR2) {
                adjustSelectedTurn(dtheta = turnThetaStep)
            }

            // 前回状態を保存
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((currentScreen == ScreenState.CONTROLLER || currentScreen == ScreenState.RECOVERY ||
                    currentScreen == ScreenState.ADJUSTMENT) &&
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        ) {
            when (keyCode) {
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
        if ((currentScreen == ScreenState.CONTROLLER || currentScreen == ScreenState.RECOVERY ||
                    currentScreen == ScreenState.ADJUSTMENT) &&
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
        baketu_turnx = prefs.getFloat("baketu_turnx", 0.0f)
        baketu_turny = prefs.getFloat("baketu_turny", 0.0f)
        baketu_turntheta = prefs.getFloat("baketu_turntheta", 0.0f)
        hoju_turnx = prefs.getFloat("hoju_turnx", 0.0f)
        hoju_turny = prefs.getFloat("hoju_turny", 0.0f)
        hoju_turntheta = prefs.getFloat("hoju_turntheta", 0.0f)
        hataSpeed = prefs.getFloat("hata_speed", 10.2f)
        baketuSpeed = prefs.getFloat("baketu_speed", 4.5f)
        hideSystemUI()
        startLockTaskMode()

        thread {
            try {
                socket = DatagramSocket()
                address = InetAddress.getByName(ip)
            } catch (e: Exception) { appendLog("SOCKET ERROR: ${e.javaClass.simpleName} ${e.message}")}
        }

        thread {
            while (true) {
                if (::socket.isInitialized &&
                    (currentScreen == ScreenState.CONTROLLER || currentScreen == ScreenState.RECOVERY ||
                            currentScreen == ScreenState.ADJUSTMENT)) {
                    send(
                        selectedMapID,
                        if (lowGain) vy / 2f else vy,
                        if (lowGain) vx / 2f else vx,
                        if (lowGain) w / 2f else w, hataSpeed,
                        baketuSpeed,
                        hata_turnx, hata_turny, hata_turntheta,
                        hoju_turnx, hoju_turny, hoju_turntheta,
                        baketu_turnx, baketu_turny, baketu_turntheta,
                        mode = when(currentScreen) {
                            ScreenState.RECOVERY -> "recovery"
                            ScreenState.ADJUSTMENT -> "adjustment"
                            else -> "normal"
                        },
                        column1, column2, column3,
                        execute, refill,reload1, reload2, reload3, firehata, firebaketu,t0,
                        left, right, up, down,
                        circle, triangle, square, cross,
                        l1, l2, r1, r2
                    )
                }
                Thread.sleep(10)
            }
        }

        thread {
            val buf = ByteArray(1024)
            while (true) {
                try {
                    if (::socket.isInitialized) {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)

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
                    }
                } catch (e: Exception) {
                    appendLog("RECV ERROR: ${e.javaClass.simpleName} ${e.message}")
                }
            }
        }
        thread {
            val logSocket = DatagramSocket(5006)
            val buf = ByteArray(1024)

            while (true) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    logSocket.receive(packet)

                    val jsonString = String(
                        packet.data,
                        0,
                        packet.length
                    )

                    val json = JSONObject(jsonString)
                    val receivedLog = json.optString("log", "")

                    if (
                        receivedLog.isNotBlank() &&
                        !receivedLog.equals("none", ignoreCase = true)
                    ) {
                        val newLines = receivedLog
                            .split("\n")
                            .filter {
                                it.isNotBlank() &&
                                        !it.equals("none", ignoreCase = true)
                            }

                        if (newLines.isNotEmpty()) {
                            runOnUiThread {
                                logList = (logList + newLines).takeLast(50)
                            }
                        }
                    }

                } catch (e: Exception) {
                    appendLog("RECV ERROR: ${e.javaClass.simpleName} ${e.message}")
                }
            }
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
                        baketuTurnX = baketu_turnx, baketuTurnY = baketu_turny, baketuTurnTheta = baketu_turntheta,
                        hojuTurnX = hoju_turnx, hojuTurnY = hoju_turny, hojuTurnTheta = hoju_turntheta,
                        selectedTurnTarget = selectedTurnTarget,
                        t0 = t0,
                        onToggleT0 = { t0 = !t0 },
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
                        onEnterAdjustment = { currentScreen = ScreenState.ADJUSTMENT },
                        execute = execute,
                        pulexecute = { pulseExecute() },
                        hataSpeed = hataSpeed,
                        baketuSpeed = baketuSpeed,onNavigateTo = { targetScreen -> currentScreen = targetScreen },
                        logList = logList
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
                        onRefill = { pulseRefill() },
                        onfirehata = { pulsefirehata() },
                        refill = refill,
                        reload1 = reload1,
                        reload2 = reload2,
                        reload3 = reload3,
                        onReload1 = { pulseReload1() },
                        onReload2 = { pulseReload2() },
                        onReload3 = { pulseReload3() },
                        firehata = firehata,onfirebaketu = { pulsefirebaketu() }, // 追加
                        firebaketu = firebaketu,             // 追加
                        pulfirebaketu = { pulsefirebaketu() },
                        pulrefill = { pulseRefill() },
                        pulfirehata = { pulsefirehata() },
                        lowGain = lowGain,
                        onToggleLowGain = { lowGain = !lowGain },onNavigateTo = { targetScreen -> currentScreen = targetScreen },
                        logList = logList
                    )
                }

                ScreenState.ADJUSTMENT -> {
                    AdjustmentUI(
                        isFlipped = isFlipped,
                        selectedTurnTarget = selectedTurnTarget,
                        onSelectTurnTarget = { target -> selectedTurnTarget = target },
                        hataTurnX = hata_turnx, hataTurnY = hata_turny, hataTurnTheta = hata_turntheta,
                        baketuTurnX = baketu_turnx, baketuTurnY = baketu_turny, baketuTurnTheta = baketu_turntheta,
                        hojuTurnX = hoju_turnx, hojuTurnY = hoju_turny, hojuTurnTheta = hoju_turntheta,
                        hataSpeed = hataSpeed,
                        baketuSpeed = baketuSpeed,
                        onHataSpeedIncrease = {
                            hataSpeed = (hataSpeed + speedStep).coerceAtMost(10.2f)
                        },
                        onHataSpeedDecrease = {
                            hataSpeed = (hataSpeed - speedStep).coerceAtLeast(9.0f)
                        },
                        onBaketuSpeedIncrease = {
                            baketuSpeed = (baketuSpeed + speedStep).coerceAtMost(5.0f)
                        },
                        onBaketuSpeedDecrease = {
                            baketuSpeed = (baketuSpeed - speedStep).coerceAtLeast(4.0f)
                        },
                        onNavigateTo = { targetScreen -> currentScreen = targetScreen }
                    )
                }
            }
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
            TurnTarget.BAKETU -> {
                baketu_turnx += dx
                baketu_turny += dy
                baketu_turntheta += dtheta
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
        vx: Float, vy: Float, w: Float, hataSpeed: Float,
        baketuSpeed: Float,
        hataTurnX: Float, hataTurnY: Float, hataTurnTheta: Float,
        hojuTurnX: Float, hojuTurnY: Float, hojuTurnTheta: Float,
        baketuTurnX: Float, baketuTurnY: Float, baketuTurnTheta: Float,
        mode: String,
        column1: String, column2: String, column3: String,
        execute: Boolean, refill: Boolean, reload1: Boolean, reload2: Boolean, reload3: Boolean,firehata: Boolean,firebaketu: Boolean, t0: Boolean,
        left: Boolean, right: Boolean, up: Boolean, down: Boolean,
        circle: Boolean, triangle: Boolean, square: Boolean, cross: Boolean,
        l1: Boolean, l2: Boolean, r1: Boolean, r2: Boolean
    ) {
        try {
            val msg = """{"map_id":"$mapId","vx":$vx,"vy":$vy,"w":$w,"hata_speed":$hataSpeed,"baketu_speed":$baketuSpeed,"hata_turnx":$hataTurnX,"hata_turny":$hataTurnY,"hata_turntheta":$hataTurnTheta,"hoju_turnx":$hojuTurnX,"hoju_turny":$hojuTurnY,"hoju_turntheta":$hojuTurnTheta,"baketu_turnx":$baketuTurnX,"baketu_turny":$baketuTurnY,"baketu_turntheta":$baketuTurnTheta,"mode":"$mode","column1":"$column1","column2":"$column2","column3":"$column3","execute":$execute,"refill":$refill,"reload1":$reload1,"reload2":$reload2,"reload3":$reload3,"firehata":$firehata,"firebaketu":$firebaketu,"t0":$t0,"left":$left,"right":$right,"up":$up,"down":$down,"circle":$circle,"triangle":$triangle,"square":$square,"cross":$cross,"l1":$l1,"l2":$l2,"r1":$r1,"r2":$r2}"""
            val buf = msg.toByteArray()
            val packet = DatagramPacket(buf, buf.size, address, port)
            sendTime = System.nanoTime()
            socket.send(packet)
        } catch (e: Exception) {
            appendLog("SEND ERROR: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        prefs.edit()
            .putFloat("hata_turnx", hata_turnx)
            .putFloat("hata_turny", hata_turny)
            .putFloat("hata_turntheta", hata_turntheta)
            .putFloat("baketu_turnx", baketu_turnx)
            .putFloat("baketu_turny", baketu_turny)
            .putFloat("baketu_turntheta", baketu_turntheta)
            .putFloat("hoju_turnx", hoju_turnx)
            .putFloat("hoju_turny", hoju_turny)
            .putFloat("hoju_turntheta", hoju_turntheta)

            .putFloat("hata_speed", hataSpeed)
            .putFloat("baketu_speed", baketuSpeed)
            .commit()
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/**
 * チーム（赤・青）選択用UI画面
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
            .background(Color.DarkGray),
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
                color = Color.White
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(40.dp)
            ) {
                Button(
                    onClick = { onSelectTeam("redmap") },
                    modifier = Modifier.size(width = 180.dp, height = 120.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text(
                        text = "RED",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Button(
                    onClick = { onSelectTeam("bluemap") },
                    modifier = Modifier.size(width = 180.dp, height = 120.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text(
                        text = "BLUE",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Button(
                onClick = onToggleFlip,
                modifier = Modifier
                    .width(220.dp)
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isFlipped) Color(0xFFFF9800) else Color(0xFF424242)
                )
            ) {
                Text(
                    text = if (isFlipped) "FLIPPED (180°)" else "ROTATE SCREEN (180°)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
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
    baketuTurnX: Float, baketuTurnY: Float, baketuTurnTheta: Float,
    hojuTurnX: Float, hojuTurnY: Float, hojuTurnTheta: Float,
    selectedTurnTarget: TurnTarget,
    t0: Boolean,
    onToggleT0: () -> Unit,
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
    onEnterAdjustment: () -> Unit,
    execute: Boolean,
    pulexecute: () -> Unit,
    hataSpeed: Float,
    baketuSpeed: Float,
    logList: List<String>
) {

    val imageBitmap = ImageBitmap.imageResource(id = R.drawable.blackarrow)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .rotate(if (isFlipped) 180f else 0f)
            .background(Color.DarkGray),
    ) {

        Box(
            modifier = Modifier
                .size(370.dp)
                .offset(x = 120.dp).graphicsLayer {
                    scaleX = if (mapID == "redmap") -1f else 1f
                }
        ) {
            Image(
                painter = painterResource(id = R.drawable.robocon_map),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = 90f
                    scaleX = if (mapID == "redmap") -1f else 1f
                },
                contentScale = ContentScale.Fit
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val mapWidth = 11700
                val mapHeight = 11700
                val scaleX = size.width / mapWidth
                val scaleY = size.height / mapHeight
                var tempPx = 0.0
                var tempPy = 0.0
                if (mapID == "bluemap") {
                    tempPx = posX
                    tempPy = posY
                } else {
                    tempPx = -posX
                    tempPy = -posY
                }

                val px = size.height - (tempPx * scaleX).toFloat()
                val py = (-tempPy * scaleY).toFloat()
                val robotPos = Offset(px, py)

                val angleDegrees = Math.toDegrees(posTheta).toFloat()
                val translateX: Float
                val translateY: Float

                if (mapID == "bluemap") {
                    translateX = robotPos.y + 1140f
                    translateY = robotPos.x - 25f
                } else {
                    translateX = -robotPos.y + 1140f
                    translateY = robotPos.x - 25f
                }

                Log.d(
                    "ControllerUI",
                    "mapID=$mapID robotPos=(${robotPos.x}, ${robotPos.y}) " +
                            "translate=($translateX, $translateY)"
                )
                withTransform({
                    translate(
                        translateX,
                        translateY
                    )
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
        val hojuInRange = isInRange(
            targetHoju,
            posX,
            posY,
            posTheta
        )

        // 左側：ステータス表示
        Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {

            Text(
                text = if (mapID == "redmap") "RED SIDE" else "BLUE SIDE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))


            Text("Robot Pose", fontWeight = FontWeight.Bold, color = Color.Cyan)
            Text(text = "X: ${"%.2f".format(posX)}", color = Color.White)
            Text(text = "Y: ${"%.2f".format(posY)}", color = Color.White)
            Text(text = "θ: ${"%.2f".format(posTheta)}", color = Color.White)

//            Spacer(modifier = Modifier.height(8.dp))
//
//            Text("Stick Input", fontWeight = FontWeight.Bold, color = Color.Magenta)
//            Text(text = "X: ${"%.2f".format(currentVy)}", color = Color.White)
//            Text(text = "Y: ${"%.2f".format(currentVx)}", color = Color.White)
//            Text(text = "θ : ${"%.2f".format(currentW)}", color = Color.White)
//
//            Text("Gamepad Buttons", fontWeight = FontWeight.Bold, color = Color.Yellow)
//            Text(text = "D-Pad: ${if(up)"↑" else ""}${if(down)"↓" else ""}${if(left)"←" else ""}${if(right)"→" else ""}", color = Color.White)
//            Text(text = "Action: ${if(circle)"○ " else ""}${if(cross)"× " else ""}${if(square)"□ " else ""}${if(triangle)"△" else ""}", color = Color.White)
//            Text(text = "Bumper: ${if(l1)"[L1] " else ""}${if(r1)"[R1]" else ""}", color = Color.White)
//            Text(text = "Trigger: ${if(l2)"[L2] " else ""}${if(r2)"[R2]" else ""}", color = Color.White)
            Text(
                "LOG",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            val logScrollState = rememberLazyListState()

            LaunchedEffect(logList.size) {
                if (logList.isNotEmpty()) {
                    logScrollState.animateScrollToItem(logList.lastIndex)
                }
            }

            LazyColumn(
                state = logScrollState,
                modifier = Modifier
                    .width(140.dp)
                    .height(170.dp)
            ) {
                items(logList) { logLine ->
                    Text(
                        text = logLine,
                        color = Color.Black,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text("RTT (ms)", fontWeight = FontWeight.Bold, color = Color.White)
            Column(modifier = Modifier.height(120.dp).verticalScroll(rememberScrollState())) {
                rttList.asReversed().forEach { Text(text = "$it ms", color = Color.Green) }
            }
        }

        // 右上：turn値の表示(表示専用)と通常モードの列設定
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.End
        ) {

            // 常にすべてのターゲティング調整値をまとめて表示する例
            Column {
                Text("HATA   X ${"%.2f".format(hataTurnX)} Y ${"%.2f".format(hataTurnY)} θ ${"%.2f".format(hataTurnTheta)}", color = Color.White, fontSize = 11.sp)
                Text("BAKETU X ${"%.2f".format(baketuTurnX)} Y ${"%.2f".format(baketuTurnY)} θ ${"%.2f".format(baketuTurnTheta)}", color = Color.White, fontSize = 11.sp)
                Text("HOJU   X ${"%.2f".format(hojuTurnX)} Y ${"%.2f".format(hojuTurnY)} θ ${"%.2f".format(hojuTurnTheta)}", color = Color.White, fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("NORMAL MODE", fontWeight = FontWeight.Bold, color = Color.Cyan, fontSize = 18.sp)

            ColumnButton(label = "Column 1: $column1", onClick = onColumn1Change)
            ColumnButton(label = "Column 2: $column2", onClick = onColumn2Change)
            ColumnButton(label = "Column 3: $column3", onClick = onColumn3Change)
            LaunchedEffect(square, hojuInRange) {
                if (square && hojuInRange) {
                    pulexecute()
                }
            }
            Button(
                onClick = {
                    if (hojuInRange) {
                        onExecute()
                    }
                },
                modifier = Modifier
                    .width(180.dp)
                    .height(60.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (execute) {
                        Color(0xFF4CAF50)
                    } else if (hojuInRange) {
                        Color(0xFF0F1E17)
                    } else {
                        Color.Gray
                    }
                )
            ) {
                Text(
                    if (hojuInRange) "実行" else "範囲外",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        }

        // t0
        Button(
            onClick = onToggleT0,
            modifier = Modifier.align(Alignment.BottomStart).padding(100.dp).size(120.dp).offset(390.dp,70.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (t0) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
        ) {
            Text(if (t0) "ENABLED" else "DISABLED", fontWeight = FontWeight.Bold)
        }

        // 右上：画面遷移ボタン・スピード値表示（表示専用）
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(30.dp).offset(-180.dp, 150.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {



            Text(
                "HATA SPEED: ${"%.1f".format(hataSpeed)}",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Text(
                "BAKETU SPEED: ${"%.1f".format(baketuSpeed)}",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        // ControllerUI 内の Box の直下に追加
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
    TurnTarget.BAKETU -> "BAKETU"
    TurnTarget.HOJU -> "HOJU"
}

@Composable
fun TurnSelectButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.width(90.dp).height(60.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFFFF9800) else Color(0xFF03A9F4)
        )
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ColumnButton(
    label: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.width(180.dp).height(50.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (label.contains("hata")) {
                Color(0xFF1A237E)
            } else if (label.contains("baketu")) {
                Color(0xFF8B0000)
            } else {
                Color(0xFF708278)
            }
        )
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold)
    }
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
    firehata: Boolean,
    onfirebaketu: () -> Unit,      // 追加
    firebaketu: Boolean,        // 追加
    pulfirebaketu: () -> Unit,   // 追加
    pulrefill: () -> Unit,
    pulfirehata: () -> Unit,
    logList: List<String>
) {
    val imageBitmap = ImageBitmap.imageResource(id = R.drawable.blackarrow)
    LaunchedEffect(circle, cross) {
        if (circle) {
            pulfirehata()
        } else if (cross) {
            pulrefill()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .rotate(if (isFlipped) 180f else 0f)
            .background(Color.DarkGray)
    ) {
        Box(
            modifier = Modifier
                .size(370.dp)
                .offset(x = 120.dp).graphicsLayer {
                    scaleX = if (mapID == "redmap") -1f else 1f
                }
        ) {
            Image(
                painter = painterResource(id = R.drawable.robocon_map),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = 90f
                    scaleX = if (mapID == "redmap") -1f else 1f
                },
                contentScale = ContentScale.Fit
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val mapWidth = 11700
                val mapHeight = 11700
                val scaleX = size.width / mapWidth
                val scaleY = size.height / mapHeight
                var tempPx = 0.0
                var tempPy = 0.0
                if (mapID == "bluemap") {
                    tempPx = posX
                    tempPy = posY
                } else {
                    tempPx = -posX
                    tempPy = -posY
                }

                val px = size.height - (tempPx * scaleX).toFloat()
                val py = (-tempPy * scaleY).toFloat()
                val robotPos = Offset(px, py)

                val angleDegrees = Math.toDegrees(posTheta).toFloat()
                val translateX: Float
                val translateY: Float

                if (mapID == "bluemap") {
                    translateX = robotPos.y + 1140f
                    translateY = robotPos.x - 25f
                } else {
                    translateX = -robotPos.y + 1140f
                    translateY = robotPos.x - 25f
                }

                withTransform({
                    translate(
                        translateX,
                        translateY
                    )
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

        Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text(
                text = if (mapID == "redmap") "RED SIDE" else "BLUE SIDE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Robot Pose", fontWeight = FontWeight.Bold, color = Color.Cyan)
            Text("X: ${"%.2f".format(posX)}", color = Color.White)
            Text("Y: ${"%.2f".format(posY)}", color = Color.White)
            Text("θ: ${"%.2f".format(posTheta)}", color = Color.White)

            Text(
                "LOG",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            val logScrollState = rememberLazyListState()

            LaunchedEffect(logList.size) {
                if (logList.isNotEmpty()) {
                    logScrollState.animateScrollToItem(logList.lastIndex)
                }
            }

            LazyColumn(
                state = logScrollState,
                modifier = Modifier
                    .width(140.dp)
                    .height(170.dp)
            ) {
                items(logList) { logLine ->
                    Text(
                        text = logLine,
                        color = Color.Black,
                        fontSize = 11.sp
                    )
                }
            }


//            Spacer(modifier = Modifier.height(8.dp))
//            Text("Stick Input", fontWeight = FontWeight.Bold, color = Color.Magenta)
//            Text("X: ${"%.2f".format(currentVy)}", color = Color.White)
//            Text("Y: ${"%.2f".format(currentVx)}", color = Color.White)
//            Text("θ : ${"%.2f".format(currentW)}", color = Color.White)
//
//            Text("Gamepad Buttons", fontWeight = FontWeight.Bold, color = Color.Yellow)
//            Text(
//                "D-Pad: ${if (up) "↑" else ""}${if (down) "↓" else ""}${if (left) "←" else ""}${if (right) "→" else ""}",
//                color = Color.White
//            )
//            Text(
//                "Action: ${if (circle) "○ " else ""}${if (cross) "× " else ""}${if (square) "□ " else ""}${if (triangle) "△" else ""}",
//                color = Color.White
//            )
//            Text(
//                "Bumper: ${if (l1) "[L1] " else ""}${if (r1) "[R1]" else ""}",
//                color = Color.White
//            )
//            Text(
//                "Trigger: ${if (l2) "[L2] " else ""}${if (r2) "[R2]" else ""}",
//                color = Color.White
//            )
//
            Spacer(modifier = Modifier.height(10.dp))
            Text("RTT (ms)", fontWeight = FontWeight.Bold, color = Color.White)
            Column(modifier = Modifier.height(120.dp).verticalScroll(rememberScrollState())) {
                rttList.asReversed().forEach { Text(text = "$it ms", color = Color.Green) }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                "RECOVERY MODE",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF9800),
                fontSize = 20.sp
            )
            Text("補充・発射操作", color = Color.White, fontSize = 12.sp)
        }

        Column(modifier=Modifier.offset(540.dp,170.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onReload1,
                modifier = Modifier.width(80.dp).height(50.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (reload1) Color(0xFF4CAF50) else Color(0xFF795548)
                )
            ) {
                Text("RL 1", fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onReload2,
                modifier = Modifier.width(80.dp).height(50.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (reload2) Color(0xFF4CAF50) else Color(0xFF795548)
                )
            ) {
                Text("RL 2", fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onReload3,
                modifier = Modifier.width(80.dp).height(50.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (reload3) Color(0xFF4CAF50) else Color(0xFF795548)
                )
            ) {
                Text("RL 3", fontWeight = FontWeight.Bold)
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(30.dp).offset(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Button(
                onClick = onRefill,
                modifier = Modifier.width(180.dp).height(60.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (refill) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFF2196F3)
                    }
                )
            ) {
                Text("補充", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
// ボタン配置カラム内（「発射」ボタンの前後など）に追加
            Button(
                onClick = onfirebaketu,
                modifier = Modifier.width(180.dp).height(60.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (firebaketu) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFFE65100)
                    }
                )
            ) {
                Text("バケツ発射", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Button(
                onClick = onfirehata,
                modifier = Modifier.width(180.dp).height(60.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (firehata) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFFB71C1C)
                    }
                )
            ) {
                Text("旗発射", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Button(
                onClick = onToggleLowGain,
                modifier = Modifier
                    .width(180.dp)
                    .height(60.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (lowGain) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFF607D8B)
                    }
                )
            ) {
                Text(
                    if (lowGain) "LOW GAIN ON" else "LOW GAIN OFF",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }


        }
        // RecoveryUI 内の Box の直下に追加
        ModeSwitchButtons(
            currentScreen = ScreenState.RECOVERY,
            onNavigate = onNavigateTo,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 200.dp)
        )
    }
}

/**
 * 調整モードUI画面
 */
@Composable
fun AdjustmentUI(
    isFlipped: Boolean,
    selectedTurnTarget: TurnTarget,
    onSelectTurnTarget: (TurnTarget) -> Unit,
    hataTurnX: Float, hataTurnY: Float, hataTurnTheta: Float,
    baketuTurnX: Float, baketuTurnY: Float, baketuTurnTheta: Float,
    hojuTurnX: Float, hojuTurnY: Float, hojuTurnTheta: Float,
    hataSpeed: Float,
    baketuSpeed: Float,
    onHataSpeedIncrease: () -> Unit,
    onHataSpeedDecrease: () -> Unit,
    onBaketuSpeedIncrease: () -> Unit,
    onBaketuSpeedDecrease: () -> Unit,
    onNavigateTo: (ScreenState) -> Unit,
) {

    Box(
        modifier = Modifier
            .fillMaxSize()
            .rotate(if (isFlipped) 180f else 0f)
            .background(Color.DarkGray)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.TopStart).offset(150.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "ADJUSTMENT MODE",
                fontWeight = FontWeight.Bold,
                color = Color(0xFF9C27B0),
                fontSize = 24.sp
            )

            // --- スピード調整領域 ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SPEED ADJUSTMENT", fontWeight = FontWeight.Bold, color = Color.Cyan)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "HATA SPEED: ${"%.1f".format(hataSpeed)}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = onHataSpeedDecrease, modifier = Modifier.size(90.dp, 40.dp)) {
                        Text("H −", fontSize = 12.sp)
                    }
                    Button(onClick = onHataSpeedIncrease, modifier = Modifier.size(90.dp, 40.dp)) {
                        Text("H ＋", fontSize = 12.sp)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "BAKETU SPEED: ${"%.1f".format(baketuSpeed)}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = onBaketuSpeedDecrease, modifier = Modifier.size(90.dp, 40.dp)) {
                        Text("B −", fontSize = 12.sp)
                    }
                    Button(onClick = onBaketuSpeedIncrease, modifier = Modifier.size(90.dp, 40.dp)) {
                        Text("B ＋", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- Turn ターゲット選択 ＆ 値表示領域 ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TURN TARGET ADJUSTMENT", fontWeight = FontWeight.Bold, color = Color.Cyan)


                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TurnSelectButton(
                        label = "HATA",
                        selected = selectedTurnTarget == TurnTarget.HATA,
                        onClick = { onSelectTurnTarget(TurnTarget.HATA) }
                    )
                    TurnSelectButton(
                        label = "BAKETU",
                        selected = selectedTurnTarget == TurnTarget.BAKETU,
                        onClick = { onSelectTurnTarget(TurnTarget.BAKETU) }
                    )
                    TurnSelectButton(
                        label = "HOJU",
                        selected = selectedTurnTarget == TurnTarget.HOJU,
                        onClick = { onSelectTurnTarget(TurnTarget.HOJU) }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                when (selectedTurnTarget) {
                    TurnTarget.HATA -> Text(
                        "HATA -> X: ${"%.2f".format(hataTurnX)}  Y: ${"%.2f".format(hataTurnY)}  θ: ${"%.2f".format(hataTurnTheta)}",
                        color = Color.Yellow, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                    TurnTarget.BAKETU -> Text(
                        "BAKETU -> X: ${"%.2f".format(baketuTurnX)}  Y: ${"%.2f".format(baketuTurnY)}  θ: ${"%.2f".format(baketuTurnTheta)}",
                        color = Color.Yellow, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                    TurnTarget.HOJU -> Text(
                        "HOJU -> X: ${"%.2f".format(hojuTurnX)}  Y: ${"%.2f".format(hojuTurnY)}  θ: ${"%.2f".format(hojuTurnTheta)}",
                        color = Color.Yellow, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }


        // AdjustmentUI 内の Box の直下に追加
        ModeSwitchButtons(
            currentScreen = ScreenState.ADJUSTMENT,
            onNavigate = onNavigateTo,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 200.dp).offset(22.dp,-25.dp)
        )
    }
}
@Composable
fun ModeSwitchButtons(
    currentScreen: ScreenState,
    onNavigate: (ScreenState) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = Color(0xFFFF9800)
    val inactiveColor = Color(0xFF424242)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        // 通常モード
        Button(
            onClick = { if (currentScreen != ScreenState.CONTROLLER) onNavigate(ScreenState.CONTROLLER) },
            modifier = Modifier.width(130.dp).height(38.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (currentScreen == ScreenState.CONTROLLER) activeColor else inactiveColor
            )
        ) {
            Text("通常モード", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
        }

        // リカバリーモード
        Button(
            onClick = { if (currentScreen != ScreenState.RECOVERY) onNavigate(ScreenState.RECOVERY) },
            modifier = Modifier.width(130.dp).height(38.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (currentScreen == ScreenState.RECOVERY) activeColor else inactiveColor
            )
        ) {
            Text("リカバリー", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
        }

        // 調整モード
        Button(
            onClick = { if (currentScreen != ScreenState.ADJUSTMENT) onNavigate(ScreenState.ADJUSTMENT) },
            modifier = Modifier.width(130.dp).height(38.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (currentScreen == ScreenState.ADJUSTMENT) activeColor else inactiveColor
            )
        ) {
            Text("調整モード", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
        }
    }
}