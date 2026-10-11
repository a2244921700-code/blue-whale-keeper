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

    /**
     * 裸金额：只有 `0.01` 这种形式，**没有货币符号**。
     *
     * ## 为什么需要它
     *
     * OCR 经常会**把 ¥ 符号吃掉**。实测微信支付成功页：
     *   屏幕上     ¥ 0.01
     *   OCR 结果   0.01        ← ¥ 被当成表格线/噪点丢了
     * 于是只认 ¥ 的规则一个都匹配不上，整条链路走到最后一步才失败。
     *
     * ## 为什么用小数点做判据
     *
     * 页面上数字很多，但**带小数点的**才是金额：
     *   20:29   → 有冒号，排除
     *   51%     → 有百分号，排除
     *   G37     → 没小数点，排除
     *   0.01    → ✅ 命中
     *
     * 前后加负向断言，避免从 `2026.10` 或 `1.2.3` 里截出半个数字。
     */
    private val BARE_AMOUNT = Regex("(?<![0-9.:])([0-9]{1,7}\\.[0-9]{1,2})(?![0-9])")

    /** 带标签的金额兜底：实付/付款金额/收款金额 */
    private val LABELED_AMOUNT = Regex("(?:实付|付款金额|收款金额|支付金额|订单金额)[：:\\s]*[¥￥]?\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)")

    private val EXPENSE_MARKERS = listOf(
        "支付成功", "付款成功", "交易成功", "已付款", "支付完成", "付款完成",
        // 微信支付成功页的其它写法
        "已支付", "交易已完成", "对方已收款",
    )
    private val INCOME_MARKERS = listOf(
        "收款成功", "到账成功", "已收款", "收款到账",
        // 微信收款成功页的原话 —— 这半句比"已收款"精确得多，
        // 而且不会和聊天里的「[转账]已收款」消息预览混淆。
        "你已收款", "资金已存入零钱", "已存入零钱", "零钱余额",
    )

    /**
     * 图片查看器的特征词 —— 命中就整条放弃。
     *
     * ## 为什么需要这个
     *
     * 用户在微信里**点开一张图片**（比如别人发的、或者自己截的支付成功截图），
     * OCR 会认认真真把图片里的「支付成功 ¥0.01」读出来 ——
     * 但那根本不是一笔真实交易，只是**一张图**。
     *
     * 实测就踩到过：OCR 结果末尾带着「转发截图」——
     * 那是图片长按菜单里的按钮，只有图片查看器才有。
     *
     * 其它词同理：保存图片、识别图中二维码、提取文字…都是图片专属操作。
     */
    private val IMAGE_VIEWER_MARKERS = listOf(
        "转发截图", "转发图片", "保存图片", "保存到手机", "编辑图片",
        "识别图中二维码", "提取文字", "翻译图片", "搜一搜",
        "发送给朋友", "图片已保存", "查看原图", "长按识别",
    )

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

    /**
     * 解析失败的**具体原因** —— 只给调试台用。
     *
     * 之前它内部有三个返回 null 的地方（图片查看 / 方向判不出 / 金额提不出），
     * 但日志里一律只写"页面文本里没找到可用的金额或方向"，
     * 完全看不出死在哪一步。用户报"识别到了却不记录"时无从排查。
     */
    fun diagnose(pageText: String): String {
        val compact = pageText.replace(Regex("[\\s\\u00A0]+"), " ").trim()
        if (compact.isEmpty()) return "文本为空"

        val img = IMAGE_VIEWER_MARKERS.firstOrNull { compact.contains(it) }
        if (img != null) return "像图片查看器（含「" + img + "」）"

        val dir = detectDirection(compact)
        val amt = extractAmount(compact)

        if (dir == null && amt == null) return "方向和金额都没认出来"
        if (dir == null) return "有金额 " + amt + " 分，但判断不出收支方向"
        if (amt == null) {
            val hasSymbol = Regex("[¥￥]").containsMatchIn(compact)
            return "方向是 " + dir + "，但金额提不出来（页面上" +
                (if (hasSymbol) "有 ¥ 符号，可能是格式没覆盖" else "**没有 ¥ 符号**，裸金额也没匹配上") + "）"
        }
        return "已能解析（" + dir + " / " + amt + " 分）"
    }

    fun parse(pageText: String): ParsedResult? {
        val compact = pageText.replace(Regex("[\\s\\u00A0]+"), " ").trim()
        if (compact.isEmpty()) return null

        // 图片查看器 → 直接放弃。
        // 用户点开一张截图，OCR 照样能读出「支付成功 ¥0.01」，
        // 但那是图片内容，不是一笔新交易。
        if (IMAGE_VIEWER_MARKERS.any { compact.contains(it) }) return null

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
        // 最后兜底：OCR 常把 ¥ 吃掉，只能靠"带小数点的数字"认金额。
        // 放在最后是刻意的 —— 带符号的信息更可信，先让它们说话。
        for (m in BARE_AMOUNT.findAll(text)) {
            val start = m.range.first
            val prev = text.take(start).trimEnd().lastOrNull()
            if (prev == '-') continue
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
