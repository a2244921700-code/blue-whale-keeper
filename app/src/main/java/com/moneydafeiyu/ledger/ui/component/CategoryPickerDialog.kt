package com.moneydafeiyu.ledger.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneydafeiyu.ledger.data.entity.CategoryEntity

/**
 * 改分类对话框。
 *
 * 数据模型上分类改成什么都不会影响金额，统计永远按当前 categoryId 现算，
 * 所以这里选完，柱状图和分类占比会立刻跟着变，不需要任何刷新。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPickerDialog(
    title: String,
    amountText: String,
    direction: String,
    categories: List<CategoryEntity>,
    currentCategoryId: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = categories.filter { it.direction == direction }
    var selected by remember { mutableStateOf(currentCategoryId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改成哪个类别？") },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    amountText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { c ->
                        val on = selected == c.id
                        Box(
                            Modifier
                                .background(
                                    if (on) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(28.dp),
                                )
                                .clickable { selected = if (on) null else c.id }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(
                                c.icon + " " + c.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "改完之后，当月柱状图和分类占比会立刻跟着变。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(selected) },
                enabled = selected != currentCategoryId,
            ) { Text("就这样") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
