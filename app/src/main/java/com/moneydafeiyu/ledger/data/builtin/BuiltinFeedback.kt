package com.moneydafeiyu.ledger.data.builtin

import com.moneydafeiyu.ledger.data.entity.FeedbackRuleEntity
import com.moneydafeiyu.ledger.data.model.MatchType

/**
 * 16 条人格化反馈规则，对应需求规格 §3.1 的场景矩阵。
 *
 * 优先级（已与你确认）：彩蛋 > 金额 > 分类+时间 > 分类 > 兜底
 * priority 越小越优先，匹配时第一条命中的胜出。
 */
object BuiltinFeedback {

    val all: List<FeedbackRuleEntity> = listOf(
        // ---- 商户名彩蛋（最稀有，优先级最高）----
        FeedbackRuleEntity(
            code = "R-09", priority = 1,
            matchType = MatchType.MERCHANT_CONTAINS, matchValue = "鱼",
            assetName = "fb_fish_1.webp", soundName = "fish.mp3", caption = "你才是大肥鱼！",
        ),
        FeedbackRuleEntity(
            code = "R-10", priority = 2,
            matchType = MatchType.MERCHANT_CONTAINS, matchValue = "鱼",
            assetName = "fb_fish_2.webp", soundName = "fish.mp3", caption = "这条鱼废了",
        ),

        // ---- 金额档（冲击力最强）----
        FeedbackRuleEntity(
            code = "R-08", priority = 10,
            matchType = MatchType.AMOUNT_GT, matchValue = "500",
            assetName = "fb_over_500.webp", soundName = "over_500.mp3", caption = "夺鲸霸拳！",
        ),
        FeedbackRuleEntity(
            code = "R-07", priority = 11,
            matchType = MatchType.AMOUNT_GT, matchValue = "100",
            assetName = "fb_over_100.webp", soundName = "over_100.mp3", caption = "!!?",
        ),

        // ---- 分类 + 时间修饰 ----
        FeedbackRuleEntity(
            code = "R-06", priority = 20,
            matchType = MatchType.TIME_AFTER, matchValue = "17:00",
            extraMatchType = MatchType.CATEGORY, extraMatchValue = "餐饮",
            assetName = "fb_dinner.webp", soundName = "dinner.mp3", caption = "先保护晚饭！",
        ),

        // ---- 分类基础反馈（同组变体随机）----
        FeedbackRuleEntity(
            code = "R-03", priority = 30,
            matchType = MatchType.CATEGORY, matchValue = "餐饮",
            assetName = "fb_food_1.webp", soundName = "food.mp3", caption = "有饭再谈",
            variantGroup = "food",
        ),
        FeedbackRuleEntity(
            code = "R-04", priority = 31,
            matchType = MatchType.CATEGORY, matchValue = "餐饮",
            assetName = "fb_food_2.webp", soundName = "food.mp3", caption = "我是吃白饭的蓝色大肥鱼！",
            variantGroup = "food",
        ),
        FeedbackRuleEntity(
            code = "R-05", priority = 32,
            matchType = MatchType.CATEGORY, matchValue = "餐饮",
            assetName = "fb_food_3.webp", soundName = "food.mp3", caption = "我能去你家吃饭吗，就一碗！",
            variantGroup = "food",
        ),

        // ---- 收入 ----
        FeedbackRuleEntity(
            code = "R-13", priority = 40,
            matchType = MatchType.INCOME_AMOUNT_GTE, matchValue = "1000",
            assetName = "fb_income_big.webp", soundName = "income_big.mp3", caption = "鲸元券 · 巨款到账！",
        ),
        FeedbackRuleEntity(
            code = "R-14", priority = 41,
            // 中档：100 ~ 1000 元
            matchType = MatchType.INCOME_AMOUNT_GTE, matchValue = "100",
            assetName = "fb_income_small.webp", soundName = "income_small.mp3", caption = "哇去！是钱诶！",
        ),
        FeedbackRuleEntity(
            code = "R-16", priority = 42,
            // 小额档：< 100 元。排在最后，前面两档都不命中才会走到这里
            matchType = MatchType.INCOME_AMOUNT_GTE, matchValue = "0",
            assetName = "fb_income_tiny.webp", soundName = "income_tiny.mp3", caption = "就这么点儿？",
        ),

        // ---- 兜底：无法确定消费类型 ----
        FeedbackRuleEntity(
            code = "R-11", priority = 90,
            matchType = MatchType.FALLBACK,
            assetName = "fb_unknown_1.webp", soundName = "unknown.mp3", caption = "脑袋空空",
            variantGroup = "unknown",
        ),
        FeedbackRuleEntity(
            code = "R-12", priority = 91,
            matchType = MatchType.FALLBACK,
            assetName = "fb_unknown_2.webp", soundName = "unknown.mp3", caption = "脑子加载中…",
            variantGroup = "unknown",
        ),

        // ---- 手动登记收入 ----
        FeedbackRuleEntity(
            code = "R-15", priority = 200,
            matchType = MatchType.MANUAL,
            assetName = "fb_manual_income.webp", caption = "手动登记收入",
        ),

        // ---- 开屏（不参与交易反馈匹配）----
        FeedbackRuleEntity(
            code = "R-01", priority = 300,
            matchType = MatchType.SPLASH, matchValue = "NOON",
            assetName = "splash_noon.webp", caption = "啊，有点饿了。中午该吃什么呢…",
        ),
        FeedbackRuleEntity(
            code = "R-02", priority = 301,
            matchType = MatchType.SPLASH, matchValue = "OTHER",
            assetName = "splash_normal.webp", caption = "震撼美味",
        ),
    )
}
