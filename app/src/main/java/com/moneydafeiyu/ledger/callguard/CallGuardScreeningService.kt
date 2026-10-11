package com.moneydafeiyu.ledger.callguard

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 第一层防线：纯号码规则，**响铃之前**就出结果。
 *
 * 这一层的价值在于「无感」—— 被拦的电话你根本不会知道，
 * 不响铃、不弹窗、不进未接来电。
 *
 * 座机判定（你指定的主力规则）依赖一个简单事实：
 * 国内手机号全是「11 位 + 1 开头」，不符合这个形状的短号基本就是座机。
 *
 * 三个保护措施：
 *   1. **通讯录里的号码一律放行** —— 这是防误杀的最后一道闸，外卖快递都在里面；
 *   2. 总开关 + 逐条规则开关，都能随时关；
 *   3. 每一次判定都写进观测日志，用一周数据回头看就知道规则准不准。
 */
class CallGuardScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "CallGuard"
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        private const val CHANNEL_ID = "call_guard_blocked"
        private const val NOTIF_ID_BLOCKED = 8801
    }

    override fun onScreenCall(callDetails: Call.Details) {
        // 只关心呼入；呼出、去电一律放行
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            allow(callDetails)
            return
        }

        val raw = callDetails.handle?.schemeSpecificPart.orEmpty()
        val digits = raw.filter { it.isDigit() }

        // callerDisplayName 记下来，用于验证「厂商云识别名到底拿不拿得到」
        val displayName = runCatching { callDetails.callerDisplayName?.toString() }.getOrNull().orEmpty()

        if (!CallGuardSettings.isEnabled(this)) {
            record(digits, raw, displayName, blocked = false, reason = "总开关已关")
            allow(callDetails)
            return
        }

        // 用户手动加的白名单优先级最高
        if (CallGuardSettings.isWhitelisted(this, digits)) {
            record(digits, raw, displayName, blocked = false, reason = "在你加的白名单里")
            allow(callDetails)
            return
        }

        // 通讯录白名单：宁可漏拦，不可误杀
        if (digits.isNotEmpty() && isInContacts(raw)) {
            record(digits, raw, displayName, blocked = false, reason = "在通讯录里")
            allow(callDetails)
            return
        }

        val verdict = CallRules.evaluate(digits, CallGuardSettings.enabledRules(this))

        if (verdict.block) {
            record(digits, raw, displayName, blocked = true, reason = verdict.reason)
            Log.i(TAG, "拦截来电 $raw（${verdict.reason}）")
            // 静默拦掉，但**必须留个痕迹** ——
            // 否则万一误杀，用户永远不知道曾经有电话打进来过。
            notifyBlocked(raw, digits, verdict.reason)
            reject(callDetails)
        } else {
            record(digits, raw, displayName, blocked = false, reason = "")
            allow(callDetails)
        }
    }

    private fun record(digits: String, raw: String, displayName: String, blocked: Boolean, reason: String) {
        runCatching {
            val ts = fmt.format(Date())
            val namePart = if (displayName.isBlank()) "（无显示名）" else displayName
            val line = "$ts | $raw | $namePart | " + (if (blocked) "已拦截 · $reason" else "放行" + (if (reason.isNotBlank()) " · $reason" else ""))
            CallGuardSettings.appendCall(this, line)
            if (blocked) CallGuardSettings.appendBlocked(this, line)
        }
    }

    /**
     * 拦截提醒。
     *
     * 用**低优先级通知**：不响铃、不振动、不弹横幅，
     * 但静静躺在通知栏里 —— 满足「不打扰」和「事后可查」两个要求。
     *
     * 通知上带「这是正常电话」按钮，一点就把号码加进白名单，
     * 下次不再拦。这是静默拦截能放心开的关键。
     */
    private fun notifyBlocked(rawNumber: String, digits: String, reason: String) {
        if (!CallGuardSettings.isNotifyBlocked(this)) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    CHANNEL_ID,
                    "拦截的来电",
                    NotificationManager.IMPORTANCE_LOW,   // 低优先级 = 静默
                ).apply { description = "被自动拦截的来电会静默记在这里" }
                nm.createNotificationChannel(channel)
            }

            val whitelistIntent = android.app.PendingIntent.getBroadcast(
                this,
                rawNumber.hashCode(),
                Intent(this, WhitelistActionReceiver::class.java).apply {
                    action = WhitelistActionReceiver.ACTION_WHITELIST
                    putExtra(WhitelistActionReceiver.EXTRA_DIGITS, digits)
                    putExtra(WhitelistActionReceiver.EXTRA_NOTIFICATION_ID, NOTIF_ID_BLOCKED)
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_missed)
                .setContentTitle("已拦截一通来电")
                .setContentText("$rawNumber · $reason")
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle()
                        .bigText("$rawNumber\n拦截原因：$reason\n\n如果是正经电话，点下面的按钮把它加进白名单。")
                )
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .addAction(
                    android.R.drawable.ic_menu_add,
                    "这是正常电话",
                    whitelistIntent,
                )
                .build()

            nm.notify(NOTIF_ID_BLOCKED, notification)
        }.onFailure { Log.w(TAG, "拦截提醒发送失败", it) }
    }

    private fun isInContacts(phoneNumber: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 没授权就不做白名单判断 —— 但这意味着可能误杀，所以后面会提示用户授权
            return false
        }
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber),
        )
        return runCatching {
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    private fun allow(callDetails: Call.Details) = respondToCall(
        callDetails,
        CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build(),
    )

    private fun reject(callDetails: Call.Details) = respondToCall(
        callDetails,
        CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSilenceCall(true)      // 静默：不响铃
            .setSkipCallLog(true)      // 不留未接来电记录，眼不见心不烦
            .setSkipNotification(true) // 不弹通知
            .build(),
    )
}
