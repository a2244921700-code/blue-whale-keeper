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
        //
        // ★ 还必须把方括号排除掉。踩过的坑：
        //   「微信支付 | [48条]微信支付：已支付¥4.85」
        //   动作词和数字之间只夹了「 | 」三个字符，正好在额度内，
        //   于是 [48条] 里的 48 被当成了金额 —— 4.85 元的单子记成 48 元。
        //   方括号里的内容是**未读计数**这类元信息，不可能是金额。
        // 注意这里包含「转账」「红包」「领取」「发出」——
        // 它们的**方向不明确**（转账给张三 vs 张三向我转账），
        // 但作为"这是一笔钱在动"的信号是成立的，值得进入解析。
        // 方向交给下面的 detectDirection 按短语判断。
        "(?:支付|付款|消费|扣款|支出|收款|到账|退款|收入|转账|红包|领取|发出|收下)" +
            "(?:成功|完成)?" +
            "[^0-9¥￥\\[\\]]{0,10}?" +
            "[¥￥]?" +
            "([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元?"
    )

    /** 带货币符号的兜底 */
    private val SYMBOL_AMOUNT = Regex("[¥￥]\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)")

    private val EXPENSE_WORDS = listOf("支出", "消费", "支付", "付款", "扣款", "已付")
    private val INCOME_WORDS = listOf("收入", "收款", "到账", "退款", "收到")

    /**
     * 收入方向的短语。
     *
     * 为什么要用短语而不是词：「转账」「红包」这两个词**本身没有方向** ——
     *   「转账给张三」是支出，「张三向我转账」是收入；
     *   「发出红包」是支出，「收到红包」是收入。
     * 只看词必然判错一半。所以先按整句短语判定，判不出来才退回单词表。
     *
     * ★ 收入短语必须**先于**支出短语检查：
     *   「张某某向你转账」里同时含「向你」和「转账」，
     *   先查支出的话会被 "向…转账" 误判成支出。
     */
    /**
     * 「转账还没接收」的短语。
     *
     * 这类通知里钱**还没到你手里** —— 微信转账 24 小时不领会退回。
     * 直接记成收入的话，万一对方撤回了或者你点了退还，
     * 账本就凭空多了一笔钱。
     *
     * 所以命中的先记为**待确认**，等你真正收款（或有第二条「已收款」通知）再落实。
     */
    private val PROVISIONAL_PATTERNS = listOf(
        Regex("向你转账"), Regex("向我转账"), Regex("向您转账"), Regex("给您转账"),
        Regex("转账给你"), Regex("转账给我"),
        Regex("请及时查收"), Regex("待接收"), Regex("待确认收款"), Regex("等待对方确认"),
    )

    /** 「钱已经到手」的短语 —— 命中它就一定不是待接收 */
    private val RECEIVED_PATTERNS = listOf(
        Regex("已收款"), Regex("收款成功"), Regex("已收下"), Regex("已存入"),
        Regex("已到账"), Regex("已入账"), Regex("已领取"),
    )

    private val INCOMING_PATTERNS = listOf(
        Regex("(?:向|给)[我您你]转账"),                   // 张三向我/你转账（补上了「你」）
        Regex("(?:向|给)你转账"),                        // 对端视角的同一件事
        Regex("收到[^，。|]{0,12}转账"),                  // 收到一笔转账
        Regex("收到[^，。|]{0,8}红包"),                   // 收到红包
        Regex("领取[^，。|]{0,8}红包"),                   // 领取红包
        Regex("红包[^，。|]{0,8}(?:到账|存入|入账|已领|已收)"),
        Regex("(?:转账|红包)[^，。|]{0,8}(?:已到账|已存入|已入账)"),
        Regex("已收款"),
        Regex("收款成功"),
        Regex("已收下"),                                  // 微信「已收下你的转账」
    )

    /** 支出方向的短语 */
    private val OUTGOING_PATTERNS = listOf(
        Regex("(?:向|给)[^，。|]{1,16}转账"),             // 向张三转账（"向我"已在上面拦掉）
        Regex("转账给[^，。|]{1,16}"),                    // 转账给张三
        Regex("已转账"),
        Regex("转账成功"),
        Regex("(?:发出|发了|发送|已发)[^，。|]{0,6}红包"), // 发出红包
        Regex("红包[^，。|]{0,6}(?:已发出|已发送)"),
    )

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

        // 金额必须认准「人民币」或 ¥ 这两个锚点。
        // 以前退回用「数字+元」，而银行通知里**卡号、余额、可用额度**都可能是
        // 「1234元」的形式，很容易把卡号当金额记进去。
        val hit = extractAmount(SourceKey.BANK, compact) ?: return null
        val cents = hit.cents
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

    // ---------------- 按来源提取金额 ----------------
    //
    // 为什么不能统一用"动作词附近的数字"：各家通知的排版完全不同，
    // 而干扰数字（未读条数、积分、红包金额）到处都是。
    // 最可靠的做法是**认准各家自己的货币锚点**：
    //   微信  →  ¥ 符号（"已支付¥4.85"）
    //   支付宝 →「元」字（"你有一笔0.08元的支出"）
    //   银行  → 「人民币」或 ¥（"收入人民币8,500.00元"，卡号绝不会带这两个标记）
    //
    // 这样 [48条]、6个积分、领1元红包 这些干扰项天然被排除。

    /** 带千分位与小数都支持的金额捕获组 */
    private const val NUM = "([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]{1,9}(?:\\.[0-9]{1,2})?)"

    /** ¥ / ￥ 后面的数字 —— 微信最常用 */
    private val SYMBOL_FIRST = Regex("[¥￥]\\s*" + NUM)

    /** 「元」前面紧挨着的数字 —— 支付宝最常用 */
    private val YUAN_LAST = Regex(NUM + "\\s*元")

    /** 「人民币」后面的数字 —— 银行到账通知的标准写法 */
    private val RMB_AMOUNT = Regex("人民币\\s*" + NUM)

    /**
     * 「实付 / 付款金额 / 订单金额」这类**带标签**的金额 —— 电商通知里最可信的那个。
     *
     * 为什么需要它：淘宝、京东、拼多多的通知里**往往有多个 ¥** ——
     * 订单总额一个、红包抵扣一个、优惠券一个、积分抵扣一个。
     * 之前这些来源统一走「取第一个 ¥」，而第一个未必是实付金额。
     */
    private val PAID_NEAR = Regex(
        "(?:实付|实付款|付款金额|支付金额|订单金额|应付|已付|共计|合计)\\s*[:：]?\\s*[¥￥]?\\s*" + NUM
    )

    data class AmountHit(val cents: Long, val rule: String)

    /**
     * 按来源提取金额。
     * @return null 表示**找不到可信金额** —— 这时宁可放弃也不能猜。
     */
    private fun extractAmount(sourceKey: String, text: String): AmountHit? {
        fun first(re: Regex, rule: String): AmountHit? {
            val m = re.find(text) ?: return null
            val cents = toCents(m.groupValues[1].replace(",", "")) ?: return null
            return if (cents > 0) AmountHit(cents, rule) else null
        }

        return when (sourceKey) {
            // 微信：只认 ¥。它的通知必定带这个符号（"已支付¥4.85"）
            SourceKey.WECHAT -> first(SYMBOL_FIRST, "wx.symbol")

            // 支付宝：认「元」。它的标准句式是"你有一笔X元的支出"
            // 注意取的是**第一个**「数字+元」——句尾常有"领1元红包"这种陷阱
            SourceKey.ALIPAY -> first(YUAN_LAST, "alipay.yuan")

            // 银行：「人民币」优先（卡号不会被误认），没有才退回 ¥
            SourceKey.BANK -> first(RMB_AMOUNT, "bank.rmb") ?: first(SYMBOL_FIRST, "bank.symbol")

            // 电商：先找「实付 / 订单金额」这种带标签的，最可信；
            // 找不到才退回「第一个 ¥」。
            //
            // 淘宝、京东、拼多多的通知里常有好几个 ¥（订单总额、红包、优惠券、
            // 积分抵扣），直接取第一个很容易抓错 —— 实测淘宝那笔离谱的 710 元
            // 就是这么来的。
            SourceKey.TAOBAO, SourceKey.JD, SourceKey.PDD ->
                first(PAID_NEAR, "shop.labeled")
                    ?: first(SYMBOL_FIRST, "shop.symbol")
                    ?: first(YUAN_LAST, "shop.yuan")

            // 其他来源：两种锚点都试，先 ¥ 后 元
            else -> first(SYMBOL_FIRST, "any.symbol") ?: first(YUAN_LAST, "any.yuan")
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

    /**
     * 解析失败的**具体原因** —— 只给调试台用。
     *
     * 为什么值得单独写一个：用户看到"没记上"时，最需要知道的是
     * 「压根没收到通知」还是「收到了但我不认识这格式」。
     * 前者要在系统权限上想办法，后者只要把原文发给我加规则就行。
     */
    fun diagnose(sourceKey: String, text: String): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.isEmpty()) return "文本为空"
        if (FUTURE_MARKERS.any { compact.contains(it) }) return "未来时态（钱还没扣，是提醒）"
        if (compact.contains("提现")) return "提现属账户间转移，不计收支"

        val hit = extractAmount(sourceKey, compact)
        if (hit == null) {
            return when (sourceKey) {
                SourceKey.WECHAT -> "没找到 ¥ 符号（微信通知必定带它，可能这条不是支付通知）"
                SourceKey.ALIPAY -> "没找到「数字+元」（支付宝标准句式里一定有）"
                SourceKey.BANK -> "没找到「人民币」或 ¥（银行到账通知的标准锚点）"
                else -> "没找到任何货币锚点（¥ 或 元）"
            }
        }
        if (detectDirection(compact) == null && directionOf(compact) == null) {
            return "有金额 " + hit.cents + " 分，但判断不出收支方向"
        }
        return "已能提取金额但没匹配到句式（规则可能漏了这种写法）"
    }

    fun parse(sourceKey: String, text: String): ParsedResult? {
        // 银行走单独规则：只收到账，不收支出
        if (sourceKey == SourceKey.BANK) return parseBank(text)
        val compact = text.replace(Regex("\\s+"), " ").trim()

        // 未来时态直接放弃 ——「将于明日扣款」不是已发生的交易，
        // 记进去等于凭空多一笔支出，而且时间还是错的。
        if (FUTURE_MARKERS.any { compact.contains(it) }) return null
        // ★ 先按来源把金额捞出来 —— 这是最可靠的一步。
        //
        // 各家的锚点不同（微信认¥、支付宝认"元"、银行认"人民币"），
        // 拿它跟句式匹配的结果互相印证：句式负责"确认这是一笔交易"，
        // 金额则用锚点提取的值，避开 [48条]、6个积分、领1元红包 这类干扰。
        val amountHit = extractAmount(sourceKey, compact)

        // 「转账还没接收」的先记个标记 —— 方向照判，但状态要挂待确认
        val isProvisional = PROVISIONAL_PATTERNS.any { it.containsMatchIn(compact) } &&
            RECEIVED_PATTERNS.none { it.containsMatchIn(compact) }

        // ---- 第一优先：支付宝「你有一笔X元的支出/收入」----
        ALIPAY_ONE_TX.find(compact)?.let { m ->
            // 优先用锚点提取的金额；提不到才退回句式里的数字
            val cents = amountHit?.cents ?: toCents(m.groupValues[1]) ?: return null
            if (cents <= 0) return null
            val dir = directionOf(m.groupValues[2]) ?: return null
            return ParsedResult(
                amountCents = cents,
                direction = dir,
                // 必须提取商户名 —— 它是自动分类的唯一依据。
                // 之前这里写死成空串，导致「付款给鱼跃餐厅」也被丢进「其他支出」。
                merchant = extractMerchant(compact),
                rule = amountHit?.rule ?: "alipay.one_tx",
                confidence = 0.90,
                provisional = isProvisional,
            )
        }

        // ---- 通用动作句式 ----
        // 先试带标签的金额（最准），再退回动词邻近匹配
        val genericMatch = LABELED_AMOUNT.find(compact) ?: GENERIC_ACTION.find(compact)
        genericMatch?.let { m ->
            val cents = amountHit?.cents ?: toCents(m.groupValues[1]) ?: return null
            if (cents <= 0) return null
            // 必须同时能判出方向，否则不敢记
            val dir = detectDirection(compact) ?: return null
            return ParsedResult(
                amountCents = cents,
                direction = dir,
                merchant = extractMerchant(compact),
                rule = amountHit?.rule ?: "generic.action",
                confidence = 0.72,
                provisional = isProvisional,
            )
        }

        // ---- 只有货币锚点，没有明确句式 ----
        amountHit?.let { h ->
            val cents = h.cents
            if (cents <= 0) return null
            val dir = detectDirection(compact) ?: return null
            return ParsedResult(
                amountCents = cents,
                direction = dir,
                merchant = extractMerchant(compact),
                rule = h.rule,
                confidence = 0.60,
                provisional = isProvisional,
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
        // ---- 第一优先：转账/红包这类"词本身不带方向"的场景 ----
        // 顺序不能反：收入短语先查，否则「张三向我转账」会被
        // 支出短语 "向…转账" 抢走，记成一笔假支出。
        if (INCOMING_PATTERNS.any { it.containsMatchIn(text) }) return Direction.INCOME
        if (OUTGOING_PATTERNS.any { it.containsMatchIn(text) }) return Direction.EXPENSE

        // ---- 判单词前必须洗掉品牌名 ----
        //
        // 踩过的坑：「支付宝」三个字里含「支付」，而「支付」在 EXPENSE_WORDS 里。
        // 于是「支付宝 | 20.00元」这种**只有金额没有方向**的通知会被判成支出，
        // 而它完全可能是退款到账或别人转来的钱。洗掉后返回 null（放弃），
        // 这比猜错强 —— 错账比漏账难发现得多。
        val cleaned = text
            .replace("支付宝", "【第三方】")
            .replace("微信支付", "【第三方】")
            .replace("云闪付", "【第三方】")
            .replace("财付通", "【第三方】")

        val hasIncome = INCOME_WORDS.any { cleaned.contains(it) }
        val hasExpense = EXPENSE_WORDS.any { cleaned.contains(it) }
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
