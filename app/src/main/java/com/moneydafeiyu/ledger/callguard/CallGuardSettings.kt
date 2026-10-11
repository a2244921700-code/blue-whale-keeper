package com.moneydafeiyu.ledger.callguard

import android.content.Context

/**
 * 通话防护的配置存储。
 *
 * 关键词列表用换行分隔存成一个字符串 —— 用户是在设置里逐行编辑的，
 * 存成 Set 反而丢顺序、不好看。
 */
object CallGuardSettings {

    private const val PREF = "call_guard"
    private const val KEY_MASTER = "master_enabled"
    private const val KEY_RULE_PREFIX = "rule_"
    private const val KEY_KEYWORDS = "keywords"
    private const val KEY_READER = "screen_reader_enabled"
    private const val KEY_BLOCKED_LOG = "blocked_log"

    /** 你指定的两个词放在最前面，其余是我补的电销公司常见注册名 */
    val DEFAULT_KEYWORDS: List<String> = listOf(
        "传媒", "国学",
        "金融", "贷款", "借钱", "网贷", "催收",
        "保险", "地产", "房产", "装饰", "装修",
        "营销", "推广", "电销",
        "中奖", "积分", "兑换",
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 总开关 */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MASTER, true)
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER, on).apply()
    }

    /** 单条号码规则的开关 */
    fun isRuleEnabled(context: Context, rule: String): Boolean =
        prefs(context).getBoolean(KEY_RULE_PREFIX + rule, CallRules.DEFAULTS[rule] ?: false)

    fun setRuleEnabled(context: Context, rule: String, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_RULE_PREFIX + rule, on).apply()
    }

    fun enabledRules(context: Context): Map<String, Boolean> =
        CallRules.DEFAULTS.keys.associateWith { isRuleEnabled(context, it) }

    /** 第二层（读屏按名字判断）的开关 */
    fun isScreenReaderEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_READER, true)
    fun setScreenReaderEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_READER, on).apply()
    }

    /** 读屏关键词 */
    fun keywords(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_KEYWORDS, null) ?: return DEFAULT_KEYWORDS
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setKeywords(context: Context, list: List<String>) {
        prefs(context).edit().putString(KEY_KEYWORDS, list.joinToString("\n")).apply()
    }

    fun keywordsAsText(context: Context): String = keywords(context).joinToString("\n")

    // ---- 拦截记录（最近 200 条，纯文本行，便于直接展示）----

    fun blockedLog(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_BLOCKED_LOG, null) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun appendBlocked(context: Context, line: String) {
        val list = blockedLog(context).toMutableList()
        list.add(0, line)
        while (list.size > 200) list.removeAt(list.size - 1)
        prefs(context).edit().putString(KEY_BLOCKED_LOG, list.joinToString("\n")).apply()
    }

    fun clearBlockedLog(context: Context) {
        prefs(context).edit().remove(KEY_BLOCKED_LOG).apply()
    }

    // ---- 号码白名单 ----
    //
    // 用户在拦截通知上点「这是正常电话」时加进来。
    // 有了它，静默拦截才是**可撤销**的 —— 否则误杀一通就永久误杀。

    private const val KEY_WHITELIST = "whitelist"

    fun whitelist(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()

    fun addToWhitelist(context: Context, digits: String) {
        if (digits.isBlank()) return
        val set = whitelist(context).toMutableSet()
        set.add(digits)
        prefs(context).edit().putStringSet(KEY_WHITELIST, set).apply()
    }

    fun removeFromWhitelist(context: Context, digits: String) {
        val set = whitelist(context).toMutableSet()
        set.remove(digits)
        prefs(context).edit().putStringSet(KEY_WHITELIST, set).apply()
    }

    fun isWhitelisted(context: Context, digits: String): Boolean =
        digits.isNotBlank() && whitelist(context).contains(digits)

    // ---- 拦截提醒开关 ----

    private const val KEY_NOTIFY_BLOCKED = "notify_blocked"

    /** 拦截后是否发提醒。默认开 —— 静默拦截必须让用户事后能知道 */
    fun isNotifyBlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_BLOCKED, true)

    fun setNotifyBlocked(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_BLOCKED, on).apply()
    }

    // ---- 来电观测记录（最近 500 条）----
    //
    // 这一份是**所有**来电，不只是被拦的。用途是回答三个问题：
    //   ① 荣耀到底允不允许第三方来电筛选生效（有记录就说明生效了）
    //   ② callerDisplayName 在你机器上究竟有没有值
    //   ③ 骚扰电话的号码有什么共性
    // 用一周数据再回头定规则，比现在拍脑袋靠谱。

    private const val KEY_CALL_LOG = "call_log"

    fun callLog(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CALL_LOG, null) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun appendCall(context: Context, line: String) {
        val list = callLog(context).toMutableList()
        list.add(0, line)
        while (list.size > 500) list.removeAt(list.size - 1)
        prefs(context).edit().putString(KEY_CALL_LOG, list.joinToString("\n")).apply()
    }

    fun clearCallLog(context: Context) {
        prefs(context).edit().remove(KEY_CALL_LOG).apply()
    }
}
