package com.moneydafeiyu.ledger.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 识别规则档案：整份规则以 JSON 形式版本化保存，支持导入导出与回滚。
 * 规则改坏了一键退回上一版。
 */
@Entity(
    tableName = "rule_profiles",
    indices = [Index(value = ["isActive"]), Index(value = ["name", "version"], unique = true)]
)
data class RuleProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val version: Int,
    /** 完整规则的 JSON */
    val contentJson: String,
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = false,
)
