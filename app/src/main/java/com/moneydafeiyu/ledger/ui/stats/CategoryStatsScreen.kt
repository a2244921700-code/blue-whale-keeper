package com.moneydafeiyu.ledger.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneydafeiyu.ledger.data.model.CategoryTotal
import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.ui.component.CategoryBarItem
import com.moneydafeiyu.ledger.ui.component.CategoryBarList
import com.moneydafeiyu.ledger.ui.home.HomeViewModel
import com.moneydafeiyu.ledger.ui.theme.ExpenseRed
import com.moneydafeiyu.ledger.ui.theme.IncomeGreen
import com.moneydafeiyu.ledger.ui.theme.JellyBorder
import com.moneydafeiyu.ledger.util.toYuan

/**
 * 分类统计。
 *
 * 收入和支出**各一张表**：两者的量级和分类体系都不一样，
 * 混在一张表里比长短没有意义（工资必然碾压所有支出项）。
 *
 * 每行给四样东西：分类名、条形长度、具体金额、占比 ——
 * 光看条形只能比高低，加上金额才知道到底差多少钱。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryStatsScreen(onBack: () -> Unit, vm: HomeViewModel = viewModel()) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.prevMonth() }) { Text("‹") }
                        Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { vm.nextMonth() }) { Text("›") }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        val s = snapshot
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("加载中…") }
            return@Scaffold
        }

        val expenseItems = s.byCategory.filter { it.direction == Direction.EXPENSE }.toBars()
        val incomeItems = s.byCategory.filter { it.direction == Direction.INCOME }.toBars()

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 总览
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(24.dp),
                
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("本月结余", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "¥" + (s.overview.totalIncomeCents - s.overview.totalExpenseCents).toYuan(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column {
                                Text("收入", style = MaterialTheme.typography.labelSmall)
                                Text("¥" + s.overview.totalIncomeCents.toYuan(), color = IncomeGreen, fontWeight = FontWeight.SemiBold)
                            }
                            Column {
                                Text("支出", style = MaterialTheme.typography.labelSmall)
                                Text("¥" + s.overview.totalExpenseCents.toYuan(), color = ExpenseRed, fontWeight = FontWeight.SemiBold)
                            }
                            Column {
                                Text("笔数", style = MaterialTheme.typography.labelSmall)
                                Text("${s.overview.txCount}", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // 支出分类
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("支出类别", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "条形长度按该类占当月最大支出的比例；百分比是该类占总支出的比重。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        CategoryBarList(items = expenseItems, emptyHint = "本月还没有支出记录")
                    }
                }
            }

            // 收入分类
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("收入类别", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        CategoryBarList(items = incomeItems, emptyHint = "本月还没有收入记录")
                    }
                }
            }
        }
    }
}

/** 分类聚合结果 → 条形项。颜色按金额大小渐变：越大越显眼 */
private fun List<CategoryTotal>.toBars(): List<CategoryBarItem> {
    val sorted = sortedByDescending { it.totalCents }
    val palette = listOf(
        Color(0xFFD9534F), Color(0xFFE8834A), Color(0xFFE0A93B),
        Color(0xFF6FA84F), Color(0xFF4A9E9E), Color(0xFF4A7EBB),
        Color(0xFF7B6BB5), Color(0xFFB5699B), Color(0xFF8C8C8C),
    )
    return sorted.mapIndexed { index, c ->
        CategoryBarItem(
            name = c.categoryName ?: "未分类",
            icon = "🏷",
            amountCents = c.totalCents,
            color = palette[index % palette.size],
        )
    }
}
