package com.moneydafeiyu.ledger.capture

/**
 * 解析结果的统一形态。
 *
 * 通知和页面两条通道的文本长得完全不一样，但"解析出什么"是一致的：
 * 金额、方向、商户。抽成同一个类型，入库流水线就不用关心证据是从哪来的。
 */
data class ParsedResult(
    val amountCents: Long,
    val direction: String,
    val merchant: String,
    /** 命中的规则名，调试台用它回答"这笔是怎么认出来的" */
    val rule: String,
    val confidence: Double,
)
