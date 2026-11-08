package com.example.controller

import android.app.Activity
import android.os.Bundle

/**
 * ES920LR（CH340）を挿したときに Android が開く、画面の無い Activity（2026-11-08）。
 *
 * AndroidManifest で USB_DEVICE_ATTACHED を受けるのはこれだけ。初めて挿したときの確認で「常に」を選ぶと、
 * 次からは挿すたびに Android がこのアプリに USB の許可を出してからここを開く（許可ダイアログは出ない）。
 * ここは何もせずすぐ閉じる。MainActivity で受けると、挿すたびに onPause でアプリが終了して開き直されていた
 * （2026-11-06 に一度外した理由）。MainActivity は挿した直後の onPause では終了せず、戻ってくるまで待つ（送るのは止める）。
 * 画面ピン留め中はここが開けないことがあるが、許可は開く前に出るので変わらない。
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
