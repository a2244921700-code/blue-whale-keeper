package com.moneydafeiyu.ledger.capture

import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 权限体检。
 *
 * 为什么必须做这件事：这个 App 的自动记账依赖一串"容易被系统悄悄关掉"的权限，
 * 少任何一个都会导致**静默漏记** —— 用户以为在记，其实什么都没记。
 * 所以不仅要能检测，还要能一键跳到对应设置页，并且每次启动都体检一遍。
 */
data class PermissionItem(
    val key: String,
    val title: String,
    /** 这项权限负责什么 */
    val purpose: String,
    /** 缺失后果 —— 让用户明白为什么值得去开 */
    val consequence: String,
    val granted: Boolean,
    /** true = 缺了自动记账就是废的 */
    val critical: Boolean,
    val settingsIntent: Intent?,
    /** 荣耀/华为等 ROM 的路径说明，和原生不同 */
    val romHint: String = "",
    /**
     * 能否用公开 API 检测。
     * 厂商私有的开关（如自启动管理）没有标准接口，硬说"未开启"是误导，
     * 所以这类项只提示"请自行确认"。
     */
    val detectable: Boolean = true,
    /** 设置页的跳转是否可靠；厂商页面常常打不开，需要如实告知 */
    val jumpReliable: Boolean = true,
)

object PermissionChecker {

    fun checkAll(context: Context): List<PermissionItem> = listOf(
        notificationListener(context),
        accessibility(context),
        overlay(context),
        postNotification(context),
        batteryOptimization(context),
        autoStart(context),
    )

    /** 通知使用权 —— 支付宝、淘宝、京东、拼多多的主链路 */
    private fun notificationListener(context: Context): PermissionItem {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        return PermissionItem(
            key = "listener",
            title = "通知使用权",
            purpose = "读取支付宝 / 淘宝 / 京东 / 拼多多的支付通知",
            consequence = "不开这个，这几家的账一笔都记不到",
            granted = enabled,
            critical = true,
            settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            romHint = "荣耀：设置 → 通知和状态栏 → 通知使用权",
        )
    }

    /** 无障碍服务 —— 微信那条链路完全靠它（读页面 + 截图 OCR） */
    private fun accessibility(context: Context): PermissionItem {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: ""
        // 精确到具体服务：同一个 App 可能有多个无障碍服务，只看包名会误判
        val expected = ComponentName(context, PaymentAccessibilityService::class.java).flattenToString()
        val enabled = raw.split(':').any { it.equals(expected, ignoreCase = true) } ||
            PaymentAccessibilityService.isConnected
        return PermissionItem(
            key = "accessibility",
            title = "无障碍服务",
            purpose = "识别支付结果页、必要时截图做文字识别",
            consequence = "不开这个，微信的账记不到（微信不发支付通知）",
            granted = enabled,
            critical = true,
            settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            romHint = "荣耀：设置 → 辅助功能 → 无障碍 → 已安装的服务",
        )
    }

    /** 悬浮窗 —— 鲸鱼娘全屏反馈的前提 */
    private fun overlay(context: Context): PermissionItem {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
        return PermissionItem(
            key = "overlay",
            title = "显示在其他应用上层",
            purpose = "支付完成后让鲸鱼娘全屏弹出来",
            consequence = "不开也能记账，只是反馈会降级成普通通知",
            granted = granted,
            critical = false,
            settingsIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            romHint = "荣耀：设置 → 应用 → 特殊访问权限 → 显示在其他应用上层",
        )
    }

    /** 通知权限（Android 13+ 需要运行时授予） */
    private fun postNotification(context: Context): PermissionItem {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return PermissionItem(
            key = "post_notification",
            title = "通知权限",
            purpose = "交易提醒、金额补录、服务异常告警",
            consequence = "不开就收不到任何提醒，漏记了你也不知道",
            granted = granted,
            critical = false,
            settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 电池优化白名单 —— 不加进去，服务会被系统休眠 */
    private fun batteryOptimization(context: Context): PermissionItem {
        val pm = context.getSystemService(PowerManager::class.java)
        val granted = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else null
        return PermissionItem(
            key = "battery",
            title = "电池优化白名单",
            purpose = "让采集服务在后台活下去",
            consequence = "不加上，锁屏一段时间后服务会被休眠，账就漏了",
            granted = granted,
            critical = true,
            settingsIntent = intent,
            romHint = "荣耀：设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接",
        )
    }

    /**
     * 自启动 / 后台运行。
     * 这项**无法用公开 API 检测**（厂商私有开关），只能常驻提醒用户自己去确认。
     */
    private fun autoStart(context: Context): PermissionItem = PermissionItem(
        key = "autostart",
        title = "自启动 / 后台运行",
        purpose = "重启手机后服务能自动恢复",
        consequence = "不设置的话，手机重启后自动记账会静默失效",
        granted = false,
        critical = false,
        settingsIntent = autoStartIntent(context),
        romHint = "荣耀：设置 → 应用 → 应用启动管理 → 找到本应用 → 改为「手动管理」并全开",
        detectable = false,
        jumpReliable = false,
    )

    /**
     * 打开「自启动 / 后台运行」设置。
     *
     * 现实情况：这个页面**没有标准 API**，各家 ROM 的 Activity 名都不一样，
     * 而且随时可能改。所以这里按"最可能命中"的顺序逐个探测，
     * 一个都打不开就老实回退到应用详情页 —— 不假装能跳准。
     */
    private fun autoStartIntent(context: Context): Intent {
        val candidates = listOf(
            // 荣耀 / 华为
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.mainscreen.MainScreenActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.mainscreen.MainScreenActivity",
            // 小米
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // OPPO / 一加
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
            // vivo
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        )
        for ((pkg, cls) in candidates) {
            val intent = Intent().setComponent(ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) != null) return intent
        }
        // 兜底：应用详情页
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 缺失的关键项数量，首页用来决定要不要报警 */
    fun criticalMissing(context: Context): List<PermissionItem> =
        checkAll(context).filter { it.critical && !it.granted }
}
