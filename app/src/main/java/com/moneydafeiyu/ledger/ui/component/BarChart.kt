package com.moneydafeiyu.ledger.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneydafeiyu.ledger.util.toYuan

data class BarDatum(val label: String, val income: Long, val expense: Long)

/**
 * 自绘柱状图 —— 不引第三方图表库，形状与配色完全可控。
 * 每根柱子可同时展示收入（上方）与支出，也可以只有一边。
 *
 * **支持轻触/左右滑动查看某一天的数值**：
 * 点在图上会选中最近的那根柱子，顶部提示条显示该日期的收入与支出；
 * 手指按住左右拖动可以连续扫过各天，松手后提示条保留（方便看清）。
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

    // 当前选中的柱子（null = 没选中，显示默认说明）
    var selected by remember(data) { mutableStateOf<Int?>(null) }

    /**
     * 把触摸的横坐标换算成柱子下标。
     * 每根柱子占用等宽的一段，落在哪段就是哪根 —— 不需要精确点到柱子上，
     * 手指在附近也算，实测这样更好按。
     */
    fun indexAt(x: Float, width: Float): Int? {
        if (width <= 0f || data.isEmpty()) return null
        val slot = width / data.size
        val i = (x / slot).toInt()
        return i.coerceIn(0, data.lastIndex)
    }

    // ---- 顶部提示条 ----
    // 没选中时说明怎么用；选中后显示该日期的具体数值。
    Row(
        Modifier.fillMaxWidth().height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        val hit = selected
        if (hit == null) {
            Text(
                "轻触或左右滑动查看每日数值",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val d = data[hit]
            Text(
                d.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(10.dp))
            if (d.income > 0) {
                Text(
                    "收 ¥" + d.income.toYuan(),
                    style = MaterialTheme.typography.labelMedium,
                    color = incomeColor,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (d.expense > 0) {
                Text(
                    "支 ¥" + d.expense.toYuan(),
                    style = MaterialTheme.typography.labelMedium,
                    color = expenseColor,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (d.income == 0L && d.expense == 0L) {
                Text(
                    "这天没有收支",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight.dp)
            .padding(horizontal = 4.dp)
            // 点击：选中最近的一根柱子
            .pointerInput(data) {
                detectTapGestures { offset ->
                    selected = indexAt(offset.x, size.width.toFloat())
                }
            }
            // 拖动：手指扫过哪根就选哪根（和点击分开两个 pointerInput，否则会互相抢）
            .pointerInput(data) {
                detectDragGestures(
                    onDragStart = { offset ->
                        selected = indexAt(offset.x, size.width.toFloat())
                    },
                    onDrag = { change, _ ->
                        selected = indexAt(change.position.x, size.width.toFloat())
                    },
                )
            }
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
            val isSel = selected == i

            // 选中的那一列铺一层很淡的底色，让人一眼看出看的是哪天
            if (isSel) {
                drawRect(
                    color = Color.White.copy(alpha = 0.10f),
                    topLeft = Offset(slot * i, 0f),
                    size = Size(slot, size.height),
                )
            }

            val dim = if (selected == null || isSel) 1f else 0.45f

            if (d.income > 0) {
                drawRoundRect(
                    color = incomeColor.copy(alpha = dim),
                    topLeft = Offset(cx + gap / 2, usableH - incH),
                    size = Size(barW, incH.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(barW / 3f, barW / 3f),
                )
            }
            if (d.expense > 0) {
                drawRoundRect(
                    color = expenseColor.copy(alpha = dim),
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