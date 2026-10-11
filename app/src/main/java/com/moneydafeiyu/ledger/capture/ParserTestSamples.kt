package com.moneydafeiyu.ledger.capture

import com.moneydafeiyu.ledger.data.model.Channel
import com.moneydafeiyu.ledger.data.model.SourceKey

/**
 * 解析测试样本。
 *
 * 全部按真机通知的实际措辞写，覆盖成功路径与**必须放弃**的路径。
 * 用途：不用真花钱、不用等真实通知，就能验证规则改动对不对。
 */
object ParserTestSamples {

    data class Sample(
        val group: String,
        val name: String,
        val sourceKey: String,
        val channel: String,
        val text: String,
    )

    val all: List<Sample> = listOf(
        // ---------- 支付宝通知 ----------
        Sample("支付宝", "0.08 元小额（你截图里那条）", SourceKey.ALIPAY, Channel.NOTIFICATION,
            "交易提醒 | 你有一笔0.08元的支出，点击领取6个支付宝积分。"),
        Sample("支付宝", "商户名里带鱼字", SourceKey.ALIPAY, Channel.NOTIFICATION,
            "交易提醒 | 你有一笔32.00元的支出，付款给鱼跃餐厅，点击查看详情"),
        Sample("支付宝", "续费会员", SourceKey.ALIPAY, Channel.NOTIFICATION,
            "你有一笔15.00元的支出，用于网易云音乐会员"),
        Sample("支付宝", "收款到账", SourceKey.ALIPAY, Channel.NOTIFICATION,
            "支付宝 | 你有一笔200.00元的收入，来自张某某的转账"),
        Sample("支付宝", "【应放弃】没有金额", SourceKey.ALIPAY, Channel.NOTIFICATION,
            "交易提醒 | 你有一笔交易，点此查看详情"),

        // ---------- 微信 ----------
        Sample("微信", "自动续费扣款成功", SourceKey.WECHAT, Channel.NOTIFICATION,
            "微信支付：你的自动续费扣款成功，¥25.00"),
        Sample("微信", "付款给商户", SourceKey.WECHAT, Channel.NOTIFICATION,
            "微信支付 | 微信支付凭证 付款金额 68.00元 收款方：海底捞火锅"),
        Sample("微信", "【应放弃】续费提醒", SourceKey.WECHAT, Channel.NOTIFICATION,
            "您签约的自动续费将于明日扣款19.9元，请保证余额充足"),

        // ---------- 拼多多 / 京东 ----------
        Sample("拼多多 / 京东", "先用后付扣款", SourceKey.PDD, Channel.NOTIFICATION,
            "【拼多多】你的先用后付订单已扣款，金额39.90元"),
        Sample("拼多多 / 京东", "订单支付成功", SourceKey.PDD, Channel.NOTIFICATION,
            "【拼多多】你的订单已支付成功，实付19.90元"),
        Sample("拼多多 / 京东", "京东订单", SourceKey.JD, Channel.NOTIFICATION,
            "京东 | 您的订单已支付，金额128.00元"),

        // ---------- 银行 ----------
        Sample("银行", "工资到账（应记收入）", SourceKey.BANK, Channel.NOTIFICATION,
            "【招商银行】您账户8473于10月10日收入工资人民币8,500.00元"),
        Sample("银行", "他人转入（应记收入）", SourceKey.BANK, Channel.NOTIFICATION,
            "您尾号8473的账户于10月11日转入1,200.00元"),
        Sample("银行", "快捷支付扣款（会记但标待确认）", SourceKey.BANK, Channel.NOTIFICATION,
            "您账户8473于10月10日23:04在【支付宝-消费-朱振毅】发生快捷支付扣款0.08元"),
        Sample("银行", "【应放弃】提现（账户间转移）", SourceKey.BANK, Channel.NOTIFICATION,
            "您账户8473于10月11日收到支付宝提现存入500.00元"),

        // ---------- 支付结果页（无障碍 / OCR 通道） ----------
        Sample("支付结果页", "支付宝成功页（含商户名）", SourceKey.ALIPAY, Channel.ACCESSIBILITY,
            "支付成功 ¥0.08 羊驼老头 ¥0.10 百次立减 -¥0.02 付款方式 招商银行储蓄卡(8473) 点击给商家对账 完成"),
        Sample("支付结果页", "大额餐饮（应触发夺鲸霸拳）", SourceKey.ALIPAY, Channel.ACCESSIBILITY,
            "支付成功 ¥600.00 某某餐厅 付款方式 零钱 完成"),
        Sample("支付结果页", "微信付款成功页", SourceKey.WECHAT, Channel.ACCESSIBILITY,
            "付款成功 ¥12.00 收款方：蜜雪冰城 付款方式 零钱 完成"),
    )

    val groups: List<String> = all.map { it.group }.distinct()
}
