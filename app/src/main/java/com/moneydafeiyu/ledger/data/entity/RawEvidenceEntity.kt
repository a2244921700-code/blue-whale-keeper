package com.moneydafeiyu.ledger.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 原始证据：不可变、永久留档。
 * 存在的意义是「规则回放调试」和误判追溯 —— 改规则后能对全部历史证据重跑，
 * 看清哪几笔会变、哪几笔是误判。这是三个参考项目共同的失手处。
 */
@Entity(
    tableName = "raw_evidence",
    indices = [
        Index(value = ["capturedAt"]),
        Index(value = ["sourceKey", "capturedAt"]),
        Index(value = ["processState", "capturedAt"]),
        Index(value = ["fingerprint"]),
    ]
)
data class RawEvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 采集通道：NOTIFICATION / ACCESSIBILITY / OCR / MANUAL */
    val channel: String,
    /** 支付来源：WECHAT / ALIPAY / TAOBAO / JD / PDD / UNIONPAY / MANUAL */
    val sourceKey: String,
    val packageName: String = "",
    val title: String = "",
    /** 原始文本（已做 PII 脱敏：手机号、卡号） */
    val rawText: String,
    /** 抓取时刻 */
    val capturedAt: Long,
    /** 解析出的交易时刻，未解析为 null */
    val occurredAt: Long? = null,
    val processState: String = "NEW",
    /** 命中了哪条忽略规则 —— 调试关键，能回答"为什么这笔记没入账" */
    val discardReason: String? = null,
    /** 当时解析结果的 JSON 快照，用于对比规则变更前后的差异 */
    val parseSnapshot: String? = null,
    /** 用了哪一版规则，可回溯"当时为什么判错" */
    val ruleVersion: Int = 0,
    /**
     * 去重指纹（sourceKey|direction|amountCents|商户|时间窗）。
     * 注意：故意不加 UNIQUE —— 所有原始证据都要留档，
     * 是否同一笔交给「证据融合」判断，避免同一分钟两笔真实消费被静默吞掉。
     */
    val fingerprint: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
