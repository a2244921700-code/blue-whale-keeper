package com.moneydafeiyu.ledger.ui.fortune

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
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
import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.ui.theme.ExpenseRed
import com.moneydafeiyu.ledger.ui.theme.IncomeGreen
import com.moneydafeiyu.ledger.util.toYuan

/**
 * 对账差额的分类弹窗。
 *
 * 登记的家当比账本余额高 → 期间有漏记的收入；
 * 登记的家当比账本余额低 → 期间有漏记的支出。
 * 让用户指明类型，然后作为正式流水入账，而不是让差额凭空消失。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdjustmentDialog(
    diffCents: Long,
    categories: List<CategoryEntity>,
    onConfirm: (categoryId: Long?) -> Unit,
    onCancel: () -> Unit,
) {
    val isIncome = diffCents > 0
    val direction = if (isIncome) Direction.INCOME else Direction.EXPENSE
    val amount = kotlin.math.abs(diffCents)
    var selected by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (isIncome) "这笔钱是哪来的？" else "这笔钱花哪了？") },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (isIncome)
                        "你登记的家当比账本算出的余额多，说明这段时间有一笔收入没被记下来。"
                    else
                        "你登记的家当比账本算出的余额少，说明这段时间有一笔支出没被记下来。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    (if (isIncome) "多出 ¥" else "少了 ¥") + amount.toYuan(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isIncome) IncomeGreen else ExpenseRed,
                )
                Spacer(Modifier.height(14.dp))
                Text("选一个类型，它会作为一笔正式记录写进账本：", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.filter { it.direction == direction }.forEach { c ->
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
                Spacer(Modifier.height(12.dp))
                Text(
                    "跳过也能登记，只是这笔差额不会出现在流水里，以后就说不清钱去哪了。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(if (selected == null) "不选类型直接登记" else "记下来并登记")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("返回修改") } },
    )
}
