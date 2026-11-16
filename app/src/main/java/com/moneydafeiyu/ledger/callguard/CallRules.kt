package com.moneydafeiyu.ledger.callguard

/**
 * 来电判定规则。
 *
 * 分两层执行，这个对象只负责**第一层：纯号码规则** ——
 * 它跑在 CallScreeningService 里，响铃之前就能出结果，
 * 所以拦掉的电话用户完全无感知（不响、不弹、不留痕）。
 *
 * 第二层（按来电显示名判断）必须读屏，那是另一条路，见 CallScreenReader。
 */
object CallRules {

    // ---- 规则标识（设置界面按这个存开关）----
    const val LANDLINE = "landline"              // 座机
    const val HIDDEN = "hidden"                  // 隐藏号码
    const val ABNORMAL = "abnormal"              // 位数异常
    const val SERVICE_NUMBER = "service_number"  // 400/95/100 服务号
    const val VIRTUAL = "virtual"                // 虚拟运营商号段
    const val INTERNATIONAL = "international"    // 国际来电

    /** 默认开启的规则。你指定的两条 + 我认为零风险的附加项 */
    val DEFAULTS: Map<String, Boolean> = mapOf(
        LANDLINE to true,
        HIDDEN to true,
        ABNORMAL to true,
        // 以下三项**默认关闭**：
        // 服务号里有「955xx 银行客服」这种正经号码，
        // 虚拟号段和国际来电也都有正常用户，默认开会误杀。
        SERVICE_NUMBER to false,
        VIRTUAL to false,
        INTERNATIONAL to false,
    )

    data class Verdict(val block: Boolean, val rule: String = "", val reason: String = "")

    /**
     * 手机号的判定基准：**11 位且以 1 开头**。
     * 国内手机号全都长这样，所以不符合这个形状、又不像服务号的，基本就是座机。
     */
    fun isMobile(digits: String): Boolean = digits.length == 11 && digits.startsWith("1")

    /**
     * 座机判定。
     *
     * 两种形态：
     *   ① 带区号：以 0 开头（010 / 021 / 0551 …）
     *   ② 本地号不带区号：7~9 位纯数字
     * 注意排除手机号和服务号，否则会把「1010xxxx」这类也算成座机。
     */
    fun isLandline(digits: String): Boolean {
        if (digits.isEmpty()) return false
        if (isMobile(digits)) return false
        if (isServiceNumber(digits)) return false
        // 网约车/快递的隐私转接号（700 段）长得很像本地座机，
        // 但它们是**正常业务来电**，必须放行 —— 否则司机永远打不进来。
        if (isRideHailing(digits)) return false
        if (digits.startsWith("0")) return true
        if (digits.length in 7..9) return true
        return false
    }

    /**
     * 服务号 / 企业号：400、800、95、100、1010 开头。
     * 骚扰电话的重灾区，但**银行客服也在里面**（955xx），所以默认不拦。
     */
    fun isServiceNumber(digits: String): Boolean =
        digits.startsWith("400") || digits.startsWith("800") ||
            digits.startsWith("95") || digits.startsWith("100") ||
            digits.startsWith("1010") || digits.startsWith("12")

    /** 虚拟运营商号段 */
    private val VIRTUAL_PREFIX = listOf("170", "171", "162", "165", "167", "133", "149", "1740", "1741")

    fun isVirtual(digits: String): Boolean =
        VIRTUAL_PREFIX.any { digits.startsWith(it) }

    /**
     * 网约车 / 快递平台的**隐私转接号**：700 号段。
     *
     * ## 为什么必须单独放行
     *
     * 平台为了保护双方隐私，不会把司机的真实号码给你，而是分配一个中间号
     * （700xxxxxxx），通话时由平台转接。这类号码的特征是：
     *
     *   - 不以 1 开头 → 不算手机号
     *   - 不带区号 → 不算"按区号判断的座机"
     *   - 长度 7~9 位 → **正好落进「本地座机」的判定区间**
     *
     * 所以不加这条排除，司机的电话会被当成座机**直接静默挂断** ——
     * 而挂断是完全无感的（不响、不弹、不留痕），用户只会觉得"司机怎么不打给我"。
     *
     * ## 为什么不是"关掉座机拦截"
     *
     * 座机拦截是主力规则，骚扰电话绝大多数来自座机。
     * 只放行 700 这一个前缀，既能接得通司机，又不动摇主体防护。
     *
     * ## 700 是唯一要放行的吗
     *
     * 目前网约车平台公开的隐私号段以 700 为主；如果后续出现新的号段，
     * 往 [RIDE_HAILING_PREFIX] 里加一条即可，判定逻辑不用动。
     */
    private val RIDE_HAILING_PREFIX = listOf("700")

    fun isRideHailing(digits: String): Boolean =
        RIDE_HAILING_PREFIX.any { digits.startsWith(it) }

    /**
     * 主判定入口。
     * @param digits 纯数字号码，可能为空（对方隐藏了号码）
     * @param enabled 规则开关表
     */
    fun evaluate(digits: String, enabled: Map<String, Boolean>): Verdict {
        val on = { key: String -> enabled[key] ?: (DEFAULTS[key] ?: false) }

        // 隐藏号码：连号码都没有
        if (digits.isEmpty()) {
            return if (on(HIDDEN)) Verdict(true, HIDDEN, "对方隐藏了号码")
            else Verdict(false)
        }

        // 位数异常：正常号码不会短于 5 位、长于 15 位（国际号码上限）
        if (on(ABNORMAL) && (digits.length < 5 || digits.length > 15)) {
            return Verdict(true, ABNORMAL, "号码位数异常（${digits.length} 位）")
        }

        // 国际来电
        if (on(INTERNATIONAL) && (digits.startsWith("00") || digits.length > 11 && !digits.startsWith("1"))) {
            return Verdict(true, INTERNATIONAL, "国际来电")
        }

        // 服务号
        if (on(SERVICE_NUMBER) && isServiceNumber(digits)) {
            return Verdict(true, SERVICE_NUMBER, "服务号/企业号")
        }

        // 虚拟号段
        if (on(VIRTUAL) && isVirtual(digits)) {
            return Verdict(true, VIRTUAL, "虚拟运营商号段")
        }

        // 座机 —— 你指定的主力规则
        if (on(LANDLINE) && isLandline(digits)) {
            return Verdict(true, LANDLINE, "座机号码")
        }

        return Verdict(false)
    }
}
