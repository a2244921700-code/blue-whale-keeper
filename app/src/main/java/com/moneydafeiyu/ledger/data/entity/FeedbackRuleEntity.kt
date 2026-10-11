package com.moneydafeiyu.ledger.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 人格化反馈规则 —— 把 16 个鲸鱼娘场景做成可配置数据，而不是写死在代码里。
 *
 * 已确认的触发优先级：彩蛋 > 金额 > 分类+时间 > 分类 > 兜底
 * priority 数值越小越优先。
 */
@Entity(
    tableName = "feedback_rules",
    indices = [Index(value = ["priority"]), Index(value = ["code"], unique = true)]
)
data class FeedbackRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** R-01 ~ R-15，对应需求规格 §3.1 */
    val code: String,
    /** 越小越优先 */
    val priority: Int,
    /** CATEGORY / AMOUNT_GT / MERCHANT_CONTAINS / TIME_AFTER / INCOME_AMOUNT_GTE / FALLBACK / SPLASH */
    val matchType: String,
    /** 匹配值：分类名 / "500" / "鱼" / "17:00" */
    val matchValue: String = "",
    /** 附加条件（如餐饮+17点后的分类条件） */
    val extraMatchType: String = "",
    val extraMatchValue: String = "",
    /** assets/feedback/ 下的文件名 */
    val assetName: String,
    /** 台词，用于无障碍描述与通知文案 */
    val caption: String = "",
    /** assets/sounds/ 下的音效文件名。留空表示这个情景没有配音 */
    val soundName: String = "",
    val enabled: Boolean = true,
    /** 同一规则多张变体图时，随机选一张 */
    val variantGroup: String = "",
)
