package com.moneydafeiyu.ledger.callguard

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 处理拦截通知上的操作按钮。
 *
 * 「这是正常电话」= 把号码加进白名单，以后不再拦。
 * 这条链路是**静默拦截能够放心开启**的前提：
 * 拦错了，用户点一下就能纠正，而且不会再有第二次。
 */
class WhitelistActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_WHITELIST = "com.moneydafeiyu.ledger.action.WHITELIST"
        const val EXTRA_DIGITS = "digits"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WHITELIST) return

        val digits = intent.getStringExtra(EXTRA_DIGITS).orEmpty()
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (digits.isBlank()) return

        CallGuardSettings.addToWhitelist(context, digits)
        Log.i("CallGuard", "已把 $digits 加入白名单")

        // 收起那条通知，给个明确反馈
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(notifId)
        }
    }
}
