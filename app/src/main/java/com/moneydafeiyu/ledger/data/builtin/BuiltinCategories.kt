package com.moneydafeiyu.ledger.data.builtin

import com.moneydafeiyu.ledger.data.entity.CategoryEntity
import com.moneydafeiyu.ledger.data.model.Direction

/**
 * 内置分类。支出 5 大实用类 + 其他；收入 6 类（已与你确认）。
 * 支出类各自绑定一个关键词库文件，首次启动时自动导入。
 */
object BuiltinCategories {

    data class Def(
        val name: String,
        val direction: String,
        val icon: String,
        val color: String,
        val sortOrder: Int,
        /** assets/keywords/ 下的文件名，null 表示无关键词库 */
        val keywordAsset: String?,
    )

    const val EXPENSE_OTHER = "其他支出"
    const val INCOME_OTHER = "其他收入"

    val expense = listOf(
        Def("餐饮", Direction.EXPENSE, "🍚", "#FF8A65", 10, "food.txt"),
        Def("交通", Direction.EXPENSE, "🚇", "#4FC3F7", 15, "transport.txt"),
        Def("购物", Direction.EXPENSE, "🛒", "#4A7EBB", 20, "shopping.txt"),
        Def("生活缴费", Direction.EXPENSE, "💡", "#FFB74D", 30, "utility.txt"),
        Def("医疗", Direction.EXPENSE, "💊", "#4DB6AC", 40, "medical.txt"),
        Def("娱乐", Direction.EXPENSE, "🎮", "#9575CD", 50, "entertainment.txt"),
        Def(EXPENSE_OTHER, Direction.EXPENSE, "📦", "#90A4AE", 99, null),
    )

    val income = listOf(
        Def("工资奖金", Direction.INCOME, "💰", "#66BB6A", 10, null),
        Def("报销退款", Direction.INCOME, "🧾", "#26A69A", 20, null),
        Def("红包转账", Direction.INCOME, "🧧", "#EF5350", 30, null),
        Def("投资收益", Direction.INCOME, "📈", "#42A5F5", 40, null),
        Def("兼职外快", Direction.INCOME, "💼", "#AB47BC", 50, null),
        Def(INCOME_OTHER, Direction.INCOME, "🎁", "#78909C", 99, null),
    )

    val all = expense + income

    fun toEntity(d: Def) = CategoryEntity(
        name = d.name,
        direction = d.direction,
        icon = d.icon,
        color = d.color,
        sortOrder = d.sortOrder,
        isSystem = true,
    )
}
