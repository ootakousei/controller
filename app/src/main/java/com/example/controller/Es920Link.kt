package com.example.controller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ============================================================
// ES920LR（EASEL 920MHz LoRa / FSK、USB は CH340）で、UDP と同じパケットをロボットの PC へ送る。
// PC 側は ~/kazusa/ver0/es920lr（es920lr_node.py）。起動の手順と設定はそちらに合わせてある。
// 全体の説明は ~/kazusa/ver0/LORA.md。
// ============================================================

// --- 無線の設定。PC 側（~/kazusa/ver0/es920lr/config/es920lr.yaml）の値をそのまま使う。
// 違うのは次の 2 つだけ。
//   - node / ownid / dstid は PC と逆向き（組 A は PC: enddevice 0001 -> 0000、スマホ: coordinator 0000 -> 0001。
//     組 B は 0003 -> 0002 / 0002 -> 0003。下の ES920_LINK_COUNT の説明）
//   - retry は 0（PC 側は 3。ただし両側とも ACK なしなので使われない）
// yaml の protocol / panid（LoRa なら bw_khz / sf、FSK なら rate_kbps）を変えたら、
// ここも同じ値にすること（そろっていないと届かない）。
// チャンネルは組ごとにフィールド選択画面で選ぶ（SharedPreferences に残る）。PC 側は届かなくなると全チャンネルを探して
// 追いかけてくるので、PC 側の channel（探し始めるチャンネル）とそろえなくても届く。
// 設定はフラッシュに保存しない（起動のたびに書き込む）。大会ではチャンネルを決まったものにする。
private const val ES920_BAUDRATE = 115200           // yaml の baudrate
private const val ES920_VENDOR_ID = 0x1a86          // CH340 系
private const val ES920_PRODUCT_ID = 0x7522         // 実機のケーブルの ID（ライブラリの標準の表に無いので足す。CH340 と同じ手順で動く）

private enum class Es920Protocol { LORA, FSK }
// ★ LoRa / FSK の切り替え。PC 側 es920lr.yaml の protocol と必ずそろえる。
private val ES920_PROTOCOL = Es920Protocol.LORA

// --- 2026-11-09〜 組 A だけで使う（スマホに 1 台、PC に 1 台）。2 組に戻すなら下の 3 つの定数と、PC の
//   202689master.py の ES920LR_LINKS、con.py の LORA_LINKS をそろえて戻す（COUNT = 2、NAMES = A, B、CHANNELS = 10, 16）。
//   2 組のまま 1 台ずつ挿すと、スマホと PC で別の組を取って通信できないことがあるので、台数と組の数は必ずそろえる。
// --- 以下は 2 組を並べて使うときの説明（2026-11-06〜）。スマホに 2 台、PC に 2 台挿し、組ごとに別のチャンネルで同時に送る。
//   組 A: スマホ 0000 → PC 0001（前からの組）、組 B: スマホ 0002 → PC 0003。PAN ID は同じ。
//   PC 側は es920lr ノードを 2 つ動かす（B は名前空間 es920lr_b。ownid / dstid は launch で入れる）。
//   PC のノードは送り元 ID が相手のものだけを受け取るので、探しているときに別の組の行を拾っても止まらない。
//   どちらの組も、その時点の最新のパケット（通し番号入り）を待たずに送る。片方の組が送れなくても（混信・抜けた・PC 側が落ちた）
//   もう片方だけで届くよう、2 組で同じ番号を重ねて送ってよい（落ちにくさを優先する）。
//   con.py は両方を受け取り、今までどおり番号が新しいものだけを使う（同じ番号の 2 つ目は捨てる）。
//   同じ番号は 2 組で中身もそろえる（短い押しを足すかどうかも同じ。Es920Group.pressedFor）。
//   どのモジュールがどの組かは決めていない。空いているモジュールを開き、起動のたびに組の ID を書き込む。
const val ES920_LINK_COUNT = 1
val ES920_LINK_NAMES = listOf("A")
// チャンネルの既定値（まだ画面で選んでいないとき）。PC 側の launch の channel と同じにしておくと、すぐ届く。
// LoRa 250kHz と FSK 100 / 150kbps はどちらも 1〜19 で、10 = 924.3 MHz（同じ周波数）。
// 2 台のモジュールはスマホの上で数 cm しか離れていないので、片方の送信がもう片方の受信を邪魔しないよう離しておく。
val ES920_DEFAULT_CHANNELS = listOf(10)
// 2 組のチャンネルがこれより近いと、画面で注意を出す（同じチャンネルは選べない）。
const val ES920_MIN_CHANNEL_GAP = 3
/** 組の自分の ID（スマホ側）。A: 0000、B: 0002。 */
private fun es920OwnId(link: Int) = "%04X".format(link * 2)
/** 組の相手の ID（PC 側）。A: 0001、B: 0003。 */
private fun es920DstId(link: Int) = "%04X".format(link * 2 + 1)
// 1〜7 は送るたびにモジュールが 50 ms 休む（遅い）。8〜19 は休止 2 ms だが、1 時間の送信時間の合計に上限がある（アプリでは数えていない）。
val ES920_SLOW_CHANNELS = 1..7

/** プロトコルごとに違うところ。 */
private class Es920Profile(
    val name: String,
    /** protocol とプロトコル専用の設定。コマンドの体系が変わるので protocol を最初に送る。 */
    val radioCommands: List<String>,
    /**
     * 設定値を送るときの形。true: 1 回 50 バイトまで（LoRa）なので、操作 + 設定値 29 バイトずつの 45 バイトを 3 回。
     * false: 操作 + 設定値 87 バイトの 103 バイトを 1 回（FSK は 225 バイトまで）。設定値を送らない回は、どちらも 16 バイト。
     */
    val chunked: Boolean,
    /** 1 回の送信の空中時間の見積もり [µs] = base + perByte × 文字数。少し多めにしてある。 */
    val airtimeBaseUs: Long,
    val airtimePerByteUs: Long,
    /** 送る周期 [ms]。Wi-Fi が切れたとき / Wi-Fi が切れていて SYNC 待ち / Wi-Fi が生きているとき。0 は返事が来たらすぐ次。 */
    val periodFastMs: Long,
    val periodSyncMs: Long,
    val periodSlowMs: Long,
    /**
     * 1 回送ったあと（返事が来てから）、次を送るまでに最低これだけ受信する時間 [ms]。
     * 周期の残りがこれより長ければ周期のほうを使う。0 なら周期だけで決める。
     */
    val listenMs: Long,
    /** 使えるチャンネル（画面の選択肢とスキャンの範囲）。 */
    val channels: IntRange = 1..19,
    /** チャンネル 1 の中心周波数と、1 つごとの間隔 [MHz]（画面の表示用）。 */
    val freqBaseMhz: Double = 920.7,
    val freqStepMhz: Double = 0.4
)

private val ES920_PROFILE = when (ES920_PROTOCOL) {
    // LoRa 250kHz / SF5、チャンネル 8〜19。速さの上限を見るための設定。
    // 周期は 0 で、返事（OK）が来たらすぐ次を送る。1 回は UART 44 文字（約 4 ms）+ キャリアセンス 1 ms +
    // 空中 約 27.5 ms + 休止 2 ms で 約 35 ms。Wi-Fi が切れている間は 1 秒に 1 回 100 ms 受信する（自己位置、
    // ES920_POSE_*）ので、計算では 約 26 Hz。空中にいる割合は 約 72%。
    // 空中時間はデータシート p.13 の 250kHz / SF5（10 byte 13 ms、50 byte 30 ms）から 8.75 ms + 0.425 ms/byte。
    Es920Protocol.LORA -> Es920Profile(
        name = "LoRa 250kHz SF5",
        radioCommands = listOf(
            "protocol 1",  // protocol: lora
            "bw 5",        // bw_khz: 250
            "sf 5"         // sf: 5
        ),
        chunked = true,
        airtimeBaseUs = 9000L,
        airtimePerByteUs = 430L,
        periodFastMs = 0L,
        periodSyncMs = 0L,     // SYNC 待ちでも同じ。PC の返事（設定値）は受信の印で受ける（ES920_SYNC_LISTEN_*）
        periodSlowMs = 0L,
        listenMs = 0L          // 毎回 30 ms 空けても LoRa の 1 行（約 27.5 ms）はほぼ入らないので、まとめて受信する（ES920_POSE_*）
    )
    // FSK 150kbps。1 周期に 1 回だけ送り、残りの時間は受信する。
    // 1 回は UART 84 文字（約 7.5 ms）+ キャリアセンス 1 ms + 空中 約 7 ms + 休止 2 ms で 約 18 ms。
    // 空中時間はデータシート p.15 の 150kbps（10 byte 3.15 ms、50 byte 5.28 ms）から 2.62 ms + 53.3 µs/byte。
    Es920Protocol.FSK -> Es920Profile(
        name = "FSK 150kbps",
        radioCommands = listOf(
            "protocol 3",  // protocol: fsk
            "rate 3"       // rate_kbps: 150。チャンネルの範囲が変わるので channel より先に送る
        ),
        chunked = false,
        airtimeBaseUs = 2700L,
        airtimePerByteUs = 54L,
        periodFastMs = 50L,    // 20 Hz。受信の時間は 1 周期に 約 30 ms
        periodSyncMs = 150L,   // PC の返事は、こちらが送ってから 約 50 ms 後に届く。それを待てるだけ受信する
        periodSlowMs = 50L,    // Wi-Fi が生きていても絞らない。con.py は通し番号で UDP と新しい方を使う
        listenMs = 0L          // 受信の時間は周期の残り（50 ms 周期なら 約 30 ms）で足りる
    )
}

/** モジュールに書き込む設定（チャンネル ch、組 link）。 */
private fun es920Commands(ch: Int, link: Int) = ES920_PROFILE.radioCommands + listOf(
    "node 1",        // coordinator（PC 側は enddevice）
    "channel $ch",
    "panid 5A2C",    // panid: 5A2C
    "ownid ${es920OwnId(link)}",    // PC 側の dstid
    "dstid ${es920DstId(link)}",    // PC 側の ownid
    "ack 2",         // ACK なし（PC 側も ack: false）。最新の操作を送り続けるので、届いたかの確認と再送はしない
    "retry 0",
    "transmode 1",   // 送り先は dstid に固定（PC 側ノードと同じ）
    "rssi 1",        // rssi: true
    "rcvid 1",       // rcvid: true
    "power 13",      // power_dbm: 13
    // format は最後（PC 側ノードと同じ）。BINARY にすると、このあとの返事が [長さ] + "OK" の形に変わるかもしれないため
    if (ES920_BINARY) "format 2" else "format 1"
)

private const val ES920_BANNER_CONFIG = "Select Mode [1.terminal or 2.processor]"
private const val ES920_BANNER_OPERATION = "operation mode is ready"
private const val ES920_BOOT_TIMEOUT_MS = 2000L
private const val ES920_COMMAND_TIMEOUT_MS = 1000L
// 送信の返事（OK / NG）を待つ時間。これが続けて ES920_MAX_TIMEOUTS 回来なければ開き直す。
private const val ES920_SEND_TIMEOUT_MS = 1000L
private const val ES920_MAX_TIMEOUTS = 3
private const val ES920_RETRY_INTERVAL_MS = 1000L
// 実際に送れている頻度をログに出す間隔。
private const val ES920_STATS_INTERVAL_MS = 5000L
// キャリアセンスで送れなかった（同じチャンネルを誰かが使っている）ときの返事。
private const val ES920_REPLY_BUSY = "NG 102"

// --- チャンネルの知らせ（PC の es920lr_node.py の CHANNEL_HINT_* と必ず合わせる）。
// 「スマホはこのチャンネルにいる（これから移る）」という 4 バイト:
//   0 u8 LORA_CHANNEL_TAG / 1 u8 チャンネル / 2 u8 LORA_CHANNEL_TAG / 3 u8 0〜2 バイトの和の下位 8 bit
// es920lr ノードが受け取って自分のチャンネルを合わせる（con.py には渡さない）。
// チャンネルを変えるときは、今のチャンネルでこれを ES920_HINT_OK 回（OK が返るまで、最大 ES920_HINT_TRIES 回）送ってから移る。
// スキャンで送る試しの 1 回分もこれ（中身は今のチャンネル）。PC がたまたまそのチャンネルを探していれば、戻ってくる。
private const val LORA_CHANNEL_TAG: Byte = 0x43
private const val ES920_HINT_OK = 3
private const val ES920_HINT_TRIES = 10
// スキャン: 1 チャンネルにつき何回送ってみるか（NG 102 の割合を見る）。
// 1 チャンネルは設定のかけ直し 約 0.4 秒 + 送信（8〜19 は 1 回 約 15 ms、1〜7 は 50 ms 休むので 約 65 ms）。19 チャンネルで 約 11 秒。
// 使用中のチャンネルは NG 102 が 1 回 約 0.1 秒かかる（全チャンネル使用中なら 約 17 秒）。
private const val ES920_SCAN_PROBES = 5
// スキャン中、1 チャンネルでこの回数返事が来なければ、モジュールがおかしいとみて開き直す。
private const val ES920_SCAN_MAX_TIMEOUTS = 2
// UDP の自己位置がこの時間届いていなければ Wi-Fi が切れたとみなし、周期を periodFastMs にして、状態の行も受信する。
// con.py は UDP と ES920LR の両方を受け取り、通し番号が新しい方を使う（切り替えの待ちは無い）。
const val ES920_WIFI_TIMEOUT_MS = 150L

// --- 試験モード（MainActivity の ES920_AIRTIME_TEST）。モジュール自身が 360 秒で送信を止めるかを確かめる。
// Wi-Fi・SYNC に関係なく周期 0 で送り続ける。
// 数えるのはモジュールを起動してから OK が返った送信だけ（モジュールの数え方に近づける）。
// 360 秒を過ぎても OK が返り続け、ES920_AIRTIME_TEST_STOP_US に届いたら「制限しない」と判定して送るのをやめる。
// 見積もりが少し多め（約 2%）なので、余裕を見て 380 秒まで送る。
private const val ES920_AIRTIME_LAW_US = 360_000_000L
private const val ES920_AIRTIME_TEST_STOP_US = 380_000_000L

// 返事（OK / NG xxx）。BINARY では前に長さ 1 バイトが付き、改行は付かない（説明書 10.2）。
private val REPLY_REGEX = Regex("OK|NG \\d{3}")
// BINARY の 1 回分の長さの上限（RSSI 4 + PAN ID 4 + ID 2 つ 8 + FSK の最大 225）。これを超える長さはずれとみる。
private const val BINARY_MAX_FRAME = 16 + 225
// BINARY で、読みかけの 1 回分がこの時間そろわなければ、ずれたとみて読みかけを捨てて読み直す [ms]。
private const val BINARY_FRAME_TIMEOUT_MS = 200L

private const val ES920_PERMISSION_ACTION = "com.example.controller.ES920_USB_PERMISSION"
// USB の許可ダイアログの結果がこの時間来なければ、頼み直す [ms]（ダイアログが出なかったときに許可待ちのまま止まらないように）
private const val ES920_PERMISSION_TIMEOUT_MS = 20_000L
// 挿したモジュールに許可が無くても、最初に見つけてからこの時間は許可ダイアログを出さない [ms]（2026-11-08）。
// 「常に」を選んであれば、Android が挿したときに許可を出す（UsbAttachActivity）ので、それを待つ
private const val ES920_AUTO_GRANT_WAIT_MS = 2000L

// --- モジュールとの間の形（PC 側 es920lr.yaml の format と必ずそろえる。そろっていないと読めない）。
// true : BINARY（format 2）。[長さ 1 バイト] + 中身 で送り、受け取りも [長さ] + RSSI・PAN ID・ID（文字）+ 中身。改行なし。
//        base64 にしないぶん短い（LoRa は 1 文字 約 0.43 ms 空中にいるので、そのまま速さに効く）。
// false: ASCII（format 1）。中身を base64 の文字にして、改行で区切る。
// どちらでも中身のバイト列（下の LORA_* の並び）は同じ。
private const val ES920_BINARY = true

// --- 無線で送る 1 回分の中身（PC 側は con.py の decode_lora_bytes / LoraAssembler。必ず合わせる）
//   offset  size  中身
//    0      14    パケットの 0〜13 バイト（PACKET_VERSION / map / object / target / ボタン・通し番号 / vx vy w）
//   14      1     bit0-1: 設定値の何番目の 29 バイトか（0〜2。29 バイトに分けて送るときだけ意味がある）
//                 bit7  : このあと受信する印（LORA_LISTEN_FLAG）
//   15      1     設定値の番号（0〜255）。設定値・マップ・synced のどれかが変わるたびに 1 増やす
//   16      ...   設定値。種類は長さで決まる:
//     16 バイト（操作だけ）: 設定値なし。ふだんはこれ（短いので速く送れる）
//     45 バイト（分割）    : 設定値の 29×n 〜 29×n+28 バイト（LoRa。1 回 50 バイトまでなので 3 回に分ける）
//     103 バイト（まるごと）: 設定値 87 バイト（FSK）
// 設定値はパケットの 14〜100 バイトと同じ並び・倍率（最後の 1 バイトは空き）。2026-11-08 に 28 → 29 バイト（version 18）。
// 番号を進めたときに設定値を写し取り、その番号の分割はすべて写しから送る（別の時刻の値を混ぜないため）。
// 設定値を送るのは、変わったとき（ES920_SETTINGS_ROUNDS_ON_CHANGE 回ぶん）と、PC に届いた確認が取れていない間の
// ES920_SETTINGS_REFRESH_MS ごと。PC は持っている設定値の番号を自己位置の行で知らせてくる（下の ES920_POSE_*）。
// PC は、届いた行の番号の設定値をまだ持っていない間は、届いた設定値を使わず保存している値を使う。
private const val LORA_HEAD_SIZE = 14
// パケットの 4〜7 バイト目のボタン類（u32、リトルエンディアン）。
private const val LORA_BUTTONS_OFFSET = 4
// 押した瞬間を落としたくないボタン（bit0〜14: 十字キー / ○△□× / L1 L2 R1 R2 / START SELECT PS）。
// con.py の MOMENTARY_BUTTON_KEYS と同じもの。レーザー・t0・synced などの状態を表すビットは足さない。
private const val LORA_MOMENTARY_MASK = 0x7FFF
// パケットの通し番号（ボタン類の u32 の bit22-31）。MainActivity の packetSeq、con.py の PACKET_SEQ_SHIFT と同じ。
private const val LORA_SEQ_SHIFT = 22
private const val LORA_SEQ_MASK = 0x3FF

/** パケットの 4〜7 バイト目のボタン類。 */
private fun buttonsOf(packet: ByteArray): Int {
    var v = 0
    for (i in 0 until 4) v = v or ((packet[LORA_BUTTONS_OFFSET + i].toInt() and 0xFF) shl (8 * i))
    return v
}
private const val LORA_CHUNK_SIZE = 29
private const val LORA_CHUNK_COUNT = 3
private const val LORA_SETTINGS_SIZE = LORA_CHUNK_SIZE * LORA_CHUNK_COUNT
private const val LORA_CONTROL_SIZE = LORA_HEAD_SIZE + 2
private const val LORA_CHUNK_FRAME_SIZE = LORA_CONTROL_SIZE + LORA_CHUNK_SIZE
private const val LORA_FULL_FRAME_SIZE = LORA_CONTROL_SIZE + LORA_SETTINGS_SIZE
// 相手のバケツの位置（2026-11-08）。操作だけの 16 バイトの後ろに、MainActivity の enemyUpBytes()（8 バイト:
// ENEMY_TAG / i16 x / i16 y / i16 z / 和）を付けた 24 バイトの行で送る。con.py の LORA_ENEMY_FRAME_SIZE / decode_enemy と合わせる。
// 設定値を載せない回に、PC に届いたと分かるまでは ES920_ENEMY_RESEND_MS ごと、分かってからは ES920_ENEMY_CHECK_MS ごとに載せる。
// 届いたかは、状態の行の 25〜31 バイト（PC が持っている値）で分かる（Wi-Fi が生きている間は UDP の返事で分かる）。
private const val LORA_ENEMY_BLOCK_SIZE = 8
private const val LORA_ENEMY_FRAME_SIZE = LORA_CONTROL_SIZE + LORA_ENEMY_BLOCK_SIZE
private const val ES920_ENEMY_RESEND_MS = 300L
private const val ES920_ENEMY_CHECK_MS = 5000L
// 設定値が変わったら、続けて何回ぶん（分割なら 3 回で 1 回ぶん）送るか。1 回落としても届くように 2。
private const val ES920_SETTINGS_ROUNDS_ON_CHANGE = 2
// PC に届いた確認が取れていない間（Wi-Fi が生きていて自己位置を受け取らない間など）、設定値を送り直す間隔 [ms]。
private const val ES920_SETTINGS_REFRESH_MS = 1000L
// 確認が取れてからこの時間は送り直さない [ms]。自己位置（確認）は 1 秒に 1 回届くので、それより長くする。
private const val ES920_SETTINGS_CONFIRM_VALID_MS = 2500L
// 設定値を変えてからこの時間たっても PC に届いた確認が取れなければ、ログに出す [ms]（自己位置を受け取れるときだけ）。
private const val ES920_SETTINGS_CONFIRM_WARN_MS = 2000L

// --- 無線で受け取る 1 回分（PC の con.py が、同期待ちのスマホへ設定値を送り返す）
// UDP（ログ用ポート 5006）が届かず ES920LR だけでつながっているときの SYNC 用。con.py の LORA_SETTINGS_* と合わせる。
// con.py は、こちらが送ってきた形（3 分割かどうか）に合わせて返してくる。
// FSK: 次の 89 バイトを 1 回。
//    0  1  LORA_SETTINGS_TAG / 1  1  map_id（PACKET_MAP_IDS の番号）/ 2  87  設定値
// LoRa: 次の 32 バイトを 3 回。
//    0  1  LORA_SETTINGS_TAG / 1  1  map_id / 2  1  何番目の 29 バイトか（0〜2）/ 3  29  設定値の 29×n〜
// 設定値は送るパケットの 14〜100 バイトと同じ並び・倍率。
// 受け取ったデータの先頭には RSSI と送り元の PAN ID・ID が付く（rssi 1 / rcvid 1 のため）。
// その長さに頼らないよう、末尾を決まった長さ（下の受け取る種類の長さ）ごとに読んでみる。
private const val LORA_SETTINGS_TAG: Byte = 0x53
private const val LORA_SETTINGS_FULL_SIZE = 2 + LORA_SETTINGS_SIZE
private const val LORA_SETTINGS_CHUNK_SIZE = 3 + LORA_CHUNK_SIZE

// --- Wi-Fi で届くもの（自己位置・/log）を ES920LR でも受け取る（con.py の LORA_LISTEN_FLAG / LORA_STATUS_* /
// LORA_LOG_* と必ず合わせる）。
// Wi-Fi が切れていて SYNC 待ちでない間、ES920_POSE_INTERVAL_MS ごとに 1 回、送る回に「このあと受信する」の印
// （offset 14 の bit7）を付け、返事（OK）から最大 ES920_POSE_LISTEN_MS の間は送らずに受信する。
// 状態の行が届いたら、そこで受信をやめてすぐ送信に戻る（操作の Hz をなるべく落とさないため）。
// con.py は印を見たら、積んであるログの行を 1 つ（あれば）、続けて状態の行を送ってくる:
//   状態の行（34 バイト）:
//     0 u8 LORA_STATUS_TAG / 1 15 バイト 自己位置（UDP の自己位置と同じ並び）/ 16 8 バイト hojustate（ASCII、余りは 0）/
//     24 u8 invalid_target の番号 / 25 7 バイト PC が持っている相手のバケツの位置（u8 has / i16 x / i16 y / i16 z。2026-11-08 追加）/
//     32 u8 LORA_STATUS_TAG / 33 u8 1〜31 バイトの和の下位 8 bit
//     自己位置の 13 / 14 バイト目に、PC が持っている設定値の状態が入る（設定値が届いたかの確認）:
//       13: bit0 = 設定値を持っている / 14: その番号
//   ログの行（/log の 1 行。4 + n バイト、n は 0〜LORA_LOG_MAX_BYTES）:
//     0 u8 番号 / 1 n バイト 文字（UTF-8）/ 1+n u8 n / 2+n u8 0〜n バイトの和の下位 8 bit / 3+n u8 LORA_LOG_TAG
//     PC は同じ行を 3 回の印に分けて送り直すので、番号が前と同じなら捨てる。
// ES920LR で届いた自己位置は画面に出すだけで、Wi-Fi の生死（wifiAlive）には使わない。
private const val ES920_POSE_INTERVAL_MS = 200L
private const val ES920_POSE_LISTEN_MS = 120L
// SYNC 待ちで periodSyncMs が 0 のとき（LoRa）は周期の残りで受信できないので、SYNC 待ちでも印を付ける。
// con.py は印を見たら、設定値の 3 行（1 行 約 35 ms）→ ログの行 → 状態の行、の順に送ってくる。
// それが入るよう 1 回の受信を長くして、回数を減らす。1 秒に 1 回 250 ms なので、操作は計算で 約 21 Hz。
private const val ES920_SYNC_LISTEN_INTERVAL_MS = 1000L
private const val ES920_SYNC_LISTEN_MS = 250L
private const val LORA_LISTEN_FLAG = 0x80
private const val LORA_STATUS_TAG: Byte = 0x50
private const val LORA_POSE_SIZE = 15
private const val LORA_HOJU_SIZE = 8
private const val LORA_ENEMY_ECHO_SIZE = 7
private const val LORA_STATUS_FRAME_SIZE = 1 + LORA_POSE_SIZE + LORA_HOJU_SIZE + 1 + LORA_ENEMY_ECHO_SIZE + 2
private const val LORA_LOG_TAG: Byte = 0x4C
private const val LORA_LOG_MAX_BYTES = 45
// PC の行がこの時間届かなければ、ログの番号を忘れる（番号は 256 で一周するので、長く途切れたあとに取りこぼさないため）[ms]
private const val LORA_LOG_SEQ_RESET_MS = 3000L

/** ES920LR のモジュールの状態（画面の生死の表示用）。 */
enum class Es920State { NO_DEVICE, STARTING, READY }

/** スキャンで 1 チャンネルを試した結果。ok: OK、busy: NG 102（キャリアセンス）、other: ほかの NG と時間切れ。 */
data class Es920ChannelScan(val channel: Int, val ok: Int, val busy: Int, val other: Int) {
    val sent get() = ok + busy + other
    /** 送ろうとした回のうち、誰かが使っていて送れなかった割合（0〜1）。 */
    val busyRate get() = if (sent > 0) busy.toDouble() / sent else 0.0
}

/** 5 秒ごとの送信の統計（ログと同じもの）。busy: NG 102、ng: ほかの NG、timeouts: 返事なし。 */
data class Es920Stats(
    val channel: Int, val hz: Double, val ok: Int, val busy: Int, val ng: Int, val timeouts: Int, val atMs: Long
) {
    val attempts get() = ok + busy + ng + timeouts
    val busyRate get() = if (attempts > 0) busy.toDouble() / attempts else 0.0
}

// --- リセットのかけ方（Es920Link.startModule）。
// RESET_HOLD_HIGH: DTR と RTS を一度下げてから立てたままにする（Serial USB Terminal で接続したときと同じ）。
//                  実機ではこれで起動する（RTS を戻してから 約 150 ms で起動メッセージ）。
// RESET_RTS_PULSE: RTS を 100 ms だけ立てる（PC 側ノードと同じ）。実機では起動メッセージが来なかった。
// RESET_NEWLINE:   リセットせず改行を送って反応を見る。
private const val RESET_RTS_PULSE = 0
private const val RESET_HOLD_HIGH = 1
private const val RESET_NEWLINE = 2
/** 試す順。startModule() は前に起動できたかけ方を先に試し、残りをこの順に試す。 */
private val RESET_ORDER = listOf(RESET_HOLD_HIGH, RESET_RTS_PULSE, RESET_NEWLINE)

/** ES920LR の準備ができていないなど、開き直せば直るかもしれない失敗。 */
private class Es920LinkException(message: String) : Exception(message)

/**
 * ES920LR の 2 組（A / B、ES920_LINK_COUNT）をまとめて動かす。MainActivity はこれだけを持つ。
 * 2 組で分け合うもの（USB のモジュール、USB の許可、短い押し、受信の時間の順番、ログの行の番号）はここに置く。コールバックは組ごとの Es920Link にそのまま渡す。
 * onScan / onStats は、どの組のものか（0 = A、1 = B）を最初の引数で渡す。
 */
class Es920Group(
    private val context: Context,
    private val log: (String) -> Unit,
    onSettings: (Int, ByteArray) -> Unit,
    waitingSettings: () -> Boolean,
    wifiAlive: () -> Boolean,
    airtimeTest: Boolean = false,
    onStatus: (ByteArray, String, Int) -> Unit = { _, _, _ -> },
    onLog: (String) -> Unit = {},
    // 状態の行で、PC が持っている相手のバケツの位置が届いたら呼ぶ（x, y, z。持っていなければ null）。IO スレッドから呼ぶ。
    onEnemyEcho: (IntArray?) -> Unit = {},
    // 組ごとの起動するチャンネル（画面で選んで保存してあるもの）
    initialChannels: List<Int> = ES920_DEFAULT_CHANNELS,
    onScan: (Int, List<Es920ChannelScan>, Int) -> Unit = { _, _, _ -> },
    onStats: (Int, Es920Stats) -> Unit = { _, _ -> },
    // USB の許可ダイアログを出す前に呼ぶ。画面ピン留め中はダイアログが出ないので、MainActivity はここでピン留めを外し、
    // 外し終えてから show() を呼ぶ（呼ばないとダイアログは出ない）。既定はすぐ出す。IO スレッドから呼ぶ。
    private val beforePermissionDialog: (show: () -> Unit) -> Unit = { it() },
    // 許可ダイアログの結果が来たら呼ぶ（MainActivity はピン留めし直す）。メインスレッドから呼ぶ。
    private val afterPermissionDialog: () -> Unit = {}
) {
    internal val usbManager: UsbManager = context.getSystemService(UsbManager::class.java)

    val links: List<Es920Link> = List(ES920_LINK_COUNT) { i ->
        Es920Link(
            group = this, index = i, log = log, onSettings = onSettings, waitingSettings = waitingSettings,
            wifiAlive = wifiAlive, airtimeTest = airtimeTest, onStatus = onStatus, onLog = onLog,
            onEnemyEcho = onEnemyEcho,
            initialChannel = initialChannels.getOrElse(i) { ES920_DEFAULT_CHANNELS[i] },
            onScan = { results, ch -> onScan(i, results, ch) },
            onStats = { stats -> onStats(i, stats) }
        )
    }

    // 相手のバケツの位置の上りの 8 バイト（null は送らない）と、PC に届いたと分かっているか。MainActivity が setEnemy で入れる
    @Volatile
    internal var enemyBlock: ByteArray? = null
        private set
    @Volatile
    internal var enemyConfirmed = false
        private set

    /** 相手のバケツの位置（上りの 8 バイト）と、PC に届いたと分かっているかを渡す。どの組も設定値を載せない回に載せる。 */
    fun setEnemy(block: ByteArray, confirmed: Boolean) {
        enemyBlock = block
        enemyConfirmed = confirmed
    }

    /** 使えるチャンネル。 */
    val channels: IntRange get() = ES920_PROFILE.channels
    /** 無線の方式の名前（画面の表示用）。 */
    val profileName: String get() = ES920_PROFILE.name
    /** チャンネルの中心周波数 [MHz]。 */
    fun freqMhz(ch: Int): Double = ES920_PROFILE.freqBaseMhz + ES920_PROFILE.freqStepMhz * (ch - 1)

    fun start(scope: CoroutineScope) {
        ContextCompat.registerReceiver(
            context, permissionReceiver, IntentFilter(ES920_PERMISSION_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        links.forEach { it.start(scope) }
    }

    fun close() {
        links.forEach { it.close() }
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
    }

    /** 送りたいパケット（send() で作った 92 バイト）と、その synced を両方の組に渡す。待たない。 */
    fun offer(packet: ByteArray, synced: Boolean) {
        // 送る間に来たパケットは捨てるので、そこで押されていたボタンは次に送るパケットに足す（pressedFor）。
        pressedSinceSend.getAndUpdate { it or (buttonsOf(packet) and LORA_MOMENTARY_MASK) }
        links.forEach { it.offer(packet, synced) }
    }

    // --- USB のモジュール。2 組が同じモジュールを開かないよう、開いているもの（deviceName）を覚える。

    private val claimed = mutableSetOf<String>()

    private fun es920Devices() = usbManager.deviceList.values.filter { it.vendorId == ES920_VENDOR_ID }

    /** まだどの組も使っていないモジュールを 1 つ取る。無ければ null。 */
    @Synchronized
    internal fun claimDevice(): UsbDevice? =
        es920Devices().sortedBy { it.deviceName }.firstOrNull { it.deviceName !in claimed }
            ?.also { claimed += it.deviceName }

    @Synchronized
    internal fun releaseDevice(device: UsbDevice) {
        claimed -= device.deviceName
    }

    /** まだどの組も使っていないモジュールが挿さっているか。 */
    @Synchronized
    internal fun hasFreeDevice(): Boolean = es920Devices().any { it.deviceName !in claimed }

    // --- USB の許可。ダイアログは 1 つずつ出す。

    /**
     * USB の許可ダイアログを出している間は true。MainActivity の onPause はダイアログでも呼ばれるので、
     * この間はアプリを終了しないようにする。
     */
    @Volatile
    var permissionPending = false
        private set
    // 許可を頼んだ時刻。ES920_PERMISSION_TIMEOUT_MS たっても結果が来なければ（ダイアログが出なかったなど）頼み直す
    @Volatile
    private var permissionRequestedAtMs = 0L

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            permissionPending = false
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log(if (granted) "LORA USB PERMISSION OK" else "LORA USB PERMISSION DENIED")
            afterPermissionDialog()
        }
    }

    // 許可の無いモジュールを最初に見つけた時刻（deviceName ごと）。ES920_AUTO_GRANT_WAIT_MS の間は頼まない
    private val noPermissionSinceMs = mutableMapOf<String, Long>()

    @Synchronized
    internal fun requestPermission(device: UsbDevice) {
        val now = System.currentTimeMillis()
        // 挿した直後は Android の自動の許可（UsbAttachActivity の「常に」）を待つ。来なければダイアログで頼む
        val since = noPermissionSinceMs.getOrPut(device.deviceName) { now }
        if (now - since < ES920_AUTO_GRANT_WAIT_MS) return
        if (permissionPending) {
            if (now - permissionRequestedAtMs < ES920_PERMISSION_TIMEOUT_MS) return
            log("LORA USB PERMISSION: no answer in ${ES920_PERMISSION_TIMEOUT_MS / 1000} s, asking again")
        }
        permissionPending = true
        permissionRequestedAtMs = now
        log("LORA USB PERMISSION REQUESTED (${device.deviceName})")
        beforePermissionDialog {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ES920_PERMISSION_ACTION).setPackage(context.packageName)
            usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }
    }

    // --- 送るものの分け合い

    /** どの組もまだ送っていないパケットで押されていたボタン（LORA_MOMENTARY_MASK のビット）。 */
    private val pressedSinceSend = AtomicInteger(0)
    // 短い押しを割り当てた最近のパケット（ボタン類の u32。通し番号入りなので、パケットごとに違う）と、割り当てた押し
    private val pressedByKey = LinkedHashMap<Int, Int>()
    private var lastPressedSeq = -1    // 最後に割り当てたパケットの通し番号（-1 はまだ無い）

    /**
     * このパケットに足す短い押し。前に送ったパケットより後に押して離したボタンを、次に送る新しいパケット（通し番号）に割り当てる。
     * もう片方の組が同じパケットを送るときは、同じものを返す（同じ番号は 2 組とも同じ中身。con.py は片方だけを使う）。
     * 別の番号に同じ押しを足すと、con.py には 2 回押したように見えることがあるので、割り当ては 1 つの番号にだけ。
     * 割り当てたものより古い番号（もう片方の組が先に新しいものを送った）には足さない（con.py が捨てる番号に載せないため）。
     */
    @Synchronized
    internal fun pressedFor(packet: ByteArray): Int {
        val key = buttonsOf(packet)
        pressedByKey[key]?.let { return it }
        val seq = (key ushr LORA_SEQ_SHIFT) and LORA_SEQ_MASK
        // 通し番号は 1024 で一周する。差が 1〜511 なら新しい（con.py の PacketSeq と同じ見方）
        val diff = (seq - lastPressedSeq) and LORA_SEQ_MASK
        if (lastPressedSeq >= 0 && diff !in 1..LORA_SEQ_MASK / 2) return 0
        lastPressedSeq = seq
        val bits = pressedSinceSend.getAndSet(0)
        pressedByKey[key] = bits
        while (pressedByKey.size > 8) pressedByKey.remove(pressedByKey.keys.first())
        return bits
    }

    /**
     * 受信の印を付けた回（このあと PC の行を受信する）は、2 組で交互にとる。PC が送る行の数を 1 組のときと同じにし、
     * それぞれの組の操作が途切れる時間を半分にする。前の印から interval たっていれば true（その組が今回とる）。
     */
    private var lastListenMs = 0L

    @Synchronized
    internal fun takeListenWindow(now: Long, interval: Long): Boolean {
        if (now - lastListenMs < interval) return false
        lastListenMs = now
        return true
    }

    /**
     * PC のログの行は、どちらの組で届いても同じ番号なので、まとめて同じ行を捨てる。初めての行なら true。
     * PC の行がしばらく届かなければ番号を忘れる（番号は 256 で一周するので、長く途切れたあとに取りこぼさないため）。
     */
    private val recentLogSeqs = ArrayDeque<Int>()
    private var lastLogAtMs = 0L

    @Synchronized
    internal fun acceptLog(seq: Int, now: Long): Boolean {
        if (now - lastLogAtMs > LORA_LOG_SEQ_RESET_MS) recentLogSeqs.clear()
        lastLogAtMs = now
        if (seq in recentLogSeqs) return false
        recentLogSeqs.addLast(seq)
        while (recentLogSeqs.size > 4) recentLogSeqs.removeFirst()
        return true
    }
}

/**
 * ES920LR の 1 組（スマホ側の 1 台）。Es920Group が作る。
 * 開いて設定し、offer() で渡された最新のパケットを決まった周期で送る。
 * 1 周期に 1 回、その時点の最新のパケットを送り、残りの時間は受信にあてる（周期は ES920_PROFILE.period*）。
 * 間に来たパケットは捨てる（古い操作を後から送らない）。
 * USB が抜けたりモジュールが返事をしなくなったら、ES920_RETRY_INTERVAL_MS ごとに開き直す。
 */
class Es920Link internal constructor(
    private val group: Es920Group,
    /** 組の番号（0 = A、1 = B）。 */
    val index: Int,
    private val log: (String) -> Unit,
    // 設定値が届いたら呼ぶ（map_id の番号, 87 バイト）。IO スレッドから呼ぶ。
    private val onSettings: (Int, ByteArray) -> Unit,
    // true の間は SYNC 待ち（PC からの設定値を待っている）。IO スレッドから呼ぶ。
    private val waitingSettings: () -> Boolean,
    // true の間は Wi-Fi で PC とつながっている（UDP の自己位置が届いている）。IO スレッドから呼ぶ。
    private val wifiAlive: () -> Boolean,
    // true: 試験モード（ES920_AIRTIME_TEST_*）。周期 0 で送り続け、モジュールのふるまいを記録する。
    private val airtimeTest: Boolean = false,
    // ES920LR で状態の行が届いたら呼ぶ（自己位置 15 バイト（UDP の自己位置と同じ並び）, hojustate, invalid_target の番号）。
    // IO スレッドから呼ぶ。
    private val onStatus: (ByteArray, String, Int) -> Unit = { _, _, _ -> },
    // ES920LR でログの行（/log の 1 行）が届いたら呼ぶ。同じ行は 1 回だけ。IO スレッドから呼ぶ。
    private val onLog: (String) -> Unit = {},
    // 状態の行で PC が持っている相手のバケツの位置が届いたら呼ぶ（持っていなければ null）。IO スレッドから呼ぶ。
    private val onEnemyEcho: (IntArray?) -> Unit = {},
    // 起動するチャンネル（画面で選んで保存してあるもの）。
    initialChannel: Int,
    // スキャンの途中と終わりに呼ぶ（それまでの結果, 今試しているチャンネル。終わったら 0）。IO スレッドから呼ぶ。
    private val onScan: (List<Es920ChannelScan>, Int) -> Unit = { _, _ -> },
    // 5 秒ごとの統計。IO スレッドから呼ぶ。
    private val onStats: (Es920Stats) -> Unit = {}
) {
    /** 組の名前（"A" / "B"）。 */
    val name: String get() = ES920_LINK_NAMES[index]
    /** ログの頭（"LORA A" など）。 */
    private val tag = "LORA ${ES920_LINK_NAMES[index]}"

    /** モジュールに設定してあるチャンネル（切り替えが終わったら変わる）。 */
    @Volatile
    var channel = initialChannel.coerceIn(ES920_PROFILE.channels)
        private set
    /** 画面で選ばれたチャンネル。channel と違えば、送る合間に切り替える。 */
    @Volatile
    private var requestedChannel = channel
    /** true の間はスキャン中（チャンネルを順に切り替えている。操作は送らない）。 */
    @Volatile
    var scanning = false
        private set
    @Volatile
    private var scanRequested = false
    @Volatile
    private var scanCancel = false
    /** 送るものを待っている間に、チャンネルの切り替え・スキャンの頼みで起こすため。 */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** チャンネルを変える。待たない（送るループが合間に切り替える）。 */
    fun requestChannel(ch: Int) {
        if (ch !in ES920_PROFILE.channels) return
        requestedChannel = ch
        wake.trySend(Unit)
    }

    /** 全チャンネルの混み具合を調べる。モジュールが READY のときだけ。待たない（結果は onScan）。 */
    fun requestScan() {
        if (state != Es920State.READY || scanning) return
        scanCancel = false
        scanRequested = true
        wake.trySend(Unit)
    }

    /** スキャンを途中でやめる（コントローラーの画面に移るとき）。調べ終えた分は onScan で返す。 */
    fun cancelScan() {
        scanRequested = false
        scanCancel = true
    }

    /** モジュールの状態。 */
    @Volatile
    var state = Es920State.NO_DEVICE
        private set
    /** 最後に送信の返事 OK が来た時刻（System.currentTimeMillis）。 */
    @Volatile
    var lastSendOkMs = 0L
        private set
    /** 最後に PC からの行（状態・ログ・設定値）を受け取った時刻（System.currentTimeMillis）。 */
    @Volatile
    var lastPcRxMs = 0L
        private set
    private var statusLines = 0     // 受け取った状態の行の数（受信を早めに切り上げるため）

    /** offer() で渡すもの。packet は send() で作った 92 バイト、synced はそのパケットの synced。 */
    private class TxPacket(val packet: ByteArray, val synced: Boolean)

    /** 受け取ったもの（readEvent）。 */
    private sealed class RxEvent {
        /** 送信の返事（"OK" / "NG xxx"）。 */
        class Reply(val text: String) : RxEvent()
        /** 無線で受け取ったデータ。ASCII なら 1 行（先頭に RSSI・ID の文字、続けて base64）、BINARY なら中身のバイト列。 */
        class Data(val line: String?, val bytes: ByteArray?) : RxEvent()
    }

    private var rxPoseLines = 0
    /**
     * 最後に起動メッセージが来たリセットのかけ方（RESET_*）。次の startModule() はこれから試す。
     * 実機では RESET_RTS_PULSE では起動せず、RESET_HOLD_HIGH で起動していたので、そこから始める。
     */
    private var resetMethod = RESET_HOLD_HIGH
    /** 途中で切れてから READY に戻るまでを測るための、切れた時刻（0 は切れていない）。 */
    private var lostAtMs = 0L

    /** startModule() で測った時間 [ms]（リセットの信号を戻してから、起動メッセージまで / start に OK が返るまで）。 */
    private class StartTiming(val bootMs: Long, val readyMs: Long, val attempts: Int)
    /** 試験モード: モジュールを起動してから OK が返った送信の空中時間の合計 [µs]（見積もり）。 */
    @Volatile
    private var testAirUs = 0L
    private val usbManager get() = group.usbManager
    private val outbox = Channel<TxPacket>(Channel.CONFLATED)
    private var job: Job? = null
    private var port: UsbSerialPort? = null
    // 受信は SerialInputOutputManager に任せる（read() をタイムアウト付きで呼ぶ方法では 1 バイトも読めなかった）。
    private var ioManager: SerialInputOutputManager? = null
    private val rxQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile
    private var rxError: Exception? = null
    // モジュールから受け取って、まだ使っていないバイト列（BINARY では文字にできないのでバイトのまま持つ）
    private var rxBuf = ByteArray(4096)
    private var rxLen = 0
    // 受け取った最後の数十バイト（BINARY の運用中でも、モジュールの起動メッセージ（文字）に気づくため）
    private var rxTail = ""
    // BINARY: 読みかけの 1 回分がそろい始めた時刻（ずれたときに読み直すため）
    private var frameSinceMs = 0L

    // --- 送る設定値（encodeFrame）
    private var txKey: ByteArray? = null                 // 設定値 87 バイト + map + synced。変わったら番号を進める
    private var txGen = 0                                 // 設定値の番号（0〜255）
    private val txSettings = ByteArray(LORA_SETTINGS_SIZE) // 番号を進めたときに写し取った設定値
    private var txGenAtMs = 0L                            // 番号を進めた時刻
    private var nextChunk = 0                             // 次に送る分割（0〜2）。0 以外なら一巡の途中
    private var pendingRounds = 0                         // あと何回ぶん設定値を送るか
    private var lastRoundAtMs = 0L                        // 最後に設定値を送り終えた時刻
    // --- PC に届いたかの確認（自己位置の 13 / 14 バイト目）
    private var confirmedGen = -1                         // PC が持っていると知らせてきた番号（txGen と同じなら届いている）
    private var confirmedAtMs = 0L
    private var warnedGen = -1                            // 届かない警告を出した番号（1 回だけ出す）
    private var settingsFrames = 0                        // 統計の間に設定値を載せて送った回数

    // 最後に相手のバケツの位置を載せて送った時刻
    private var lastEnemyAtMs = 0L

    private val rxSettings = ByteArray(LORA_SETTINGS_SIZE)
    private val rxSettingsSeen = mutableSetOf<Int>()
    private var rxSettingsMap = -1
    private var rxSettingsLines = 0

    /** 送りたいパケット（send() で作った 92 バイト）と、その synced を渡す。待たない（Es920Group.offer から呼ぶ）。 */
    internal fun offer(packet: ByteArray, synced: Boolean) {
        outbox.trySend(TxPacket(packet, synced))
    }

    internal fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            var lastError: String? = null
            while (isActive) {
                var device: UsbDevice? = null
                try {
                    // もう片方の組が開いていないモジュールを取る
                    device = group.claimDevice() ?: throw Es920LinkException("not connected")
                    state = Es920State.STARTING
                    if (!usbManager.hasPermission(device)) {
                        group.requestPermission(device)
                        throw Es920LinkException("waiting USB permission")
                    }
                    open(device)
                    val ch = requestedChannel
                    val t = startModule(resetMethod, ch)
                    channel = ch
                    log("$tag READY: ${ES920_PROFILE.name} ${if (ES920_BINARY) "BINARY" else "ASCII"} ch $ch " +
                        "id ${es920OwnId(index)}->${es920DstId(index)} ${device.deviceName} " +
                        "(boot ${t.bootMs} ms, ready ${t.readyMs} ms, attempts ${t.attempts})")
                    if (lostAtMs > 0) {
                        // ふつうの開き直し（USB の閉じ直し + ES920_RETRY_INTERVAL_MS の待ち込み）で戻るまでの時間。
                        log("$tag RECOVERED in ${System.currentTimeMillis() - lostAtMs} ms after link lost")
                        lostAtMs = 0L
                    }
                    lastError = null
                    // モジュールをリセットし直したので、試験の数えはじめもここから。
                    testAirUs = 0L
                    state = Es920State.READY
                    sendLoop()
                } catch (e: Exception) {
                    // 同じ理由で失敗し続けている間は、ログは最初の 1 回だけ出す。
                    val msg = "${e.javaClass.simpleName} ${e.message}"
                    if (airtimeTest && testAirUs > 0) {
                        // 上限の前後でモジュールが再起動・無応答になったかを見るため、そのときの合計も出す。
                        log("$tag AIRTIME TEST: link lost at %.1f s (${e.message}). counter restarts after reopen".format(testAirUs / 1e6))
                    }
                    if (msg != lastError) log("$tag ERROR: ${e.message ?: e.javaClass.simpleName}")
                    lastError = msg
                    // 一度つながってから切れたときだけ測る（挿さっていない間などは数えない）。
                    if (lostAtMs == 0L && port != null) lostAtMs = System.currentTimeMillis()
                } finally {
                    closePort()
                    device?.let { group.releaseDevice(it) }
                    state = if (group.hasFreeDevice()) Es920State.STARTING else Es920State.NO_DEVICE
                }
                delay(ES920_RETRY_INTERVAL_MS)
            }
        }
    }

    internal fun close() {
        job?.cancel()
        closePort()
    }

    // ------------------------------------------------------------------ USB

    private fun open(device: UsbDevice) {
        val driver = UsbSerialProber(
            UsbSerialProber.getDefaultProbeTable().apply {
                addProduct(ES920_VENDOR_ID, ES920_PRODUCT_ID, Ch34xSerialDriver::class.java)
            }
        ).probeDevice(device)
            ?: throw Es920LinkException("no driver for USB device")
        val connection = usbManager.openDevice(device)
            ?: throw Es920LinkException("openDevice failed")
        val p = driver.ports.first()
        p.open(connection)
        port = p
        p.setParameters(ES920_BAUDRATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // RTS を立てたままだとモジュールがリセットされたまま動かない。
        p.dtr = false
        p.rts = false
        clearRx()
        rxBytes = 0
        rxQueue.clear()
        rxError = null
        ioManager = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) { rxQueue.put(data) }
            override fun onRunError(e: Exception) { rxError = e }
        }).also { it.start() }
    }

    private fun closePort() {
        try { ioManager?.stop() } catch (_: Exception) {}
        ioManager = null
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    // ------------------------------------------------------------------ モジュール

    /**
     * RTS でリセットし、プロセッサモードで設定を書き込んで運用モードにする（PC 側ノードと同じ手順）。
     * firstMethod: 最初に試すリセットのかけ方（RESET_*）。起動しなければ残りを RESET_ORDER の順に試す。
     * ch: 設定するチャンネル。quiet: 起動メッセージなどをログに出さない（チャンネルの切り替え・スキャン用）。
     */
    private fun startModule(firstMethod: Int, ch: Int, quiet: Boolean = false): StartTiming {
        val p = port ?: throw Es920LinkException("port closed")
        if (!quiet) log("$tag control lines: supported=${p.supportedControlLines} now=${p.controlLines}")
        // 起動メッセージが何も来ないときは、リセットのかけ方を変えて順に試す（RESET_*）。
        val order = listOf(firstMethod) + RESET_ORDER.filter { it != firstMethod }
        var methodIndex = 0   // 今試しているリセットのかけ方（order の何番目か）。組の番号 index とは別
        var attempt = order[0]
        var tries = 0
        var end = 0L
        var resetAt = 0L
        fun reset() {
            tries++
            attempt = order[methodIndex]
            when (attempt) {
                RESET_RTS_PULSE -> { p.rts = true; Thread.sleep(100); p.rts = false }
                RESET_HOLD_HIGH -> { p.dtr = false; p.rts = false; Thread.sleep(100); p.dtr = true; p.rts = true }
                else -> writeLine("")
            }
            // 信号を戻した（モジュールが起動しはじめる）時刻から測る。
            resetAt = System.currentTimeMillis()
            end = resetAt + ES920_BOOT_TIMEOUT_MS
        }
        reset()
        while (true) {
            val line = readLine(end - System.currentTimeMillis())
            if (line == null) {
                log("$tag no boot message, attempt $attempt (rx=$rxBytes bytes, pending=${rxPreview()})")
                if (++methodIndex >= order.size) throw Es920LinkException("no boot message (rx=$rxBytes bytes)")
                reset()
                continue
            }
            if (!quiet) log("$tag boot: $line")
            if (line.trim() == ES920_BANNER_CONFIG) break
            if (ES920_BANNER_OPERATION in line) {
                // 保存されている設定（format も）で動くので、BINARY かどうかがこちらの想定と違うかもしれない。
                log("LORA: module boots in operation mode, using saved settings")
                resetMethod = attempt
                val ms = System.currentTimeMillis() - resetAt
                return StartTiming(ms, ms, tries)
            }
        }
        val bootMs = System.currentTimeMillis() - resetAt
        resetMethod = attempt
        command("2")  // プロセッサモード
        es920Commands(ch, this.index).forEach { command(it) }
        command("start")
        // start のあとに続けて何か（改行など）が出ていても、BINARY の読み取りがずれないよう捨てる。
        Thread.sleep(50)
        while (rxQueue.poll() != null) { /* 捨てる */ }
        clearRx()
        return StartTiming(bootMs, System.currentTimeMillis() - resetAt, tries)
    }

    /**
     * 設定のコマンドを送り、OK でなければ例外。
     * format 2（BINARY）のあとは返事が [長さ] + "OK"（改行なし）になるかもしれないので、
     * 行ではなく OK / NG xxx の文字の並びを探す。
     */
    private fun command(cmd: String) {
        clearRx()
        writeLine(cmd)
        val end = System.currentTimeMillis() + ES920_COMMAND_TIMEOUT_MS
        while (true) {
            val text = String(rxBuf, 0, rxLen, Charsets.ISO_8859_1)
            val m = REPLY_REGEX.find(text)
            if (m != null) {
                var used = m.range.last + 1
                if (text.startsWith("\r\n", used)) used += 2
                dropRx(used)
                if (m.value == "OK") return
                throw Es920LinkException("\"$cmd\" -> ${m.value}")
            }
            val remain = end - System.currentTimeMillis()
            if (remain <= 0) {
                if (rxLen > 0) throw Es920LinkException("\"$cmd\" -> ${rxPreview()}")
                throw Es920LinkException("no reply to \"$cmd\"")
            }
            pump(remain)
        }
    }

    /** 今の状態での送る周期 [ms]。 */
    private fun period(): Long = when {
        wifiAlive() -> ES920_PROFILE.periodSlowMs
        waitingSettings() -> ES920_PROFILE.periodSyncMs
        else -> ES920_PROFILE.periodFastMs
    }

    /** 設定値が PC に届いたかの状態（5 秒ごとのログ用）。 */
    private fun settingsStatus(): String = when {
        confirmedGen == txGen -> "OK"                         // PC から同じ番号が返ってきた
        pendingRounds > 0 || nextChunk != 0 -> "SENDING"      // 送っている途中
        else -> "SENT"                                        // 送ったが、まだ確認が取れていない（Wi-Fi が生きている間はこれ）
    }

    /** 1 周期に 1 回、offer() の最新のパケットを送り、残りの時間は受信する。 */
    private suspend fun sendLoop() {
        var timeouts = 0
        var ok = 0
        var ng = 0            // NG 102 以外の NG
        var busy = 0          // NG 102（キャリアセンスで送れなかった。同じチャンネルを誰かが使っている）
        var noReply = 0       // 返事が来なかった
        var held = 0
        var statsStart = System.currentTimeMillis()
        // 書いてから返事（OK / NG）が来るまでの時間 [ms]。モジュールが待たせて制限する場合に伸びる。
        var replyMsSum = 0L
        var replyMsMax = 0L
        var replies = 0
        // 試験モード用
        val ngCodes = sortedMapOf<String, Int>()   // 統計の間に来た NG（時間切れは "TIMEOUT"）の内訳
        val ngSeen = mutableSetOf<String>()        // 初めて来た NG の種類を 1 回だけログに出す
        var passedLaw = false
        var testDone = false
        if (airtimeTest) {
            log("$tag AIRTIME TEST: period 0, stop at %.0f s".format(ES920_AIRTIME_TEST_STOP_US / 1e6))
        }
        while (true) {
            // チャンネルの切り替えとスキャンは、送る合間にする。終わったら統計を測り直す。
            if (scanRequested || requestedChannel != channel) {
                if (scanRequested) {
                    scanRequested = false
                    scanChannels()
                } else {
                    switchChannel(requestedChannel)
                }
                timeouts = 0
                ok = 0; ng = 0; busy = 0; noReply = 0; held = 0
                replyMsSum = 0L; replyMsMax = 0L; replies = 0
                rxSettingsLines = 0; rxPoseLines = 0; settingsFrames = 0
                statsStart = System.currentTimeMillis()
                continue
            }
            // コントローラーの画面でない間は offer() が来ないので、ここで止まって送らない
            // （チャンネルの切り替え・スキャンを頼まれたら起きる）。
            // もう片方の組が同じパケットを送っていても、待たずに送る（同じ番号を 2 組で重ねて送り、落ちにくくする）。
            val tx = select<TxPacket?> {
                outbox.onReceive { it }
                wake.onReceive { null }
            } ?: continue
            val cycleStart = System.currentTimeMillis()
            val cyclePeriod = if (airtimeTest) 0L else period()
            // 試験モードで判定が出たあとは送らない。
            val hold = airtimeTest && testDone
            // Wi-Fi が切れている間は、ときどき送ったあとに受信する時間をとる。SYNC 待ちは周期の残りで受信できるときは要らない。
            // 受信の時間は 2 組で交互にとる（Es920Group.takeListenWindow）。
            val syncWindow = waitingSettings() && ES920_PROFILE.periodSyncMs == 0L
            val poseWindow = !hold && !wifiAlive() && (syncWindow || !waitingSettings()) &&
                group.takeListenWindow(cycleStart, if (syncWindow) ES920_SYNC_LISTEN_INTERVAL_MS else ES920_POSE_INTERVAL_MS)
            val frame = encodeFrame(tx, poseWindow, cycleStart)
            if (frame.size > LORA_ENEMY_FRAME_SIZE) settingsFrames++
            val wireSize = if (ES920_BINARY) frame.size else (frame.size + 2) / 3 * 4
            val air = ES920_PROFILE.airtimeBaseUs + ES920_PROFILE.airtimePerByteUs * wireSize
            // 送信の返事が来た時刻（そこから listenMs 受信する）
            var replyAt = cycleStart
            if (hold) {
                held++
                // 周期 0（LoRa）でも空回りしないよう、少し受信してから次を見る。
                listenUntil(cycleStart + 100) { false }
            } else {
                writeFrame(frame)
                val reply = waitReply()
                replyAt = System.currentTimeMillis()
                if (reply != null) {
                    val ms = System.currentTimeMillis() - cycleStart
                    replyMsSum += ms
                    replyMsMax = maxOf(replyMsMax, ms)
                    replies++
                }
                when (reply) {
                    "OK" -> {
                        ok++
                        timeouts = 0
                        lastSendOkMs = System.currentTimeMillis()
                        if (airtimeTest) testAirUs += air
                    }
                    null -> {
                        noReply++
                        if (airtimeTest) {
                            // 試験中は開き直さない（RTS のリセットでモジュールの数えが消えるかもしれないため）。
                            countTestNg(ngCodes, ngSeen, "TIMEOUT")
                        } else if (++timeouts >= ES920_MAX_TIMEOUTS) {
                            throw Es920LinkException("no reply to send")
                        }
                    }
                    else -> {
                        if (reply == ES920_REPLY_BUSY) busy++ else ng++
                        timeouts = 0
                        // NG 102 は混んでいると 1 秒に何十回も来るので、1 回ずつは出さず 5 秒ごとの CS で数える。
                        if (airtimeTest) countTestNg(ngCodes, ngSeen, reply)
                        else if (reply != ES920_REPLY_BUSY) log("$tag SEND $reply")
                    }
                }
                if (airtimeTest) {
                    if (!passedLaw && testAirUs >= ES920_AIRTIME_LAW_US) {
                        passedLaw = true
                        log("$tag AIRTIME TEST: passed 360 s, module still answers OK")
                    }
                    if (testAirUs >= ES920_AIRTIME_TEST_STOP_US) {
                        testDone = true
                        log("$tag AIRTIME TEST RESULT: module did NOT limit (OK up to %.1f s). stopped sending".format(testAirUs / 1e6))
                    }
                }
            }
            // 受信の時間。途中で Wi-Fi が切れて周期が短くなったら、すぐ次を送る。
            // 1 回送ったら、最低 listenMs（印を付けたときは ES920_POSE_LISTEN_MS）は受信する（周期の残りのほうが長ければそちら）。
            val minListen = when {
                !poseWindow || hold -> ES920_PROFILE.listenMs
                syncWindow -> ES920_SYNC_LISTEN_MS
                else -> ES920_POSE_LISTEN_MS
            }
            val listenEnd = if (hold) cycleStart + cyclePeriod else maxOf(cycleStart + cyclePeriod, replyAt + minListen)
            // 印を付けた回は、状態の行（PC が最後に送る）が届いたらすぐ送信に戻る。
            val statusBefore = statusLines
            listenUntil(listenEnd) {
                (poseWindow && !hold && statusLines != statusBefore && System.currentTimeMillis() >= cycleStart + cyclePeriod) ||
                    (!airtimeTest && period() < cyclePeriod && System.currentTimeMillis() >= replyAt + minListen)
            }

            val now = System.currentTimeMillis()
            // 設定値を変えてからしばらくたっても PC に届いた確認が取れない（自己位置は届いているのに）ときは知らせる。
            if (confirmedGen != txGen && warnedGen != txGen && rxPoseLines > 0 &&
                now - txGenAtMs >= ES920_SETTINGS_CONFIRM_WARN_MS
            ) {
                warnedGen = txGen
                log("$tag SETTINGS NOT CONFIRMED: #$txGen for ${now - txGenAtMs} ms (PC has #$confirmedGen). resending")
            }
            if (now - statsStart >= ES920_STATS_INTERVAL_MS) {
                // ch: 今のチャンネル。Hz: OK が返った回数 / 秒
                // CS: NG 102（キャリアセンスで送れなかった = 同じチャンネルを誰かが使っている）の回数 / 送ろうとした回数と割合。
                //     多ければ混んでいるので、フィールド選択画面でスキャンして空いているチャンネルに移る
                // NG: NG 102 以外の NG の回数、TO: 返事が来なかった回数
                // RX: その間に受け取った設定値の数（SYNC 待ちで 0 のままなら PC からの返信が届いていない）
                // POSE: その間に受け取った自己位置の行の数（1 回の受信の時間に最大 3 行）
                // SET: 設定値の番号と、PC に届いたか（OK / SENDING / SENT）、その間に設定値を載せて送った回数
                // HOLD: 試験モードで送るのをやめたあとの周期の数
                // REPLY: 書いてから返事が来るまでの平均 / 最大 [ms]
                val stats = Es920Stats(channel, ok * 1000.0 / (now - statsStart), ok, busy, ng, noReply, now)
                log("$tag ch%d %.1f Hz (CS %d/%d %.0f%%, NG %d, TO %d, RX %d, POSE %d, SET #%d %s x%d, HOLD %d, REPLY %.0f/%d ms)".format(
                    channel, stats.hz, busy, stats.attempts, stats.busyRate * 100, ng, noReply,
                    rxSettingsLines, rxPoseLines,
                    txGen, settingsStatus(), settingsFrames, held,
                    if (replies > 0) replyMsSum.toDouble() / replies else 0.0, replyMsMax))
                onStats(stats)
                if (airtimeTest) {
                    val codes = if (ngCodes.isEmpty()) "-" else ngCodes.entries.joinToString(" ") { "${it.key}x${it.value}" }
                    log("$tag AIRTIME TEST: %.1f s since module start, NG: %s".format(testAirUs / 1e6, codes))
                    if (passedLaw && !testDone && ok == 0 && ng + busy + noReply > 0) {
                        log("$tag AIRTIME TEST RESULT: module seems to LIMIT (no OK for %.0f s after %.1f s)".format(
                            (now - statsStart) / 1e3, testAirUs / 1e6))
                    }
                    ngCodes.clear()
                }
                rxSettingsLines = 0
                rxPoseLines = 0
                settingsFrames = 0
                ok = 0
                ng = 0
                busy = 0
                noReply = 0
                held = 0
                replyMsSum = 0L
                replyMsMax = 0L
                replies = 0
                statsStart = now
            }
        }
    }

    /** チャンネルの知らせ（LORA_CHANNEL_TAG のコメント）の 1 回分。 */
    private fun channelHintFrame(ch: Int): ByteArray {
        val b = byteArrayOf(LORA_CHANNEL_TAG, ch.toByte(), LORA_CHANNEL_TAG, 0)
        b[3] = sum8(b, 0, 3).toByte()
        return b
    }

    /**
     * チャンネルを to に変える。先に今のチャンネルで「to に移る」と PC に知らせ（PC の es920lr ノードが追いかけてくる）、
     * それからモジュールを設定し直す。知らせが届かなくても、PC は届かなくなったら全チャンネルを探す。
     */
    private fun switchChannel(to: Int) {
        val from = channel
        val t0 = System.currentTimeMillis()
        var sentOk = 0
        var busy = 0
        for (i in 0 until ES920_HINT_TRIES) {
            if (sentOk >= ES920_HINT_OK) break
            writeFrame(channelHintFrame(to))
            when (waitReply()) {
                "OK" -> sentOk++
                ES920_REPLY_BUSY -> busy++
                null -> break  // モジュールが返事をしない。知らせはあきらめて設定し直す（PC は探して見つける）
            }
        }
        startModule(resetMethod, to, quiet = true)
        channel = to
        log("$tag CH $from -> $to: told PC $sentOk/$ES920_HINT_OK (busy $busy), ${System.currentTimeMillis() - t0} ms")
    }

    /**
     * 使えるチャンネルを順に設定し、それぞれで ES920_SCAN_PROBES 回送ってみて、NG 102（キャリアセンス）の割合を見る。
     * 送るのはチャンネルの知らせ（中身は今のチャンネル）なので、PC がそのチャンネルにいても操作としては使われない。
     * 終わったら今のチャンネルに戻す。途中の結果は onScan で返す。
     */
    private fun scanChannels() {
        val home = channel
        val t0 = System.currentTimeMillis()
        val results = mutableListOf<Es920ChannelScan>()
        scanning = true
        log("$tag SCAN start: ch ${ES920_PROFILE.channels.first}-${ES920_PROFILE.channels.last}, $ES920_SCAN_PROBES tries each")
        try {
            for (ch in ES920_PROFILE.channels) {
                if (scanCancel) break
                onScan(results.toList(), ch)
                startModule(resetMethod, ch, quiet = true)
                var ok = 0
                var busy = 0
                var other = 0
                var noReply = 0
                for (i in 0 until ES920_SCAN_PROBES) {
                    if (scanCancel) break
                    writeFrame(channelHintFrame(home))
                    when (waitReply()) {
                        "OK" -> ok++
                        ES920_REPLY_BUSY -> busy++
                        null -> {
                            other++
                            if (++noReply >= ES920_SCAN_MAX_TIMEOUTS) throw Es920LinkException("no reply during scan (ch $ch)")
                        }
                        else -> other++
                    }
                }
                if (ok + busy + other > 0) results += Es920ChannelScan(ch, ok, busy, other)
            }
        } finally {
            scanning = false
            onScan(results.toList(), 0)
        }
        // 開き直しになったとき（上の例外）は、start() のループが requestedChannel で設定し直す。
        startModule(resetMethod, home, quiet = true)
        log("$tag SCAN done in %.1f s%s: %s".format(
            (System.currentTimeMillis() - t0) / 1e3, if (scanCancel) " (canceled)" else "",
            results.joinToString(" ") { "${it.channel}:${(it.busyRate * 100).toInt()}%" }))
    }

    /** 試験モード: NG の種類を数え、初めて来た種類はそのときの合計と一緒にログに出す。 */
    private fun countTestNg(codes: MutableMap<String, Int>, seen: MutableSet<String>, code: String) {
        codes[code] = (codes[code] ?: 0) + 1
        if (seen.add(code)) log("$tag AIRTIME TEST: first $code at %.1f s".format(testAirUs / 1e6))
    }

    /** 送信の返事を待つ。OK / "NG xxx" を返し、時間切れなら null。間に来た受信データは onReceived に渡す。 */
    private fun waitReply(): String? {
        val end = System.currentTimeMillis() + ES920_SEND_TIMEOUT_MS
        while (true) {
            when (val ev = readEvent(end - System.currentTimeMillis()) ?: return null) {
                is RxEvent.Reply -> return ev.text
                is RxEvent.Data -> onReceived(ev)
            }
        }
    }

    /** 送らずに endMs まで受信する（stopEarly() が true になったらそこでやめる）。届いたものは onReceived に渡す。 */
    private fun listenUntil(endMs: Long, stopEarly: () -> Boolean) {
        while (true) {
            val remain = endMs - System.currentTimeMillis()
            if (remain <= 0 || stopEarly()) return
            val ev = readEvent(remain.coerceAtMost(20)) ?: continue
            if (ev is RxEvent.Data) onReceived(ev)
        }
    }

    /**
     * 無線で受け取ったデータの末尾 size バイトを返す（読めない・短いときは null）。
     * 先頭の RSSI・ID の長さに頼らないよう、受け取る種類の長さごとに末尾を読む。
     */
    private fun tail(ev: RxEvent.Data, size: Int): ByteArray? {
        ev.bytes?.let { b -> return if (b.size >= size) b.copyOfRange(b.size - size, b.size) else null }
        val line = ev.line ?: return null
        val chars = (size + 2) / 3 * 4
        if (line.length < chars) return null
        val b = try {
            Base64.decode(line.takeLast(chars), Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (b.size == size) b else null
    }

    /** バイト列の from から n バイトの和の下位 8 bit。 */
    private fun sum8(b: ByteArray, from: Int, n: Int): Int {
        var s = 0
        for (i in from until from + n) s += b[i].toInt() and 0xFF
        return s and 0xFF
    }

    /** ログの行なら (番号, 文字) を返す。長さが決まっていないので、末尾の長さのバイトから見る。 */
    private fun parseLogFrame(ev: RxEvent.Data): Pair<Int, String>? {
        for (n in 0..LORA_LOG_MAX_BYTES) {
            val frame = tail(ev, n + 4) ?: continue
            if (frame[n + 3] != LORA_LOG_TAG || (frame[n + 1].toInt() and 0xFF) != n) continue
            if ((frame[n + 2].toInt() and 0xFF) != sum8(frame, 0, n + 1)) continue
            return (frame[0].toInt() and 0xFF) to String(frame, 1, n, Charsets.UTF_8)
        }
        return null
    }

    /** 無線で受け取った 1 回分。状態・ログ・設定値なら、それぞれに渡す（LoRa の設定値の 3 回はそろってから）。 */
    private fun onReceived(ev: RxEvent.Data) {
        val now = System.currentTimeMillis()
        tail(ev, LORA_STATUS_FRAME_SIZE)?.let { frame ->
            val body = LORA_POSE_SIZE + LORA_HOJU_SIZE + 1 + LORA_ENEMY_ECHO_SIZE
            if (frame[0] == LORA_STATUS_TAG && frame[1 + body] == LORA_STATUS_TAG &&
                (frame[2 + body].toInt() and 0xFF) == sum8(frame, 1, body)
            ) {
                rxPoseLines++
                statusLines++
                lastPcRxMs = now
                val pose = frame.copyOfRange(1, 1 + LORA_POSE_SIZE)
                onSettingsState(hasSettings = (pose[13].toInt() and 1) != 0, pcGen = pose[14].toInt() and 0xFF)
                val hoju = String(frame, 1 + LORA_POSE_SIZE, LORA_HOJU_SIZE, Charsets.US_ASCII).trimEnd('\u0000')
                val invalidAt = 1 + LORA_POSE_SIZE + LORA_HOJU_SIZE
                onStatus(pose, hoju, frame[invalidAt].toInt() and 0xFF)
                // PC が持っている相手のバケツの位置（u8 has / i16 x / i16 y / i16 z）
                val echo = java.nio.ByteBuffer.wrap(frame, invalidAt + 1, LORA_ENEMY_ECHO_SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val has = echo.get().toInt() != 0
                val xyz = intArrayOf(echo.short.toInt(), echo.short.toInt(), echo.short.toInt())
                onEnemyEcho(if (has) xyz else null)
                return
            }
        }
        parseLogFrame(ev)?.let { (seq, text) ->
            lastPcRxMs = now
            // 同じ行は PC が何回かに分けて送り直し、どちらの組でも届くので、2 組まとめて 1 回だけ出す
            if (group.acceptLog(seq, now)) onLog(text)
            return
        }
        // FSK の設定値（89 バイト、1 回）
        tail(ev, LORA_SETTINGS_FULL_SIZE)?.let { frame ->
            if (frame[0] == LORA_SETTINGS_TAG) {
                rxSettingsLines++
                lastPcRxMs = now
                onSettings(frame[1].toInt() and 0xFF, frame.copyOfRange(2, frame.size))
                return
            }
        }
        // LoRa の設定値の 3 回のうちの 1 回（32 バイト）
        val frame = tail(ev, LORA_SETTINGS_CHUNK_SIZE) ?: return
        if (frame[0] != LORA_SETTINGS_TAG) return
        val map = frame[1].toInt() and 0xFF
        val n = frame[2].toInt()
        if (n !in 0 until LORA_CHUNK_COUNT) return
        rxSettingsLines++
        lastPcRxMs = now
        // マップが変わったら、集めかけの値は別物なので捨てる
        if (map != rxSettingsMap) {
            rxSettingsMap = map
            rxSettingsSeen.clear()
        }
        frame.copyInto(rxSettings, LORA_CHUNK_SIZE * n, 3, frame.size)
        rxSettingsSeen.add(n)
        if (rxSettingsSeen.size == LORA_CHUNK_COUNT) {
            rxSettingsSeen.clear()
            onSettings(map, rxSettings.copyOf())
        }
    }

    /** PC が持っている設定値の番号が自己位置の行で届いた。今の番号と同じなら届いている。違えば送り直す。 */
    private fun onSettingsState(hasSettings: Boolean, pcGen: Int) {
        val now = System.currentTimeMillis()
        if (hasSettings && pcGen == txGen) {
            if (confirmedGen != txGen) log("$tag SETTINGS CONFIRMED: #$txGen reached PC in ${now - txGenAtMs} ms")
            confirmedGen = txGen
            confirmedAtMs = now
        } else {
            confirmedGen = if (hasSettings) pcGen else -1
            // 送っている途中でなければ、もう 1 回ぶん送る（PC の con.py が再起動して設定値を失ったときなど）
            if (pendingRounds == 0) pendingRounds = 1
        }
    }

    /** 101 バイトのパケットを、無線で送る 1 回分にする（並びはファイル先頭の LORA_* のコメント）。 */
    private fun encodeFrame(tx: TxPacket, listen: Boolean, now: Long): ByteArray {
        val packet = tx.packet
        // 設定値・マップ・synced のどれかが変わったら、番号を進めて写し取り、送り始める。
        val key = packet.copyOfRange(LORA_HEAD_SIZE, LORA_HEAD_SIZE + LORA_SETTINGS_SIZE) +
            byteArrayOf(packet[1], if (tx.synced) 1 else 0)
        if (!key.contentEquals(txKey)) {
            txKey = key
            txGen = (txGen + 1) and 0xFF
            packet.copyInto(txSettings, 0, LORA_HEAD_SIZE, LORA_HEAD_SIZE + LORA_SETTINGS_SIZE)
            txGenAtMs = now
            nextChunk = 0
            pendingRounds = ES920_SETTINGS_ROUNDS_ON_CHANGE
        }
        // 確認が取れていなければ、ときどき送り直す（Wi-Fi が生きていて自己位置を受け取らない間もこれで届く）
        val confirmed = confirmedGen == txGen && now - confirmedAtMs < ES920_SETTINGS_CONFIRM_VALID_MS
        if (pendingRounds == 0 && nextChunk == 0 && !confirmed && now - lastRoundAtMs >= ES920_SETTINGS_REFRESH_MS) {
            pendingRounds = 1
        }
        val info = if (listen) LORA_LISTEN_FLAG else 0
        val withSettings = pendingRounds > 0 || nextChunk != 0
        // 設定値を載せない回に、相手のバケツの位置をときどき載せる（PC に届いたと分かるまでは短い間隔で）
        val enemy = group.enemyBlock
        val withEnemy = !withSettings && enemy != null &&
            now - lastEnemyAtMs >= (if (group.enemyConfirmed) ES920_ENEMY_CHECK_MS else ES920_ENEMY_RESEND_MS)
        val size = when {
            withEnemy -> LORA_ENEMY_FRAME_SIZE
            !withSettings -> LORA_CONTROL_SIZE
            ES920_PROFILE.chunked -> LORA_CHUNK_FRAME_SIZE
            else -> LORA_FULL_FRAME_SIZE
        }
        val frame = ByteArray(size)
        packet.copyInto(frame, 0, 0, LORA_HEAD_SIZE)
        // 前に送ってから押して離したボタンも、このパケット（通し番号）にだけ押したことにして載せる（短い押しを落とさないため）。
        // もう片方の組が同じ番号を送れば、そちらにも同じく載る。続く番号には載せないので、その番号が 2 組とも落ちたら届かない。
        // Wi-Fi が生きている間は載せない。その押しは UDP で届いているので、con.py がこの 1 回を使うと二重に押したことになる。
        val pressed = group.pressedFor(packet)
        val buttons = buttonsOf(packet) or (if (wifiAlive()) 0 else pressed)
        for (i in 0 until 4) frame[LORA_BUTTONS_OFFSET + i] = (buttons ushr (8 * i)).toByte()
        frame[LORA_HEAD_SIZE + 1] = txGen.toByte()
        when (size) {
            LORA_CONTROL_SIZE -> frame[LORA_HEAD_SIZE] = info.toByte()
            LORA_ENEMY_FRAME_SIZE -> {
                frame[LORA_HEAD_SIZE] = info.toByte()
                enemy!!.copyInto(frame, LORA_CONTROL_SIZE, 0, LORA_ENEMY_BLOCK_SIZE)
                lastEnemyAtMs = now
            }
            LORA_FULL_FRAME_SIZE -> {
                frame[LORA_HEAD_SIZE] = info.toByte()
                txSettings.copyInto(frame, LORA_CONTROL_SIZE)
                pendingRounds--
                lastRoundAtMs = now
            }
            else -> {
                val chunk = nextChunk
                frame[LORA_HEAD_SIZE] = (chunk or info).toByte()
                val from = LORA_CHUNK_SIZE * chunk
                txSettings.copyInto(frame, LORA_CONTROL_SIZE, from, from + LORA_CHUNK_SIZE)
                nextChunk = (chunk + 1) % LORA_CHUNK_COUNT
                if (nextChunk == 0) {
                    pendingRounds = maxOf(0, pendingRounds - 1)
                    lastRoundAtMs = now
                }
            }
        }
        return frame
    }

    // ------------------------------------------------------------------ シリアル

    private fun writeLine(text: String) {
        val p = port ?: throw Es920LinkException("port closed")
        p.write((text + "\r\n").toByteArray(Charsets.US_ASCII), 500)
    }

    /** 1 回分を送る。BINARY は [長さ 1 バイト] + 中身、ASCII は base64 の文字 + 改行。 */
    private fun writeFrame(frame: ByteArray) {
        if (!ES920_BINARY) {
            writeLine(Base64.encodeToString(frame, Base64.NO_WRAP))
            return
        }
        val p = port ?: throw Es920LinkException("port closed")
        p.write(byteArrayOf(frame.size.toByte()) + frame, 500)
    }

    /** open() 以降に受け取った合計バイト数（起動メッセージが来ないときの原因調べ用）。 */
    private var rxBytes = 0L

    private fun clearRx() {
        rxLen = 0
        rxTail = ""
        frameSinceMs = 0L
    }

    private fun dropRx(n: Int) {
        System.arraycopy(rxBuf, n, rxBuf, 0, rxLen - n)
        rxLen -= n
    }

    /** まだ使っていない受信データの頭（ログ用、文字コードの数字で）。 */
    private fun rxPreview(): String =
        (0 until minOf(rxLen, 40)).map { rxBuf[it].toInt() and 0xFF }.toString()

    /** 受け取ったデータを最大 timeoutMs 待って rxBuf に足す。何か足したら true。 */
    private fun pump(timeoutMs: Long): Boolean {
        rxError?.let { throw Es920LinkException("read failed: ${it.message}") }
        val data = rxQueue.poll(timeoutMs.coerceIn(0, 50), TimeUnit.MILLISECONDS) ?: return false
        rxBytes += data.size
        if (rxLen + data.size > rxBuf.size) rxBuf = rxBuf.copyOf(maxOf(rxBuf.size * 2, rxLen + data.size))
        data.copyInto(rxBuf, rxLen)
        rxLen += data.size
        rxTail = (rxTail + String(data, Charsets.ISO_8859_1)).takeLast(64)
        return true
    }

    /** 1 行（改行なし）を返す。timeoutMs で来なければ null。空行は読み飛ばす。 */
    private fun readLine(timeoutMs: Long): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            var i = 0
            while (i + 1 < rxLen && !(rxBuf[i] == '\r'.code.toByte() && rxBuf[i + 1] == '\n'.code.toByte())) i++
            if (i + 1 < rxLen) {
                val line = String(rxBuf, 0, i, Charsets.US_ASCII)
                dropRx(i + 2)
                if (line.isNotBlank()) return line
                continue
            }
            val remain = end - System.currentTimeMillis()
            if (remain <= 0) return null
            pump(remain)
        }
    }

    /** BINARY の 1 回分（[長さ 1 バイト] + 中身）の中身を返す。timeoutMs で来なければ null。 */
    private fun readBinaryFrame(timeoutMs: Long): ByteArray? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            // BINARY でも、モジュールが再起動すると起動メッセージは文字で出る。
            if (ES920_BANNER_CONFIG in rxTail || ES920_BANNER_OPERATION in rxTail) {
                throw Es920LinkException("module rebooted")
            }
            if (rxLen > 0) {
                val n = rxBuf[0].toInt() and 0xFF
                if (n == 0 || n > BINARY_MAX_FRAME) {
                    dropRx(1)  // 長さとしてありえない。ずれているので 1 バイト捨てる
                    continue
                }
                if (rxLen >= 1 + n) {
                    val frame = rxBuf.copyOfRange(1, 1 + n)
                    dropRx(1 + n)
                    frameSinceMs = 0L
                    return frame
                }
                val now = System.currentTimeMillis()
                if (frameSinceMs == 0L) {
                    frameSinceMs = now
                } else if (now - frameSinceMs > BINARY_FRAME_TIMEOUT_MS) {
                    // そろわないまま時間がたった。ずれているので読みかけを捨てて読み直す
                    rxLen = 0
                    frameSinceMs = 0L
                    continue
                }
            }
            val remain = end - System.currentTimeMillis()
            if (remain <= 0) return null
            pump(remain)
        }
    }

    /** 運用中に受け取ったものを 1 つ返す（返事か、無線のデータ）。timeoutMs で来なければ null。 */
    private fun readEvent(timeoutMs: Long): RxEvent? {
        if (ES920_BINARY) {
            val frame = readBinaryFrame(timeoutMs) ?: return null
            if (frame.size == 2 || frame.size == 6) {
                val text = String(frame, Charsets.ISO_8859_1)
                if (REPLY_REGEX.matches(text)) return RxEvent.Reply(text)
            }
            return RxEvent.Data(null, frame)
        }
        val line = readLine(timeoutMs) ?: return null
        if (line == "OK" || (line.startsWith("NG ") && line.length == 6)) return RxEvent.Reply(line)
        if (line.trim() == ES920_BANNER_CONFIG || ES920_BANNER_OPERATION in line) {
            throw Es920LinkException("module rebooted")
        }
        return RxEvent.Data(line, null)
    }
}
