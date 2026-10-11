package com.moneydafeiyu.ledger.capture

import java.security.MessageDigest

/**
 * 证据指纹：用于判断"这条证据我是不是已经收过了"。
 *
 * 注意实现上的取舍：指纹**不设唯一约束**，它只用于候选合并。
 * 参考项目里把指纹做成 UNIQUE 后，同一分钟内两笔同额同商户的真实消费会被静默吞掉，
 * 造成漏记 —— 那个代价比偶尔重复大得多。
 */
object Fingerprint {

    /** 时间桶：同一分钟内的同文本视为同一条，避免服务被系统重绑后重复入库 */
    private const val BUCKET_MS = 60_000L

    fun of(sourceKey: String, rawText: String, capturedAt: Long): String {
        val normalized = rawText.replace(Regex("\\s+"), " ").trim()
        val bucket = capturedAt / BUCKET_MS
        return sha256("$sourceKey|$normalized|$bucket").take(32)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
