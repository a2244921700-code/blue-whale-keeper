package com.moneydafeiyu.ledger.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class BarDatum(val label: String, val income: Long, val expense: Long)

/**
 * 自绘柱状图 —— 不引第三方图表库，形状与配色完全可控。
 * 每根柱子可同时展示收入（上方）与支出，也可以只有一边。
 */
@Composable
fun BarChart(
    data: List<BarDatum>,
    incomeColor: Color,
    expenseColor: Color,
    modifier: Modifier = Modifier,
    barHeight: Int = 160,
    showLabels: Boolean = true,
) {
    if (data.isEmpty()) {
        Box(modifier = modifier.height(barHeight.dp), contentAlignment = Alignment.Center) {
            Text("本月还没有记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val maxValue = data.maxOf { maxOf(it.income, it.expense) }.coerceAtLeast(1L)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight.dp)
            .padding(horizontal = 4.dp)
    ) {
        val n = data.size
        val slot = size.width / n
        val barW = (slot * 0.34f).coerceAtMost(18f)
        val gap = barW * 0.18f
        val usableH = size.height - 6f

        data.forEachIndexed { i, d ->
            val cx = slot * i + slot / 2f
            val incH = (d.income.toFloat() / maxValue) * usableH
            val expH = (d.expense.toFloat() / maxValue) * usableH

            if (d.income > 0) {
                drawRoundRect(
                    color = incomeColor,
                    topLeft = Offset(cx + gap / 2, usableH - incH),
                    size = Size(barW, incH.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(barW / 3f, barW / 3f),
                )
            }
            if (d.expense > 0) {
                drawRoundRect(
                    color = expenseColor,
                    topLeft = Offset(cx - barW - gap / 2, usableH - expH),
                    size = Size(barW, expH.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(barW / 3f, barW / 3f),
                )
            }
        }
    }

    if (showLabels) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val step = (data.size / 6).coerceAtLeast(1)
            data.filterIndexed { i, _ -> i % step == 0 }.forEach { d ->
                Text(
                    text = d.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
