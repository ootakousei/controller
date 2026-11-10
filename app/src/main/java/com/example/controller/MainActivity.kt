package com.example.controller

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt
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
    HATA,
    HOJU,
    BS2,
    BS3
}

// ???????????????? SPEED ADJUST ????????
private const val BAKETU_SPEED_MIN = 4.0f
private const val BAKETU_SPEED_MAX = 5.0f
private const val HATA_SPEED_MIN = 9.0f
private const val HATA_SPEED_MAX = 10.5f
// HOJU（移動先 HOJU で撃つとき）の射出速度の範囲。mast2 の input.cpp の HOJU_VEL_MIN / MAX と合わせる。
private const val HOJU_SPEED_MIN = 4.0f
private const val HOJU_SPEED_MAX = 10.0f
// HOJU の射出速度の保存値が無いときの値（con.py の SETTING_DEFAULTS と同じ）。
private const val HOJU_DEFAULT_SPEED = 5.5f
// 移動バケツ（idoubake）を狙うときの射出速度の差（2026-11-08 追加）の範囲。移動バケツ中、ロボット（mast2 の input_node）は
// □△○ の速度を「drive2 が起動時に計算した速度 + これ」にする（hoju の速度には足さない）。
// 保存値が無ければ 0（con.py の SETTING_DEFAULTS と同じ）。刻みは射出速度と同じ speedStep。
private const val IDOUBAKE_SPEED_DIF_MIN = -4.0f
private const val IDOUBAKE_SPEED_DIF_MAX = 4.0f
// retry ボタンを押してから true を送り続ける時間 [s]。
private const val RETRY_HOLD_SECONDS = 15
// 移動バケツ（idoubake）を解除する前後のスティックの大きさ。drive2 の hoju_line_deadzone と合わせる（2026-11-08）。
// 比べるのは PC へ送る値（vx。lowGain なら半分）で、PC の controller_val.y と同じもの。
private const val HOJU_LINE_DEADZONE = 0.05f

// SPEED ADJUST の +/- を押しっぱなしにしたときの連続調整の間隔。HATA の turn 調整と同じ値。
private const val SPEED_REPEAT_INITIAL_DELAY_MS = 160L
private const val SPEED_REPEAT_INTERVAL_MS = 100L

// appendLog が書くログファイル（アプリの filesDir 内）。
private const val LOG_FILE_NAME = "debug_log.txt"
private const val LOG_FILE_OLD_NAME = "debug_log.old.txt"
private const val LOG_FILE_MAX_BYTES = 1_000_000L
// 同じメッセージを続けて書く最短間隔。間に来た分は回数だけ残す。
private const val LOG_FILE_REPEAT_INTERVAL_MS = 1000L

// ★ ES920LR の送信時間の試験。true にすると、アプリ側の 1 時間の送信時間の制限（330 秒で HOLD）を外して
// 送り続け、360 秒を超えたときにモジュール自身が送信を止めるか（NG / 無応答 / 返事の遅れ）をログに残す。
// 380 秒まで OK が返り続けたら「制限しない」と判定して送るのをやめる。終わったら必ず false に戻すこと。
// 2026-10-30 の試験で、モジュール自身が上限で送信を止めることを確かめた。
private const val ES920_AIRTIME_TEST = false

// ロボット（con.py）への送信間隔。20ms = 50Hz。
private const val SEND_INTERVAL_MS = 20L

// 同じパケットを並行して送る経路（Android の Network）。つながっている経路すべてへ同じものを送る。
// ETH = 今つないでいる無線子機（有線 LAN として見える）、WIFI = 端末の Wi-Fi。
// どれもつながっていない間は、デフォルト経路のソケットで送る（以前と同じ動き）。
// 宛先はどの経路も同じ ip / port。経路ごとの宛先などの設定はあとで足す。
private val SEND_PATH_TRANSPORTS = listOf(
    "ETH" to NetworkCapabilities.TRANSPORT_ETHERNET,
    "WIFI" to NetworkCapabilities.TRANSPORT_WIFI
)

/**
 * 特定の Network に紐付けた送受信用ソケット。
 * 送信は経路ごとの専用ループが outbox から取り出して行うので、全経路へ同時に送られ、
 * 1本が詰まってももう1本は待たされない。outbox は最新の1パケットだけを残す（詰まった経路は古い分を捨てる）。
 */
private class SendPath(val name: String, val network: Network, val socket: DatagramSocket) {
    val outbox = Channel<ByteArray>(Channel.CONFLATED)

    fun close() {
        outbox.close()
        try { socket.close() } catch (_: Exception) {}
    }
}

// ロボットへ送るバイナリパケット（リトルエンディアン、101 byte）。並びは send() を参照。
// con.py の decode_packet() と必ず合わせること。
// 2026-11-08 に version 18。idoubake_speed_dif（i16）を 98 byte 目に足し、100 byte 目は空き（0）。
// 設定値（14〜100 byte）を 87 byte にして、ES920LR の LoRa で 29 byte × 3 回に割り切れるようにするため。
private const val PACKET_VERSION: Byte = 18
private const val PACKET_SIZE = 101

// float は「値 × 倍率」を四捨五入して int16 で送る（con.py の STICK_SCALE / SETTING_SCALES と合わせる）。
// 整数で送るので、con.py 側で割り戻しても 0.30000001 のように桁が伸びない。
private const val STICK_SCALE = 10000f      // vx / vy / w     : 0.0001 刻み
private const val TURN_XY_SCALE = 10f       // turn x / y      : 0.1 刻み（±3276.7 まで）
private const val TURN_THETA_SCALE = 100f   // turn theta      : 0.01 刻み（±327.67 まで）
private const val SPEED_SCALE = 1000f       // 射出速度         : 0.001 刻み（32.767 まで）

// turn / speed の名前と倍率。パケットの 14 バイト目からの並び（con.py の SETTING_KEYS と同じ）。
private val SETTING_SCALES = listOf(
    "bs2_turnx" to TURN_XY_SCALE, "bs2_turny" to TURN_XY_SCALE, "bs2_turntheta" to TURN_THETA_SCALE,
    "hata_far_turnx" to TURN_XY_SCALE, "hata_far_turny" to TURN_XY_SCALE, "hata_far_turntheta" to TURN_THETA_SCALE,
    "hoju_turnx" to TURN_XY_SCALE, "hoju_turny" to TURN_XY_SCALE, "hoju_turntheta" to TURN_THETA_SCALE,
    "hata_far_hutuu_speed1" to SPEED_SCALE, "hata_far_hutuu_speed2" to SPEED_SCALE, "hata_far_hutuu_speed3" to SPEED_SCALE,
    "bs2_speed1" to SPEED_SCALE, "bs2_speed2" to SPEED_SCALE, "bs2_speed3" to SPEED_SCALE,
    "bs3_speed1" to SPEED_SCALE, "bs3_speed2" to SPEED_SCALE, "bs3_speed3" to SPEED_SCALE,
    "bs3_turnx" to TURN_XY_SCALE, "bs3_turny" to TURN_XY_SCALE, "bs3_turntheta" to TURN_THETA_SCALE,
    "hata_t_turnx" to TURN_XY_SCALE, "hata_t_turny" to TURN_XY_SCALE, "hata_t_turntheta" to TURN_THETA_SCALE,
    "hata_near_hutuu_speed1" to SPEED_SCALE, "hata_near_hutuu_speed2" to SPEED_SCALE, "hata_near_hutuu_speed3" to SPEED_SCALE,
    "hata_near_deka_speed1" to SPEED_SCALE, "hata_near_deka_speed2" to SPEED_SCALE, "hata_near_deka_speed3" to SPEED_SCALE,
    "hata_far_deka_speed1" to SPEED_SCALE, "hata_far_deka_speed2" to SPEED_SCALE, "hata_far_deka_speed3" to SPEED_SCALE,
    "hata_o_turnx" to TURN_XY_SCALE, "hata_o_turny" to TURN_XY_SCALE, "hata_o_turntheta" to TURN_THETA_SCALE,
    "hoju_speed1" to SPEED_SCALE, "hoju_speed2" to SPEED_SCALE, "hoju_speed3" to SPEED_SCALE,
    "idoubake_turnx" to TURN_XY_SCALE, "idoubake_turny" to TURN_XY_SCALE, "idoubake_turntheta" to TURN_THETA_SCALE,
    "idoubake_speed_dif" to SPEED_SCALE,
)

// 文字列の id は、この並びの番号（u8）で送る。並びにない id は 0xFF。
private val PACKET_MAP_IDS = listOf("redmap", "bluemap")
// 撃つ速度の組まで送る（packetObject()）。
private val PACKET_OBJECT_IDS = listOf("baketu_bs2", "baketu_bs3", "hata_near_hutuu", "hata_near_deka", "hata_far_hutuu", "hata_far_deka", "hoju")
private val PACKET_TARGET_IDS = listOf(
    "", "none",
    // "hbo" / "hbt" は移動先から消したが、con.py と番号を合わせるため席だけ残している。
    "ura", "edge", "tb1", "tb2", "hbo", "hbt",
    "bs2_a", "bs2_b", "bs2_c", "bs3_a", "bs3_b", "bs3_c",
    // "hata_far" は drive2 の地点 hata。元の "hata" の席をそのまま使っている（con.py と番号を合わせること）。
    "hata_far", "hoju", "dekahoju",
    "hata_t", "hata_o"
)

// ロボット（con.py）から返ってくる自己位置のバイナリパケット（リトルエンディアン、15 byte）。
// con.py の POSE_STRUCT と必ず合わせること。
//   offset  size  中身
//    0      u8    POSE_PACKET_VERSION
//    1      i32   x     × POSE_XY_SCALE（mm）
//    5      i32   y     × POSE_XY_SCALE（mm）
//    9      i32   theta × POSE_THETA_SCALE（rad）
//   13      u8    bit i = PC が ES920LR の組 i（bit0 = A、bit1 = B）の行を受け取れている
//                 （画面の LoRa の生死。Wi-Fi が生きている間はこれで見る）
//   14      u8    invalid_target の番号（進んでいたら MOVE TARGET を解除する。onInvalidSeq）
private const val POSE_PACKET_VERSION: Byte = 2
private const val POSE_PACKET_SIZE = 15
private const val POSE_XY_SCALE = 100.0        // 0.01mm 刻み
private const val POSE_THETA_SCALE = 10000.0   // 0.0001rad 刻み

// 相手のバケツの位置（2026-11-08）。フィールド選択画面で選び、操作のパケットとは別の小さいパケットで送る。
// con.py の ENEMY_* と必ず合わせる。値はゾーン基準の mm:
//   原点は相手スタートゾーンの右下、x = 奥（前）へ、y = 左へ。y は青ではフェンスからの距離、赤ではその逆向き
//   （ロボットの地図は赤で y が反転しているため）。z = バケツの底の床からの高さ（ロボットは z + 250 = 口を狙う）。
//   2026-11-08 に移動バケツ位置決めの仕様（kazusa_notes/mag_pc/2026-11-08_idoubake_spec.md）に合わせた。
//   ロボットは launch のときに 1 回だけ使う（con.py → launch_starter → drive2）。起動したあとに変えても次に起動し直すまで効かない。
//   上り（8 バイト）: 0 u8 ENEMY_TAG / 1 i16 x / 3 i16 y / 5 i16 z / 7 u8 1〜6 バイトの和の下位 8 bit
//     UDP ではこのまま 5005 番へ。ES920LR では操作だけの行の後ろに付ける（Es920Link.kt の LORA_ENEMY_*）。
//   PC の返事（UDP、9 バイト）: 0 u8 ENEMY_TAG / 1 u8 has / 2 i16 x / 4 i16 y / 6 i16 z / 8 u8 1〜7 バイトの和の下位 8 bit
//     ES920LR では状態の行に同じ値（has / x / y / z）が入る。
//   PC が今持っている値が返ってくるので、自分の値と同じなら届いた（番号は使わない）。
//   届くまで ENEMY_RESEND_MS ごとに送り直し続け、届いてからも ENEMY_CHECK_MS ごとに送って確かめる。
private const val ENEMY_TAG: Byte = 0x45
private const val ENEMY_UP_SIZE = 8
private const val ENEMY_ACK_SIZE = 9
private const val ENEMY_RESEND_MS = 300L
private const val ENEMY_CHECK_MS = 5000L
// +/- 1 回の量と範囲 [mm]（con.py の ENEMY_*_RANGE と同じ）
private const val ENEMY_STEP = 50
private val ENEMY_X_RANGE = 0..1000
private val ENEMY_Y_RANGE = 0..1000
private val ENEMY_Z_RANGE = 0..1800   // 2026-11-08 に 2000 -> 1800（ロボットの drive2 が受け付ける範囲）
private const val ENEMY_DEFAULT_X = 500
private const val ENEMY_DEFAULT_Y = 500
private const val ENEMY_DEFAULT_Z = 1200

private fun packetIndex(ids: List<String>, id: String): Byte =
    ids.indexOf(id).let { if (it < 0) 0xFF else it }.toByte()

/** 値 × scale を四捨五入して int16 で書く。範囲外は端に張り付かせる。 */
private fun ByteBuffer.putScaled(value: Float, scale: Float) {
    putShort(
        Math.round(value * scale)
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    )
}

/** 値を 1/scale 刻みに丸める。足し引きを繰り返して 0.30000001 のように伸びるのを防ぐ。 */
private fun Float.roundToScale(scale: Float): Float = Math.round(this * scale) / scale

/** OBJECT ごとの射出速度の可変範囲。 */
private fun speedRangeFor(objectId: String): ClosedFloatingPointRange<Float> = when (objectId) {
    "hata" -> HATA_SPEED_MIN..HATA_SPEED_MAX
    "hoju" -> HOJU_SPEED_MIN..HOJU_SPEED_MAX
    else -> BAKETU_SPEED_MIN..BAKETU_SPEED_MAX
}

class MainActivity : ComponentActivity() {

    private val ip = "192.168.11.7"
    private val port = 5005

    // デフォルト経路のソケット。SEND_PATH_TRANSPORTS の経路が1本もつながっていないときだけ送信に使う。
    private lateinit var socket: DatagramSocket
    // つながっている経路ごとのソケット。ネットワークのコールバック（別スレッド）から増減する。
    private val sendPaths = CopyOnWriteArrayList<SendPath>()
    private val networkCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private lateinit var address: InetAddress
    private lateinit var logSocket: DatagramSocket
    // ES920LR（LoRa）でも同じパケットを送る。UDP とは別に、無線が送れる頻度で最新のものを送る。
    // UDP で最後に自己位置を受け取った時刻（System.nanoTime）。Es920Link が Wi-Fi の生死を見るのに使う。
    @Volatile
    private var lastPoseNanos = 0L

    // ES920LR の組の数は Es920Link.kt の ES920_LINK_COUNT で決まる（2026-11-09〜 組 A だけ）。
    private val es920: Es920Group by lazy {
        Es920Group(
            this, ::appendLog, ::applyLoraSettings,
            waitingSettings = { !settingsSynced },
            wifiAlive = { System.nanoTime() - lastPoseNanos < ES920_WIFI_TIMEOUT_MS * 1_000_000 },
            airtimeTest = ES920_AIRTIME_TEST,
            onStatus = ::applyLoraStatus,
            onLog = ::applyLoraLog,
            onEnemyEcho = { xyz -> enemyAck = xyz?.toList() },
            initialChannels = List(ES920_LINK_COUNT) { i -> prefs.getInt(es920ChannelPref(i), ES920_DEFAULT_CHANNELS[i]) },
            onScan = { link, results, scanningCh ->
                runOnUiThread {
                    loraScan = results
                    loraScanningCh = scanningCh
                    loraScanLink = link
                    if (scanningCh == 0) loraScanAtMs = System.currentTimeMillis()
                }
            },
            onStats = { link, stats -> runOnUiThread { loraStats = loraStats.toMutableList().also { it[link] = stats } } },
            // 画面ピン留め中は USB の許可ダイアログが出ないので、出す間だけピン留めを外す（USB_PIN_* の説明）
            beforePermissionDialog = { show ->
                runOnUiThread {
                    repinJob?.cancel()
                    if (isLockTaskActive()) stopLockTask()
                    lifecycleScope.launch {
                        delay(USB_UNPIN_SETTLE_MS)
                        show()
                    }
                }
            },
            afterPermissionDialog = {
                // コントローラーの画面でピン留めを外していたときだけ、ピン留めし直す（フィールド選択画面ではピン留めしない）。
                // もう 1 台の許可が続くかもしれないので、少し待ってから
                repinJob?.cancel()
                repinJob = lifecycleScope.launch {
                    delay(USB_REPIN_DELAY_MS)
                    if (!es920.permissionPending && currentScreen == ScreenState.CONTROLLER) startLockTaskMode()
                }
            }
        )
    }
    // USB の許可のあと、ピン留めし直すのを待っている処理
    private var repinJob: Job? = null

    private fun isLockTaskActive(): Boolean =
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    // フィールド選択画面の LORA CHANNEL の表示。リストは組ごと（0 = A、1 = B）。
    // 選んだチャンネル（保存する。モジュールの切り替えが終わるまでは es920.links[i].channel と違う）
    private var loraSelectedCh by mutableStateOf(ES920_DEFAULT_CHANNELS)
    // モジュールに設定してあるチャンネルと状態（LINK_REFRESH_MS ごとに写す）
    private var loraActiveCh by mutableStateOf(ES920_DEFAULT_CHANNELS)
    private var loraState by mutableStateOf(List(ES920_LINK_COUNT) { Es920State.NO_DEVICE })
    // パネルでチャンネルを選ぶ・スキャンする組
    private var loraEditLink by mutableStateOf(0)
    // スキャンの結果と、今試しているチャンネル（0 はスキャンしていない）、終わった時刻（0 はまだ）、スキャンした組。
    // 混み具合はどちらの組で調べても同じなので、最後のスキャンの結果を 1 つだけ持つ。
    private var loraScan by mutableStateOf<List<Es920ChannelScan>>(emptyList())
    private var loraScanningCh by mutableStateOf(0)
    private var loraScanAtMs by mutableStateOf(0L)
    private var loraScanLink by mutableStateOf(0)
    // 最後の 5 秒ごとの統計（コントローラーの画面で送っていた間のもの）
    private var loraStats by mutableStateOf(List<Es920Stats?>(ES920_LINK_COUNT) { null })

    /**
     * パネルで選んでいる組の ES920LR のチャンネルを選ぶ。保存して、モジュールを切り替える（PC には今のチャンネルで知らせてから移る）。
     * もう片方の組と同じチャンネルは選べない（お互いのキャリアセンスで送れなくなる）。
     */
    private fun selectLoraChannel(ch: Int) {
        if (ch !in es920.channels) return
        val link = loraEditLink
        val name = ES920_LINK_NAMES[link]
        val other = loraSelectedCh.withIndex().firstOrNull { it.index != link && it.value == ch }
        if (other != null) {
            appendLog("LORA $name CH $ch: used by ${ES920_LINK_NAMES[other.index]}")
            return
        }
        loraSelectedCh = loraSelectedCh.toMutableList().also { it[link] = ch }
        prefs.edit().putInt(es920ChannelPref(link), ch).apply()
        es920.links[link].requestChannel(ch)
        appendLog("LORA $name CH SELECT $ch")
    }

    // UDP の自己位置の 13 バイト目: PC が ES920LR の行を受け取れているか（Wi-Fi が生きている間の LoRa の生死）。
    // bit i が組 i（bit0 = A、bit1 = B）。
    @Volatile
    private var pcHearsLora = 0
    // invalid_target の番号。UDP の自己位置と ES920LR の状態の行のどちらかで番号が進んでいたら MOVE TARGET を解除する。
    // /log の行は落ちても送り直さないので、解除には使わない（2026-11-09）。
    private val invalidSeq = InvalidSeqTracker()

    /** invalid_target の番号が届いた。IO スレッドから呼ぶ。 */
    private fun onInvalidSeq(seq: Int) {
        if (invalidSeq.update(seq, SystemClock.elapsedRealtime())) {
            runOnUiThread { clearMoveTarget() }
        }
    }

    // 画面の LINK の表示（LINK_REFRESH_MS ごとに作り直す）。
    private var linkWifiOk by mutableStateOf(false)
    // LoRa は組ごと（0 = A、1 = B）。
    private var linkLoraText by mutableStateOf(ES920_LINK_NAMES.map { "LORA $it ---" })
    private var linkLoraOk by mutableStateOf(List(ES920_LINK_COUNT) { false })

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
    // 旗用の turn 調整値。移動先ごとに HATA_T / HATA_O / FAR（HATA_FAR）の 3 組。
    // 保存値が無ければ、分ける前の NEAR 用の値（HATA_T / HATA_O のみ）、HUTUU 用の値（それも無ければ hatat_turn*、hata_turn*）を引き継ぐ。
    private var hata_far_turnx by mutableStateOf(0.0f)
    private var hata_far_turny by mutableStateOf(0.0f)
    private var hata_far_turntheta by mutableStateOf(0.0f)
    private var hata_t_turnx by mutableStateOf(0.0f)
    private var hata_t_turny by mutableStateOf(0.0f)
    private var hata_t_turntheta by mutableStateOf(0.0f)
    private var hata_o_turnx by mutableStateOf(0.0f)
    private var hata_o_turny by mutableStateOf(0.0f)
    private var hata_o_turntheta by mutableStateOf(0.0f)
    private var hoju_turnx by mutableStateOf(0.0f)
    private var hoju_turny by mutableStateOf(0.0f)
    private var hoju_turntheta by mutableStateOf(0.0f)
    // 移動バケツ（idoubake）を狙うときの turn（2026-11-07 追加）。移動先は HOJU のまま。
    // ロボットの drive2 が使うのは theta [deg] だけ（移動バケツ中の向き = 計算した向き + これ。2026-11-08）。x / y は使わない。
    private var idoubake_turnx by mutableStateOf(0.0f)
    private var idoubake_turny by mutableStateOf(0.0f)
    private var idoubake_turntheta by mutableStateOf(0.0f)
    // 移動バケツ中か。true の間、TURN ADJUST は hoju turn の代わりに idoubake turn を動かす。
    // 2026-11-08 から、ロボットの drive2 と同じ規則で決める（drive2 は bit21 を見ず、SELECT と前後のスティックから自分で決める）:
    //   入る: 移動先 HOJU を選んでいる間に SELECT を押した（前後のスティックを倒していないときだけ。パスの途中でもよい。
    //         drive2 はパスの途中なら覚えておき、hoju に着いたら移動バケツにする）。もう一度押しても何もしない
    //   出る: 前後のスティックを倒した（onGenericMotionEvent）、移動先を選び直した / 解除した（applyMoveTarget / clearMoveTarget）、
    //         ロボットの invalid_target（clearMoveTarget）
    // 移動先（place）を増やすのではない。con.py へはボタン類の bit21 で送る（PC は使わない）。
    private var hojuIdoubake by mutableStateOf(false)
    // 移動バケツを狙うときの射出速度の差（2026-11-08 追加）。SPEED ADJUST の HOJU の DIF の行で動かす。
    // con.py へは設定値として送り、保存もする（ほかの turn / speed と同じ）。移動バケツ中の射出速度に足される。
    private var idoubakeSpeedDif by mutableStateOf(0.0f)

    // 相手のバケツの位置 [mm]（ENEMY_* の説明）。フィールド選択画面の +/- で選ぶ。スマホの中に保存して持つ（loadEnemy / changeEnemy）。
    // PC と同期していなくても（届いていなくても、PC が別の値を持っていても）この値を使い続け、PC の値で上書きしない。
    // PC から返ってくる値（enemyAck）は、届いたかを比べるのにだけ使う。
    private var enemyX by mutableStateOf(ENEMY_DEFAULT_X)
    private var enemyY by mutableStateOf(ENEMY_DEFAULT_Y)
    private var enemyZ by mutableStateOf(ENEMY_DEFAULT_Z)
    // PC が持っていると返してきた値（UDP の返事か ES920LR の状態の行）。null は PC が持っていない / まだ返事が無い。
    private var enemyAck by mutableStateOf<List<Int>?>(null)
    // PC に今の値が届いているか
    private val enemyConfirmed: Boolean
        get() = enemyAck == listOf(enemyX, enemyY, enemyZ)
    // 最後に UDP で送った時刻（SystemClock.elapsedRealtime）。値を変えたら 0 にしてすぐ送る
    @Volatile
    private var enemyLastSentMs = 0L
    // BS2 / BS3 用の turn 調整値。MOVE TARGET で BS2_* / BS3_* を選んでいるときの対象。
    // 以前は2つ共通の baketu_turn* だったので、保存値が無ければそれを引き継ぐ。
    private var bs2_turnx by mutableStateOf(0.0f)
    private var bs2_turny by mutableStateOf(0.0f)
    private var bs2_turntheta by mutableStateOf(0.0f)
    private var bs3_turnx by mutableStateOf(0.0f)
    private var bs3_turny by mutableStateOf(0.0f)
    private var bs3_turntheta by mutableStateOf(0.0f)

    // t0
    private var t0 by mutableStateOf(false)
    // retry（電源ボタンの横）。押すと RETRY_HOLD_SECONDS の間 true を送り続け、その間は押せない。
    // パケットのボタンの bit20（con.py の PACKET_BUTTON_KEYS の "retry"）。
    private var retry by mutableStateOf(false)
    private var retryRemainingSec by mutableStateOf(0)   // true を送る残りの秒（画面の表示用。切り上げ）
    private var retryJob: Job? = null

    // 画面上の物体選択（バケツ / 旗 / HOJU）3択排他。
    // 常にどれか1つが選択状態で、send() で "object" として送り続ける。
    // 未選択状態はなし（選択中のボタンを再タップしても解除されない）。
    private var selectedSpeed by mutableStateOf("baketu")

    // マップ横の一覧から選んだ移動先。
    // 基本は常にどれか1つが選択状態で、send() で "target" として送り続ける。
    // 選択中のボタンをもう一度押すか、Lスティック / Rスティックの押し込み（L3 / R3）で "none" に戻る。
    private var selectedPlace by mutableStateOf("")

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
    // つながっていると無線子機（ETH）経由の通信が使えなくなるので、
    // 画面の背景を点滅させて警告を出す。
    private var otherNetworkUp by mutableStateOf(false)

    // ????????????? String ("bluemap" ??? "redmap")???????????
    private var currentScreen by mutableStateOf(ScreenState.TEAM_SELECTION)
    private var selectedMapID by mutableStateOf("bluemap")
    private var isFlipped by mutableStateOf(true)

    // turn????
    private var selectedTurnTarget by mutableStateOf(TurnTarget.HATA)

    // turn???1????????
    private val turnXYStep = 2f
    private val turnThetaStep = 0.1f
    // 移動バケツの turn x / y は 1 回で 100 mm（PC の drive2 が狙う点（相手のバケツ）をこれだけ動かす。2026-11-10）。
    // 範囲はアプリでは止めない（PC が狙う点を 0〜1000 にクランプする。送るのは ±3276.7 まで）。
    private val idoubakeTurnXYStep = 100f
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
    private var bs2Speed1 by mutableStateOf(4.5f)
    private var hataFarHutuuSpeed1 by mutableStateOf(10.2f)
    private var bs2Speed2 by mutableStateOf(4.5f)
    private var hataFarHutuuSpeed2 by mutableStateOf(10.2f)
    private var bs2Speed3 by mutableStateOf(4.5f)
    private var hataFarHutuuSpeed3 by mutableStateOf(10.2f)
    // 旗の速度は NEAR（移動先が HATA_T / HATA_O。共通）と FAR（それ以外。HATA_FAR・未選択・手動）の 2 か所で、
    // それぞれ HUTUU / DEKA の 2 組。hataFarHutuuSpeed1..3 は上（バケツと並んでいるもの）。
    // FAR の保存値が無ければ、元の旗の速度 hata_speed* を引き継ぐ。
    private var hataFarDekaSpeed1 by mutableStateOf(10.2f)
    private var hataFarDekaSpeed2 by mutableStateOf(10.2f)
    private var hataFarDekaSpeed3 by mutableStateOf(10.2f)
    // NEAR の保存値が無ければ、分ける前の hatat_speed*（それも無ければ元の旗の速度 hata_speed*）を引き継ぐ。
    private var hataNearHutuuSpeed1 by mutableStateOf(10.2f)
    private var hataNearHutuuSpeed2 by mutableStateOf(10.2f)
    private var hataNearHutuuSpeed3 by mutableStateOf(10.2f)
    private var hataNearDekaSpeed1 by mutableStateOf(10.2f)
    private var hataNearDekaSpeed2 by mutableStateOf(10.2f)
    private var hataNearDekaSpeed3 by mutableStateOf(10.2f)

    // BS3 用のバケツの射出速度。bs2Speed1..3 が BS2 用。
    // 保存値が無ければ、そのサイドの BS2 の値を引き継ぐ（loadSettings / applyRemoteSettings）。
    private var bs3Speed1 by mutableStateOf(4.5f)
    private var bs3Speed2 by mutableStateOf(4.5f)
    private var bs3Speed3 by mutableStateOf(4.5f)

    // HOJU の射出速度（OBJECT が HOJU のとき。移動先 HOJU を選ぶと HOJU になる）。
    // 保存値が無ければ HOJU_DEFAULT_SPEED（con.py の SETTING_DEFAULTS と同じ）。
    private var hojuSpeed1 by mutableStateOf(HOJU_DEFAULT_SPEED)
    private var hojuSpeed2 by mutableStateOf(HOJU_DEFAULT_SPEED)
    private var hojuSpeed3 by mutableStateOf(HOJU_DEFAULT_SPEED)

    // 最後に MOVE TARGET で選んだ BS が BS3 なら true（起動直後は BS2）。
    // バケツの SPEED ADJUST の表示・調整対象と、ロボットが使う速度（packetObject()）がこれで決まる。
    private var baketuBs3 by mutableStateOf(false)

    // SPEED ADJUST で、バケツの BS2 / BS3 のうち撃つときに使わない方を出しているなら true。
    // 表示・調整の対象が変わるだけで、ロボットが使う速度は変わらない。移動先を変えたら false に戻す。
    private var baketuSpeedOtherBs by mutableStateOf(false)

    // SPEED ADJUST にバケツの BS3 の速度を出すなら true。
    private val baketuSpeedShowsBs3: Boolean
        get() = baketuBs3 != baketuSpeedOtherBs

    // 最後に MOVE TARGET で選んだ旗の移動先が HATA_O なら true（起動直後は HATA_T）。
    // TURN ADJUST の旗の NEAR 側で、HATA_T / HATA_O のどちらの turn を出すかに使う。
    // 旗の速度は HATA_T / HATA_O 共通。con.py へも送るが、ロボットでは使っていない（drive2 は移動先で turn を選ぶ）。
    private var hataO by mutableStateOf(false)

    // 旗の速度の組。true なら DEKA 用（起動直後は HUTUU）。DEKAHOJU を選ぶと DEKA になる。
    // × ボタンと画面の HUTUU / DEKA ボタンでも切り替えられる。DEKA は発射ボタンを押すまで移動では解除しない（applyMoveTarget）。
    // 旗の SPEED ADJUST の表示・調整対象と、ロボットが使う速度（packetObject()）がこれで決まる。turn は分けていない。
    private var hataDeka by mutableStateOf(false)

    // SPEED / TURN ADJUST で、旗の NEAR / FAR のうちロボットが使わない方を出しているなら true。
    // turn の NEAR は、HATA_T / HATA_O のうち hataO で決まる方。
    // 表示・調整の対象が変わるだけで、ロボットが使う turn・速度は今まで通り移動先で決まる。移動先を変えたら false に戻す。
    private var hataAdjustOtherRange by mutableStateOf(false)

    // SPEED / TURN ADJUST に旗の NEAR の値を出すなら true。
    private val hataAdjustNear: Boolean
        get() = (selectedPlace in HATA_NEAR_RANGE_TARGETS) != hataAdjustOtherRange

    // 旗の DEKA で発射ボタンを押したら true。次の移動先を選ぶと HUTUU に戻す（applyMoveTarget）。
    private var hataDekaFired = false

    // ?????1????????
    // バケツ（BS2 / BS3）は 0.01、それ以外（旗 / HOJU。移動バケツの DIF も含む）は 0.02。押したときの selectedSpeed で決まる。
    private val speedStep: Float
        get() = if (selectedSpeed == "baketu") 0.01f else 0.02f

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
    private fun startRetry() {
        if (retry) return
        retry = true
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            try {
                for (left in RETRY_HOLD_SECONDS downTo 1) {
                    retryRemainingSec = left
                    delay(1000L)
                }
            } finally {
                retry = false
                retryRemainingSec = 0
            }
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

    /**
     * SEND_PATH_TRANSPORTS の経路ごとに Network を要求し、つながったらその Network に紐付けたソケットを作る。
     * registerNetworkCallback ではなく requestNetwork にしているのは、要求している間は Android が
     * その Network を切らずに残すため（有線 LAN がつながっていると、使われていない Wi-Fi は切られることがある）。
     * ロボット側の AP はインターネットにつながらないので、INTERNET を条件から外す。
     */
    private fun registerSendPaths() {
        val cm = getSystemService(ConnectivityManager::class.java)
        SEND_PATH_TRANSPORTS.forEach { (name, transport) ->
            val request = NetworkRequest.Builder()
                .addTransportType(transport)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = addSendPath(name, network)
                override fun onLost(network: Network) = removeSendPath(name, network)
            }
            try {
                cm.requestNetwork(request, callback)
                networkCallbacks += callback
            } catch (e: Exception) {
                appendLog("PATH $name REQUEST ERROR: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private fun unregisterSendPaths() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallbacks.forEach { callback ->
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
        networkCallbacks.clear()
        sendPaths.forEach { it.close() }
        sendPaths.clear()
    }

    /** name の経路を network に紐付けたソケットで作り直し、受信ループを回す。 */
    private fun addSendPath(name: String, network: Network) {
        val newSocket = try {
            DatagramSocket().also { network.bindSocket(it) }
        } catch (e: Exception) {
            appendLog("PATH $name UP ERROR: ${e.javaClass.simpleName} ${e.message}")
            return
        }
        val path = SendPath(name, network, newSocket)
        // 同じ経路の古いソケットが残っていれば閉じる（送受信ループは close で抜ける）。
        sendPaths.filter { it.name == name }.forEach { old ->
            sendPaths.remove(old)
            old.close()
        }
        sendPaths += path
        appendLog("PATH $name UP")
        lifecycleScope.launch(Dispatchers.IO) { pathSendLoop(path) }
        lifecycleScope.launch(Dispatchers.IO) { receiveLoop(path.socket) }
    }

    /** 経路ごとの送信ループ。send() が outbox に入れたパケットを、この経路のソケットで送る。 */
    private suspend fun pathSendLoop(path: SendPath) {
        for (data in path.outbox) {
            try {
                path.socket.send(DatagramPacket(data, data.size, address, port))
            } catch (e: Exception) {
                if (!path.socket.isClosed) {
                    appendLog("SEND ERROR ${path.name}: ${e.javaClass.simpleName} ${e.message}")
                }
            }
        }
    }

    private fun removeSendPath(name: String, network: Network) {
        sendPaths.filter { it.name == name && it.network == network }.forEach { path ->
            sendPaths.remove(path)
            path.close()
            appendLog("PATH $name DOWN")
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
        writeLogFile(msg)
    }

    // ログファイルの間引き用。appendLog は IO スレッドと UI スレッドの両方から呼ばれるのでロックを取る。
    private val logFileLock = Any()
    private var lastFileLogMsg: String? = null
    private var lastFileLogTime = 0L
    private var suppressedFileLogCount = 0

    /**
     * LOG_FILE_NAME に1行追記する。通信が切れて SEND ERROR が 50Hz で出続けても膨らまないよう、
     * 同じメッセージは LOG_FILE_REPEAT_INTERVAL_MS に1回だけ書き、間引いた回数は次に書くときに残す。
     * LOG_FILE_MAX_BYTES を超えたら LOG_FILE_OLD_NAME に回して書き直す（古い方は1世代だけ残る）。
     */
    private fun writeLogFile(msg: String) {
        synchronized(logFileLock) {
            val now = System.currentTimeMillis()
            if (msg == lastFileLogMsg && now - lastFileLogTime < LOG_FILE_REPEAT_INTERVAL_MS) {
                suppressedFileLogCount++
                return
            }
            try {
                val file = File(filesDir, LOG_FILE_NAME)
                if (file.length() > LOG_FILE_MAX_BYTES) {
                    file.renameTo(File(filesDir, LOG_FILE_OLD_NAME))
                }
                val text = buildString {
                    if (suppressedFileLogCount > 0) {
                        append("$now: (previous message repeated $suppressedFileLogCount more times)\n")
                    }
                    append("$now: $msg\n")
                }
                openFileOutput(LOG_FILE_NAME, MODE_APPEND).use { it.write(text.toByteArray()) }
            } catch (_: Exception) {}
            lastFileLogMsg = msg
            lastFileLogTime = now
            suppressedFileLogCount = 0
        }
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

            // 前後のスティックを倒したら移動バケツを解除する（ロボットの drive2 と同じ規則。hojuIdoubake の説明）
            if (hojuIdoubake && hojuLineStickTilted()) {
                hojuIdoubake = false
                appendLog("HOJU TURN: HOJU (stick)")
            }

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
                // 移動バケツ中の theta（L2 / R2）だけは押しっぱなしで連続調整する（1回目は下の押した瞬間の処理）。
                if (idoubakeThetaHoldRepeat() && (currentL2 || currentR2)) {
                    startHataTurnRepeat()
                } else {
                    stopHataTurnRepeat()
                }

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

                // 旗は全部の軸、移動バケツは theta（L2 / R2）だけを連続調整する。
                val allAxes = selectedTurnTarget.usesHoldRepeat()
                if (!allAxes && !idoubakeThetaHoldRepeat()) break

                val dpadLeft = allAxes && left
                val dpadRight = allAxes && right
                val dpadUp = allAxes && up
                val dpadDown = allAxes && down
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

    /** 移動バケツの turn を調整中か。この間は theta（L2 / R2）だけ押しっぱなしで連続調整する。 */
    private fun idoubakeThetaHoldRepeat(): Boolean =
        selectedTurnTarget == TurnTarget.HOJU && hojuIdoubake

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentScreen == ScreenState.CONTROLLER &&
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        ) {
            when (keyCode) {
                // PS ボタンで OBJECT を バケツ <-> 旗 と切り替える
                KeyEvent.KEYCODE_BUTTON_MODE -> {
                    ps = true
                    if (event.repeatCount == 0) {
                        cycleSelectedObject()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_START -> { start = true; return true }
                // SELECT ボタンで、BS 系を選んでいれば BS ボタンを押したときと同じく次へ送る。
                // HATA_T / HATA_O を選んでいれば、もう一方に切り替える。HOJU なら hoju turn <-> idoubake turn を切り替える。
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    select = true
                    if (event.repeatCount == 0) {
                        cycleSelectTarget()
                    }
                    return true
                }
                // L3 / R3（スティック押し込み）で MOVE TARGET（selected target）を解除する
                KeyEvent.KEYCODE_BUTTON_THUMBL,
                KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                    if (event.repeatCount == 0) {
                        clearMoveTarget()
                    }
                    return true
                }
                // × で旗の速度の組を HUTUU <-> DEKA と切り替える。× はロボットにも今まで通り送る。
                KeyEvent.KEYCODE_BUTTON_A -> {
                    cross = true
                    if (event.repeatCount == 0) {
                        toggleHataDeka()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> { circle = true; onShootPressed(); return true }
                KeyEvent.KEYCODE_BUTTON_X -> { square = true; onShootPressed(); return true }
                KeyEvent.KEYCODE_BUTTON_Y -> { triangle = true; onShootPressed(); return true }
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
        // HATA_T / HATA_O の turn がまだ無い（分ける前の）設定値なら、NEAR 用の値を両方に引き継ぐ。
        // FAR も含めてそれも無ければ、HUTUU 用の値（それも無ければ hatat_turn*、hata_turn*）を引き継ぐ。DEKA 用の値は引き継がない。
        val hataTurnX = f("hata_hutuu_turnx", f("hatat_turnx", f("hata_turnx", hata_far_turnx)))
        val hataTurnY = f("hata_hutuu_turny", f("hatat_turny", f("hata_turny", hata_far_turny)))
        val hataTurnTheta = f("hata_hutuu_turntheta", f("hatat_turntheta", f("hata_turntheta", hata_far_turntheta)))
        hata_far_turnx = f("hata_far_turnx", hataTurnX)
        hata_far_turny = f("hata_far_turny", hataTurnY)
        hata_far_turntheta = f("hata_far_turntheta", hataTurnTheta)
        hata_t_turnx = f("hata_t_turnx", f("hata_near_turnx", hataTurnX))
        hata_t_turny = f("hata_t_turny", f("hata_near_turny", hataTurnY))
        hata_t_turntheta = f("hata_t_turntheta", f("hata_near_turntheta", hataTurnTheta))
        hata_o_turnx = f("hata_o_turnx", f("hata_near_turnx", hataTurnX))
        hata_o_turny = f("hata_o_turny", f("hata_near_turny", hataTurnY))
        hata_o_turntheta = f("hata_o_turntheta", f("hata_near_turntheta", hataTurnTheta))
        hoju_turnx = f("hoju_turnx", hoju_turnx)
        hoju_turny = f("hoju_turny", hoju_turny)
        hoju_turntheta = f("hoju_turntheta", hoju_turntheta)
        // 移動バケツの turn x / y は起動時に 0 から始める（con.py も 0 を送る。届いた値は使わない。2026-11-10）
        idoubake_turnx = 0.0f
        idoubake_turny = 0.0f
        idoubake_turntheta = f("idoubake_turntheta", idoubake_turntheta)
        idoubakeSpeedDif = f("idoubake_speed_dif", idoubakeSpeedDif)
        // bs2 / bs3_turn* がまだ無い（共通の baketu_turn* だった頃の）設定値なら、baketu_turn* を引き継ぐ。
        bs2_turnx = f("bs2_turnx", f("baketu_turnx", bs2_turnx))
        bs2_turny = f("bs2_turny", f("baketu_turny", bs2_turny))
        bs2_turntheta = f("bs2_turntheta", f("baketu_turntheta", bs2_turntheta))
        bs3_turnx = f("bs3_turnx", f("baketu_turnx", bs3_turnx))
        bs3_turny = f("bs3_turny", f("baketu_turny", bs3_turny))
        bs3_turntheta = f("bs3_turntheta", f("baketu_turntheta", bs3_turntheta))
        // DEKA の値が無ければ、なくしたデカ雑巾の速度 dekazoukin_speed* を引き継ぐ。
        // FAR / NEAR の値が無い（分ける前の）設定値なら、FAR は元の旗の速度 hata_speed*、
        // NEAR は HATA_T 用だった hatat_speed*（それも無ければ hata_speed*）を引き継ぐ。
        val hataSpeed1 = f("hata_speed1", hataFarHutuuSpeed1)
        val hataSpeed2 = f("hata_speed2", hataFarHutuuSpeed2)
        val hataSpeed3 = f("hata_speed3", hataFarHutuuSpeed3)
        val hatatSpeed1 = f("hatat_speed1", hataSpeed1)
        val hatatSpeed2 = f("hatat_speed2", hataSpeed2)
        val hatatSpeed3 = f("hatat_speed3", hataSpeed3)
        hataFarHutuuSpeed1 = f("hata_far_hutuu_speed1", hataSpeed1)
        hataFarHutuuSpeed2 = f("hata_far_hutuu_speed2", hataSpeed2)
        hataFarHutuuSpeed3 = f("hata_far_hutuu_speed3", hataSpeed3)
        hataFarDekaSpeed1 = f("hata_far_deka_speed1", f("dekazoukin_speed1", hataSpeed1))
        hataFarDekaSpeed2 = f("hata_far_deka_speed2", f("dekazoukin_speed2", hataSpeed2))
        hataFarDekaSpeed3 = f("hata_far_deka_speed3", f("dekazoukin_speed3", hataSpeed3))
        hataNearHutuuSpeed1 = f("hata_near_hutuu_speed1", hatatSpeed1)
        hataNearHutuuSpeed2 = f("hata_near_hutuu_speed2", hatatSpeed2)
        hataNearHutuuSpeed3 = f("hata_near_hutuu_speed3", hatatSpeed3)
        hataNearDekaSpeed1 = f("hata_near_deka_speed1", f("dekazoukin_speed1", hatatSpeed1))
        hataNearDekaSpeed2 = f("hata_near_deka_speed2", f("dekazoukin_speed2", hatatSpeed2))
        hataNearDekaSpeed3 = f("hata_near_deka_speed3", f("dekazoukin_speed3", hatatSpeed3))
        // bs2 / bs3_speed* が無い（前の名前の）設定値なら、baketu_speed* / baketu_bs3_speed* を引き継ぐ。
        bs2Speed1 = f("bs2_speed1", f("baketu_speed1", bs2Speed1))
        bs2Speed2 = f("bs2_speed2", f("baketu_speed2", bs2Speed2))
        bs2Speed3 = f("bs2_speed3", f("baketu_speed3", bs2Speed3))
        // BS3 の値が無い（BS3 を足す前の）設定値なら、BS2 の値を引き継ぐ。
        bs3Speed1 = f("bs3_speed1", f("baketu_bs3_speed1", bs2Speed1))
        bs3Speed2 = f("bs3_speed2", f("baketu_bs3_speed2", bs2Speed2))
        bs3Speed3 = f("bs3_speed3", f("baketu_bs3_speed3", bs2Speed3))
        hojuSpeed1 = f("hoju_speed1", hojuSpeed1)
        hojuSpeed2 = f("hoju_speed2", hojuSpeed2)
        hojuSpeed3 = f("hoju_speed3", hojuSpeed3)
        // 値を入れ終わってから同期済みにする（sendLoop が古い値を synced=true で送らないように）。
        settingsSynced = true
        // 手元の SharedPreferences はキャッシュとして更新しておく。
        saveSettings()
        appendLog("SETTINGS SYNCED: $mapId")
    }

    /**
     * LoRa で届いた con.py の設定値（87 バイト、送るパケットの 14〜100 バイトと同じ並び・倍率。最後の 1 バイトは空き）を反映する。
     * Wi-Fi が無く、ログ用ポート 5006 の "settings" が届かないときの SYNC 用。
     */
    private fun applyLoraSettings(mapIndex: Int, data: ByteArray) {
        val mapId = PACKET_MAP_IDS.getOrNull(mapIndex) ?: return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val settings = JSONObject().put("map_id", mapId)
        for ((name, scale) in SETTING_SCALES) {
            settings.put(name, (buf.short / scale).toDouble())
        }
        runOnUiThread { applyRemoteSettings(settings) }
    }

    /**
     * サイドごとのキーを読む。"<mapId>_<name>" が無ければ旧バージョンの
     * サイド共用キー "<name>" を使う（それも無ければ default）。
     */
    /**
     * ES920LR で届いた状態の行（自己位置 15 バイト（UDP の自己位置と同じ並び）、hojustate、invalid_target の番号）を反映する。
     * Wi-Fi で /log と自己位置が届いているときと同じことをする。IO スレッドから呼ばれる。
     * Wi-Fi の生死は UDP だけで見るので、lastPoseNanos は更新しない。
     */
    private fun applyLoraStatus(data: ByteArray, hoju: String, invalidSeqValue: Int) {
        if (data.size != POSE_PACKET_SIZE || data[0] != POSE_PACKET_VERSION) return
        val pose = ByteBuffer.wrap(data, 1, POSE_PACKET_SIZE - 1).order(ByteOrder.LITTLE_ENDIAN)
        val x = pose.int / POSE_XY_SCALE
        val y = pose.int / POSE_XY_SCALE
        val theta = pose.int / POSE_THETA_SCALE
        runOnUiThread {
            posX = x
            posY = y
            posTheta = theta
            if (hoju.isNotBlank()) hojuState = hoju
        }
        onInvalidSeq(invalidSeqValue)
    }

    /** ES920LR で届いたログの 1 行（hojustate 以外の /log）を SYSTEM LOG に出す。IO スレッドから呼ばれる。 */
    private fun applyLoraLog(line: String) {
        if (line.isBlank()) return
        runOnUiThread { logList = (logList + line).takeLast(50) }
    }

    /** 画面の LINK の表示を作り直す。UI スレッドで呼ぶ。 */
    private fun refreshLinkStatus() {
        val wifi = lastPoseNanos != 0L && System.nanoTime() - lastPoseNanos < LINK_WIFI_TIMEOUT_MS * 1_000_000
        linkWifiOk = wifi
        val now = System.currentTimeMillis()
        loraState = es920.links.map { it.state }
        loraActiveCh = es920.links.map { it.channel }
        val status = es920.links.map { link ->
            val head = "LORA ${link.name}"
            when (link.state) {
                Es920State.NO_DEVICE -> "$head NO USB" to false
                Es920State.STARTING -> "$head START..." to false
                Es920State.READY -> {
                    // スマホのモジュールが送れているか（返事 OK）
                    val txOk = now - link.lastSendOkMs < LINK_LORA_TIMEOUT_MS
                    // PC の行（状態・ログ・設定値）が届いているか。PC は届いた行に返すので、届けば PC にも届いている。
                    val pcRx = now - link.lastPcRxMs < LINK_LORA_TIMEOUT_MS
                    // UP: PC がこちらの行を受け取れているか。Wi-Fi が生きている間は PC が UDP で知らせてくる。
                    // DN: こちらが PC の行を受け取れているか。Wi-Fi が生きている間は PC が送ってこない（無駄な電波を出さない）ので "--"。
                    val up = if (wifi) ((pcHearsLora shr link.index) and 1) != 0 else pcRx
                    val down: Boolean? = if (wifi) null else pcRx
                    fun okNg(v: Boolean) = if (v) "OK" else "NG"
                    (if (!txOk) "$head TX NG" else "$head UP ${okNg(up)} DN ${down?.let(::okNg) ?: "--"}") to
                        (txOk && up && down != false)
                }
            }
        }
        linkLoraText = status.map { it.first }
        linkLoraOk = status.map { it.second }
    }

    private fun loadSideFloat(mapId: String, name: String, default: Float): Float =
        prefs.getFloat("${mapId}_$name", prefs.getFloat(name, default))

    /** 選んだサイドの turn 調整値と射出速度を読み込む。 */
    private fun loadSettings(mapId: String) {
        // HATA_T / HATA_O の turn の保存値が無ければ、分ける前の NEAR 用の値を両方に引き継ぐ。
        // FAR も含めてそれも無ければ、HUTUU 用の値（DEKA 用は引き継がない）、hatat_turn*、hata_turn* を引き継ぐ（どれも saveSettings で消す）。
        val hataTurnX = loadSideFloat(mapId, "hata_hutuu_turnx", loadSideFloat(mapId, "hatat_turnx", loadSideFloat(mapId, "hata_turnx", 0.0f)))
        val hataTurnY = loadSideFloat(mapId, "hata_hutuu_turny", loadSideFloat(mapId, "hatat_turny", loadSideFloat(mapId, "hata_turny", 0.0f)))
        val hataTurnTheta = loadSideFloat(mapId, "hata_hutuu_turntheta", loadSideFloat(mapId, "hatat_turntheta", loadSideFloat(mapId, "hata_turntheta", 0.0f)))
        hata_far_turnx = loadSideFloat(mapId, "hata_far_turnx", hataTurnX)
        hata_far_turny = loadSideFloat(mapId, "hata_far_turny", hataTurnY)
        hata_far_turntheta = loadSideFloat(mapId, "hata_far_turntheta", hataTurnTheta)
        hata_t_turnx = loadSideFloat(mapId, "hata_t_turnx", loadSideFloat(mapId, "hata_near_turnx", hataTurnX))
        hata_t_turny = loadSideFloat(mapId, "hata_t_turny", loadSideFloat(mapId, "hata_near_turny", hataTurnY))
        hata_t_turntheta = loadSideFloat(mapId, "hata_t_turntheta", loadSideFloat(mapId, "hata_near_turntheta", hataTurnTheta))
        hata_o_turnx = loadSideFloat(mapId, "hata_o_turnx", loadSideFloat(mapId, "hata_near_turnx", hataTurnX))
        hata_o_turny = loadSideFloat(mapId, "hata_o_turny", loadSideFloat(mapId, "hata_near_turny", hataTurnY))
        hata_o_turntheta = loadSideFloat(mapId, "hata_o_turntheta", loadSideFloat(mapId, "hata_near_turntheta", hataTurnTheta))
        hoju_turnx = loadSideFloat(mapId, "hoju_turnx", 0.0f)
        hoju_turny = loadSideFloat(mapId, "hoju_turny", 0.0f)
        hoju_turntheta = loadSideFloat(mapId, "hoju_turntheta", 0.0f)
        // 移動バケツの turn x / y は保存した値から戻さず 0 から始める（狙う点を動かす量。2026-11-10）。theta は今までどおり戻す
        idoubake_turnx = 0.0f
        idoubake_turny = 0.0f
        idoubake_turntheta = loadSideFloat(mapId, "idoubake_turntheta", 0.0f)
        idoubakeSpeedDif = loadSideFloat(mapId, "idoubake_speed_dif", 0.0f)
        // bs2 / bs3_turn* の保存値が無ければ、共通だった頃の baketu_turn* を引き継ぐ（baketu は saveSettings で消す）。
        val baketuTurnX = loadSideFloat(mapId, "baketu_turnx", 0.0f)
        val baketuTurnY = loadSideFloat(mapId, "baketu_turny", 0.0f)
        val baketuTurnTheta = loadSideFloat(mapId, "baketu_turntheta", 0.0f)
        bs2_turnx = loadSideFloat(mapId, "bs2_turnx", baketuTurnX)
        bs2_turny = loadSideFloat(mapId, "bs2_turny", baketuTurnY)
        bs2_turntheta = loadSideFloat(mapId, "bs2_turntheta", baketuTurnTheta)
        bs3_turnx = loadSideFloat(mapId, "bs3_turnx", baketuTurnX)
        bs3_turny = loadSideFloat(mapId, "bs3_turny", baketuTurnY)
        bs3_turntheta = loadSideFloat(mapId, "bs3_turntheta", baketuTurnTheta)
        // DEKA の保存値が無ければ、なくしたデカ雑巾の速度 dekazoukin_speed* を引き継ぐ。
        // FAR / NEAR の保存値が無ければ、FAR は元の旗の速度 hata_speed*、NEAR は HATA_T 用だった hatat_speed*
        // （それも無ければ hata_speed*）を引き継ぐ（hata / hatat / hatao_speed* は saveSettings で消す）。
        val hataSpeed1 = loadSideFloat(mapId, "hata_speed1", 10.2f)
        val hataSpeed2 = loadSideFloat(mapId, "hata_speed2", 10.2f)
        val hataSpeed3 = loadSideFloat(mapId, "hata_speed3", 10.2f)
        val hatatSpeed1 = loadSideFloat(mapId, "hatat_speed1", hataSpeed1)
        val hatatSpeed2 = loadSideFloat(mapId, "hatat_speed2", hataSpeed2)
        val hatatSpeed3 = loadSideFloat(mapId, "hatat_speed3", hataSpeed3)
        hataFarHutuuSpeed1 = loadSideFloat(mapId, "hata_far_hutuu_speed1", hataSpeed1)
        hataFarHutuuSpeed2 = loadSideFloat(mapId, "hata_far_hutuu_speed2", hataSpeed2)
        hataFarHutuuSpeed3 = loadSideFloat(mapId, "hata_far_hutuu_speed3", hataSpeed3)
        hataFarDekaSpeed1 = loadSideFloat(mapId, "hata_far_deka_speed1", loadSideFloat(mapId, "dekazoukin_speed1", hataSpeed1))
        hataFarDekaSpeed2 = loadSideFloat(mapId, "hata_far_deka_speed2", loadSideFloat(mapId, "dekazoukin_speed2", hataSpeed2))
        hataFarDekaSpeed3 = loadSideFloat(mapId, "hata_far_deka_speed3", loadSideFloat(mapId, "dekazoukin_speed3", hataSpeed3))
        hataNearHutuuSpeed1 = loadSideFloat(mapId, "hata_near_hutuu_speed1", hatatSpeed1)
        hataNearHutuuSpeed2 = loadSideFloat(mapId, "hata_near_hutuu_speed2", hatatSpeed2)
        hataNearHutuuSpeed3 = loadSideFloat(mapId, "hata_near_hutuu_speed3", hatatSpeed3)
        hataNearDekaSpeed1 = loadSideFloat(mapId, "hata_near_deka_speed1", loadSideFloat(mapId, "dekazoukin_speed1", hatatSpeed1))
        hataNearDekaSpeed2 = loadSideFloat(mapId, "hata_near_deka_speed2", loadSideFloat(mapId, "dekazoukin_speed2", hatatSpeed2))
        hataNearDekaSpeed3 = loadSideFloat(mapId, "hata_near_deka_speed3", loadSideFloat(mapId, "dekazoukin_speed3", hatatSpeed3))
        // bs2 / bs3_speed* の保存値が無ければ、前の名前の baketu_speed* / baketu_bs3_speed* を引き継ぐ（saveSettings で消す）。
        bs2Speed1 = loadSideFloat(mapId, "bs2_speed1", loadSideFloat(mapId, "baketu_speed1", 4.5f))
        bs2Speed2 = loadSideFloat(mapId, "bs2_speed2", loadSideFloat(mapId, "baketu_speed2", 4.5f))
        bs2Speed3 = loadSideFloat(mapId, "bs2_speed3", loadSideFloat(mapId, "baketu_speed3", 4.5f))
        // BS3 の保存値が無ければ、そのサイドの BS2 の値を引き継ぐ。
        bs3Speed1 = loadSideFloat(mapId, "bs3_speed1", loadSideFloat(mapId, "baketu_bs3_speed1", bs2Speed1))
        bs3Speed2 = loadSideFloat(mapId, "bs3_speed2", loadSideFloat(mapId, "baketu_bs3_speed2", bs2Speed2))
        bs3Speed3 = loadSideFloat(mapId, "bs3_speed3", loadSideFloat(mapId, "baketu_bs3_speed3", bs2Speed3))
        hojuSpeed1 = loadSideFloat(mapId, "hoju_speed1", HOJU_DEFAULT_SPEED)
        hojuSpeed2 = loadSideFloat(mapId, "hoju_speed2", HOJU_DEFAULT_SPEED)
        hojuSpeed3 = loadSideFloat(mapId, "hoju_speed3", HOJU_DEFAULT_SPEED)
        loadedSettingsMapID = mapId
        appendLog("SETTINGS LOADED: $mapId")
    }

    /** 読み込んだサイドのキーで書き戻す。チーム未選択なら何もしない。 */
    private fun saveSettings() {
        val mapId = loadedSettingsMapID ?: return
        prefs.edit()
            .putFloat("${mapId}_hata_far_turnx", hata_far_turnx)
            .putFloat("${mapId}_hata_far_turny", hata_far_turny)
            .putFloat("${mapId}_hata_far_turntheta", hata_far_turntheta)
            .putFloat("${mapId}_hata_t_turnx", hata_t_turnx)
            .putFloat("${mapId}_hata_t_turny", hata_t_turny)
            .putFloat("${mapId}_hata_t_turntheta", hata_t_turntheta)
            .putFloat("${mapId}_hata_o_turnx", hata_o_turnx)
            .putFloat("${mapId}_hata_o_turny", hata_o_turny)
            .putFloat("${mapId}_hata_o_turntheta", hata_o_turntheta)
            // HATA_T / HATA_O / FAR に引き継ぎ済みなので、分ける前の NEAR / HUTUU / DEKA 用の値、hatat / hatao_turn*、共通だった頃の hata_turn* は消す。
            .remove("${mapId}_hata_near_turnx")
            .remove("${mapId}_hata_near_turny")
            .remove("${mapId}_hata_near_turntheta")
            .remove("${mapId}_hata_hutuu_turnx")
            .remove("${mapId}_hata_hutuu_turny")
            .remove("${mapId}_hata_hutuu_turntheta")
            .remove("${mapId}_hata_deka_turnx")
            .remove("${mapId}_hata_deka_turny")
            .remove("${mapId}_hata_deka_turntheta")
            .remove("${mapId}_hatat_turnx")
            .remove("${mapId}_hatat_turny")
            .remove("${mapId}_hatat_turntheta")
            .remove("${mapId}_hatao_turnx")
            .remove("${mapId}_hatao_turny")
            .remove("${mapId}_hatao_turntheta")
            .remove("${mapId}_hata_turnx")
            .remove("${mapId}_hata_turny")
            .remove("${mapId}_hata_turntheta")
            // DEKAHATA はなくしたので、その turn 調整値も消す。
            .remove("${mapId}_dekahata_turnx")
            .remove("${mapId}_dekahata_turny")
            .remove("${mapId}_dekahata_turntheta")
            .putFloat("${mapId}_hoju_turnx", hoju_turnx)
            .putFloat("${mapId}_hoju_turny", hoju_turny)
            .putFloat("${mapId}_hoju_turntheta", hoju_turntheta)
            .putFloat("${mapId}_idoubake_turnx", idoubake_turnx)
            .putFloat("${mapId}_idoubake_turny", idoubake_turny)
            .putFloat("${mapId}_idoubake_turntheta", idoubake_turntheta)
            .putFloat("${mapId}_idoubake_speed_dif", idoubakeSpeedDif)
            .putFloat("${mapId}_bs2_turnx", bs2_turnx)
            .putFloat("${mapId}_bs2_turny", bs2_turny)
            .putFloat("${mapId}_bs2_turntheta", bs2_turntheta)
            .putFloat("${mapId}_bs3_turnx", bs3_turnx)
            .putFloat("${mapId}_bs3_turny", bs3_turny)
            .putFloat("${mapId}_bs3_turntheta", bs3_turntheta)
            // bs2 / bs3 に引き継ぎ済みなので、共通だった頃の baketu_turn* は消す。
            .remove("${mapId}_baketu_turnx")
            .remove("${mapId}_baketu_turny")
            .remove("${mapId}_baketu_turntheta")
            .putFloat("${mapId}_hata_far_hutuu_speed1", hataFarHutuuSpeed1)
            .putFloat("${mapId}_hata_far_hutuu_speed2", hataFarHutuuSpeed2)
            .putFloat("${mapId}_hata_far_hutuu_speed3", hataFarHutuuSpeed3)
            .putFloat("${mapId}_hata_far_deka_speed1", hataFarDekaSpeed1)
            .putFloat("${mapId}_hata_far_deka_speed2", hataFarDekaSpeed2)
            .putFloat("${mapId}_hata_far_deka_speed3", hataFarDekaSpeed3)
            .putFloat("${mapId}_hata_near_hutuu_speed1", hataNearHutuuSpeed1)
            .putFloat("${mapId}_hata_near_hutuu_speed2", hataNearHutuuSpeed2)
            .putFloat("${mapId}_hata_near_hutuu_speed3", hataNearHutuuSpeed3)
            .putFloat("${mapId}_hata_near_deka_speed1", hataNearDekaSpeed1)
            .putFloat("${mapId}_hata_near_deka_speed2", hataNearDekaSpeed2)
            .putFloat("${mapId}_hata_near_deka_speed3", hataNearDekaSpeed3)
            // FAR / NEAR に引き継ぎ済みなので、分ける前の hata / hatat / hatao_speed* は消す。
            .remove("${mapId}_hata_speed1")
            .remove("${mapId}_hata_speed2")
            .remove("${mapId}_hata_speed3")
            .remove("${mapId}_hatat_speed1")
            .remove("${mapId}_hatat_speed2")
            .remove("${mapId}_hatat_speed3")
            .remove("${mapId}_hatao_speed1")
            .remove("${mapId}_hatao_speed2")
            .remove("${mapId}_hatao_speed3")
            .putFloat("${mapId}_bs2_speed1", bs2Speed1)
            .putFloat("${mapId}_bs2_speed2", bs2Speed2)
            .putFloat("${mapId}_bs2_speed3", bs2Speed3)
            .putFloat("${mapId}_bs3_speed1", bs3Speed1)
            .putFloat("${mapId}_bs3_speed2", bs3Speed2)
            .putFloat("${mapId}_bs3_speed3", bs3Speed3)
            .putFloat("${mapId}_hoju_speed1", hojuSpeed1)
            .putFloat("${mapId}_hoju_speed2", hojuSpeed2)
            .putFloat("${mapId}_hoju_speed3", hojuSpeed3)
            // 引き継ぎ済みなので、前の名前の baketu_speed* / baketu_bs3_speed* と、なくしたデカ雑巾の速度は消す。
            .remove("${mapId}_baketu_speed1")
            .remove("${mapId}_baketu_speed2")
            .remove("${mapId}_baketu_speed3")
            .remove("${mapId}_baketu_bs3_speed1")
            .remove("${mapId}_baketu_bs3_speed2")
            .remove("${mapId}_baketu_bs3_speed3")
            .remove("${mapId}_dekazoukin_speed1")
            .remove("${mapId}_dekazoukin_speed2")
            .remove("${mapId}_dekazoukin_speed3")
            .commit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // turn 調整値と射出速度は赤ゾーン / 青ゾーンで別に保存するので、
        // ここでは読まない。サイドが決まった時点（onSelectTeam）で loadSettings() を呼ぶ。
        hideSystemUI()
        // 画面ピン留めは、フィールド選択画面を終えてコントローラーの画面に入るとき（onSelectTeam）にする。
        // フィールド選択画面の間は ES920LR の USB の許可ダイアログを出せるように、ピン留めしない。

        // Wi-Fi / モバイル回線の判定は起動時の1回だけ。
        otherNetworkUp = isOtherNetworkConnected()
        if (otherNetworkUp) appendLog("WIFI / MOBILE DATA ON: turn them off")

        // 無線子機（有線 LAN）と Wi-Fi の両方へ送れるよう、経路ごとのソケットを用意する。
        registerSendPaths()
        // ES920LR（LoRa）。挿さっていなければ 1 秒ごとに探し続ける。
        es920.start(lifecycleScope)
        loraSelectedCh = es920.links.map { it.channel }
        loraActiveCh = es920.links.map { it.channel }

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

        // 画面の LINK（Wi-Fi / LoRa の生死）の表示を作り直す。
        lifecycleScope.launch {
            while (isActive) {
                refreshLinkStatus()
                delay(LINK_REFRESH_MS)
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
            // 相手のバケツの位置は画面に関係なく（フィールド選択画面でも）送る
            launch { enemyLoop() }
            launch { receiveLoop(socket) }
            launch { logReceiveLoop() }
        }
        loadEnemy()
        setContent {
            when (currentScreen) {
                ScreenState.TEAM_SELECTION -> {
                    TeamSelectionScreen(
                        enemyX = enemyX, enemyY = enemyY, enemyZ = enemyZ,
                        enemyConfirmed = enemyConfirmed,
                        onEnemyChange = ::changeEnemy,
                        isFlipped = isFlipped,
                        networkWarning = otherNetworkUp,
                        onToggleFlip = { isFlipped = !isFlipped },
                        lora = LoraChannelUi(
                            profileName = es920.profileName,
                            channels = es920.channels,
                            freqMhz = es920::freqMhz,
                            links = List(ES920_LINK_COUNT) { i ->
                                LoraLinkUi(
                                    name = ES920_LINK_NAMES[i],
                                    selected = loraSelectedCh[i],
                                    active = loraActiveCh[i],
                                    state = loraState[i],
                                    stats = loraStats[i]
                                )
                            },
                            editLink = loraEditLink,
                            scan = loraScan,
                            scanningCh = loraScanningCh,
                            scanLink = loraScanLink,
                            scanAtMs = loraScanAtMs
                        ),
                        onSelectLoraLink = { loraEditLink = it },
                        onScanLora = { es920.links[loraEditLink].requestScan() },
                        onSelectLoraChannel = ::selectLoraChannel,
                        onSelectTeam = { mapStr ->
                            // スキャン中ならやめる（操作を送れるように）
                            es920.links.forEach { it.cancelScan() }
                            selectedMapID = mapStr
                            loadSettings(mapStr)
                            currentScreen = ScreenState.CONTROLLER
                            startLockTaskMode()
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
                        hataFarTurnX = hata_far_turnx, hataFarTurnY = hata_far_turny, hataFarTurnTheta = hata_far_turntheta,
                        hataTTurnX = hata_t_turnx, hataTTurnY = hata_t_turny, hataTTurnTheta = hata_t_turntheta,
                        hataOTurnX = hata_o_turnx, hataOTurnY = hata_o_turny, hataOTurnTheta = hata_o_turntheta,
                        hojuTurnX = hoju_turnx, hojuTurnY = hoju_turny, hojuTurnTheta = hoju_turntheta,
                        idoubakeTurnX = idoubake_turnx, idoubakeTurnY = idoubake_turny, idoubakeTurnTheta = idoubake_turntheta,
                        hojuIdoubake = hojuIdoubake,
                        bs2TurnX = bs2_turnx, bs2TurnY = bs2_turny, bs2TurnTheta = bs2_turntheta,
                        bs3TurnX = bs3_turnx, bs3TurnY = bs3_turny, bs3TurnTheta = bs3_turntheta,
                        selectedTurnTarget = selectedTurnTarget,
                        t0 = t0,
                        onToggleT0 = { toggleT0() },
                        retry = retry,
                        retryRemainingSec = retryRemainingSec,
                        onRetry = { startRetry() },
                        left = left, right = right, up = up, down = down,
                        circle = circle, square = square, cross = cross, triangle = triangle,
                        l1 = l1, l2 = l2, r1 = r1, r2 = r2,
                        selectedSpeed = selectedSpeed,
                        onSelectObject = { value ->
                            // ?3?????????????1?????????????????????
                            selectedSpeed = value
                        },
                        selectedPlace = selectedPlace,
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
                        onSpeedAllIncrease = { adjustSelectedObjectSpeedAll(speedStep) },
                        onSpeedAllDecrease = { adjustSelectedObjectSpeedAll(-speedStep) },
                        idoubakeSpeedDif = idoubakeSpeedDif,
                        onSpeedDifIncrease = { adjustIdoubakeSpeedDif(speedStep) },
                        onSpeedDifDecrease = { adjustIdoubakeSpeedDif(-speedStep) },
                        logList = logList,
                        hojuState = hojuState,
                        t0locked = t0Locked,
                        settingsSynced = settingsSynced,
                        baketuBs3 = baketuBs3,
                        baketuSpeedBs3 = baketuSpeedShowsBs3,
                        onShowBaketuSpeedBs = { bs3 -> showBaketuSpeedBs(bs3) },
                        hataDeka = hataDeka,
                        onSelectHataDeka = { deka -> if (deka != hataDeka) toggleHataDeka() },
                        hataAdjustNear = hataAdjustNear,
                        hataO = hataO,
                        onShowHataAdjustRange = { near -> showHataAdjustRange(near) },
                        linkWifiOk = linkWifiOk,
                        linkLoraText = linkLoraText,
                        linkLoraOk = linkLoraOk
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
     * SEND_INTERVAL_MS（20ms = 50Hz）ごとに、操作と設定値をまとめて UDP で送る。
     * lifecycleScope.launch(Dispatchers.IO) の中から呼ぶ。
     */
    private suspend fun CoroutineScope.sendLoop() {
        while (isActive) {
            // ES920LR を挿したあとの確認などで裏に回っている間（sendPausedForUsb）は送らない（onPause の説明）
            if (currentScreen == ScreenState.CONTROLLER && !sendPausedForUsb) {
                send(
                    selectedMapID,
                    if (lowGain) vy / 2f else vy,
                    if (lowGain) vx / 2f else vx,
                    if (lowGain) w / 2f else w,
                    hataFarHutuuSpeed1, bs2Speed1,
                    hataFarHutuuSpeed2, bs2Speed2,
                    hataFarHutuuSpeed3, bs2Speed3,
                    bs3Speed1, bs3Speed2, bs3Speed3,
                    hata_far_turnx, hata_far_turny, hata_far_turntheta,
                    hata_t_turnx, hata_t_turny, hata_t_turntheta,
                    hata_o_turnx, hata_o_turny, hata_o_turntheta, hataO,
                    hataNearHutuuSpeed1, hataNearHutuuSpeed2, hataNearHutuuSpeed3,
                    hataNearDekaSpeed1, hataNearDekaSpeed2, hataNearDekaSpeed3,
                    hataFarDekaSpeed1, hataFarDekaSpeed2, hataFarDekaSpeed3,
                    hoju_turnx, hoju_turny, hoju_turntheta,
                    bs2_turnx, bs2_turny, bs2_turntheta,
                    bs3_turnx, bs3_turny, bs3_turntheta,
                    hojuSpeed1, hojuSpeed2, hojuSpeed3,
                    idoubake_turnx, idoubake_turny, idoubake_turntheta, hojuIdoubake,
                    idoubakeSpeedDif,
                    packetObject(), selectedPlace,
                    hataLaser, hojuLaser, t0, retry,
                    left, right, up, down,
                    circle, triangle, square, cross,
                    l1, l2, r1, r2,
                    start, select, ps,
                    settingsSynced
                )
            }
            delay(SEND_INTERVAL_MS)
        }
    }

    /**
     * ??????????????????????????????
     */
    // ------------------------------------------------------------------ 相手のバケツの位置（ENEMY_* の説明）

    /** 保存してある相手のバケツの位置を読む（onCreate）。 */
    private fun loadEnemy() {
        enemyX = prefs.getInt("enemy_x", ENEMY_DEFAULT_X).coerceIn(ENEMY_X_RANGE)
        enemyY = prefs.getInt("enemy_y", ENEMY_DEFAULT_Y).coerceIn(ENEMY_Y_RANGE)
        enemyZ = prefs.getInt("enemy_z", ENEMY_DEFAULT_Z).coerceIn(ENEMY_Z_RANGE)
    }

    /** フィールド選択画面の +/-。axis は 0 = x、1 = y、2 = z。変えたら保存して、すぐ送る。 */
    private fun changeEnemy(axis: Int, delta: Int) {
        when (axis) {
            0 -> enemyX = (enemyX + delta).coerceIn(ENEMY_X_RANGE)
            1 -> enemyY = (enemyY + delta).coerceIn(ENEMY_Y_RANGE)
            else -> enemyZ = (enemyZ + delta).coerceIn(ENEMY_Z_RANGE)
        }
        // 画面から離れるとすぐプロセスごと終了する（exitOnPause）ので、apply ではなく commit でその場で書く（saveSettings と同じ）
        prefs.edit().putInt("enemy_x", enemyX).putInt("enemy_y", enemyY).putInt("enemy_z", enemyZ).commit()
        enemyLastSentMs = 0L
    }

    /** 上りの 8 バイト。 */
    private fun enemyUpBytes(): ByteArray {
        val b = ByteBuffer.allocate(ENEMY_UP_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put(ENEMY_TAG)
        b.putShort(enemyX.toShort())
        b.putShort(enemyY.toShort())
        b.putShort(enemyZ.toShort())
        val data = b.array()
        var sum = 0
        for (i in 1 until ENEMY_UP_SIZE - 1) sum += data[i].toInt() and 0xFF
        data[ENEMY_UP_SIZE - 1] = sum.toByte()
        return data
    }

    /** UDP で届いた PC の返事（9 バイト）。和が合えば、PC が持っている値として覚える。 */
    private fun applyEnemyAck(buf: ByteArray) {
        var sum = 0
        for (i in 1 until ENEMY_ACK_SIZE - 1) sum += buf[i].toInt() and 0xFF
        if ((sum and 0xFF) != (buf[ENEMY_ACK_SIZE - 1].toInt() and 0xFF)) {
            appendLog("RECV ERROR: enemy ack checksum")
            return
        }
        val b = ByteBuffer.wrap(buf, 1, ENEMY_ACK_SIZE - 2).order(ByteOrder.LITTLE_ENDIAN)
        val has = b.get().toInt() != 0
        val xyz = listOf(b.short.toInt(), b.short.toInt(), b.short.toInt())
        enemyAck = if (has) xyz else null
    }

    /**
     * 相手のバケツの位置を、PC に届いたと分かるまで ENEMY_RESEND_MS ごとに UDP で送り直し続ける
     * （届いてからも ENEMY_CHECK_MS ごとに送って確かめる）。ES920LR には Es920Group.setEnemy で渡し、
     * コントローラーの画面で操作を送る行に載せてもらう。画面に関係なく回す。
     */
    private suspend fun CoroutineScope.enemyLoop() {
        var wasConfirmed = false
        while (isActive) {
            val up = enemyUpBytes()
            val confirmed = enemyConfirmed
            es920.setEnemy(up, confirmed)
            if (confirmed != wasConfirmed) {
                appendLog(
                    if (confirmed) "ENEMY TARGET OK: X $enemyX Y $enemyY Z $enemyZ"
                    else "ENEMY TARGET SENDING: X $enemyX Y $enemyY Z $enemyZ"
                )
                wasConfirmed = confirmed
            }
            val now = SystemClock.elapsedRealtime()
            if (now - enemyLastSentMs >= if (confirmed) ENEMY_CHECK_MS else ENEMY_RESEND_MS) {
                enemyLastSentMs = now
                sendUdpDirect(up)
            }
            delay(50)
        }
    }

    /**
     * 操作のパケットとは別のものを、つながっている経路すべてへ UDP で送る。経路の outbox は最新の 1 つしか残さないので
     * （操作のパケットと入れ替わらないよう）使わず、その場で送る。IO スレッドから呼ぶ。
     */
    private fun sendUdpDirect(data: ByteArray) {
        val sockets = sendPaths.toList().map { it.socket }.ifEmpty { listOf(socket) }
        for (sock in sockets) {
            try {
                sock.send(DatagramPacket(data, data.size, address, port))
            } catch (e: Exception) {
                if (!sock.isClosed) appendLog("SEND ERROR (enemy): ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private suspend fun CoroutineScope.receiveLoop(sock: DatagramSocket) {
        // 経路ごとのソケットでも回すので、ソケットが閉じられたら抜ける（経路が切れたとき）。
        val buf = ByteArray(1024)
        while (isActive && !sock.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet) // ??????I/O?Dispatchers.IO???????

                // 相手のバケツの位置の返事（ENEMY_* の説明）。自己位置ではないので往復時間には数えない
                if (packet.length == ENEMY_ACK_SIZE && buf[0] == ENEMY_TAG) {
                    applyEnemyAck(buf)
                    continue
                }
                // 自己位置（バイナリ。並びは POSE_PACKET_* を参照）
                if (packet.length == POSE_PACKET_SIZE && buf[0] == POSE_PACKET_VERSION) {
                    val pose = ByteBuffer.wrap(buf, 1, POSE_PACKET_SIZE - 1).order(ByteOrder.LITTLE_ENDIAN)
                    posX = pose.int / POSE_XY_SCALE
                    posY = pose.int / POSE_XY_SCALE
                    posTheta = pose.int / POSE_THETA_SCALE
                    pcHearsLora = pose.get().toInt() and 0xFF
                    onInvalidSeq(pose.get().toInt() and 0xFF)
                    lastPoseNanos = System.nanoTime()
                } else {
                    appendLog("RECV ERROR: unknown pose packet (len=${packet.length})")
                }
                val rtt = (System.nanoTime() - sendTime) / 1_000_000
                // 受信ループが経路の数だけ並行して回るのでロックを取る。
                synchronized(rttList) {
                    if (rttList.size > 50) rttList.removeAt(0)
                    rttList.add(rtt)
                }
            } catch (e: Exception) {
                // close()???SocketException?Activity???????????????????
                if (isActive && !sock.isClosed) {
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

                    receivedLog.split("\n")
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
                        .forEach { line ->
                            // invalid_target の行はログに出すだけ。MOVE TARGET の解除は番号で決める（onInvalidSeq）。
                            // ここでも解除すると、遅れて届いた行が選び直した移動先を消してしまう。
                            if (line.startsWith("hojustate:")) {
                                // 画面には "hojustate:" を外した中身だけを出す。
                                // hojustate???UI????SYSTEM LOG???????
                                latestHojuState = line.removePrefix("hojustate:").trim()
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
     * MOVE TARGET の選択。hata_t・hata_o / hoju なら turn 調整の対象もそれに合わせる。
     * 選択中のボタンをもう一度押すと解除する（L3 / R3 と同じ扱い）。
     * ただし CYCLING_MOVE_TARGETS のボタン（BS2 / BS3）は解除せず、押すたびに次の移動先へ送って一周する。
     * value は画面のボタンの id（"bs2" など）でも、送る移動先の id（"bs2_a" など）でもよい。
     * hata_t・hata_o / hoju に切り替わったときは、対応するレーザーを1回だけ ON にする
     * （ON のまま固定はしないので、そのあと手動で OFF にできる）。
     * OBJECT も移動先に合わせて切り替える（BS 系 -> バケツ / hata_t・hata_o・hata_far・dekahoju -> 旗 / hoju -> HOJU）。
     * DEKAHOJU を選ぶと旗の速度を DEKA にする。DEKA は発射ボタンを押すまで移動では解除せず、押したあとの次の移動先で HUTUU に戻す（hataDekaFired）。
     * hata_t・hata_o・hata_far は turn 調整の対象・レーザー・OBJECT の扱いが共通。
     * turn は hata_t・hata_o・hata_far でそれぞれ別。速度は hata_t・hata_o が NEAR、hata_far が FAR（HATA_NEAR_RANGE_TARGETS）で、さらに × で HUTUU / DEKA を選ぶ。
     * こちらも選んだ瞬間に1回だけなので、そのあと手動や PS ボタンで変えられる。
     */
    private fun selectMoveTarget(value: String) {
        // 順送りのボタン。選択中なら次へ（最後の次は先頭）、そうでなければ先頭から。
        CYCLING_MOVE_TARGETS[value]?.let { cycle ->
            val index = cycle.indexOf(selectedPlace)
            if (index >= 0) {
                applyMoveTarget(cycle[(index + 1) % cycle.size])
            } else if (canPressMoveTarget(value, selectedPlace, unlockedTargets)) {
                applyMoveTarget(cycle.first())
            }
            return
        }
        // 同じボタンをもう一度押したら解除する。選択中なのでロック判定は見るまでもない。
        if (selectedPlace == value) {
            clearMoveTarget()
            return
        }
        // 押せない移動先はここで弾く。
        if (!canPressMoveTarget(value, selectedPlace, unlockedTargets)) return
        applyMoveTarget(value)
    }

    /** 移動先を value にして、それに合わせて turn 調整の対象・レーザー・OBJECT を切り替える。 */
    private fun applyMoveTarget(value: String) {
        selectedPlace = value
        // idoubake は HOJU の位置決め中に SELECT で選ぶもの。移動先を選び直したら hoju turn に戻す。
        hojuIdoubake = false
        hataAdjustOtherRange = false
        baketuSpeedOtherBs = false
        if (value == "dekahoju") {
            hataDeka = true
        } else if (hataDekaFired) {
            hataDeka = false
        }
        hataDekaFired = false
        // 選ばれていない方のレーザーは位置決めが解けたとみなして落とす。
        if (value !in HATA_TARGETS) hataLaser = false
        if (value != "hoju") hojuLaser = false
        when (value) {
            in HATA_TARGETS -> {
                hataO = value == "hata_o"
                setTurnTarget(TurnTarget.HATA)
                // 選んだ瞬間に1回だけ点ける。以降は固定しないので手動で消せる。
                hataLaser = true
                selectedSpeed = "hata"
            }
            "hoju" -> {
                setTurnTarget(TurnTarget.HOJU)
                hojuLaser = true
                selectedSpeed = "hoju"
            }
            "dekahoju" -> {
                selectedSpeed = "hata"
            }
            in BS_TARGETS -> {
                setTurnTarget(if (value in BS3_TARGETS) TurnTarget.BS3 else TurnTarget.BS2)
                selectedSpeed = "baketu"
                // バケツの射出速度は、最後に選んだ BS のものを使う（解除しても戻さない）。
                baketuBs3 = value in BS3_TARGETS
            }
        }
    }

    /** L3 / R3 で MOVE TARGET を解除する。位置決めをやめるのでレーザーも両方消す。 */
    private fun clearMoveTarget() {
        selectedPlace = "none"
        hojuIdoubake = false
        hataAdjustOtherRange = false
        baketuSpeedOtherBs = false
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
     * SELECT ボタンで MOVE TARGET を順送りする。選択中の移動先が順送りのボタン（BS2 / BS3）のものなら、
     * そのボタンを押したときと同じく次へ送る。HATA_T / HATA_O ならもう一方に切り替える。
     * HOJU なら、移動バケツ（idoubake）にする（移動先は HOJU のまま。もう一度押しても戻さない。hojuIdoubake の説明）。
     * それ以外（none・HATA_FAR 含む）のときは何もしない。
     */
    private fun cycleSelectTarget() {
        if (selectedPlace == "hoju") {
            // ロボットの drive2 と同じく、前後のスティックを倒している間は入らない。入っていれば何もしない
            if (!hojuIdoubake && !hojuLineStickTilted()) {
                hojuIdoubake = true
                appendLog("HOJU TURN: IDOUBAKE")
                // L2 / R2 を押したまま入ったときも連続調整を始める
                if (l2 || r2) startHataTurnRepeat()
            }
            return
        }
        // HATA_T / HATA_O を選んでいれば、もう一方に切り替える（画面で押したときと同じ扱い）。
        if (selectedPlace in HATA_NEAR_TARGETS) {
            selectMoveTarget(HATA_NEAR_TARGETS.first { it != selectedPlace })
            return
        }
        val buttonId = CYCLING_MOVE_TARGETS.entries
            .firstOrNull { selectedPlace in it.value }?.key ?: return
        selectMoveTarget(buttonId)
    }

    /** 前後のスティック（PC へ送る値。controller_val.y）を HOJU_LINE_DEADZONE 以上倒しているか。 */
    private fun hojuLineStickTilted(): Boolean =
        Math.abs(if (lowGain) vx / 2f else vx) >= HOJU_LINE_DEADZONE

    /**
     * PS ボタンで OBJECT を バケツ -> 旗 -> HOJU -> バケツ と順送りする。
     * 並びは FIELD_OBJECTS の順番に従うので、画面のボタンの並びと一致する。
     */
    private fun cycleSelectedObject() {
        val ids = FIELD_OBJECTS.map { it.id }
        if (ids.isEmpty()) return
        val nextIndex = (ids.indexOf(selectedSpeed) + 1) % ids.size
        selectedSpeed = ids[nextIndex]
    }

    private fun adjustSelectedTurn(
        dx: Float = 0f,
        dy: Float = 0f,
        dtheta: Float = 0f
    ) {
        // con.py の保存値を受け取るまでは調整しない（受け取った時点で上書きされるため）。
        if (!settingsSynced) return
        // 送るときの刻みに丸めておく（0.1 の足し引きで 0.30000001 のように伸びないように）。
        fun xy(v: Float, d: Float) = (v + d).roundToScale(TURN_XY_SCALE)
        fun theta(v: Float, d: Float) = (v + d).roundToScale(TURN_THETA_SCALE)
        when (selectedTurnTarget) {
            // 旗は SPEED ADJUST と同じ方（NEAR / FAR）を動かす。NEAR は HATA_T / HATA_O のうち hataO で決まる方。
            TurnTarget.HATA -> when {
                !hataAdjustNear -> {
                    hata_far_turnx = xy(hata_far_turnx, dx)
                    hata_far_turny = xy(hata_far_turny, dy)
                    hata_far_turntheta = theta(hata_far_turntheta, dtheta)
                }
                hataO -> {
                    hata_o_turnx = xy(hata_o_turnx, dx)
                    hata_o_turny = xy(hata_o_turny, dy)
                    hata_o_turntheta = theta(hata_o_turntheta, dtheta)
                }
                else -> {
                    hata_t_turnx = xy(hata_t_turnx, dx)
                    hata_t_turny = xy(hata_t_turny, dy)
                    hata_t_turntheta = theta(hata_t_turntheta, dtheta)
                }
            }
            // idoubake の間は、ロボットが使っている idoubake turn を動かす。
            // x / y は狙う点を動かす量で、1 回 idoubakeTurnXYStep（100 mm）。theta はほかの turn と同じ刻み。
            TurnTarget.HOJU -> if (hojuIdoubake) {
                idoubake_turnx = xy(idoubake_turnx, Math.signum(dx) * idoubakeTurnXYStep)
                idoubake_turny = xy(idoubake_turny, Math.signum(dy) * idoubakeTurnXYStep)
                idoubake_turntheta = theta(idoubake_turntheta, dtheta)
            } else {
                hoju_turnx = xy(hoju_turnx, dx)
                hoju_turny = xy(hoju_turny, dy)
                hoju_turntheta = theta(hoju_turntheta, dtheta)
            }
            TurnTarget.BS2 -> {
                bs2_turnx = xy(bs2_turnx, dx)
                bs2_turny = xy(bs2_turny, dy)
                bs2_turntheta = theta(bs2_turntheta, dtheta)
            }
            TurnTarget.BS3 -> {
                bs3_turnx = xy(bs3_turnx, dx)
                bs3_turny = xy(bs3_turny, dy)
                bs3_turntheta = theta(bs3_turntheta, dtheta)
            }
        }
    }

    /**
     * 選択中の OBJECT に対応する column（1..3）の射出速度を返す。
     * SPEED ADJUST はこの値を表示するので、OBJECT を切り替えると調整対象も切り替わる。
     */
    private fun selectedObjectSpeed(column: Int): Float = when (selectedSpeed) {
        // 旗は、移動先が HATA_T / HATA_O なら NEAR、それ以外なら FAR の、今の HUTUU / DEKA の組を出す。
        // ロボットへは同じ選び方で OBJECT に入れて送る（packetObject()）。NEAR / FAR ボタンでもう一方を出しているときだけ、撃つ組と違う。
        "hata" -> when {
            hataAdjustNear -> if (hataDeka) {
                when (column) {
                    1 -> hataNearDekaSpeed1
                    2 -> hataNearDekaSpeed2
                    else -> hataNearDekaSpeed3
                }
            } else {
                when (column) {
                    1 -> hataNearHutuuSpeed1
                    2 -> hataNearHutuuSpeed2
                    else -> hataNearHutuuSpeed3
                }
            }
            else -> if (hataDeka) {
                when (column) {
                    1 -> hataFarDekaSpeed1
                    2 -> hataFarDekaSpeed2
                    else -> hataFarDekaSpeed3
                }
            } else {
                when (column) {
                    1 -> hataFarHutuuSpeed1
                    2 -> hataFarHutuuSpeed2
                    else -> hataFarHutuuSpeed3
                }
            }
        }
        "hoju" -> when (column) {
            1 -> hojuSpeed1
            2 -> hojuSpeed2
            else -> hojuSpeed3
        }
        // バケツは最後に選んだ BS（baketuBs3）の方を出す。BS2 / BS3 ボタンでもう一方を出しているときだけ、撃つ組と違う。
        else -> if (baketuSpeedShowsBs3) {
            when (column) {
                1 -> bs3Speed1
                2 -> bs3Speed2
                else -> bs3Speed3
            }
        } else {
            when (column) {
                1 -> bs2Speed1
                2 -> bs2Speed2
                else -> bs2Speed3
            }
        }
    }

    /** × ボタン: 旗の速度の組を HUTUU <-> DEKA と切り替える。 */
    private fun toggleHataDeka() {
        hataDeka = !hataDeka
        hataDekaFired = false
    }

    /** 発射ボタン（□ △ ○）: 旗の DEKA で押したら、次の移動先で HUTUU に戻す。 */
    private fun onShootPressed() {
        if (selectedSpeed == "hata" && hataDeka) hataDekaFired = true
    }

    /** SPEED ADJUST の NEAR / FAR ボタン: 旗の turn・速度のどちらを表示・調整するかを選ぶ。ロボットが使う値は変わらない。 */
    private fun showHataAdjustRange(near: Boolean) {
        hataAdjustOtherRange = near != (selectedPlace in HATA_NEAR_RANGE_TARGETS)
    }

    /** SPEED ADJUST の BS2 / BS3 ボタン: バケツの速度のどちらを表示・調整するかを選ぶ。撃つときの速度は変わらない。 */
    private fun showBaketuSpeedBs(bs3: Boolean) {
        baketuSpeedOtherBs = bs3 != baketuBs3
    }

    /**
     * SPEED ADJUST の ALL: 表示中の組の C1〜C3 をまとめて delta 分動かす。
     * どれか 1 つでも範囲を出るなら、どれも動かさない（C1〜C3 の差を保つため）。
     */
    private fun adjustSelectedObjectSpeedAll(delta: Float) {
        if (!settingsSynced) return
        val range = speedRangeFor(selectedSpeed)
        // speedStep の足し引きで誤差が乗るので、端との比較は少し余裕を持たせる（SpeedAdjustRow と同じ）。
        val fits = (1..3).all { column ->
            val v = (selectedObjectSpeed(column) + delta).roundToScale(SPEED_SCALE)
            v >= range.start - 1e-4f && v <= range.endInclusive + 1e-4f
        }
        if (!fits) return
        for (column in 1..3) adjustSelectedObjectSpeed(column, delta)
    }

    /** 移動バケツの射出速度の差を delta 分動かす（IDOUBAKE_SPEED_DIF_MIN〜MAX）。 */
    private fun adjustIdoubakeSpeedDif(delta: Float) {
        if (!settingsSynced) return
        idoubakeSpeedDif = (idoubakeSpeedDif + delta).roundToScale(SPEED_SCALE)
            .coerceIn(IDOUBAKE_SPEED_DIF_MIN, IDOUBAKE_SPEED_DIF_MAX)
    }

    /** 選択中の OBJECT の column（1..3）の速度を delta 分動かす。範囲は物体ごと。 */
    private fun adjustSelectedObjectSpeed(column: Int, delta: Float) {
        if (!settingsSynced) return
        // 送るときの刻みに丸めてから範囲に収める（speedStep の足し引きで桁が伸びないように）。
        fun clamp(v: Float) = v.roundToScale(SPEED_SCALE).coerceIn(speedRangeFor(selectedSpeed))
        when (selectedSpeed) {
            "hata" -> when {
                hataAdjustNear -> if (hataDeka) {
                    when (column) {
                        1 -> hataNearDekaSpeed1 = clamp(hataNearDekaSpeed1 + delta)
                        2 -> hataNearDekaSpeed2 = clamp(hataNearDekaSpeed2 + delta)
                        else -> hataNearDekaSpeed3 = clamp(hataNearDekaSpeed3 + delta)
                    }
                } else {
                    when (column) {
                        1 -> hataNearHutuuSpeed1 = clamp(hataNearHutuuSpeed1 + delta)
                        2 -> hataNearHutuuSpeed2 = clamp(hataNearHutuuSpeed2 + delta)
                        else -> hataNearHutuuSpeed3 = clamp(hataNearHutuuSpeed3 + delta)
                    }
                }
                else -> if (hataDeka) {
                    when (column) {
                        1 -> hataFarDekaSpeed1 = clamp(hataFarDekaSpeed1 + delta)
                        2 -> hataFarDekaSpeed2 = clamp(hataFarDekaSpeed2 + delta)
                        else -> hataFarDekaSpeed3 = clamp(hataFarDekaSpeed3 + delta)
                    }
                } else {
                    when (column) {
                        1 -> hataFarHutuuSpeed1 = clamp(hataFarHutuuSpeed1 + delta)
                        2 -> hataFarHutuuSpeed2 = clamp(hataFarHutuuSpeed2 + delta)
                        else -> hataFarHutuuSpeed3 = clamp(hataFarHutuuSpeed3 + delta)
                    }
                }
            }
            "hoju" -> when (column) {
                1 -> hojuSpeed1 = clamp(hojuSpeed1 + delta)
                2 -> hojuSpeed2 = clamp(hojuSpeed2 + delta)
                else -> hojuSpeed3 = clamp(hojuSpeed3 + delta)
            }
            else -> if (baketuSpeedShowsBs3) {
                when (column) {
                    1 -> bs3Speed1 = clamp(bs3Speed1 + delta)
                    2 -> bs3Speed2 = clamp(bs3Speed2 + delta)
                    else -> bs3Speed3 = clamp(bs3Speed3 + delta)
                }
            } else {
                when (column) {
                    1 -> bs2Speed1 = clamp(bs2Speed1 + delta)
                    2 -> bs2Speed2 = clamp(bs2Speed2 + delta)
                    else -> bs2Speed3 = clamp(bs2Speed3 + delta)
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

    // パケットの通し番号（0〜1023、10 bit）。send() のたびに 1 増やす（並びは send() のコメント）。
    // con.py の PACKET_SEQ_SHIFT / PACKET_SEQ_MASK と必ず合わせる。
    private var packetSeq = 0

    /** ロボットへ送る OBJECT。旗は NEAR / FAR と HUTUU / DEKA、バケツは BS2 / BS3 まで入れる。HOJU は "hoju"（mast2 の input.cpp がこの名前の組で撃つ）。 */
    private fun packetObject(): String = when (selectedSpeed) {
        "baketu" -> if (baketuBs3) "baketu_bs3" else "baketu_bs2"
        "hata" -> {
            val range = if (selectedPlace in HATA_NEAR_RANGE_TARGETS) "near" else "far"
            val kind = if (hataDeka) "deka" else "hutuu"
            "hata_${range}_$kind"
        }
        else -> selectedSpeed
    }

    private fun send(
        mapId: String,
        vx: Float, vy: Float, w: Float,
        hataFarHutuuSpeed1: Float, bs2Speed1: Float,
        hataFarHutuuSpeed2: Float, bs2Speed2: Float,
        hataFarHutuuSpeed3: Float, bs2Speed3: Float,
        bs3Speed1: Float, bs3Speed2: Float, bs3Speed3: Float,
        hataFarTurnX: Float, hataFarTurnY: Float, hataFarTurnTheta: Float,
        hataTTurnX: Float, hataTTurnY: Float, hataTTurnTheta: Float,
        hataOTurnX: Float, hataOTurnY: Float, hataOTurnTheta: Float, hataO: Boolean,
        hataNearHutuuSpeed1: Float, hataNearHutuuSpeed2: Float, hataNearHutuuSpeed3: Float,
        hataNearDekaSpeed1: Float, hataNearDekaSpeed2: Float, hataNearDekaSpeed3: Float,
        hataFarDekaSpeed1: Float, hataFarDekaSpeed2: Float, hataFarDekaSpeed3: Float,
        hojuTurnX: Float, hojuTurnY: Float, hojuTurnTheta: Float,
        bs2TurnX: Float, bs2TurnY: Float, bs2TurnTheta: Float,
        bs3TurnX: Float, bs3TurnY: Float, bs3TurnTheta: Float,
        hojuSpeed1: Float, hojuSpeed2: Float, hojuSpeed3: Float,
        idoubakeTurnX: Float, idoubakeTurnY: Float, idoubakeTurnTheta: Float, idoubake: Boolean,
        idoubakeSpeedDif: Float,
        selectedSpeed: String, selectedPlace: String,
        hataLaser: Boolean, hojuLaser: Boolean, t0: Boolean, retry: Boolean,
        left: Boolean, right: Boolean, up: Boolean, down: Boolean,
        circle: Boolean, triangle: Boolean, square: Boolean, cross: Boolean,
        l1: Boolean, l2: Boolean, r1: Boolean, r2: Boolean,
        start: Boolean, select: Boolean, ps: Boolean,
        synced: Boolean
    ) {
        try {
            // offset  size  中身
            //  0      u8    PACKET_VERSION
            //  1      u8    map_id（PACKET_MAP_IDS の番号）
            //  2      u8    object（PACKET_OBJECT_IDS の番号）
            //  3      u8    target（PACKET_TARGET_IDS の番号）
            //  4      u32   bit0-21: ボタン類（bit0 から下の buttons の並び。bit20 は retry、bit21 は idoubake）、bit22-31: 通し番号（packetSeq）
            //  8      i16×3 vx / vy / w
            // 14      i16×9 turn（bs2 / 旗の FAR 用 / hoju の x, y, theta）
            // 32      i16×6 speed（旗の FAR の HUTUU 用 / bs2 の 1..3）
            // 44      i16×3 speed（bs3 の 1..3）
            // 50      i16×3 turn（bs3 の x, y, theta）
            // 56      i16×3 turn（旗の HATA_T 用の x, y, theta）
            // 62      i16×6 speed（旗の NEAR の HUTUU 用の 1..3、DEKA 用の 1..3）
            // 74      i16×3 speed（旗の FAR の DEKA 用の 1..3）
            // 80      i16×3 turn（旗の HATA_O 用の x, y, theta）
            // 86      i16×3 speed（hoju の 1..3）
            // 92      i16×3 turn（移動バケツ idoubake 用の x, y, theta）
            // 98      i16   移動バケツの射出速度の差 idoubake_speed_dif（2026-11-08）
            // 100     u8    空き（0。設定値を 29 byte × 3 に割り切るため）
            val buttons = listOf(
                left, right, up, down,
                circle, triangle, square, cross,
                l1, l2, r1, r2,
                start, select, ps,
                hataLaser, hojuLaser, t0, synced,
                hataO, retry, idoubake
            ).foldIndexed(0) { bit, acc, on -> if (on) acc or (1 shl bit) else acc }

            val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(PACKET_VERSION)
            buf.put(packetIndex(PACKET_MAP_IDS, mapId))
            buf.put(packetIndex(PACKET_OBJECT_IDS, selectedSpeed))
            buf.put(packetIndex(PACKET_TARGET_IDS, selectedPlace))
            // 通し番号は 1 回ごとに 1 増やす。con.py は UDP と ES920LR のうち番号が新しい方を使い、同じ番号や古い番号は捨てる。
            packetSeq = (packetSeq + 1) and 0x3FF
            buf.putInt(buttons or (packetSeq shl 22))
            buf.putScaled(vx, STICK_SCALE)
            buf.putScaled(vy, STICK_SCALE)
            buf.putScaled(w, STICK_SCALE)
            // turn / speed は con.py の SETTING_KEYS と同じ並び。
            buf.putScaled(bs2TurnX, TURN_XY_SCALE)
            buf.putScaled(bs2TurnY, TURN_XY_SCALE)
            buf.putScaled(bs2TurnTheta, TURN_THETA_SCALE)
            buf.putScaled(hataFarTurnX, TURN_XY_SCALE)
            buf.putScaled(hataFarTurnY, TURN_XY_SCALE)
            buf.putScaled(hataFarTurnTheta, TURN_THETA_SCALE)
            buf.putScaled(hojuTurnX, TURN_XY_SCALE)
            buf.putScaled(hojuTurnY, TURN_XY_SCALE)
            buf.putScaled(hojuTurnTheta, TURN_THETA_SCALE)
            buf.putScaled(hataFarHutuuSpeed1, SPEED_SCALE)
            buf.putScaled(hataFarHutuuSpeed2, SPEED_SCALE)
            buf.putScaled(hataFarHutuuSpeed3, SPEED_SCALE)
            buf.putScaled(bs2Speed1, SPEED_SCALE)
            buf.putScaled(bs2Speed2, SPEED_SCALE)
            buf.putScaled(bs2Speed3, SPEED_SCALE)
            buf.putScaled(bs3Speed1, SPEED_SCALE)
            buf.putScaled(bs3Speed2, SPEED_SCALE)
            buf.putScaled(bs3Speed3, SPEED_SCALE)
            buf.putScaled(bs3TurnX, TURN_XY_SCALE)
            buf.putScaled(bs3TurnY, TURN_XY_SCALE)
            buf.putScaled(bs3TurnTheta, TURN_THETA_SCALE)
            buf.putScaled(hataTTurnX, TURN_XY_SCALE)
            buf.putScaled(hataTTurnY, TURN_XY_SCALE)
            buf.putScaled(hataTTurnTheta, TURN_THETA_SCALE)
            buf.putScaled(hataNearHutuuSpeed1, SPEED_SCALE)
            buf.putScaled(hataNearHutuuSpeed2, SPEED_SCALE)
            buf.putScaled(hataNearHutuuSpeed3, SPEED_SCALE)
            buf.putScaled(hataNearDekaSpeed1, SPEED_SCALE)
            buf.putScaled(hataNearDekaSpeed2, SPEED_SCALE)
            buf.putScaled(hataNearDekaSpeed3, SPEED_SCALE)
            buf.putScaled(hataFarDekaSpeed1, SPEED_SCALE)
            buf.putScaled(hataFarDekaSpeed2, SPEED_SCALE)
            buf.putScaled(hataFarDekaSpeed3, SPEED_SCALE)
            buf.putScaled(hataOTurnX, TURN_XY_SCALE)
            buf.putScaled(hataOTurnY, TURN_XY_SCALE)
            buf.putScaled(hataOTurnTheta, TURN_THETA_SCALE)
            buf.putScaled(hojuSpeed1, SPEED_SCALE)
            buf.putScaled(hojuSpeed2, SPEED_SCALE)
            buf.putScaled(hojuSpeed3, SPEED_SCALE)
            buf.putScaled(idoubakeTurnX, TURN_XY_SCALE)
            buf.putScaled(idoubakeTurnY, TURN_XY_SCALE)
            buf.putScaled(idoubakeTurnTheta, TURN_THETA_SCALE)
            buf.putScaled(idoubakeSpeedDif, SPEED_SCALE)
            // 100 byte 目は空き（allocate で 0 のまま）

            sendTime = System.nanoTime()
            // LoRa にも同じものを渡す（送るのは Es920Link のループ。ここでは待たない）。
            es920.offer(buf.array(), synced)
            // つながっている経路すべてへ同時に送る（各経路の pathSendLoop が送る。ここでは待たない）。
            // 1本も無ければデフォルト経路で送る。
            val paths = sendPaths.toList()
            if (paths.isEmpty()) {
                socket.send(DatagramPacket(buf.array(), PACKET_SIZE, address, port))
            } else {
                val data = buf.array()
                paths.forEach { path -> path.outbox.trySend(data) }
            }
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
        unregisterSendPaths()
        es920.close()
        closeSockets()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // ES920LR を挿したときの確認・UsbAttachActivity から戻った。また送る（onPause の説明）
        sendPausedForUsb = false
    }

    override fun onPause() {
        super.onPause()
        // ES920LR の USB 許可ダイアログでも onPause が来る。ここで終了すると許可できないので、その間は残す。
        if (es920.permissionPending) return
        // ES920LR を挿した直後（まだどの組も開いていないモジュールがある）は、初めての「Controller で開く」の確認や
        // UsbAttachActivity が前に出る。そのときは終了せず、戻ってくる（onResume）までずっと待つ。
        // 待っている間は送らない（裏に回ったまま古い操作を送り続けないため。ロボットは途絶として止まる。今までの終了と同じ）。
        // それ以外（ふつうに別のアプリへ移ったときなど）は今までどおりすぐ終了する。
        if (es920.hasFreeDevice()) {
            sendPausedForUsb = true
            return
        }
        exitOnPause()
    }

    // ES920LR を挿した直後の onPause で、終了せずに待っている間は true（送らない）。onResume で false
    @Volatile
    private var sendPausedForUsb = false

    /** onPause でアプリを終了する（送るのをやめ、プロセスごと閉じる）。 */
    private fun exitOnPause() {
        stopHataTurnRepeat()
        saveSettings()
        // ??????????????????????????????????
        networkJob?.cancel()
        unregisterSendPaths()
        es920.close()
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
            text = "ON のままだと ETH 経由の通信が使えなくなります",
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
    modifier: Modifier = Modifier,
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
        modifier = modifier.fillMaxWidth()
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
 * SPEED ADJUST の ALL の行。表示中の組の C1〜C3 をまとめて +/- する。
 * どれか 1 つでも上限 / 下限に届いている側のボタンは無効（灰色）にする（差を保つため、そこで止める）。
 */
@Composable
private fun SpeedAdjustAllRow(
    modifier: Modifier = Modifier,
    values: List<Float>,
    range: ClosedFloatingPointRange<Float>,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    accent: Color = ControllerColors.Accent,
    enabled: Boolean = true
) {
    // 0.01 刻みの足し引きで誤差が乗るので、端との比較は少し余裕を持たせる（SpeedAdjustRow と同じ）。
    val canDecrease = enabled && values.all { it > range.start + 1e-4f }
    val canIncrease = enabled && values.all { it < range.endInclusive - 1e-4f }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = "ALL",
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        SpeedStepButton(text = "-", enabled = canDecrease, onStep = onDecrease, accent = accent)
        SpeedStepButton(text = "+", enabled = canIncrease, onStep = onIncrease, accent = accent)
    }
}

/** SPEED ADJUST で組（旗の NEAR / FAR、HUTUU / DEKA、バケツの BS2 / BS3）を選ぶボタン。旗は 4 つ並ぶので余白を詰めてある。 */
@Composable
private fun SpeedSetChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(5.dp)
    val contentColor = if (selected) ControllerColors.TextPrimary else ControllerColors.TextSecondary
    Button(
        onClick = onClick,
        modifier = modifier.height(26.dp),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) accent.copy(alpha = 0.45f) else ControllerColors.Surface2,
            contentColor = contentColor
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
                .border(1.dp, if (selected) accent else ControllerColors.Border, shape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                softWrap = false
            )
        }
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
        // 行の高さに合わせて縮む（旗は組を選ぶボタンのぶん行が低くなる）。押しやすいよう 38 x 44dp から広げた。
        modifier = Modifier.width(44.dp).heightIn(max = 56.dp).fillMaxHeight(),
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
                fontSize = 20.sp,
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
    // 相手のバケツの位置 [mm] と、PC に届いているか。+/- で onEnemyChange(axis 0 = x / 1 = y / 2 = z, 増やす量)
    enemyX: Int, enemyY: Int, enemyZ: Int,
    enemyConfirmed: Boolean,
    onEnemyChange: (axis: Int, delta: Int) -> Unit,
    isFlipped: Boolean,
    // 起動時に Wi-Fi / モバイル回線がつながっていたら true。背景の点滅と警告を出す。
    networkWarning: Boolean,
    onToggleFlip: () -> Unit,
    // ES920LR のチャンネルの表示と操作（右側のパネル）
    lora: LoraChannelUi,
    onSelectLoraLink: (Int) -> Unit,
    onScanLora: () -> Unit,
    onSelectLoraChannel: (Int) -> Unit,
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // 2026-11-08 に下へ ENEMY TARGET を足したので 32 -> 16 に詰めた
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                EnemyTargetPanel(enemyX, enemyY, enemyZ, enemyConfirmed, onEnemyChange)
            }
            LoraChannelPanel(lora = lora, onSelectLink = onSelectLoraLink, onScan = onScanLora, onSelect = onSelectLoraChannel)
        }
    }
}

/**
 * フィールド選択画面の ENEMY TARGET。相手のバケツ（底の高さ）をゾーン基準の mm で選ぶ（ENEMY_* の説明）。
 * 見出しの右に、PC に届いているか（PC OK / SENDING...）を出す。届くまでアプリが送り直し続ける。
 */
@Composable
private fun EnemyTargetPanel(
    x: Int, y: Int, z: Int,
    confirmed: Boolean,
    onChange: (axis: Int, delta: Int) -> Unit
) {
    HudPanel(modifier = Modifier.width(400.dp), contentPadding = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { HudSectionTitle(text = "ENEMY TARGET (mm)") }
            Text(
                text = if (confirmed) "PC OK" else "SENDING...",
                color = if (confirmed) ControllerColors.Success else ControllerColors.Warning,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("X" to x, "Y" to y, "Z" to z).forEachIndexed { axis, (label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HudButton(
                        text = "-", onClick = { onChange(axis, -ENEMY_STEP) },
                        modifier = Modifier.width(34.dp), height = 34.dp, fontSize = 16.sp
                    )
                    Text(
                        text = "$label $value",
                        color = ControllerColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(58.dp)
                    )
                    HudButton(
                        text = "+", onClick = { onChange(axis, ENEMY_STEP) },
                        modifier = Modifier.width(34.dp), height = 34.dp, fontSize = 16.sp
                    )
                }
            }
        }
    }
}

/** フィールド選択画面の LORA CHANNEL パネルに渡すもの。 */
class LoraChannelUi(
    val profileName: String,
    val channels: IntRange,
    val freqMhz: (Int) -> Double,
    /** 組ごと（0 = A、1 = B）の表示 */
    val links: List<LoraLinkUi>,
    /** タイルを押したとき・SCAN でチャンネルを変える組 */
    val editLink: Int,
    /** 最後のスキャンの結果（どちらの組で調べても混み具合は同じ） */
    val scan: List<Es920ChannelScan>,
    /** 今スキャンで試しているチャンネル（0 はスキャンしていない） */
    val scanningCh: Int,
    /** スキャンしている（した）組 */
    val scanLink: Int,
    /** スキャンが終わった時刻（0 はまだ） */
    val scanAtMs: Long
)

/** LORA CHANNEL パネルの組 1 つ分。 */
class LoraLinkUi(
    val name: String,
    /** 選んだチャンネル */
    val selected: Int,
    /** モジュールに設定してあるチャンネル（切り替え中は selected と違う） */
    val active: Int,
    val state: Es920State,
    /** 最後の 5 秒ごとの送信の統計（コントローラーの画面で送っていた間のもの） */
    val stats: Es920Stats?
)

// LORA CHANNEL パネルの 1 行に並べるチャンネルの数。
private const val LORA_TILES_PER_ROW = 5

/** NG 102 の割合の色。15 回中 1 回までは空いているとみる。 */
private fun loraBusyColor(rate: Double): Color = when {
    rate < 0.1 -> ControllerColors.Success
    rate < 0.3 -> ControllerColors.Warning
    else -> ControllerColors.Danger
}

/**
 * ES920LR のチャンネルを選ぶパネル。上の A / B で組を選び、タイルを押すとその組のチャンネルを切り替える（PC は追いかけてくる）。
 * SCAN は選んでいる組のモジュールで全チャンネルを試し、NG 102（キャリアセンスで送れなかった = 誰かが使っている）の割合をタイルに出す。
 */
@Composable
private fun LoraChannelPanel(
    lora: LoraChannelUi,
    onSelectLink: (Int) -> Unit,
    onScan: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val scanByCh = lora.scan.associateBy { it.channel }
    val scanning = lora.scanningCh != 0
    val edit = lora.links[lora.editLink]
    HudPanel(modifier = Modifier.width(300.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { HudSectionTitle(text = "LORA CHANNEL") }
            HudButton(
                text = if (scanning) "SCAN ${lora.links[lora.scanLink].name} ${lora.scanningCh}/${lora.channels.last}"
                else "SCAN ${edit.name}",
                onClick = onScan,
                modifier = Modifier.width(120.dp),
                enabled = edit.state == Es920State.READY && !scanning,
                height = 36.dp,
                fontSize = 13.sp,
                accent = ControllerColors.Accent
            )
        }
        // 組ごとのチャンネルとモジュールの状態。押すと、タイルで変える組になる
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            lora.links.forEachIndexed { i, link ->
                LoraLinkChip(
                    link = link,
                    freqMhz = lora.freqMhz(link.selected),
                    editing = i == lora.editLink,
                    scanning = scanning && i == lora.scanLink,
                    onClick = { onSelectLink(i) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // 2 組のチャンネルが近いと、スマホの上で片方の送信がもう片方の受信を邪魔しうる
        val gap = lora.links.zipWithNext { a, b -> kotlin.math.abs(a.selected - b.selected) }.minOrNull()
        val stats = edit.stats
        Text(
            text = when {
                gap != null && gap < ES920_MIN_CHANNEL_GAP -> "A と B が近い（$gap ch）。$ES920_MIN_CHANNEL_GAP ch 以上離す"
                stats == null -> "${lora.profileName} · ${edit.name} の送信の統計はまだない"
                else -> "${edit.name} 直近 5 秒 ch${stats.channel}: %.1f Hz · NG102 %d/%d (%.0f%%)".format(
                    stats.hz, stats.busy, stats.attempts, stats.busyRate * 100)
            },
            color = when {
                gap != null && gap < ES920_MIN_CHANNEL_GAP -> ControllerColors.Warning
                stats == null -> ControllerColors.TextSecondary
                else -> loraBusyColor(stats.busyRate)
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        // チャンネルの一覧。数字の下は、スキャンで NG 102 が返った割合
        lora.channels.chunked(LORA_TILES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { ch ->
                    // このチャンネルを選んでいる組（選んでいる組の印と、もう片方の組の印）
                    val owner = lora.links.indexOfFirst { it.selected == ch }
                    LoraChannelTile(
                        ch = ch,
                        result = scanByCh[ch],
                        owner = if (owner >= 0) lora.links[owner].name else null,
                        selected = owner == lora.editLink,
                        scanningNow = ch == lora.scanningCh,
                        // もう片方の組のチャンネルは押せない
                        enabled = !scanning && (owner < 0 || owner == lora.editLink),
                        onClick = { onSelect(ch) }
                    )
                }
            }
        }
        Text(
            text = "% = NG 102（誰かが使っていて送れなかった）の割合。" +
                "押すと ${edit.name} を切り替え。${ES920_SLOW_CHANNELS.first}〜${ES920_SLOW_CHANNELS.last} は送るたび 50 ms 休むので遅い" +
                if (lora.scanAtMs > 0) "。SCAN ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(lora.scanAtMs)}" else "",
            color = ControllerColors.TextSecondary,
            fontSize = 10.sp,
            lineHeight = 13.sp
        )
    }
}

/** LORA CHANNEL パネルの組 1 つ分の札（"A CH10 READY"）。editing: タイルで変える組。 */
@Composable
private fun LoraLinkChip(
    link: LoraLinkUi,
    freqMhz: Double,
    editing: Boolean,
    scanning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(6.dp)
    val (stateText, stateColor) = when {
        link.state == Es920State.NO_DEVICE -> "NO USB" to ControllerColors.Danger
        link.state == Es920State.STARTING -> "START..." to ControllerColors.Warning
        scanning -> "SCANNING" to ControllerColors.Warning
        link.selected != link.active -> "SWITCHING" to ControllerColors.Warning
        else -> "READY" to ControllerColors.Success
    }
    Column(
        modifier = modifier
            .background(if (editing) ControllerColors.Accent.copy(alpha = 0.22f) else ControllerColors.Surface2, shape)
            .border(if (editing) 2.dp else 1.dp, if (editing) ControllerColors.Accent else ControllerColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = "${link.name}  CH ${link.selected}  ${"%.1f".format(freqMhz)}",
            color = ControllerColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 14.sp,
            maxLines = 1
        )
        Text(text = stateText, color = stateColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 12.sp)
    }
}

@Composable
private fun LoraChannelTile(
    ch: Int,
    result: Es920ChannelScan?,
    // このチャンネルを選んでいる組の名前（どちらも選んでいなければ null）
    owner: String?,
    // タイルで変えている組のチャンネル
    selected: Boolean,
    scanningNow: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    val color = result?.let { loraBusyColor(it.busyRate) } ?: ControllerColors.TextMuted
    // もう片方の組のチャンネル
    val other = owner != null && !selected
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 38.dp)
            .background(
                when {
                    selected -> ControllerColors.Accent.copy(alpha = 0.22f)
                    other -> ControllerColors.TextMuted.copy(alpha = 0.18f)
                    else -> ControllerColors.Surface2
                },
                shape
            )
            .border(
                if (selected || other || scanningNow) 2.dp else 1.dp,
                when {
                    selected -> ControllerColors.Accent
                    scanningNow -> ControllerColors.Warning
                    other -> ControllerColors.TextSecondary
                    else -> ControllerColors.Border
                },
                shape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (owner != null) "$ch $owner" else "$ch",
                color = if (ch in ES920_SLOW_CHANNELS) ControllerColors.TextSecondary else ControllerColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 15.sp
            )
            Text(
                text = when {
                    scanningNow -> "..."
                    result == null -> "--"
                    result.ok + result.busy == 0 -> "ERR"   // OK も NG 102 も返らなかった（ほかの NG・時間切れだけ）
                    else -> "${(result.busyRate * 100).roundToInt()}%"
                },
                color = if (scanningNow) ControllerColors.Warning else color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 12.sp
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
    FieldObject("hoju", "HOJU")
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
// 画面の LINK の表示を作り直す間隔と、生きているとみなす時間 [ms]。
// LoRa は Wi-Fi が切れている間 200 ms ごとに PC の行を受け取るので、5 回続けて落としたら NG にする。
private const val LINK_REFRESH_MS = 250L
// ES920LR のチャンネル（フィールド選択画面で選んだもの）を残す SharedPreferences のキー。
// 組 A は前からのキー、組 B は "es920_channel_b"。
private const val PREF_ES920_CHANNEL = "es920_channel"
private fun es920ChannelPref(link: Int) =
    if (link == 0) PREF_ES920_CHANNEL else "${PREF_ES920_CHANNEL}_${ES920_LINK_NAMES[link].lowercase()}"
private const val LINK_WIFI_TIMEOUT_MS = 500L
// --- 画面ピン留めと USB の許可ダイアログ。ピン留め中はダイアログが出ない。
// ピン留めはコントローラーの画面に入るときにするので、ふつうはフィールド選択画面で許可を済ませる。
// コントローラーの画面でモジュールを挿し直したときだけ、ダイアログを出す間ピン留めを外す
// （ES920LR を挿したら許可を自動で出す USB_DEVICE_ATTACHED は、挿すたびにアプリが開き直されるので 2026-11-06 に外した）。
// ピン留めを外してからダイアログを出すまで待つ時間 [ms]
private const val USB_UNPIN_SETTLE_MS = 300L
// 許可の結果が来てからピン留めし直すまで待つ時間 [ms]。2 台目の許可が続けて来るときに、外し直さずに済むように
private const val USB_REPIN_DELAY_MS = 2000L
private const val LINK_LORA_TIMEOUT_MS = 1000L

// ping の宛先と間隔。結果は NETWORK RTT の下に出す。
private const val PING_HOST = "192.168.11.1"
private const val PING_INTERVAL_MS = 1000L

/** 何も選んでいない状態かどうか。起動直後の "" も含む。 */
private fun isMoveTargetCleared(selectedPlace: String): Boolean =
    selectedPlace.isEmpty() || selectedPlace == "none"

/**
 * その移動先のボタンをいま押せるか。
 * 何か選択している間は、どのボタンも押せる（選び直しを妨げないため）。
 * 何も選んでいないときだけ、自己位置がそのボタンの範囲に入っているかで決まる。
 */
private fun canPressMoveTarget(
    targetId: String,
    selectedPlace: String,
    unlockedTargets: Set<String>
): Boolean =
    !isMoveTargetCleared(selectedPlace) || targetId in unlockedTargets

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

// BS2 / BS3 ボタンそれぞれで押すたびに送る移動先。並びの順に送り、最後の次は先頭に戻る。
// send() の "target" にはボタンの id（"bs2"）ではなく、ここの id が入る。
private val BS2_TARGETS = listOf("bs2_a", "bs2_b", "bs2_c")
private val BS3_TARGETS = listOf("bs3_a", "bs3_b", "bs3_c")
private val BS_TARGETS = BS2_TARGETS + BS3_TARGETS

// 旗の移動先。turn 調整の対象・レーザー・OBJECT の扱いが共通。
private val HATA_TARGETS = listOf("hata_t", "hata_o", "hata_far")
// SELECT ボタンで切り替え合う旗の移動先。
private val HATA_NEAR_TARGETS = listOf("hata_t", "hata_o")
// 旗の速度が NEAR になる移動先。それ以外は FAR。
// TURN ADJUST の NEAR / FAR の表示にも使う（turn 自体は HATA_T / HATA_O / FAR で別。drive2.cpp が移動先で選ぶ）。
private val HATA_NEAR_RANGE_TARGETS = HATA_NEAR_TARGETS

// 押すたびに順送りするボタン。キーがボタンの id、値が送る移動先の並び。
// 選択中に押しても解除されない（解除は L3 / R3）。SELECT ボタンでも同じように送れる。
private val CYCLING_MOVE_TARGETS = mapOf("bs2" to BS2_TARGETS, "bs3" to BS3_TARGETS)

/** そのボタンが選択中か。順送りのボタンは、並びのどれかを選んでいれば選択中とみなす。 */
private fun isMoveTargetButtonSelected(buttonId: String, selectedPlace: String): Boolean =
    selectedPlace == buttonId || CYCLING_MOVE_TARGETS[buttonId]?.contains(selectedPlace) == true

// 画面での並びをそのまま表す。内側の listOf 1つが 1 行分で、1 行に最大 4 つ並ぶ。
// unlockArea の x / y（mm）・theta（度）がそのボタンを押せる場所。enabled = false でロックなし。
// 座標はロボット側のマップ（mast2/include/mast2/map/map.hpp の points、青ゾーン基準）に合わせてある。
// 向きはあちらが rad なので度に直して書く（-1.57 rad = -90°）。
// map.hpp の地点名は con.py の MAST2_TARGET_NAMES でこちらの id と対応させている。
// 青ゾーン用。画面での並びもそのまま使う（各行とも 1 が右端に来るよう逆順、右詰めで表示）。
private val BLUE_MOVE_TARGET_ROWS = listOf(
    listOf(
        MoveTarget("ura", "URA", UnlockArea(1700f, 5950f, 0f, enabled = true)),
        MoveTarget("edge", "EDGE", UnlockArea(700f, 2955f, 0f, enabled = true)),
        MoveTarget("tb2", "TB2", UnlockArea(2900f, 8645f, 0f, enabled = true)),
        MoveTarget("tb1", "TB1", UnlockArea(2900f, 2955f, 0f, enabled = true))
    ),
    listOf(
        // BS2 / BS3 は押すたびに _A -> _B -> _C と送る（CYCLING_MOVE_TARGETS）。未選択のときは最初に入る _A を表示する。
        // 押せる場所は真ん中の B（bs2B / bs3B）。A / C も許容幅（±500mm）に入る。
        // hata {4260, 5950, 0}。
        MoveTarget("hata_far", "HATA_FAR", UnlockArea(4260f, 5950f, 0f, enabled = true)),
        MoveTarget("bs3", "BS3_A", UnlockArea(5080f, 7616f, 0f, enabled = true)),
        MoveTarget("bs2", "BS2_A", UnlockArea(5080f, 3976f, 0f, enabled = true))
    ),
    listOf(
        // hataT {4930, 5080, 0.22 rad} / hataO {4930, 6700, -0.22 rad}。0.22 rad = 12.6°。
        MoveTarget("hata_o", "HATA_O", UnlockArea(4930f, 6700f, -12.6f, enabled = true)),
        MoveTarget("hata_t", "HATA_T", UnlockArea(4930f, 5080f, 12.6f, enabled = true)),
        MoveTarget("hoju", "HOJU", UnlockArea(4050f, 500f, 0f, enabled = true)),
        MoveTarget("dekahoju", "DEKAHOJU", UnlockArea(3900f, 500f, -90f, enabled = true))
    )
)

// 赤ゾーン用。フィールドが左右反転するので、各行を青の逆順（1 が左端）に並べ、左詰めで表示する。
// 座標はロボット側（map_utils.hpp の set_filed_color）と同じく、青の y と向きの符号を反転したもの。
// 並び・ラベル・座標とも青とは独立に書き換えられる。
private val RED_MOVE_TARGET_ROWS = listOf(
    listOf(
        MoveTarget("tb1", "TB1", UnlockArea(2900f, -2955f, 0f, enabled = true)),
        MoveTarget("tb2", "TB2", UnlockArea(2900f, -8645f, 0f, enabled = true)),
        MoveTarget("edge", "EDGE", UnlockArea(700f, -2955f, 0f, enabled = true)),
        MoveTarget("ura", "URA", UnlockArea(1700f, -5950f, 0f, enabled = true))
    ),
    listOf(
        // BS2 / BS3 は押すたびに _A -> _B -> _C と送る（CYCLING_MOVE_TARGETS）。未選択のときは最初に入る _A を表示する。
        // 押せる場所は真ん中の B（bs2B / bs3B）。A / C も許容幅（±500mm）に入る。
        MoveTarget("bs2", "BS2_A", UnlockArea(5080f, -3976f, 0f, enabled = true)),
        MoveTarget("bs3", "BS3_A", UnlockArea(5080f, -7616f, 0f, enabled = true)),
        // 青の hata の y の符号を反転したもの。
        MoveTarget("hata_far", "HATA_FAR", UnlockArea(4260f, -5950f, 0f, enabled = true))
    ),
    listOf(
        MoveTarget("dekahoju", "DEKAHOJU", UnlockArea(3900f, -500f, 90f, enabled = true)),
        MoveTarget("hoju", "HOJU", UnlockArea(4050f, -500f, 0f, enabled = true)),
        // 青の hataT / hataO の y と向きの符号を反転したもの。
        MoveTarget("hata_t", "HATA_T", UnlockArea(4930f, -5080f, -12.6f, enabled = true)),
        MoveTarget("hata_o", "HATA_O", UnlockArea(4930f, -6700f, 12.6f, enabled = true))
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
    hataFarTurnX: Float, hataFarTurnY: Float, hataFarTurnTheta: Float,
    hataTTurnX: Float, hataTTurnY: Float, hataTTurnTheta: Float,
    hataOTurnX: Float, hataOTurnY: Float, hataOTurnTheta: Float,
    hojuTurnX: Float, hojuTurnY: Float, hojuTurnTheta: Float,
    // 移動バケツの turn と、HOJU の位置決め中に SELECT で idoubake にしているか。
    idoubakeTurnX: Float, idoubakeTurnY: Float, idoubakeTurnTheta: Float,
    hojuIdoubake: Boolean,
    bs2TurnX: Float, bs2TurnY: Float, bs2TurnTheta: Float,
    bs3TurnX: Float, bs3TurnY: Float, bs3TurnTheta: Float,
    selectedTurnTarget: TurnTarget,
    t0: Boolean,
    onToggleT0: () -> Unit,
    // retry を送っている間は true。残りの秒を出し、押せなくする。
    retry: Boolean,
    retryRemainingSec: Int,
    onRetry: () -> Unit,
    left: Boolean, right: Boolean, up: Boolean, down: Boolean,
    circle: Boolean, square: Boolean, cross: Boolean, triangle: Boolean,
    l1: Boolean, l2: Boolean, r1: Boolean, r2: Boolean,
    selectedSpeed: String,
    onSelectObject: (String) -> Unit,
    selectedPlace: String,
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
    // C1〜C3 をまとめて +/-（どれか 1 つでも端に届いたら、その向きには動かさない）
    onSpeedAllIncrease: () -> Unit,
    onSpeedAllDecrease: () -> Unit,
    // 移動バケツの射出速度の差。HOJU の SPEED ADJUST の DIF の行で +/-（IDOUBAKE_SPEED_DIF_MIN〜MAX）
    idoubakeSpeedDif: Float,
    onSpeedDifIncrease: () -> Unit,
    onSpeedDifDecrease: () -> Unit,
    logList: List<String>,
    hojuState: String,
    t0locked: Boolean,
    // con.py から turn / speed を受け取るまでは false。その間は調整ボタンを無効にする。
    settingsSynced: Boolean,
    // 最後に選んだ BS が BS3 なら true。バケツは撃つときに BS3 用の速度を使う。
    baketuBs3: Boolean,
    // バケツの SPEED ADJUST が BS3 を出していれば true。baketuBs3 とは、BS2 / BS3 ボタンで変えたときだけ違う。
    baketuSpeedBs3: Boolean,
    onShowBaketuSpeedBs: (bs3: Boolean) -> Unit,
    // 旗の速度の組が DEKA なら true。旗の SPEED ADJUST（NEAR / FAR とも）は DEKA 用を出している。
    hataDeka: Boolean,
    onSelectHataDeka: (deka: Boolean) -> Unit,
    // 旗の SPEED / TURN ADJUST が NEAR を出していれば true。ロボットが使う方とは、NEAR / FAR ボタンで変えたときだけ違う。
    hataAdjustNear: Boolean,
    // 最後に選んだ旗の移動先が HATA_O なら true。TURN ADJUST の NEAR は、これで HATA_T / HATA_O のどちらかになる。
    hataO: Boolean,
    onShowHataAdjustRange: (near: Boolean) -> Unit,
    // 画面の LINK。Wi-Fi が生きているか / LoRa の状態の文字 / LoRa が生きているか。
    linkWifiOk: Boolean,
    // 組ごと（0 = A、1 = B）
    linkLoraText: List<String>,
    linkLoraOk: List<Boolean>
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
            HudSectionTitle("PLACE  /  ${if (selectedPlace == "none") "---" else selectedPlace.uppercase()}", ControllerColors.Accent, 12.sp)
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
                            val selected = isMoveTargetButtonSelected(target.id, selectedPlace)
                            MoveTargetButton(
                                // 順送りのボタンは、選択中ならいま送っている移動先（"BS2_A" など）を出す。
                                label = if (selected && target.id in CYCLING_MOVE_TARGETS) selectedPlace.uppercase() else target.label,
                                selected = selected,
                                // 何も選んでいない間だけ、付近に居ないボタンをロックする。
                                enabled = canPressMoveTarget(target.id, selectedPlace, unlockedTargets),
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
                        .height(84.dp)
                        .background(ControllerColors.Background, RoundedCornerShape(5.dp))
                        .border(1.dp, ControllerColors.Border, RoundedCornerShape(5.dp))
                        .padding(5.dp)
                ) {
                    LazyColumn(state = logScrollState, modifier = Modifier.fillMaxSize()) {
                        items(logList) { logLine ->
                            // 枠が狭く長い行（LORA の統計など）が見切れるので、1 行に切らずに折り返して全部出す。
                            Text(
                                text = logLine,
                                color = ControllerColors.TextSecondary,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                modifier = Modifier.padding(bottom = 2.dp)
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

                // Wi-Fi / LoRa（A / B）の生死。入れるぶん SYSTEM LOG の枠を 130dp から縮めてある（B を足したときに 96 → 84dp）。
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .width(140.dp)
                        .height(42.dp)
                        .background(ControllerColors.Background, RoundedCornerShape(5.dp))
                        .border(
                            1.dp,
                            if (linkWifiOk || linkLoraOk.any { it }) ControllerColors.Border else ControllerColors.Danger,
                            RoundedCornerShape(5.dp)
                        )
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column {
                        Text(
                            text = if (linkWifiOk) "WIFI OK" else "WIFI DOWN",
                            color = if (linkWifiOk) ControllerColors.Success else ControllerColors.Danger,
                            fontSize = 10.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        linkLoraText.forEachIndexed { i, text ->
                            Text(
                                text = text,
                                color = if (linkLoraOk.getOrElse(i) { false }) ControllerColors.Success else ControllerColors.Danger,
                                fontSize = 10.sp,
                                lineHeight = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // 右上：turn調整パネルと SPEED ADJUST。
        // 下端の LOW GAIN / レーザー列（約94dp）にかからないよう下に余白を取り、
        // 残りの高さを SPEED ADJUST に weight(1f) で全部使わせる。
        // LOW GAIN を 47dp から 35dp に細くしたぶん、余白を 116dp から縮めた（空いた高さは t0 を大きくするのに使う）。
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(1.dp)
                .width(168.dp)
                .fillMaxHeight()
                .padding(bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End
        ) {
            // 射出速度調整。選択中の OBJECT の column1..3 だけを表示するので3行（+ まとめて動かす ALL の行）。
            // t0 と入れ替えてカラム先頭。weight(1f) で t0 を除いた残りの高さを使う。
            val speedAccent = when (selectedSpeed) {
                "hata" -> ControllerColors.Accent
                "hoju" -> ControllerColors.Success
                else -> ControllerColors.Warning
            }
            // バケツは BS2 / BS3 で速度が別なので、どちらを出しているかを見出しに出す（幅が狭いので「バケツ」の代わり）。
            // 旗は NEAR（HATA_T / HATA_O）/ FAR（それ以外）それぞれ HUTUU / DEKA で速度が別なので、どれを出しているかを見出しに出す
            // （幅が狭いので「HATA」は付けない）。
            // HOJU は 1 組だけなので「HOJU」。
            val speedTargetName = when (selectedSpeed) {
                "hata" -> "${if (hataAdjustNear) "NEAR" else "FAR"} ${if (hataDeka) "DEKA" else "HUTUU"}"
                "hoju" -> "HOJU"
                else -> if (baketuSpeedBs3) "BS3" else "BS2"
            }
            val hataNearInUse = selectedPlace in HATA_NEAR_RANGE_TARGETS
            // 撃つときに使わない方の速度を出している間は、見出しの色で分かるようにする。
            // バケツの色が Warning なので、それと区別できる Danger にしてある。
            val speedTitleColor = when {
                !settingsSynced -> ControllerColors.Danger
                selectedSpeed == "hata" && hataAdjustNear != hataNearInUse -> ControllerColors.Danger
                selectedSpeed == "baketu" && baketuSpeedBs3 != baketuBs3 -> ControllerColors.Danger
                else -> speedAccent
            }
            HudPanel(modifier = Modifier.fillMaxWidth().weight(1f), accent = speedAccent, contentPadding = 4.dp) {
                // 幅が狭いので「SPEED ADJUST」を縮めてある（NEAR HUTUU が 1 行に収まるように）。
                HudSectionTitle(
                    if (settingsSynced) "SPEED / $speedTargetName" else "SPEED / SYNC...",
                    speedTitleColor,
                    11.sp
                )
                // バケツも BS2 / BS3 の 2 組あるので、移動先を変えずに選べるようにする。● は撃つときに使う BS。
                if (selectedSpeed == "baketu") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SpeedSetChip(
                            text = if (baketuBs3) "BS2" else "●BS2",
                            selected = !baketuSpeedBs3,
                            onClick = { onShowBaketuSpeedBs(false) },
                            accent = speedAccent,
                            modifier = Modifier.weight(1f)
                        )
                        SpeedSetChip(
                            text = if (baketuBs3) "●BS3" else "BS3",
                            selected = baketuSpeedBs3,
                            onClick = { onShowBaketuSpeedBs(true) },
                            accent = speedAccent,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // 旗は組が 4 つあるので、移動先を変えずに選べるようにする。● は撃つときに使う NEAR / FAR。
                if (selectedSpeed == "hata") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SpeedSetChip(
                            text = if (hataNearInUse) "●NEAR" else "NEAR",
                            selected = hataAdjustNear,
                            onClick = { onShowHataAdjustRange(true) },
                            accent = speedAccent,
                            modifier = Modifier.weight(1f)
                        )
                        SpeedSetChip(
                            text = if (hataNearInUse) "FAR" else "●FAR",
                            selected = !hataAdjustNear,
                            onClick = { onShowHataAdjustRange(false) },
                            accent = speedAccent,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        SpeedSetChip(
                            text = "HUTUU",
                            selected = !hataDeka,
                            onClick = { onSelectHataDeka(false) },
                            accent = speedAccent,
                            modifier = Modifier.weight(1f)
                        )
                        SpeedSetChip(
                            text = "DEKA",
                            selected = hataDeka,
                            onClick = { onSelectHataDeka(true) },
                            accent = speedAccent,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    val speedRange = speedRangeFor(selectedSpeed)
                    // C1〜C3 をまとめて動かす。押しっぱなしの連続調整も 1 本ずつのときと同じ
                    SpeedAdjustAllRow(
                        modifier = Modifier.weight(1f),
                        values = listOf(speed1, speed2, speed3),
                        range = speedRange,
                        enabled = settingsSynced,
                        onIncrease = onSpeedAllIncrease,
                        onDecrease = onSpeedAllDecrease,
                        accent = speedAccent
                    )
                    SpeedAdjustRow(
                        modifier = Modifier.weight(1f),
                        label = "C1",
                        value = speed1,
                        range = speedRange,
                        enabled = settingsSynced,
                        onIncrease = { onSpeedIncrease(1) },
                        onDecrease = { onSpeedDecrease(1) },
                        accent = speedAccent
                    )
                    SpeedAdjustRow(
                        modifier = Modifier.weight(1f),
                        label = "C2",
                        value = speed2,
                        range = speedRange,
                        enabled = settingsSynced,
                        onIncrease = { onSpeedIncrease(2) },
                        onDecrease = { onSpeedDecrease(2) },
                        accent = speedAccent
                    )
                    SpeedAdjustRow(
                        modifier = Modifier.weight(1f),
                        label = "C3",
                        value = speed3,
                        range = speedRange,
                        enabled = settingsSynced,
                        onIncrease = { onSpeedIncrease(3) },
                        onDecrease = { onSpeedDecrease(3) },
                        accent = speedAccent
                    )
                    // HOJU のときだけ、移動バケツの射出速度の差（idoubake_speed_dif）の行を出す。
                    // idoubake（SELECT）にしている間は色を変える（TURN の IDOUBK の行と同じ）。
                    if (selectedSpeed == "hoju") {
                        SpeedAdjustRow(
                            modifier = Modifier.weight(1f),
                            label = "DIF",
                            value = idoubakeSpeedDif,
                            range = IDOUBAKE_SPEED_DIF_MIN..IDOUBAKE_SPEED_DIF_MAX,
                            enabled = settingsSynced,
                            onIncrease = onSpeedDifIncrease,
                            onDecrease = onSpeedDifDecrease,
                            accent = if (hojuIdoubake) ControllerColors.Warning else speedAccent
                        )
                    }
                }
            }

            // 下端の列: retry と t0（SPEED ADJUST と入れ替えてカラム下端へ）。retry は t0 の左。
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // retry: 押すと RETRY_HOLD_SECONDS の間 true を送る。その間は押せず、残りの秒を出す。
            Button(
                onClick = onRetry,
                enabled = !retry,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(72.dp)
                    .border(1.dp, if (retry) ControllerColors.Warning else ControllerColors.Border, androidx.compose.foundation.shape.CircleShape),
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ControllerColors.Surface2,
                    // 送っている間（押せない間）も暗くせず、送っていることが分かる色にする
                    disabledContainerColor = Color(0xFF4D3A12)
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("RETRY", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        color = if (retry) ControllerColors.Warning else ControllerColors.TextPrimary)
                    if (retry) {
                        Text("$retryRemainingSec", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ControllerColors.Warning)
                    }
                }
            }
            // t0
            // SPEED ADJUST の +/- を大きくするため 80dp から 60dp に縮め、LOW GAIN を細くした分で 72dp に戻した。
            Button(
                onClick = onToggleT0,
                enabled = !t0locked,
                // 小さくした円に "OFF" が収まるよう、ボタンの内側の余白はなくす。
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(72.dp)
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
        }

        // 物体選択（バケツ / 旗 / HOJU）3択排他。
        // 常に1つ点灯して "object" を送り続ける。
        HudPanel(
            modifier = Modifier
                .width(134.dp)
                .offset(527.dp, 0.dp),
            accent = ControllerColors.Accent,
            contentPadding = 4.dp
        ) {
            HudSectionTitle("SPEED  /  ${objectDisplayName(selectedSpeed)}", ControllerColors.Accent, 12.sp)
            // HOJU を足して 3 つにしたので、下の TURN ADJUST にかからないようボタンを低くして間隔を詰めてある。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FIELD_OBJECTS.forEach { fieldObject ->
                    // 旗とバケツは撃つときに使う組も出す。OBJECT が旗でなくても × で切り替わるので常に出す。HOJU は 1 組だけ。
                    val isHata = fieldObject.id == "hata"
                    ObjectSelectButton(
                        label = when (fieldObject.id) {
                            "hata" -> "${fieldObject.label} ${if (selectedPlace in HATA_NEAR_RANGE_TARGETS) "NEAR" else "FAR"} ${if (hataDeka) "DEKA" else "HUTUU"}"
                            "baketu" -> "${fieldObject.label} ${if (baketuBs3) "BS3" else "BS2"}"
                            else -> fieldObject.label
                        },
                        selected = selectedSpeed == fieldObject.id,
                        onClick = { onSelectObject(fieldObject.id) },
                        fontSize = if (isHata) 10.sp else 12.sp,
                        height = 34.dp
                    )
                }
            }
        }

        // turn 調整値の表示（旧 t0 ボタンの位置）。操作は D-pad / L2 R2。
        HudPanel(
            modifier = Modifier
                .width(165.dp)
                .offset(498.dp, 163.dp),
            accent = ControllerColors.Warning,
            // 下の hojustate 表示にかからないよう余白を詰めて少し上げてある。
            // 2026-11-07 に IDOUBK の行を足した（位置は変えていない。下に 1 行ぶん伸びる）。
            contentPadding = 4.dp
        ) {
            HudSectionTitle(
                if (settingsSynced) "TURN ADJUST  /  ${selectedTurnTarget.displayName(hataAdjustNear, hataO, hojuIdoubake)}" else "TURN ADJUST  /  SYNC...",
                if (settingsSynced) ControllerColors.Warning else ControllerColors.Danger,
                10.sp
            )
            // 行の間隔をなくしてある。行が増えたぶんはパネルを上げて、下の hojustate にかからないようにしてある。
            Column {
                Text("BS2    X ${"%.2f".format(bs2TurnX)} Y ${"%.2f".format(bs2TurnY)} T ${"%.2f".format(bs2TurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
                Text("BS3    X ${"%.2f".format(bs3TurnX)} Y ${"%.2f".format(bs3TurnY)} T ${"%.2f".format(bs3TurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
                Text("FAR    X ${"%.2f".format(hataFarTurnX)} Y ${"%.2f".format(hataFarTurnY)} T ${"%.2f".format(hataFarTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
                Text("HATA_T X ${"%.2f".format(hataTTurnX)} Y ${"%.2f".format(hataTTurnY)} T ${"%.2f".format(hataTTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
                Text("HATA_O X ${"%.2f".format(hataOTurnX)} Y ${"%.2f".format(hataOTurnY)} T ${"%.2f".format(hataOTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
                Text("HOJU   X ${"%.2f".format(hojuTurnX)} Y ${"%.2f".format(hojuTurnY)} T ${"%.2f".format(hojuTurnTheta)}", color = ControllerColors.TextPrimary, fontSize = 8.sp)
                // 移動バケツの turn。HOJU の位置決め中に SELECT で idoubake にしている間は色を変える（TURN ADJUST が動かすのはこの行）。
                Text(
                    "IDOUBK X ${"%.2f".format(idoubakeTurnX)} Y ${"%.2f".format(idoubakeTurnY)} T ${"%.2f".format(idoubakeTurnTheta)}",
                    color = if (hojuIdoubake) ControllerColors.Warning else ControllerColors.TextPrimary,
                    fontSize = 8.sp
                )
            }
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
                // 右上の列（t0）に高さを回すため 47dp から細くした。
                height = 35.dp,
                fontSize = 15.sp,
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

/** TURN ADJUST の見出し。旗は調整している方（HATA_T / HATA_O / FAR）も出す。 */
fun TurnTarget.displayName(hataNear: Boolean, hataO: Boolean, hojuIdoubake: Boolean = false): String = when (this) {
    TurnTarget.HATA -> when {
        !hataNear -> "HATA FAR"
        hataO -> "HATA_O"
        else -> "HATA_T"
    }
    TurnTarget.HOJU -> if (hojuIdoubake) "HOJU (IDOUBAKE)" else "HOJU"
    TurnTarget.BS2 -> "BS2"
    TurnTarget.BS3 -> "BS3"
}

/** 押しっぱなしで連続調整する対象か。旗を狙う HATA が対象。 */
private fun TurnTarget.usesHoldRepeat(): Boolean =
    this == TurnTarget.HATA

/** send() ?????? "object" ?????????????? */
private fun objectDisplayName(id: String): String =
    FIELD_OBJECTS.firstOrNull { it.id == id }?.label ?: "---"

/** ???????3?????1?? */
@Composable
private fun ObjectSelectButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    height: androidx.compose.ui.unit.Dp = 40.dp
) {
    HudButton(
        text = if (selected) "▶  $label" else label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (selected) Color(0xFF123B4A) else ControllerColors.Surface2,
        contentColor = if (selected) ControllerColors.Accent else ControllerColors.TextSecondary,
        height = height,
        fontSize = fontSize,
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
