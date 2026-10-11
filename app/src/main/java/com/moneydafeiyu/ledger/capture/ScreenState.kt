package com.moneydafeiyu.ledger.capture

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * 屏幕状态检测。
 *
 * 为什么需要它：银行打款、拼多多"先用后付"扣款这类通知，
 * 经常在**手机锁屏放兜里**的时候弹出来。这种时候：
 *   - 记账必须照常进行（否则就漏了）
 *   - 但**不能弹鲸鱼娘全屏窗**（黑着屏弹出来既看不见也吓人）
 * 所以锁屏时静默记账，解锁后用户能在首页看到。
 */
object ScreenState {

    fun isLocked(context: Context): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java)
        // isKeyguardLocked 在部分 ROM 上可能返回 false，再补一个屏幕熄灭的判断
        if (km?.isKeyguardLocked == true) return true
        val pm = context.getSystemService(PowerManager::class.java)
        return pm?.isInteractive == false
    }
}
