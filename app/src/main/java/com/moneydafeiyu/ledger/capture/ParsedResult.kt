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
    /**
     * 这笔钱是「暂定」的 —— 收到了消息，但钱还没真正到账。
     *
     * 目前只有一种情况：微信转账的**待接收**通知。
     * 这种会被记成待确认状态，等你收款后再落实，避免对方撤回后账目对不上。
     */
    val provisional: Boolean = false,
)
