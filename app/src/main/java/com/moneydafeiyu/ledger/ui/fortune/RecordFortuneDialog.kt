package com.moneydafeiyu.ledger.ui.fortune

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneydafeiyu.ledger.util.yuanToCents

/**
 * 「登记当前家当」—— 把此刻手上真实拥有的总钱数如实录一次。
 * 刻意做得低调：入口藏在「当前全部身家」页右上角菜单里，因为这是不常用的操作。
 */
@Composable
fun RecordFortuneDialog(
    onDismiss: () -> Unit,
    onConfirm: (amountCents: Long, note: String) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val cents = amount.yuanToCents() ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登记当前家当") },
        text = {
            Column {
                Text(
                    "把此刻手上真实拥有的钱合计算一次（现金 + 微信零钱 + 支付宝余额 + 银行卡 + 理财等等）。\n" +
                        "登记之后，每一笔收支都会从这个数上自动增减。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("当前总共多少钱（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选，例如「发工资后」）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(cents, note.trim()) }, enabled = cents > 0) { Text("登记") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
