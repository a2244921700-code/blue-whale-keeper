package com.moneydafeiyu.ledger.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 页面结构签名。
 *
 * ## 这解决的是什么问题
 *
 * 微信的支付结果页是**自绘**的 —— 无障碍节点树里 text 全是空的，
 * 所以读不到「支付成功」「¥0.01」这些文字，任何基于文本的判定都失效。
 *
 * 但**节点本身还在**：布局层级、类名、资源 id 都读得到。
 * 而金额会变、商户名会变，**布局骨架却始终一样**。
 * 所以骨架就是这个页面的身份证。
 *
 * ## 怎么用
 *
 * 用户在某支付页上手动「教」一次（长按通知栏的那条通知），
 * 记下：包名 + Activity + 结构指纹。之后每次页面切换，
 * 算出指纹一比就知道「又是这个页面」—— 这时才触发 OCR 去读金额。
 *
 * 好处是**没教过的页面零开销**，不会像之前那样对所有页面疯狂读屏。
 *
 * 思路来自参考实现 AutoAccountingOrg/AutoAccounting 的 PageSignature。
 */
data class PageSignature(
    val packageName: String,
    val activityName: String,
    /** 视图结构骨架的哈希：类名 + 资源 id + 描述，不含文本，不受数据变化影响 */
    val structureFingerprint: String,
    val label: String = "",
    val addedAt: Long = System.currentTimeMillis(),
) {
    fun key(): String = "$packageName|$activityName|$structureFingerprint"

    fun toJson(): JSONObject = JSONObject().apply {
        put("pkg", packageName)
        put("act", activityName)
        put("fp", structureFingerprint)
        put("label", label)
        put("at", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = PageSignature(
            packageName = o.optString("pkg", ""),
            activityName = o.optString("act", ""),
            structureFingerprint = o.optString("fp", ""),
            label = o.optString("label", ""),
            addedAt = o.optLong("at", 0L),
        )
    }
}

/**
 * 页面签名的存取与匹配。
 *
 * 存 SharedPreferences 而不是数据库：条目只有几条到几十条，
 * 为它加一次数据库迁移不划算。
 */
object PageSignatureStore {

    private const val PREF = "page_signatures"
    private const val KEY = "list"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(context: Context): List<PageSignature> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { PageSignature.fromJson(it) } }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, sig: PageSignature) {
        val list = all(context).toMutableList()
        list.removeAll { it.key() == sig.key() }
        list.add(sig)
        save(context, list)
    }

    fun remove(context: Context, key: String) {
        save(context, all(context).filter { it.key() != key })
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    /**
     * 匹配：包名 + Activity + 结构指纹。
     *
     * 三个都要对上 —— 刻意严格。
     * 因为一旦误匹配就会去截图做 OCR（几百毫秒的代价），
     * 宁可漏（用户再教一次）也不能乱触发。
     */
    fun matches(context: Context, pkg: String, activity: String, fingerprint: String): Boolean {
        if (fingerprint.isBlank()) return false
        return all(context).any {
            it.packageName == pkg &&
                it.activityName == activity &&
                it.structureFingerprint == fingerprint
        }
    }

    private fun save(context: Context, list: List<PageSignature>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }
}
