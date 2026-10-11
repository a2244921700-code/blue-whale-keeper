package com.moneydafeiyu.ledger.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「当前家当」的一次登记快照。
 *
 * 语义：用户在某时刻把手上真实拥有的总钱数（现金 + 各账户余额 + 理财等）如实录一次。
 * 之后每一笔收支都从这个基准上增减，所以：
 *
 *     当前全部身家 = 最近一次登记的家当 + 此后所有收入 - 此后所有支出
 *
 * 这样做的好处是不需要用户去维护每个账户的期初余额，
 * 漏记了也只要重新登记一次就能把账对齐。
 */
@Entity(
    tableName = "asset_snapshots",
    indices = [Index(value = ["recordedAt"])]
)
data class AssetSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 登记时的总资产（分） */
    val amountCents: Long,
    /** 这份家当对应的时刻 */
    val recordedAt: Long,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
