package com.moneydafeiyu.ledger.capture

import android.content.Context
import com.moneydafeiyu.ledger.data.db.MoneyDatabase
import com.moneydafeiyu.ledger.data.entity.CategoryEntity
import com.moneydafeiyu.ledger.data.entity.CategoryKeywordEntity
import com.moneydafeiyu.ledger.data.model.KeywordTier

/**
 * 分类判定 —— 拿商户名去撞你准备的那 6 个关键词库。
 *
 * 这是整条自动化链路里最能体现效果的一环：
 *   通知只给金额 → 页面给出「羊驼老头」→ 这一步判定它属于哪一类。
 *
 * 打分规则严格沿用你设计的四层结构：
 *   强信号词 +10（「餐厅」「火锅」这种一看就知道的）
 *   品牌词   +8 （「瑞幸」「蜜雪冰城」）
 *   品类词   +6 （「米线」「奶茶」）
 *   后缀词   +3 （「小馆」「食府」）
 *   排除词   → 该分类**直接出局**

 * 最后一条最关键：你每个词库里都写了排除词（比如购物类里排除「外卖/餐饮」），
 * 它的作用就是防止「某某餐饮管理有限公司」被算成购物。
 * 命中排除词就整个分类否决，而不是简单扣分 —— 互斥就该是互斥的。
 */
object CategoryClassifier {

    private data class CategoryWithWords(
        val category: CategoryEntity,
        val words: List<CategoryKeywordEntity>,
    )

    @Volatile
    private var cache: List<CategoryWithWords>? = null

    /** 关键词库是用户可编辑的，改完要让它失效重载 */
    fun invalidate() {
        cache = null
    }

    private suspend fun load(context: Context): List<CategoryWithWords> {
        cache?.let { return it }
        val db = MoneyDatabase.get(context)
        val categories = db.categoryDao().loadAll()
        val list = categories.map { c ->
            CategoryWithWords(c, db.categoryDao().keywordsOf(c.id, KeywordTier.STRONG) +
                db.categoryDao().keywordsOf(c.id, KeywordTier.BRAND) +
                db.categoryDao().keywordsOf(c.id, KeywordTier.CATEGORY) +
                db.categoryDao().keywordsOf(c.id, KeywordTier.SUFFIX) +
                db.categoryDao().keywordsOf(c.id, KeywordTier.EXCLUDE))
        }
        cache = list
        return list
    }

    /**
     * 返回最匹配的分类 id；都不匹配则返回 null（交给「其他」兜底）。
     */
    suspend fun classify(
        context: Context,
        text: String,
        direction: String,
    ): Long? {
        if (text.isBlank()) return null
        val data = load(context)

        var bestScore = 0
        var bestId: Long? = null

        for (item in data) {
            val cat = item.category
            if (cat.direction != direction) continue
            // 「其他」是兜底分类，不参与竞争，否则它会抢走一切
            if (cat.name.contains("其他")) continue

            var score = 0
            var excluded = false
            for (w in item.words) {
                if (!w.enabled) continue
                if (!text.contains(w.word)) continue
                when (w.tier) {
                    KeywordTier.EXCLUDE -> { excluded = true }
                    KeywordTier.STRONG -> score += 10
                    KeywordTier.BRAND -> score += 8
                    KeywordTier.CATEGORY -> score += 6
                    KeywordTier.SUFFIX -> score += 3
                }
                if (excluded) break
            }
            if (excluded) continue
            if (score > bestScore) {
                bestScore = score
                bestId = cat.id
            }
        }
        return bestId
    }
}
