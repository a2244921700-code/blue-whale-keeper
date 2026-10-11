package com.moneydafeiyu.ledger.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.moneydafeiyu.ledger.R

/**
 * 「教它认识这个页面」的通知入口。
 *
 * ## 为什么需要这么一个入口
 *
 * 微信支付页是自绘的，节点里读不到文字。要识别它只能截图做 OCR，
 * 而 OCR 有代价（几百毫秒 + 耗电），不可能对所有页面都做。
 *
 * 所以设计成**用户教一次，之后自动认**：教的时候记下页面结构骨架，
 * 以后骨架一对上就自动 OCR。问题只剩下 —— **怎么在不离开微信的情况下教它？**
 *
 * 切回本 App 是不行的：一切走，支付页就没了，缓存也跟着失效。
 * 所以用通知：**下拉通知栏点一下就行，微信始终留在前台。**
 *
 * ## 为什么用缓存的指纹而不是现算
 *
 * 下拉通知栏时，前台已经变成 SystemUI 了，现算指纹必然算到通知栏的骨架。
 * 所以无障碍服务在每次窗口切换时就缓存了一份，这里直接取缓存。
 */
object PageTeachNotifier {

    private const val CHANNEL_ID = "page_teach"
    private const val NOTIF_ID = 8802
    private const val TAG = "PageTeach"

    /** 发通知的节流：自绘页面切换很频繁，不节流会一直响 */
    @Volatile
    private var lastNotifyAt: Long = 0L
    private const val NOTIFY_THROTTLE_MS = 60_000L

    fun maybeNotify(context: Context, pkg: String, activity: String, fingerprint: String) {
        if (fingerprint.isBlank()) return
        if (PageSignatureStore.matches(context, pkg, activity, fingerprint)) return

        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < NOTIFY_THROTTLE_MS) return
        lastNotifyAt = now

        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "记录支付页面",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = "遇到读不出文字的支付页时，用它教应用认识这个页面" }
                )
            }

            val pi = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, PageTeachReceiver::class.java).apply {
                    action = PageTeachReceiver.ACTION_TEACH
                    putExtra(PageTeachReceiver.EXTRA_PKG, pkg)
                    putExtra(PageTeachReceiver.EXTRA_ACT, activity)
                    putExtra(PageTeachReceiver.EXTRA_FP, fingerprint)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("认不出这个页面？")
                .setContentText("点这里让大肥鱼记住它，以后自动记录")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "这个页面读不出文字（微信支付页通常是自绘的）。\n\n" +
                            "点下面的按钮记住它的样子，以后每次出现都会自动识别。"
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_add, "记住这个页面", pi)
                .build()

            nm.notify(NOTIF_ID, n)
        }.onFailure { Log.w(TAG, "发通知失败", it) }
    }
}

/** 处理「记住这个页面」的点击 */
class PageTeachReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TEACH = "com.moneydafeiyu.ledger.action.TEACH_PAGE"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_ACT = "act"
        const val EXTRA_FP = "fp"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TEACH) return

        // 优先用通知里带的信息；没有就取无障碍服务的实时缓存。
        // 两条路都是为了拿到「用户点按钮之前看的那一页」——
        // 点按钮的瞬间前台已经变成通知栏了，绝不能现算。
        var pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        var act = intent.getStringExtra(EXTRA_ACT).orEmpty()
        var fp = intent.getStringExtra(EXTRA_FP).orEmpty()

        if (fp.isBlank()) {
            val (p, a, f) = PaymentAccessibilityService.currentPage()
            pkg = p; act = a; fp = f
        }

        if (fp.isBlank()) {
            Log.w("PageTeach", "拿不到页面指纹，无法记录")
            return
        }

        PageSignatureStore.add(
            context,
            PageSignature(
                packageName = pkg,
                activityName = act,
                structureFingerprint = fp,
                label = "手动记住 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date()),
            )
        )
        Log.i("PageTeach", "已记住页面：$pkg / $act / $fp")

        // 收起通知
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(8802)
        }
    }
}
