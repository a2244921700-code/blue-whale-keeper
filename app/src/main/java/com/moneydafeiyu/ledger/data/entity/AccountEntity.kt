package com.moneydafeiyu.ledger.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 账户 / 资产。第一版不强制区分，默认只建一个「默认账户」，
 * 后续可加微信零钱、支付宝余额、银行卡、花呗等。
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["sortOrder"])]
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String = "CASH",
    val icon: String = "",
    val color: String = "#4A7EBB",
    val initialBalanceCents: Long = 0,
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
)
