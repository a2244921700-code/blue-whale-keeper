package com.moneydafeiyu.ledger.capture

import com.moneydafeiyu.ledger.data.model.Direction

/**
 * 支付结果页解析器 —— 从无障碍读到的页面文本里抠出金额和**商户名**。
 *
 * 样本来自真机截图（支付宝支付成功页）：
 *
 *     支付成功  ¥0.08  羊驼老头  ¥0.10  百次立减  -¥0.02
 *     付款方式  招商银行储蓄卡(8473)  点击给商家对账  完成
 *
 * 里面有三个 ¥ 数字，「羊驼老头」夹在第一个和第二个之间：
 *   ¥0.08  ← 实付（要的就是它）
 *   ¥0.10  ← 原价
 *   -¥0.02 ← 优惠，带负号必须排除
 *
 * 所以两个关键规则：
 *   1. 金额取**第一个不带负号的 ¥ 数字**（不是最大、不是最后）；
 *   2. 商户取**第一个金额与第二个金额之间**的那段文字。
 */
object PageTextParser {

    /** 带货币符号的金额（页面上的实付金额） */
    private val SYMBOL_AMOUNT = Regex("[¥￥]\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)")

    /** 带标签的金额兜底：实付/付款金额/收款金额 */
    private val LABELED_AMOUNT = Regex("(?:实付|付款金额|收款金额|支付金额|订单金额)[：:\\s]*[¥￥]?\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)")

    private val EXPENSE_MARKERS = listOf("支付成功", "付款成功", "交易成功", "已付款", "支付完成", "付款完成")
    private val INCOME_MARKERS = listOf("收款成功", "到账成功", "已收款", "收款到账")

    /** 明显不是商户名的词 —— 页面上的按钮、标签、支付方式都在这儿 */
    private val MERCHANT_BLACKLIST = listOf(
        "支付成功", "付款成功", "交易成功", "收款成功", "完成", "返回", "回首页",
        "付款方式", "支付方式", "收款方", "商户", "订单", "交易", "账单", "详情",
        "余额", "零钱", "花呗", "银行卡", "储蓄卡", "信用卡", "对账", "查看",
        "优惠", "立减", "红包", "积分", "营销", "推荐", "首页", "我的",
    )

    private val LABELED_MERCHANT = listOf(
        Regex("(?:收款方|商户名称|商户|付款给|收款人)[：:\\s]+([^\\s，。|]{2,24})"),
        Regex("向\\s*([^\\s，。|]{2,24})\\s*(?:付款|支付)"),
    )

    fun parse(pageText: String): ParsedResult? {
        val compact = pageText.replace(Regex("[\\s\\u00A0]+"), " ").trim()
        if (compact.isEmpty()) return null

        val direction = detectDirection(compact) ?: return null
        val amountCents = extractAmount(compact) ?: return null
        if (amountCents <= 0) return null

        val merchant = extractMerchant(compact)

        return ParsedResult(
            amountCents = amountCents,
            direction = direction,
            merchant = merchant,
            rule = if (merchant.isNotBlank()) "page.with_merchant" else "page.amount_only",
            // 页面上直接看到的信息，可信度比通知高
            confidence = if (merchant.isNotBlank()) 0.93 else 0.80,
        )
    }

    private fun detectDirection(text: String): String? = when {
        INCOME_MARKERS.any { text.contains(it) } -> Direction.INCOME
        EXPENSE_MARKERS.any { text.contains(it) } -> Direction.EXPENSE
        else -> null
    }

    private fun extractAmount(text: String): Long? {
        // 优先带标签的，最准
        LABELED_AMOUNT.find(text)?.let { m ->
            toCents(m.groupValues[1])?.let { if (it > 0) return it }
        }
        // 否则取第一个不带负号的 ¥ 数字
        for (m in SYMBOL_AMOUNT.findAll(text)) {
            val start = m.range.first
            val prev = text.take(start).trimEnd().lastOrNull()
            if (prev == '-') continue // 这是优惠金额，跳过
            toCents(m.groupValues[1])?.let { if (it > 0) return it }
        }
        return null
    }

    private fun extractMerchant(text: String): String {
        // 1) 有标签就按标签取，最可靠
        for (p in LABELED_MERCHANT) {
            val m = p.find(text) ?: continue
            val s = clean(m.groupValues[1])
            if (isUsableMerchant(s)) return s
        }

        // 2) 没标签就靠位置：第一个金额与第二个金额之间那段
        val matches = SYMBOL_AMOUNT.findAll(text).filter { m ->
            val prev = text.take(m.range.first).trimEnd().lastOrNull()
            prev != '-'
        }.toList()
        if (matches.size >= 2) {
            val between = text.substring(matches[0].range.last + 1, matches[1].range.first).trim()
            val candidate = clean(between)
            if (isUsableMerchant(candidate)) return candidate
        }

        return ""
    }

    private fun clean(raw: String): String =
        raw.trim().trim('|', '·', '-', '，', ',', '。', '：', ':').trim()

    private fun isUsableMerchant(s: String): Boolean {
        if (s.length < 2 || s.length > 24) return false
        if (s.all { !it.isLetterOrDigit() }) return false
        if (s.matches(Regex("[0-9.\\s¥￥-]+"))) return false
        if (MERCHANT_BLACKLIST.any { s.contains(it) }) return false
        return true
    }

    private fun toCents(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val parts = s.split('.')
        val yuan = parts[0].toLongOrNull() ?: return null
        val cents = if (parts.size > 1) parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: 0L else 0L
        return yuan * 100 + cents
    }
}
