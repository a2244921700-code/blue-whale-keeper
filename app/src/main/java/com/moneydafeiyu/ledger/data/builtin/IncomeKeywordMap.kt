package com.moneydafeiyu.ledger.data.builtin

/**
 * 收入关键词分流：收入关键词.txt 里是一大堆词，需要按语义分到 6 个收入子类。
 * 规则是「子串命中」，按顺序匹配，第一条命中的获胜。
 * 用户之后可以在 App 里调整这些词——这里只是给出一份合理的初始划分。
 */
object IncomeKeywordMap {

    /** 顺序敏感：越靠前优先级越高 */
    val rules: List<Pair<String, List<String>>> = listOf(
        "工资奖金" to listOf(
            "工资", "薪资", "薪水", "薪酬", "奖金", "年终", "提成", "绩效", "加班",
            "补贴", "补助", "津贴", "工钱", "代发", "工分",
        ),
        "报销退款" to listOf(
            "报销", "退款", "退货", "返现", "返利", "退税", "理赔", "赔偿", "退回",
        ),
        "红包转账" to listOf(
            "红包", "收款", "礼金", "压岁钱", "转账", "押金", "保证金", "收款码",
            "二维码", "慰问金", "抚恤金",
        ),
        "投资收益" to listOf(
            "利息", "分红", "收益", "股息", "理财", "基金", "股票", "债券",
            "租金", "房租", "存款", "公积金", "社保返还",
        ),
        "兼职外快" to listOf(
            "兼职", "外快", "稿", "直播", "二手", "佣金", "回扣", "版权", "专利",
            "讲课", "咨询", "劳务", "服务收入", "项目款", "工程款", "货款",
        ),
    )

    /** 命中不了上述任何一条的，落到「其他收入」 */
    fun classify(word: String): String {
        for ((category, keys) in rules) {
            for (k in keys) if (word.contains(k)) return category
        }
        return BuiltinCategories.INCOME_OTHER
    }
}
