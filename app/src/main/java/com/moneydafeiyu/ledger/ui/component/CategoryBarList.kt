package com.moneydafeiyu.ledger.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneydafeiyu.ledger.util.toYuan

data class CategoryBarItem(
    val name: String,
    val icon: String,
    val amountCents: Long,
    val color: Color,
)

/**
 * 分类统计条形表。
 *
 * 为什么用**横向**条形而不是竖向柱子：
 * 分类名是中文词组（"生活缴费""工资奖金"），竖着排根本放不下，
 * 斜着写或者截断都很难看。横向排布则天然能放下名字 + 条形 + 金额 + 占比四样东西。
 *
 * 条形长度按「占最大值的比例」算而不是按「占总量的比例」——
 * 后者在只有一个分类时也是满格，看不出量级差别；前者能让最大的那项永远占满，
 * 其余按比例缩短，一眼就能看出"第二名的花销是第一名的几成"。
 */
@Composable
fun CategoryBarList(
    items: List<CategoryBarItem>,
    emptyHint: String,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().padding(vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val total = items.sumOf { it.amountCents }.coerceAtLeast(1L)
    val max = items.maxOf { it.amountCents }.coerceAtLeast(1L)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (item in items) {
            val ratio = item.amountCents.toFloat() / max          // 条形长度：相对最大值
            val percent = item.amountCents.toFloat() / total * 100f // 占比：相对总量

            Column(Modifier.fillMaxWidth()) {
                // 第一行：图标 + 名称 + 金额 + 占比
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.icon, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "¥" + item.amountCents.toYuan(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        String.format("%.0f%%", percent),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(38.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                // 第二行：条形
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(ratio.coerceIn(0.02f, 1f))
                            .height(8.dp)
                            .background(item.color, RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
    }
}
