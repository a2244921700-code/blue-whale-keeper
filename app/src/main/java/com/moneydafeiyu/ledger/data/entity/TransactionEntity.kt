package com.moneydafeiyu.ledger.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 正式账目。金额一律整数「分」，永不使用浮点，统计不会丢精度。
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["occurredAt"]),
        Index(value = ["status", "occurredAt"]),
        Index(value = ["categoryId", "occurredAt"]),
        Index(value = ["direction", "occurredAt"]),
        Index(value = ["merchant"]),
        Index(value = ["batchId"]),
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountCents: Long,
    /** EXPENSE / INCOME / TRANSFER */
    val direction: String,
    val merchant: String = "",
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val note: String = "",
    val occurredAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    /** PENDING(待确认) / CONFIRMED / DELETED */
    val status: String = "CONFIRMED",
    val confidence: Double = 1.0,
    val sourceKey: String = "MANUAL",
    /** 历史账单导入批次，手动/自动记的为 null */
    val batchId: Long? = null,
    /** 是否由用户手动改过分类（改过的不会被规则回放覆盖） */
    val categoryLocked: Boolean = false,
)
