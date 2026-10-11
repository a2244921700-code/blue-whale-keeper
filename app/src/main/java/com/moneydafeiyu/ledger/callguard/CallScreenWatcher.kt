package com.moneydafeiyu.ledger.callguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log

/**
 * 第二层防线：按**来电显示名**判断（例如「XX传媒」「XX国学」）。
 *
 * 为什么必须用读屏：
 *   厂商云识别出来的名字（小米/华为/荣耀的号码识别）**不经过任何对外接口**，
 *   它只是电话应用画在屏幕上的一行字。除了把屏幕读下来，没有别的办法。
 *
 * 这条路的三个固有代价，必须接受：
 *   1. **会响一两声**才挂断 —— 屏幕上有字的时候，铃声早就响了；
 *   2. 依赖电话应用的界面结构，系统更新换 UI 就可能失效；
 *   3. 需要无障碍服务常驻。
 *
 * 所以它是「补充」而不是「主力」：号码规则能解决的一律走第一层。
 */
class CallScreenWatcher(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "CallGuard"

        /**
         * 常见拨号/通话应用的包名。
         *
         * 不同 ROM 差别很大（AOSP 是 incallui + dialer，华为荣耀走 contacts），
         * 所以这里列一串，能命中哪个算哪个 —— 反正只在来电状态下才处理。
         */
        val PHONE_PACKAGES = setOf(
            "com.android.incallui",
            "com.android.dialer",
            "com.android.server.telecom",
            "com.google.android.dialer",
            "com.hihonor.incallui",
            "com.hihonor.phone",
            "com.hihonor.contacts",
            "com.huawei.incallui",
            "com.huawei.phone",
            "com.huawei.contacts",
            "com.android.contacts",
            "com.miui.incallui",
            "com.android.phone",
        )

        fun isPhonePackage(pkg: String?): Boolean = pkg != null && pkg in PHONE_PACKAGES
    }

    /** 当前是否正在响铃 —— 只在响铃时才去匹配，避免误杀正常界面 */
    @Volatile
    private var ringing: Boolean = false

    /** 本次来电是否已经处理过，避免重复挂断 */
    @Volatile
    private var handledThisRing: Boolean = false

    private val telecom: TelecomManager? by lazy {
        service.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    }

    fun start() {
        runCatching {
            val tm = service.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            @Suppress("DEPRECATION")
            tm.listen(object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    @Suppress("DEPRECATION")
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING -> {
                            ringing = true
                            handledThisRing = false
                            Log.i(TAG, "来电响铃，等待界面出现后读屏判断")
                        }
                        TelephonyManager.CALL_STATE_IDLE,
                        TelephonyManager.CALL_STATE_OFFHOOK,
                        -> {
                            ringing = false
                            handledThisRing = false
                        }
                    }
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }.onFailure { Log.w(TAG, "监听通话状态失败（可能缺 READ_PHONE_STATE）", it) }
    }

    /**
     * 无障碍事件送来的屏幕文本。
     * @return true 表示已按关键词挂断
     */
    fun onScreenText(text: String): Boolean {
        if (!ringing || handledThisRing) return false
        if (!CallGuardSettings.isScreenReaderEnabled(service)) return false
        if (text.length < 2) return false

        val compact = text.replace(Regex("\\s+"), "")
        val keywords = CallGuardSettings.keywords(service)
        val hit = keywords.firstOrNull { compact.contains(it) } ?: return false

        handledThisRing = true
        Log.i(TAG, "来电显示名命中「$hit」，执行挂断")
        CallGuardSettings.appendCall(service, nowLabel() + " | （读屏命中） | 含「$hit」 | 已挂断 · 关键词")
        endCall()
        return true
    }

    /**
     * 挂断当前通话。
     *
     * 用 TelecomManager.endCall()：API 28 起对第三方开放，
     * 需要 ANSWER_PHONE_CALLS 权限（运行时权限）。
     */
    private fun endCall() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "系统版本低于 Android 9，无法通过公开接口挂断")
            return
        }
        runCatching {
            @Suppress("DEPRECATION")
            telecom?.endCall()
        }.onFailure { Log.e(TAG, "挂断失败（多半是没给 ANSWER_PHONE_CALLS 权限）", it) }
    }

    private fun nowLabel(): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
}
