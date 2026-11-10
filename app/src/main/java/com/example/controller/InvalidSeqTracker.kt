package com.example.controller

/**
 * invalid_target の番号（0〜255）を見て、ロボットが移動先を受け付けなかったことを見つける。
 * 番号は UDP の自己位置（返事のたび）と ES920LR の状態の行で届く。/log の行は落ちても送り直さないので、解除はこの番号で決める。
 *
 * 経路が複数あり、Wi-Fi にたまっていた古い返事が後から届くことがあるので、番号が前に進んだときだけ新しいとみなす。
 * 後ろの番号が resetAfterMs 続いたら（前の番号が 1 回も来なかったら）、con.py が再起動して番号がやり直しになったとみて、
 * 解除せずにその番号から数え直す。最初に届いた番号も解除しない。
 * 再起動後の番号がたまたま前に進んで見えるときは 1 回解除するが、止まる側なので許す。
 * 受信のスレッドが複数あるので、update はロックを取る。
 */
internal class InvalidSeqTracker(private val resetAfterMs: Long = 1000L) {
    private var last = -1
    private var behindSinceMs = -1L

    /** 届いた番号を渡す。新しい invalid_target が来ていれば true（1 回の変化につき 1 回だけ）。 */
    @Synchronized
    fun update(seq: Int, nowMs: Long): Boolean {
        if (last < 0) {
            last = seq
            return false
        }
        val diff = (seq - last) and 0xFF
        if (diff == 0) {
            behindSinceMs = -1L
            return false
        }
        if (diff < 128) {
            last = seq
            behindSinceMs = -1L
            return true
        }
        if (behindSinceMs < 0) behindSinceMs = nowMs
        if (nowMs - behindSinceMs >= resetAfterMs) {
            last = seq
            behindSinceMs = -1L
        }
        return false
    }
}
