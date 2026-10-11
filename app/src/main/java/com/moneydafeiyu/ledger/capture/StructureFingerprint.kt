package com.moneydafeiyu.ledger.capture

import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

/**
 * 页面结构指纹。
 *
 * ## 为什么不能用文本
 *
 * 微信支付页是自绘的：节点树里 text 全空，读不到「支付成功」「¥0.01」。
 * 但**节点结构还在**。而金额天天变、商户名天天变，
 * **布局骨架却始终是那一套** —— 这正好适合做身份标识。
 *
 * ## 取什么、不取什么
 *
 * 取：
 *   - 类名（短名，如 TextView）
 *   - 资源 id（如 tv_amount）—— 这是最有辨识度的部分
 *   - contentDescription（归一化：数字换成 #，截断 30 字）
 *
 * 不取：
 *   - **text** —— 会变，取了就白搭
 *   - 坐标、尺寸 —— 会随屏幕/字号变
 *
 * 思路来自参考实现 AutoAccountingOrg/AutoAccounting。
 */
object StructureFingerprint {

    private const val MAX_DEPTH = 5
    private const val MAX_CHILDREN = 10

    /**
     * 算当前活动窗口的结构指纹。
     * @return 指纹哈希；拿不到节点时返回空串（调用方需自行判空）
     */
    fun of(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val skeleton = build(root, 0)
        return if (skeleton.isBlank()) "" else md5(skeleton)
    }

    /** 同时给出骨架明文 —— 调试台里显示它，便于人工核对两个页面像不像 */
    fun skeletonOf(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        return build(root, 0)
    }

    private fun build(node: AccessibilityNodeInfo, depth: Int): String {
        if (depth > MAX_DEPTH) return ""
        val desc = describe(node)
        val count = minOf(node.childCount, MAX_CHILDREN)
        if (count == 0) return desc

        val sb = StringBuilder(desc).append('(')
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val s = build(child, depth + 1)
            if (s.isNotEmpty()) {
                if (sb.last() != '(') sb.append(',')
                sb.append(s)
            }
            @Suppress("DEPRECATION") runCatching { child.recycle() }
        }
        return sb.append(')').toString()
    }

    /** 单个节点的描述：类名 + id + 归一化描述 */
    private fun describe(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "?"
        val id = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        // contentDescription 归一化：数字→#，这样"剩余 3 次"和"剩余 5 次"算同一个骨架
        val name = node.contentDescription?.toString()
            ?.replace(Regex("[0-9]+"), "#")
            ?.trim()
            ?.take(30)
            ?.takeIf { it.isNotBlank() }

        return when {
            id != null && name != null -> "$cls#$id@$name"
            id != null -> "$cls#$id"
            name != null -> "$cls@$name"
            else -> cls
        }
    }

    private fun md5(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
