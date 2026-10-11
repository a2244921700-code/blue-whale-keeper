package com.moneydafeiyu.ledger.feedback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.moneydafeiyu.ledger.R
import com.moneydafeiyu.ledger.data.entity.FeedbackRuleEntity
import com.moneydafeiyu.ledger.ui.MainActivity

/**
 * 鲸鱼娘全屏反馈。
 *
 * 为什么用悬浮窗而不是 Activity：
 * Android 10 起禁止后台启动 Activity，支付完想立刻弹东西出来，
 * 走 WindowManager 加 TYPE_APPLICATION_OVERLAY 是唯一稳的路子。
 *
 * 没有悬浮窗权限时**降级为高优先级通知**（带大图）——
 * 反馈不能因为少个权限就彻底消失。
 */
object FeedbackOverlay {

    private const val TAG = "FeedbackOverlay"
    private const val CHANNEL_ID = "dafeiyu_feedback"
    private const val SHOW_MS = 2800L

    private val main = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var currentWm: WindowManager? = null

    fun show(
        context: Context,
        rule: FeedbackRuleEntity,
        amountText: String,
        subtitle: String,
        onTap: (() -> Unit)? = null,
    ) {
        // 主路径：起一个独立的全屏 Activity。
        // 只要持有「显示在其他应用上层」权限，后台启动 Activity 就是被系统豁免允许的，
        // 而且 Activity 一定在最上层 —— 比悬浮窗可靠得多。
        val started = runCatching {
            FeedbackActivity.start(context, rule.assetName, rule.caption, amountText, subtitle, rule.soundName)
        }.isSuccess

        if (!started) {
            Log.w(TAG, "无法启动反馈 Activity，降级为通知")
            notifyFallback(context, rule, amountText, subtitle)
        }
    }

    private fun canOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun loadBitmap(context: Context, assetName: String) = runCatching {
        context.assets.open("feedback/$assetName").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /** 没有悬浮窗权限时的降级：高优先级通知 + 大图 */
    private fun notifyFallback(
        context: Context,
        rule: FeedbackRuleEntity,
        amountText: String,
        subtitle: String,
    ) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "鲸鱼娘反馈", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val bmp = loadBitmap(context, rule.assetName)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(rule.caption.ifBlank { "记好了" })
                .setContentText("$amountText  $subtitle")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            if (bmp != null) {
                builder.setStyle(
                    NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as android.graphics.Bitmap?)
                )
            }
            nm.notify(rule.code.hashCode(), builder.build())
        }.onFailure { Log.e(TAG, "降级通知也失败了", it) }
    }
}
