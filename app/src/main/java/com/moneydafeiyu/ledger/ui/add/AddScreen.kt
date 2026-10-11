package com.moneydafeiyu.ledger.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moneydafeiyu.ledger.util.asFull
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import java.util.Calendar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.ui.theme.ExpenseRed
import com.moneydafeiyu.ledger.ui.theme.IncomeGreen
import com.moneydafeiyu.ledger.ui.theme.JellyBorder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddScreen(onDone: () -> Unit, vm: AddViewModel = viewModel()) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记一笔") },
                navigationIcon = { TextButton(onClick = onDone) { Text("取消") } },
            )
        },
        bottomBar = {
            Column(Modifier.padding(12.dp)) {
                Button(
                    onClick = { vm.save(onDone) },
                    enabled = vm.amountCents > 0,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text(if (vm.direction == Direction.INCOME) "记下这笔收入" else "记下这笔支出")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 14.dp),
        ) {
            // 支出 / 收入 切换 —— 记录收入与记录支出同等重要，入口放在最显眼处
            TabRow(selectedTabIndex = if (vm.direction == Direction.EXPENSE) 0 else 1) {
                Tab(
                    selected = vm.direction == Direction.EXPENSE,
                    onClick = { vm.switchDirection(Direction.EXPENSE) },
                    text = { Text("支出") },
                )
                Tab(
                    selected = vm.direction == Direction.INCOME,
                    onClick = { vm.switchDirection(Direction.INCOME) },
                    text = { Text("收入") },
                )
            }

            Spacer(Modifier.height(14.dp))

            // 金额显示
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(24.dp),
            
            border = JellyBorder,
        ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.End) {
                    Text("金额", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = "¥" + vm.amountText.ifEmpty { "0.00" },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (vm.direction == Direction.INCOME) IncomeGreen else ExpenseRed,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            NumberPad(onKey = { vm.press(it) })

            Spacer(Modifier.height(6.dp))

            // 手动记账可以补记过去的账：时间、类别都要能改
            val ctx = LocalContext.current
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("时间", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    pickDateTime(ctx, vm.occurredAt) { vm.updateOccurredAt(it) }
                }) {
                    Text(vm.occurredAt.asFull(), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("分类", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cats.forEach { c ->
                    val selected = vm.categoryId == c.id
                    Box(
                        Modifier
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(28.dp),
                            )
                            .clickable { vm.selectCategory(c.id) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(
                            "${c.icon} ${c.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = vm.merchant,
                onValueChange = { vm.merchant = it },
                label = { Text("商户 / 对方（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = vm.note,
                onValueChange = { vm.note = it },
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NumberPad(onKey: (String) -> Unit) {
    val rows = remember { listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "⌫")) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp))
                            .clickable { onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(k, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** 先选日期再选时间，最后合并成时间戳 */
private fun pickDateTime(context: android.content.Context, initial: Long, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, day)
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    onPicked(cal.timeInMillis)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true,
            ).show()
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH),
    ).show()
}
