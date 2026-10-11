package com.moneydafeiyu.ledger.capture

import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.data.model.SourceKey

/**
 * 支付文本解析器。
 *
 * 规则全部来自**真机抓到的原始样本**，不是凭空猜的。目前手上这批支付宝样本：
 *
 *   "交易提醒 | 你有一笔0.08元的支出，点击领取6个支付宝积分。"
 *   "交易提醒 | 你有一笔0.02元的支出，点击领取6个支付宝积分。"
 *   "交易提醒 | 你有一笔0.08元的支出，领1元生活缴费红包。"     ← 句尾还有个「1元」陷阱
 *   "交易提醒 | 你有一笔交易，点此查看详情"                     ← 没有金额
 *
 * 设计要点：
 * 1. **优先匹配带金额的完整句式**，而不是满文本找数字 ——
 *    否则"领1元红包"会被当成消费金额；
 * 2. 没有金额的通知**直接放弃**，宁可漏记也不能记错（错账比漏账更难发现）；
 * 3. 命中规则名要返回，方便调试台显示"为什么这笔被认出来了"。
 */
object PaymentTextParser {

    /** 「你有一笔X元的支出/收入」—— 支付宝标准句式，最精确 */
    private val ALIPAY_ONE_TX = Regex(
        "你有一笔\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元的?\\s*(支出|收入|消费|收款)"
    )

    /** 「支付宝消费X元」「付款X元」「收款X元」这类通用句式 */
    private val GENERIC_ACTION = Regex(
        // 动词与数字之间允许夹少量非数字字符 —— 真机文本大量长这样：
        //   「已扣款，金额39.90元」「扣款成功，¥25.00」
        // 但不能放任太多，否则「扣款短信，您余额还剩1000元」会把余额当消费额。
        "(?:支付|付款|消费|扣款|支出|收款|到账|退款|收入)" +
            "(?:成功|完成)?" +
            "[^0-9¥￥]{0,10}?" +
            "[¥￥]?" +
            "([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元?"
    )

    /** 带货币符号的兜底 */
    private val SYMBOL_AMOUNT = Regex("[¥￥]\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)")

    private val EXPENSE_WORDS = listOf("支出", "消费", "支付", "付款", "扣款", "已付")
    private val INCOME_WORDS = listOf("收入", "收款", "到账", "退款", "收到")

    /** 银行到账信号 */
    private val BANK_CREDIT_WORDS = listOf(
        "收入", "到账", "到账成功", "转入", "存入", "汇入", "入账", "已入账",
        "代发", "工资", "转入金额", "收入金额", "存入金额", "转入人民币",
    )

    /** 银行支出信号 —— 命中任一就整条放弃，避免与支付宝/微信重复 */
    private val BANK_DEBIT_WORDS = listOf(
        "支出", "消费", "扣款", "已扣", "支付", "付款", "取现", "转出", "支出金额",
        "消费金额", "代扣", "快捷支付扣", "还款", "手续费",
        // 提现属于自己账户之间的转移：支付宝少了、银行卡多了，总身家没变。
        // 记成收入会让"当前全部身家"凭空变大。
        "提现",
    )

    /** 银行金额：普遍带千分位 */
    private val BANK_AMOUNT = Regex("([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)\\s*元")

    private val BANK_COUNTERPARTY = listOf(
        Regex("(?:付款方|付款人|汇款人|转入方|对方户名)[：:\\s]+([^\\s，。|]{2,24})"),
        Regex("来自\\s*([^\\s，。|]{2,24})"),
    )

    /**
     * 未来时态标记 —— 命中就**绝不记账**。
     *
     * 这类通知是「提醒你明天要扣钱了」，钱还没扣。
     * 记进去等于凭空多一笔支出，而且时间还是错的。
     * 这是自动续费场景里最容易踩的坑。
     */
    private val FUTURE_MARKERS = listOf(
        "将于", "即将", "明日", "明天", "预计", "待扣", "请保证", "余额不足",
        "提醒您", "请及时", "到期前", "会自动扣", "将自动", "将要", "即将扣",
        "扣款提醒", "续费提醒", "即将到期", "马上到期",
    )

    /** 「金额39.90元」这种带标签的写法 */
    private val LABELED_AMOUNT = Regex(
        "(?:金额|扣款金额|支付金额|订单金额|实付|付款金额)[：:\\s]*[¥￥]?\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元?"
    )

    /** 明确表示"不是一笔真实支付"的噪音 */
    private val NOISE = listOf(
        "点此查看详情", "点击查看", "积分", "领红包", "立减", "优惠",
    )

    /**
     * 银行通知专用解析。
     *
     * 规则：**只认到账/收入，一律不认支出**。
     *
     * 理由（用户提出，判断非常准确）：银行卡支出与支付宝/微信支出是同一笔的两面 ——
     * "扫码付款 0.08 元"会同时产生支付宝通知和银行扣款通知，
     * 两条都记就变成两笔支出了。而银行**到账**通常没有别的来源，必须记。
     */
    fun parseBank(text: String): ParsedResult? {
        val compact = text.replace(Regex("\\s+"), " ").trim()

        // 先把第三方品牌名剥掉再判收支：
        // 「支付宝」里含「支付」，「微信支付」里也含 —— 不剥离的话
        // 「收到支付宝提现存入 500 元」会被误判成支出而丢掉。
        val cleaned = compact
            .replace("支付宝", "【第三方】")
            .replace("微信支付", "【第三方】")
            .replace("云闪付", "【第三方】")
            .replace("财付通", "【第三方】")

        // 提现是账户之间的转移：支付宝少了、银行卡多了，总身家没变。
        // 记成收入会让「当前全部身家」凭空变大，所以直接放弃。
        if (compact.contains("提现")) return null

        val isCredit = BANK_CREDIT_WORDS.any { cleaned.contains(it) }
        val isDebit = BANK_DEBIT_WORDS.any { cleaned.contains(it) }

        val m = BANK_AMOUNT.find(compact) ?: SYMBOL_AMOUNT.find(compact) ?: return null
        val cents = toCents(m.groupValues[1].replace(",", "")) ?: return null
        if (cents <= 0) return null

        val merchant = extractBankCounterparty(compact)

        return when {
            // 到账收入：银行是唯一来源，放心记
            isCredit && !isDebit -> ParsedResult(
                amountCents = cents,
                direction = Direction.INCOME,
                merchant = merchant,
                rule = "bank.credit",
                confidence = 0.78,
            )
            // 银行支出：**大概率**和支付宝/微信的支出是同一笔，
            // 但不能一律丢掉 —— 自动续费、先用后付这种可能只走银行卡，
            // 丢了就永远不会被记上。所以降置信度收下来，
            // 靠去重融合处理重复，靠「待确认」让用户一眼能删。
            isDebit && !isCredit -> ParsedResult(
                amountCents = cents,
                direction = Direction.EXPENSE,
                merchant = merchant,
                rule = "bank.debit",
                confidence = 0.55,
            )
            // 收支信号打架（比如「转入后又转出」），不敢猜
            else -> null
        }
    }

    private fun extractBankCounterparty(text: String): String {
        for (p in BANK_COUNTERPARTY) {
            val m = p.find(text) ?: continue
            val s = m.groupValues[1].trim()
            if (s.length in 2..24 && !NOISE.any { s.contains(it) }) return s
        }
        return ""
    }

    fun parse(sourceKey: String, text: String): ParsedResult? {
        // 银行走单独规则：只收到账，不收支出
        if (sourceKey == SourceKey.BANK) return parseBank(text)
        val compact = text.replace(Regex("\\s+"), " ").trim()

        // 未来时态直接放弃 ——「将于明日扣款」不是已发生的交易，
        // 记进去等于凭空多一笔支出，而且时间还是错的。
        if (FUTURE_MARKERS.any { compact.contains(it) }) return null
        // ---- 第一优先：支付宝「你有一笔X元的支出/收入」----
        ALIPAY_ONE_TX.find(compact)?.let { m ->
            val cents = toCents(m.groupValues[1]) ?: return null
            if (cents <= 0) return null
            val dir = directionOf(m.groupValues[2]) ?: return null
            return ParsedResult(
                amountCents = cents,
                direction = dir,
                // 必须提取商户名 —— 它是自动分类的唯一依据。
                // 之前这里写死成空串，导致「付款给鱼跃餐厅」也被丢进「其他支出」。
                merchant = extractMerchant(compact),
                rule = "alipay.one_tx",
                confidence = 0.90,
            )
        }

        // ---- 通用动作句式 ----
        // 先试带标签的金额（最准），再退回动词邻近匹配
        val genericMatch = LABELED_AMOUNT.find(compact) ?: GENERIC_ACTION.find(compact)
        genericMatch?.let { m ->
            val cents = toCents(m.groupValues[1]) ?: return null
            if (cents <= 0) return null
            // 必须同时能判出方向，否则不敢记
            val dir = detectDirection(compact) ?: return null
            return ParsedResult(
                amountCents = cents,
                direction = dir,
                merchant = extractMerchant(compact),
                rule = "generic.action",
                confidence = 0.72,
            )
        }

        // ---- 只有货币符号 ----
        SYMBOL_AMOUNT.find(compact)?.let { m ->
            val cents = toCents(m.groupValues[1]) ?: return null
            if (cents <= 0) return null
            val dir = detectDirection(compact) ?: return null
            return ParsedResult(
                amountCents = cents,
                direction = dir,
                merchant = extractMerchant(compact),
                rule = "symbol.amount",
                confidence = 0.60,
            )
        }

        return null
    }

    private fun directionOf(word: String): String? = when (word) {
        "支出", "消费" -> Direction.EXPENSE
        "收入", "收款" -> Direction.INCOME
        else -> null
    }

    private fun detectDirection(text: String): String? {
        val hasIncome = INCOME_WORDS.any { text.contains(it) }
        val hasExpense = EXPENSE_WORDS.any { text.contains(it) }
        return when {
            hasIncome && !hasExpense -> Direction.INCOME
            hasExpense && !hasIncome -> Direction.EXPENSE
            // 同时出现时不敢猜，交给人工确认
            else -> null
        }
    }

    /** 常见商户句式；抓不到就留空，绝不编造 */
    private val MERCHANT_PATTERNS = listOf(
        Regex("商户[：:\\s]+([^\\s，。|]{2,20})"),
        Regex("收款方[：:\\s]+([^\\s，。|]{2,20})"),
        Regex("向\\s*([^\\s，。|]{2,20})\\s*(?:付款|支付)"),
        Regex("来自\\s*([^\\s，。|]{2,20})"),
    )

    private fun extractMerchant(text: String): String {
        for (p in MERCHANT_PATTERNS) {
            val m = p.find(text) ?: continue
            val s = m.groupValues[1].trim()
            if (s.isNotEmpty() && s.length <= 20 && !NOISE.any { s.contains(it) }) return s
        }
        return ""
    }

    /** 字符串转分，走字符串解析避免浮点误差 */
    private fun toCents(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val parts = s.split('.')
        val yuan = parts[0].toLongOrNull() ?: return null
        val cents = if (parts.size > 1) {
            parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: 0L
        } else 0L
        return yuan * 100 + cents
    }

    /** 这个来源是否值得解析（白名单外的直接忽略） */
    fun supports(sourceKey: String): Boolean = when (sourceKey) {
        SourceKey.WECHAT, SourceKey.ALIPAY, SourceKey.TAOBAO,
        SourceKey.JD, SourceKey.PDD, SourceKey.UNIONPAY, SourceKey.BANK -> true
        else -> false
    }
}
