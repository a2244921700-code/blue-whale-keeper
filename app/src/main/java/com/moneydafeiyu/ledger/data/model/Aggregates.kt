package com.moneydafeiyu.ledger.data.model

/** 按天聚合（当月柱状图 · 按天模式） */
data class DailyTotal(
    val day: String,
    val direction: String,
    val totalCents: Long,
)

/** 按分类聚合（当月柱状图 · 按分类模式 + 占比图） */
data class CategoryTotal(
    val categoryId: Long?,
    val categoryName: String?,
    val direction: String,
    val totalCents: Long,
    val txCount: Long,
)

/** 总览 */
data class Overview(
    val totalIncomeCents: Long,
    val totalExpenseCents: Long,
    val txCount: Long,
)
