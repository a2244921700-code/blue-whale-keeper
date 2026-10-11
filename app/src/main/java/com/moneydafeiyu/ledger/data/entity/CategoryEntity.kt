package com.moneydafeiyu.ledger.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分类。支出与收入各自独立分类（用户明确要求：支出分类的同时收入也要分类）。
 */
@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["direction", "sortOrder"]),
        Index(value = ["parentId"]),
        Index(value = ["name", "direction"], unique = true),
    ]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** EXPENSE / INCOME */
    val direction: String,
    val icon: String = "",
    val color: String = "#4A7EBB",
    /** 支持二级分类，null 为一级 */
    val parentId: Long? = null,
    val sortOrder: Int = 0,
    /** 系统内置分类不可删除，只能改名/改色 */
    val isSystem: Boolean = false,
)
