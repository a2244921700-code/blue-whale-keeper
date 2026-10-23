package com.moneydafeiyu.ledger.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneydafeiyu.ledger.util.toYuan
import kotlin.math.abs

/**
 * 余额折线图。
 *
 * 余额曲线和"收支柱状图"的读法完全不同：柱状看的是**增量**（今天花了多少），
 * 曲线看的是**存量**（我手里还剩多少）。所以这里不画零轴，
 * 而是把纵轴范围压缩到数据的最小值~最大值之间 ——
 * 否则一条在 8000~9000 之间波动的曲线会被压成一条直线，什么也看不出来。
 *
 * **支持轻触/左右滑动查看某一天的数值**：
 * 顶部提示条显示该日期、当天的家当金额，以及**相对上一个点的变化**
 * （变多显示 +，变少显示 -，方便一眼看出那天发生了什么）。
 */
@Composable
fun LineChart(
    points: List<Long>,
    labels: List<String>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    height: Int = 170,
) {
    if (points.isEmpty()) {
        Box(modifier.height(height.dp), contentAlignment = Alignment.Center) {
            Text("还没有数据", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val minV = points.min()
    val maxV = points.max()
    val span = (maxV - minV).coerceAtLeast(1L)
    // 上下各留 12% 余量，曲线不会贴着边框
    val pad = (span * 0.12f).toLong().coerceAtLeast(1L)
    val lo = minV - pad
    val hi = maxV + pad
    val range = (hi - lo).coerceAtLeast(1L)

    var selected by remember(points) { mutableStateOf<Int?>(null) }

    /** 触摸横坐标 → 最近的数据点下标（折线是连续曲线，找最近点比找区间自然） */
    fun nearestIndex(x: Float, width: Float): Int? {
        if (width <= 0f || points.isEmpty()) return null
        if (points.size == 1) return 0
        val stepX = width / (points.size - 1)
        return (x / stepX).toInt().coerceIn(0, points.lastIndex)
    }

    /** 相对上一个点的变化；第一个点没有参照，返回 null */
    fun deltaOf(i: Int): Long? = if (i > 0) points[i] - points[i - 1] else null

    Column(modifier.fillMaxWidth()) {
        // ---- 顶部：选中时显示明细，没选中时显示最高/最低 ----
        Row(
            Modifier.fillMaxWidth().height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hit = selected
            if (hit == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("最高 ¥" + maxV.toYuan(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("轻触或左右滑动查看每日数值", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("最低 ¥" + minV.toYuan(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    labels.getOrElse(hit) { "" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "¥" + points[hit].toYuan(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                val d = deltaOf(hit)
                if (d != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        (if (d >= 0) "+" else "-") + "¥" + abs(d).toYuan(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        // 变多用绿色、变少用红色 —— 和全局收支配色一致
                        color = if (d >= 0) Color(0xFF66BB6A) else Color(0xFFEF5350),
                    )
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp)
                .padding(top = 8.dp)
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        selected = nearestIndex(offset.x, size.width.toFloat())
                    }
                }
                .pointerInput(points) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            selected = nearestIndex(offset.x, size.width.toFloat())
                        },
                        onDrag = { change, _ ->
                            selected = nearestIndex(change.position.x, size.width.toFloat())
                        },
                    )
                }
        ) {
            val n = points.size
            val stepX = if (n > 1) size.width / (n - 1) else 0f
            fun yOf(v: Long): Float = size.height - ((v - lo).toFloat() / range) * size.height
            fun xOf(i: Int): Float = i * stepX

            // 折线路径 + 填充路径
            val line = Path()
            val fill = Path()
            points.forEachIndexed { i, v ->
                val x = xOf(i); val y = yOf(v)
                if (i == 0) { line.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y) }
                else { line.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(xOf(n - 1), size.height)
            fill.close()

            // 曲线下方渐隐填充，让走势更醒目
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0.02f))
                ),
            )
            drawPath(path = line, color = lineColor, style = Stroke(width = 3f))

            // 每个点画个小圆点：数据点少时能看出具体位置
            if (n <= 40) {
                points.forEachIndexed { i, v ->
                    drawCircle(color = lineColor, radius = 3.5f, center = Offset(xOf(i), yOf(v)))
                }
            }

            // ---- 选中态：竖线 + 放大的圆点，方便对准 ----
            selected?.let { i ->
                val x = xOf(i)
                val y = yOf(points[i])
                drawLine(
                    color = lineColor.copy(alpha = 0.45f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5f,
                )
                drawCircle(color = Color.White, radius = 8f, center = Offset(x, y))
                drawCircle(color = lineColor, radius = 5.5f, center = Offset(x, y))
            }
        }

        // 横轴标签：只挑几个，避免挤成一团
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val step = (labels.size / 6).coerceAtLeast(1)
            labels.filterIndexed { i, _ -> i % step == 0 }.forEach { l ->
                Text(l, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}