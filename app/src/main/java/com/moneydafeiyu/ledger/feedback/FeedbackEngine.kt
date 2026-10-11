package com.moneydafeiyu.ledger.feedback

import android.content.Context
import com.moneydafeiyu.ledger.data.db.MoneyDatabase
import com.moneydafeiyu.ledger.data.entity.FeedbackRuleEntity
import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.data.model.MatchType
import java.util.Calendar

/**
 * 反馈规则引擎 —— 16 个鲸鱼娘场景的匹配器。
 *
 * 优先级（已与你确认）：彩蛋 > 金额 > 分类+时间 > 分类 > 兜底
 * 规则全部存在数据库里，所以以后可以在 App 内直接改，不用动代码。
 *
 * 同一组的多张变体（比如餐饮有 3 张图）会**随机选一张**，
 * 这样同一类消费反复出现时不会一直看到同一张脸。
 */
object FeedbackEngine {

    suspend fun pick(
        context: Context,
        amountCents: Long,
        direction: String,
        merchant: String,
        categoryName: String?,
        occurredAt: Long = System.currentTimeMillis(),
    ): FeedbackRuleEntity? {
        val rules = MoneyDatabase.get(context).feedbackRuleDao().loadEnabled()
            .filter { it.matchType != MatchType.SPLASH && it.matchType != MatchType.MANUAL } // 开屏与手动各有入口
            .sortedBy { it.priority }

        // 找出**所有**命中的规则，而不是只取第一条。
        // 只取第一条会导致同一个情景永远弹同一张图 —— 用户明确反馈过这个问题。
        val hits = rules.filter { matches(it, amountCents, direction, merchant, categoryName, occurredAt) }
        if (hits.isEmpty()) return null

        val sorted = hits.sortedBy { it.priority }
        val first = sorted.first()

        // 与优先级最高的那条**匹配条件完全相同**的，视为同情景的变体，在这组里随机选。
        // 刻意不依赖数据库里的 variantGroup 字段 —— 老版本装机的用户那个字段可能是空的，
        // 按条件分组才可靠。
        val sameScene = sorted.filter {
            it.matchType == first.matchType &&
                it.matchValue == first.matchValue &&
                it.extraMatchType == first.extraMatchType &&
                it.extraMatchValue == first.extraMatchValue
        }
        return sameScene.random()
    }

    /** 开屏图专用（按时间选） */
    suspend fun pickSplash(context: Context, hour: Int): FeedbackRuleEntity? {
        val rules = MoneyDatabase.get(context).feedbackRuleDao().loadEnabled()
            .filter { it.matchType == MatchType.SPLASH }
        val wantNoon = hour in 11..13
        return rules.firstOrNull {
            (wantNoon && it.matchValue.equals("NOON", true)) ||
                (!wantNoon && it.matchValue.equals("OTHER", true))
        }
    }

    /** 手动登记收入专用反馈 */
    suspend fun pickManualIncome(context: Context): FeedbackRuleEntity? =
        MoneyDatabase.get(context).feedbackRuleDao().loadEnabled()
            .firstOrNull { it.matchType == MatchType.MANUAL }

    private fun matches(
        rule: FeedbackRuleEntity,
        amountCents: Long,
        direction: String,
        merchant: String,
        categoryName: String?,
        occurredAt: Long,
    ): Boolean {
        val value = rule.matchValue.toLongOrNull() ?: 0L
        return when (rule.matchType) {
            // 商户名彩蛋：收款方里含"鱼"就触发
            MatchType.MERCHANT_CONTAINS ->
                merchant.isNotBlank() && merchant.contains(rule.matchValue)

            MatchType.AMOUNT_GT ->
                direction == Direction.EXPENSE && amountCents > value * 100

            MatchType.INCOME_AMOUNT_GTE ->
                direction == Direction.INCOME && amountCents >= value * 100

            // 分类 + 时间修饰：例如"餐饮 且 17:00 之后"
            MatchType.TIME_AFTER -> {
                val (h, m) = parseHhMm(rule.matchValue) ?: return false
                val cal = Calendar.getInstance().apply { timeInMillis = occurredAt }
                val after = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) >= h * 60 + m
                val catOk = rule.extraMatchType != MatchType.CATEGORY ||
                    (categoryName != null && categoryName == rule.extraMatchValue)
                after && catOk
            }

            MatchType.CATEGORY ->
                categoryName != null && categoryName == rule.matchValue

            // 兜底：分类没定下来时用（"脑袋空空"）
            MatchType.FALLBACK -> categoryName == null || categoryName.contains("其他")

            MatchType.MANUAL, MatchType.SPLASH -> false
            else -> false
        }
    }

    private fun parseHhMm(s: String): Pair<Int, Int>? {
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        return h to m
    }
}
