package com.moneydafeiyu.ledger.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.moneydafeiyu.ledger.data.model.Channel
import com.moneydafeiyu.ledger.data.model.SourcePackages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知采集通道。
 *
 * 真机经验：
 * 1. 金额常只出现在 bigText / subText，必须合并**全部**文本字段；
 * 2. 国产 ROM 可能"勾了权限却不绑定服务"，所以既要暴露 isConnected，
 *    也要把**每一次回调**都记进事件流 —— 否则无法区分"没收到"和"被过滤"；
 * 3. 回调在主线程，耗时动作一律甩到 IO 协程。
 */
class PaymentNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "PaymentNotify"

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var lastEventAt: Long = 0L
            private set

        @Volatile
        var lastEventPackage: String = ""
            private set

        /** 服务被系统创建的次数，用来判断它有没有被反复杀掉重启 */
        @Volatile
        var createCount: Int = 0
            private set

        private val PAY_HINTS = listOf(
            "支付", "付款", "收款", "转账", "红包", "消费", "扣款", "到账", "退款",
            "交易", "账单", "已付", "支出", "收入", "缴费", "充值", "订单",
            "余额", "零钱", "花呗", "白条", "钱包", "成功", "元", "收款方", "商户",
            // 自动扣款 / 自动续费这一类。它们的措辞和普通支付完全不同，
            // 比如「您的自动续费将于明日扣款」「先用后付扣款成功」，
            // 不加这些词会被粗筛直接滤掉，用户却以为 App 在好好记账。
            "自动扣款", "自动续费", "续费", "代扣", "先用后付", "免密", "签约",
            "订阅", "会员", "到期", "待扣", "已扣",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createCount += 1
        Log.i(TAG, "服务被创建，这是第 $createCount 次")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.i(TAG, "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.w(TAG, "通知监听已断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        lastEventAt = System.currentTimeMillis()
        lastEventPackage = pkg

        val notification = sbn.notification ?: run {
            CaptureEventLog.record(pkg, false, "通知对象为空", "")
            return
        }
        val extras = notification.extras ?: run {
            CaptureEventLog.record(pkg, false, "通知没有 extras", "")
            return
        }

        val parts = listOf(
            extras.getString(Notification.EXTRA_TITLE),
            extras.getString(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString(),
            extras.getCharSequence("android.infoText")?.toString(),
            notification.tickerText?.toString(),
        ).filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }
            // 真机样本里出现过 title/text/bigText 内容相同导致文本重复两遍，
            // 去重后解析器不会被同一串数字骗两次
            .distinct()

        if (parts.isEmpty()) {
            CaptureEventLog.record(pkg, false, "通知文本为空（可能被系统隐藏了详情）", "")
            return
        }

        val joined = parts.joinToString(" | ")
        val title = extras.getString(Notification.EXTRA_TITLE)?.trim().orEmpty()
        val knownSource = SourcePackages.of(pkg)
        val catchAll = CaptureSettings.isCatchAll(applicationContext)

        val sourceKey: String
        if (catchAll) {
            sourceKey = knownSource ?: "OTHER"
            CaptureEventLog.record(pkg, true, "宽集模式", joined)
        } else {
            if (knownSource == null) {
                CaptureEventLog.record(pkg, false, "不在白名单 App 里", joined)
                return
            }
            if (!looksPaymentRelated(joined)) {
                CaptureEventLog.record(pkg, false, "不含支付关键词，被过滤", joined)
                return
            }
            sourceKey = knownSource
            CaptureEventLog.record(pkg, true, "已入库", joined)
        }

        val postedAt = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        scope.launch {
            runCatching {
                val evidenceId = EvidenceIngest.submit(
                    context = applicationContext,
                    channel = Channel.NOTIFICATION,
                    sourceKey = sourceKey,
                    packageName = pkg,
                    title = if (catchAll && title.isEmpty()) pkg else title,
                    rawText = joined,
                    capturedAt = postedAt,
                )
                // 证据先落库，紧接着就跑解析入账 —— 原文永远留着，解析错了还能回溯
                if (evidenceId > 0 && !catchAll) {
                    val outcome = IngestPipeline.process(
                        context = applicationContext,
                        evidenceId = evidenceId,
                        sourceKey = sourceKey,
                        rawText = joined,
                        occurredAt = postedAt,
                    )
                    Log.i(TAG, "解析结果：$outcome")
                }
            }.onFailure { Log.e(TAG, "采集或入账失败", it) }
        }
    }

    private fun looksPaymentRelated(text: String): Boolean =
        PAY_HINTS.any { text.contains(it) }
}
