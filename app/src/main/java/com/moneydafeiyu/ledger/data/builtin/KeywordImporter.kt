package com.moneydafeiyu.ledger.data.builtin

import com.moneydafeiyu.ledger.data.model.KeywordTier

/**
 * 解析你准备的 6 个关键词库（.txt）。
 *
 * 原始格式形如：
 *   【强信号词】
 *   餐厅、饭店、饭馆、…
 *   【排除词】
 *   小米、苹果、…
 *
 * 分区标题自动映射到关键词层级，词之间用顿号分隔。
 */
object KeywordImporter {

    data class Parsed(val word: String, val tier: String)

    /** 素材里出现过的所有分区名 → 层级 */
    fun tierOf(section: String): String = when {
        section.contains("排除") || section.contains("降权") -> KeywordTier.EXCLUDE
        section.contains("强信号") -> KeywordTier.STRONG
        section.contains("后缀") -> KeywordTier.SUFFIX
        section.contains("品类") || section.contains("费用类型") -> KeywordTier.CATEGORY
        section.contains("品牌") || section.contains("平台") || section.contains("商超")
            || section.contains("百货") || section.contains("数码") || section.contains("母婴")
            || section.contains("机构") || section.contains("APP") || section.contains("购物")
            || section.contains("宠物") || section.contains("服饰") || section.contains("家居") -> KeywordTier.BRAND
        else -> KeywordTier.CATEGORY
    }

    fun parse(text: String): List<Parsed> {
        val out = LinkedHashMap<String, Parsed>()
        var tier = KeywordTier.STRONG
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("【")) {
                val close = line.indexOf("】")
                val section = if (close > 0) line.substring(1, close) else line.substring(1)
                tier = tierOf(section)
                val rest = if (close > 0) line.substring(close + 1).trim() else ""
                if (rest.isNotEmpty()) addAll(out, rest, tier)
                continue
            }
            addAll(out, line, tier)
        }
        return out.values.toList()
    }

    private fun addAll(out: MutableMap<String, Parsed>, line: String, tier: String) {
        for (piece in line.split('、', '，', ',', '；', ';', '|')) {
            val w = piece.trim().removePrefix("-").trim()
            if (w.isEmpty() || w.length > 30) continue
            // 过滤掉说明性文字与纯符号
            if (w.startsWith("(") || w.startsWith("（")) continue
            if (w.all { !it.isLetterOrDigit() }) continue
            out.putIfAbsent("$tier\u0000$w", Parsed(w, tier))
        }
    }
}
