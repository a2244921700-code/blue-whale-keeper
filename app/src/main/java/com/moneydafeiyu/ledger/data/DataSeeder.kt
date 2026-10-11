package com.moneydafeiyu.ledger.data

import android.content.Context
import android.util.Log
import com.moneydafeiyu.ledger.data.builtin.BuiltinCategories
import com.moneydafeiyu.ledger.data.builtin.BuiltinFeedback
import com.moneydafeiyu.ledger.data.builtin.IncomeKeywordMap
import com.moneydafeiyu.ledger.data.builtin.KeywordImporter
import com.moneydafeiyu.ledger.data.db.MoneyDatabase
import com.moneydafeiyu.ledger.data.entity.AccountEntity
import com.moneydafeiyu.ledger.data.entity.CategoryKeywordEntity
import com.moneydafeiyu.ledger.data.model.KeywordTier

/**
 * 内置数据的初始化与**增量补充**。
 *
 * 关键设计：不是"首次启动才灌一次"，而是**每次启动都检查、缺什么补什么**。
 * 这样以后新增一个分类（比如后加的「交通」），已经装了 App 的老用户
 * 升级后也能自动获得，不需要清数据重装。
 *
 * 全部操作幂等：关键词表有 (categoryId, word, tier) 唯一索引，
 * 重复插入会被 IGNORE；分类靠 name+direction 查重。
 */
object DataSeeder {

    private const val TAG = "DataSeeder"

    suspend fun seed(context: Context, db: MoneyDatabase) {
        seedCategoriesAndKeywords(context, db)
        seedAccount(db)
        seedFeedback(db)
    }

    private suspend fun seedCategoriesAndKeywords(context: Context, db: MoneyDatabase) {
        val dao = db.categoryDao()
        val idByName = HashMap<String, Long>()

        // ---- 1. 分类：缺哪个补哪个 ----
        var added = 0
        for (def in BuiltinCategories.all) {
            val existing = dao.findByName(def.name, def.direction)
            val id = if (existing != null) {
                existing.id
            } else {
                val inserted = dao.insert(BuiltinCategories.toEntity(def))
                if (inserted > 0) {
                    added++
                    inserted
                } else {
                    dao.findByName(def.name, def.direction)?.id
                }
            }
            if (id != null) idByName[def.name] = id
        }
        if (added > 0) Log.i(TAG, "本次补入 $added 个新分类")

        // ---- 2. 支出类关键词：用**内容哈希**判断词库有没有变，变了就整体重导 ----
        //
        // 为什么不能只看"这个分类有没有词"：词库是会改的（用户随时可能加新词，
        // 比如地铁 App 的名字各地都不一样）。只看有没有词，改了也永远导不进去。
        // 记一个内容哈希，一变就清空重来，这样用户改完词库装新版就自动生效。
        val prefs = context.getSharedPreferences("keyword_sync", Context.MODE_PRIVATE)
        var total = 0
        for (def in BuiltinCategories.expense) {
            val asset = def.keywordAsset ?: continue
            val cid = idByName[def.name] ?: continue

            val text = runCatching {
                context.assets.open("keywords/$asset").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (text == null) {
                Log.w(TAG, "关键词库缺失: $asset")
                continue
            }

            val hash = text.hashCode().toString()
            val key = "kw_" + def.name
            val oldHash = prefs.getString(key, null)
            val alreadyHasWords = dao.countKeywords(cid) > 0

            if (oldHash == hash && alreadyHasWords) continue

            // 词库内容变了（或首次导入 / 老数据没有哈希记录）
            if (alreadyHasWords) {
                dao.deleteKeywordsOf(cid)
                Log.i(TAG, "${def.name}: 词库有更新，清空后重新导入")
            }
            val parsed = KeywordImporter.parse(text)
            dao.insertKeywords(parsed.map { CategoryKeywordEntity(categoryId = cid, word = it.word, tier = it.tier) })
            prefs.edit().putString(key, hash).apply()
            total += parsed.size
            Log.i(TAG, "${def.name}: 导入 ${parsed.size} 个关键词")
        }

        // ---- 3. 收入类关键词：整组都没导入过才分流 ----
        val incomeIds = BuiltinCategories.income.mapNotNull { idByName[it.name] }
        val incomeTextForHash = runCatching {
            context.assets.open("keywords/income.txt").bufferedReader().use { it.readText() }
        }.getOrNull()
        val incomeHash = incomeTextForHash?.hashCode()?.toString()
        val incomeKey = "kw_收入"
        val incomeChanged = prefs.getString(incomeKey, null) != incomeHash
        val incomeAlreadySeeded = incomeIds.any { dao.countKeywords(it) > 0 }

        if (incomeChanged && incomeAlreadySeeded) {
            for (cid in incomeIds) dao.deleteKeywordsOf(cid)
            Log.i(TAG, "收入词库有更新，清空后重新导入")
        }
        if (!incomeAlreadySeeded || incomeChanged) {
            val incomeText = runCatching {
                context.assets.open("keywords/income.txt").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (incomeText != null) {
                val parsed = KeywordImporter.parse(incomeText)
                val strong = parsed.filter { it.tier == KeywordTier.STRONG }
                val exclude = parsed.filter { it.tier == KeywordTier.EXCLUDE }
                val buckets = HashMap<String, MutableList<String>>()
                for (p in strong) {
                    buckets.getOrPut(IncomeKeywordMap.classify(p.word)) { mutableListOf() }.add(p.word)
                }
                for ((catName, words) in buckets) {
                    val cid = idByName[catName] ?: continue
                    dao.insertKeywords(words.map { CategoryKeywordEntity(categoryId = cid, word = it, tier = KeywordTier.STRONG) })
                    total += words.size
                }
                for (cid in incomeIds) {
                    dao.insertKeywords(exclude.map { CategoryKeywordEntity(categoryId = cid, word = it.word, tier = KeywordTier.EXCLUDE) })
                    total += exclude.size
                }
                prefs.edit().putString(incomeKey, incomeHash).apply()
            }
        }

        if (total > 0) Log.i(TAG, "本次导入关键词 $total 条，库内共 ${dao.keywordCount()} 条")
    }

    private suspend fun seedAccount(db: MoneyDatabase) {
        val dao = db.accountDao()
        if (dao.count() > 0) return
        dao.insert(AccountEntity(name = "默认账户", type = "CASH", icon = "🐳", color = "#4A7EBB", sortOrder = 0))
        Log.i(TAG, "默认账户已创建")
    }

    /**
     * 反馈规则以**代码里的定义为准**做校准。
     *
     * 为什么敢直接覆盖：反馈规则用户改不了（规则编辑器只管分类关键词），
     * 所以代码就是唯一事实来源。
     *
     * 这个修正解决了一个真实问题：老用户库里的规则是旧版本写入的，
     * 只要当时缺了哪条、或者哪个字段不对（比如彩蛋规则的匹配词），
     * 就会一直错下去 —— 表现为「吃鱼的彩蛋永远不触发」。
     * 每次启动校准一遍，装上新版本就自动修好，不用清数据。
     */
    private suspend fun seedFeedback(db: MoneyDatabase) {
        val dao = db.feedbackRuleDao()
        var added = 0
        var fixed = 0

        for (rule in BuiltinFeedback.all) {
            val old = dao.findByCode(rule.code)
            if (old == null) {
                dao.insert(rule)
                added++
                continue
            }
            // 保留用户的启停选择，其余字段一律向代码定义看齐
            val expected = rule.copy(id = old.id, enabled = old.enabled)
            if (expected != old) {
                dao.insert(expected)
                fixed++
            }
        }
        Log.i(TAG, "反馈规则校准完成：新增 $added 条，修正 $fixed 条")
    }
}
