package com.moneydafeiyu.ledger.capture

import android.content.Context

/**
 * 采集开关。
 *
 * 「宽集模式」是诊断用的：打开后不再按包名和关键词过滤，
 * 手机上任何 App 的通知都会被记下来。
 * 它的唯一用途是回答一个问题 —— **服务到底有没有在工作**。
 * 如果宽集模式下能看到支付宝的通知，说明链路通、只是关键词没匹配上；
 * 如果连宽集模式都空空如也，那就是系统压根没把通知送过来。
 */
object CaptureSettings {

    private const val PREF = "capture_settings"
    private const val KEY_CATCH_ALL = "catch_all"
    private const val KEY_SHOW_ALL_IN_LIST = "show_all_in_list"
    private const val KEY_SOUND_WHEN_SILENT = "sound_when_silent"

    /** 宽集模式：抓所有 App 的通知（诊断用，平时应关闭） */
    fun isCatchAll(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CATCH_ALL, false)

    fun setCatchAll(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_CATCH_ALL, on).apply()
    }

    /**
     * 静音/振动模式下是否仍然播放音效。
     *
     * 默认**否** —— 用户把手机调成静音是个明确意愿，不该被 App 无视。
     * 插画照常弹，只是不出声。
     */
    fun isSoundWhenSilent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOUND_WHEN_SILENT, false)

    fun setSoundWhenSilent(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND_WHEN_SILENT, on).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
