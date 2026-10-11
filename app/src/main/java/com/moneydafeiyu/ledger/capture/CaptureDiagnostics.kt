package com.moneydafeiyu.ledger.capture

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 采集链路体检。
 *
 * 自动记账最容易失败的地方不是解析，而是"通知根本没送到"。
 * 这个对象把链路拆成三段分别检测，让用户（和我）能一眼看出卡在哪：
 *
 *   ① 系统授权  —— 用户在设置里勾了"通知使用权"吗
 *   ② 服务绑定  —— 系统真的把服务拉起来了吗（国产 ROM 常在这一步骗人）
 *   ③ 数据到达  —— 调试台里有没有东西
 */
object CaptureDiagnostics {

    /** ① 系统设置里是否勾选了本应用 */
    fun systemGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    /** ② 系统当前是否真的把服务绑起来了 */
    fun serviceConnected(): Boolean = PaymentNotificationListener.isConnected

    /** 系统记录的所有已授权监听器（原始字符串，便于看出到底是谁） */
    fun enabledListenersRaw(context: Context): String =
        Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: "(空)"

    /**
     * 请求系统重新绑定监听服务。
     *
     * 这是公开 API，**不需要 root 也不需要 Shizuku**。
     * 国产 ROM 经常出现"设置里勾了、服务却没起来"的状态，
     * 这一招通常能把它拽回来，比让用户手动关开权限快得多。
     */
    fun requestRebind(context: Context) {
        runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(context, PaymentNotificationListener::class.java)
            )
        }
    }
}
