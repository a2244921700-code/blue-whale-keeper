package com.moneydafeiyu.ledger.data.entity

import androidx.room.Entity

/**
 * 证据 ↔ 交易 关联（多对多）。
 * 一笔交易可能由多条证据合成：通知抓到金额，OCR 补上商户 —— 两条证据指向同一笔交易。
 * 这是「证据融合」相对「简单去重」的关键差别。
 */
@Entity(
    tableName = "transaction_evidence",
    primaryKeys = ["transactionId", "evidenceId"],
)
data class TransactionEvidenceEntity(
    val transactionId: Long,
    val evidenceId: Long,
    /** PRIMARY（主证据）/ SUPPORTING（佐证） */
    val role: String = "PRIMARY",
)
