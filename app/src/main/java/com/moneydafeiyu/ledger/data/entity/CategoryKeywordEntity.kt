package com.moneydafeiyu.ledger.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分类关键词 —— 直接映射你准备的 6 个关键词库的分区结构。
 * 词量级几千条，独立成表便于可视化编辑与命中统计。
 */
@Entity(
    tableName = "category_keywords",
    indices = [
        Index(value = ["categoryId", "tier"]),
        Index(value = ["word"]),
        Index(value = ["categoryId", "word", "tier"], unique = true),
    ]
)
data class CategoryKeywordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val word: String,
    /** STRONG / CATEGORY / BRAND / SUFFIX / EXCLUDE */
    val tier: String,
    val enabled: Boolean = true,
    /** 命中次数，用于评估词的有效性，把长期不命中的词清理掉 */
    val hitCount: Long = 0,
)
