package com.moneydafeiyu.ledger.data.model

/** 收支方向 */
object Direction {
    const val EXPENSE = "EXPENSE"
    const val INCOME = "INCOME"
    const val TRANSFER = "TRANSFER"
    val all = listOf(EXPENSE, INCOME, TRANSFER)
    fun label(v: String) = when (v) {
        EXPENSE -> "支出"; INCOME -> "收入"; TRANSFER -> "转账"; else -> v
    }
}

/** 采集通道 —— 一笔交易的证据可能来自多个通道 */
object Channel {
    const val NOTIFICATION = "NOTIFICATION"
    const val ACCESSIBILITY = "ACCESSIBILITY"
    const val OCR = "OCR"
    const val MANUAL = "MANUAL"
    fun label(v: String) = when (v) {
        NOTIFICATION -> "通知"; ACCESSIBILITY -> "页面识别"
        OCR -> "截图识别"; MANUAL -> "手动"; else -> v
    }
}

/** 支付来源。淘宝/京东/拼多多有绕过微信支付宝的独立支付渠道 */
object SourceKey {
    const val WECHAT = "WECHAT"
    const val ALIPAY = "ALIPAY"
    const val TAOBAO = "TAOBAO"
    const val JD = "JD"
    const val PDD = "PDD"
    const val UNIONPAY = "UNIONPAY"
    const val BANK = "BANK"
    const val MANUAL = "MANUAL"

    val all = listOf(WECHAT, ALIPAY, TAOBAO, JD, PDD, UNIONPAY, BANK, MANUAL)

    fun label(v: String) = when (v) {
        WECHAT -> "微信"; ALIPAY -> "支付宝"; TAOBAO -> "淘宝"
        JD -> "京东"; PDD -> "拼多多"; UNIONPAY -> "云闪付"
        BANK -> "银行"; MANUAL -> "手动"; else -> v
    }
}

/** 包名 → 来源。M2 采集通道用这张表做白名单 */
object SourcePackages {
    val map: Map<String, String> = mapOf(
        "com.tencent.mm" to SourceKey.WECHAT,
        "com.eg.android.AlipayGphone" to SourceKey.ALIPAY,
        // 淘宝（支付宝渠道为主，但订单支付/亲情号等有独立通知）
        "com.taobao.taobao" to SourceKey.TAOBAO,
        "com.taobao.idlefish" to SourceKey.TAOBAO,
        // 京东：主 App + 京东金融（京东支付/白条/小金库走 jrapp）
        "com.jingdong.app.mall" to SourceKey.JD,
        "com.jd.jrapp" to SourceKey.JD,
        // 拼多多 + 多多钱包
        "com.xunmeng.pinduoduo" to SourceKey.PDD,
        // 银联云闪付
        "com.unionpay" to SourceKey.UNIONPAY,
        "com.unionpay.mobilepay" to SourceKey.UNIONPAY,
        // 银行 App。银行通知只用来抓**到账收入** ——
        // 银行的支出通知和支付宝/微信的支出是同一笔的两面，都记就重复了。
        "com.icbc" to SourceKey.BANK,
        "com.icbc.im" to SourceKey.BANK,
        "com.chinamworld.main" to SourceKey.BANK,
        "com.chinamworld.bocmbci" to SourceKey.BANK,
        "com.android.bankabc" to SourceKey.BANK,
        "cmb.pb" to SourceKey.BANK,
        "com.mybank.android.phone" to SourceKey.BANK,
        "com.android.bankcomm" to SourceKey.BANK,
        "com.yitong.mbank.psbc" to SourceKey.BANK,
        "com.cmbc.cc.mbank" to SourceKey.BANK,
        "com.yunhui.creditcard" to SourceKey.BANK,
        "com.cebbank.mobile.cemb" to SourceKey.BANK,
        "cn.com.spdb.mobilebank.per" to SourceKey.BANK,
        "com.pingan.paces.ccms" to SourceKey.BANK,
        "com.hxb.mobile.client" to SourceKey.BANK,
        "com.cib.cibmb" to SourceKey.BANK,
        "com.bank.ningbo" to SourceKey.BANK,
        "com.bankofbeijing.mobilebanking" to SourceKey.BANK,
        "com.shsscb.mobilebank" to SourceKey.BANK,
        "cn.com.cmbc.newmbank" to SourceKey.BANK,
        "com.gdb.mobilebank" to SourceKey.BANK,
    )
    fun of(packageName: String?): String? = map[packageName]
    fun isSupported(packageName: String?): Boolean = map.containsKey(packageName)
}

/** 原始证据的处理状态 */
object ProcessState {
    const val NEW = "NEW"
    const val MERGED = "MERGED"
    const val PARSED = "PARSED"
    const val DISCARDED = "DISCARDED"
}

/** 交易状态 */
object TxStatus {
    const val PENDING = "PENDING"
    const val CONFIRMED = "CONFIRMED"
    const val DELETED = "DELETED"
}

/** 关键词层级 —— 直接对应你准备的 6 个关键词库的分区结构 */
object KeywordTier {
    const val STRONG = "STRONG"     // 【强信号词】命中即高置信度
    const val CATEGORY = "CATEGORY" // 【品类词】
    const val BRAND = "BRAND"       // 【常见品牌】/【平台/品牌】
    const val SUFFIX = "SUFFIX"     // 【后缀词】
    const val EXCLUDE = "EXCLUDE"   // 【排除词】跨类互斥降权，防止"超市里的餐饮"这种误判

    val all = listOf(STRONG, CATEGORY, BRAND, SUFFIX, EXCLUDE)
    fun label(v: String) = when (v) {
        STRONG -> "强信号"; CATEGORY -> "品类"; BRAND -> "品牌"
        SUFFIX -> "后缀"; EXCLUDE -> "排除降权"; else -> v
    }
}

/** 反馈规则匹配方式 —— 对应 16 个场景矩阵 */
object MatchType {
    const val CATEGORY = "CATEGORY"                   // 分类命中
    const val AMOUNT_GT = "AMOUNT_GT"                 // 金额大于
    const val MERCHANT_CONTAINS = "MERCHANT_CONTAINS" // 商户名包含
    const val TIME_AFTER = "TIME_AFTER"               // 时间晚于（如 17:00）
    const val INCOME_AMOUNT_GTE = "INCOME_AMOUNT_GTE" // 收入金额大于等于
    const val FALLBACK = "FALLBACK"                   // 兜底
    const val SPLASH = "SPLASH"                       // 开屏
    const val MANUAL = "MANUAL"                       // 手动记账专属反馈（R-15）
}
