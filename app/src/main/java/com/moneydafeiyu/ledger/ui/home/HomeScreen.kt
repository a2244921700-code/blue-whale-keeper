package com.moneydafeiyu.ledger.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import com.moneydafeiyu.ledger.ui.component.CategoryPickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.moneydafeiyu.ledger.capture.CaptureDiagnostics
import com.moneydafeiyu.ledger.capture.PaymentNotificationListener
import com.moneydafeiyu.ledger.capture.PermissionChecker
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneydafeiyu.ledger.data.model.CategoryTotal
import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.data.model.SourceKey
import com.moneydafeiyu.ledger.data.model.TxStatus
import com.moneydafeiyu.ledger.data.repo.FortuneState
import com.moneydafeiyu.ledger.data.repo.MonthSnapshot
import com.moneydafeiyu.ledger.ui.component.BarChart
import com.moneydafeiyu.ledger.ui.component.BarDatum
import com.moneydafeiyu.ledger.ui.theme.ExpenseRed
import com.moneydafeiyu.ledger.ui.theme.IncomeGreen
import com.moneydafeiyu.ledger.ui.theme.JellyBorder
import com.moneydafeiyu.ledger.util.asDayTime
import com.moneydafeiyu.ledger.util.toYuan
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAdd: () -> Unit,
    onFortune: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val fortune by vm.fortune.collectAsStateWithLifecycle()
    var chartMode by remember { mutableStateOf(0) } // 0=按天 1=按分类

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
                actions = {
                    // 权限体检、采集调试台、规则编辑器都收进设置里，
                    // 首页顶部只留一个入口，不然太挤
                    TextButton(onClick = onSettings) { Text("⚙️") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Text("＋", style = MaterialTheme.typography.headlineMedium) }
        },
    ) { padding ->
        val s = snap
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("加载中…")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            // 底部留出悬浮按钮的高度，否则最后一条记录的「确认/删除」会被加号挡住
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { HealthBanner(onSettings) }
            item { FortuneCard(fortune, onFortune) }
            item { OverviewCard(s) }
            item {
                ChartCard(
                    snapshot = s,
                    mode = chartMode,
                    onModeChange = { chartMode = it },
                    onOpenStats = onStats,
                )
            }
            if (s.transactions.isNotEmpty()) {
                item {
                    Text(
                        "流水明细",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    )
                }
                items(s.transactions, key = { it.id }) { tx ->
                    val catName = s.categories.firstOrNull { it.id == tx.categoryId }
                    val rowTitle = tx.merchant.ifBlank { catName?.name ?: SourceKey.label(tx.sourceKey) }
                    val rowAmount = (if (tx.direction == Direction.INCOME) "+" else "-") + tx.amountCents.toYuan()
                    var askCategory by remember { mutableStateOf(false) }

                    // 左滑改分类。它不是破坏性操作，所以用轻量的滑动手势；
                    // 删除仍然锁在"长按 + 二次确认"后面。
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) askCategory = true
                            false // 永远不真正滑掉，让它弹回去
                        },
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp))
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Text("改分类 ←", style = MaterialTheme.typography.bodyMedium)
                            }
                        },
                    ) {
                        TransactionRow(
                            title = rowTitle,
                            subtitle = "${tx.occurredAt.asDayTime()} · ${SourceKey.label(tx.sourceKey)}${if (tx.status == TxStatus.PENDING) " · 待确认" else ""}",
                            amountText = rowAmount,
                            amountColor = if (tx.direction == Direction.INCOME) IncomeGreen else ExpenseRed,
                            emoji = catName?.icon ?: "💸",
                            pending = tx.status == TxStatus.PENDING,
                            onConfirm = { vm.confirmTransaction(tx.id) },
                            onDelete = { vm.deleteTransaction(tx.id) },
                        )
                    }

                    if (askCategory) {
                        CategoryPickerDialog(
                            title = rowTitle,
                            amountText = rowAmount,
                            direction = tx.direction,
                            categories = s.categories,
                            currentCategoryId = tx.categoryId,
                            onPick = { id ->
                                vm.changeCategory(tx.id, id)
                                askCategory = false
                            },
                            onDismiss = { askCategory = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(s: MonthSnapshot) {
    val income = s.overview.totalIncomeCents
    val expense = s.overview.totalExpenseCents
    val balance = income - expense
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(26.dp),
    
            border = JellyBorder,
        ) {
        Column(Modifier.padding(18.dp)) {
            Text("本月结余", style = MaterialTheme.typography.bodyMedium)
            Text(
                "¥" + balance.toYuan(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text("收入", style = MaterialTheme.typography.labelSmall)
                    Text("¥" + income.toYuan(), color = IncomeGreen, style = MaterialTheme.typography.titleMedium)
                }
                Column {
                    Text("支出", style = MaterialTheme.typography.labelSmall)
                    Text("¥" + expense.toYuan(), color = ExpenseRed, style = MaterialTheme.typography.titleMedium)
                }
                Column {
                    Text("笔数", style = MaterialTheme.typography.labelSmall)
                    Text("${s.overview.txCount}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ChartCard(
    snapshot: MonthSnapshot,
    mode: Int,
    onModeChange: (Int) -> Unit,
    onOpenStats: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp),
            border = JellyBorder,
        ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "当月收支",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { onOpenStats() },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = mode == 0, onClick = { onModeChange(0) }, label = { Text("按天") })
                    FilterChip(selected = mode == 1, onClick = { onModeChange(1) }, label = { Text("按分类") })
                }
            }
            Spacer(Modifier.height(8.dp))

            val bars: List<BarDatum> = if (mode == 0) {
                dailyBars(snapshot)
            } else {
                categoryBars(snapshot)
            }
            BarChart(data = bars, incomeColor = IncomeGreen, expenseColor = ExpenseRed)

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Legend(IncomeGreen, "收入")
                    Legend(ExpenseRed, "支出")
                }
                // 竖柱只能看高低，想知道具体多少钱要点进分类统计
                Text(
                    "分类金额明细 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenStats() },
                )
            }
        }
    }
}

/** 按天：补齐 1 到月末，没有记录的天也画出来（0 值），柱子才不会被压缩 */
private fun dailyBars(s: MonthSnapshot): List<BarDatum> {
    val days = s.month.lengthOfMonth()
    val map = HashMap<Int, BarDatum>()
    for (d in 1..days) map[d] = BarDatum("$d", 0L, 0L)
    for (d in s.daily) {
        val day = d.day.takeLast(2).trimStart('0').toIntOrNull() ?: continue
        val cur = map[day] ?: continue
        map[day] = if (d.direction == Direction.INCOME) cur.copy(income = d.totalCents) else cur.copy(expense = d.totalCents)
    }
    return (1..days).mapNotNull { map[it] }
}

/** 按分类：支出与收入各自成组，取金额前若干名 */
private fun categoryBars(s: MonthSnapshot): List<BarDatum> {
    val expense = s.byCategory.filter { it.direction == Direction.EXPENSE }.take(8)
    val income = s.byCategory.filter { it.direction == Direction.INCOME }.take(8)
    val out = mutableListOf<BarDatum>()
    expense.forEach { out += BarDatum(shortName(it), 0L, it.totalCents) }
    income.forEach { out += BarDatum(shortName(it), it.totalCents, 0L) }
    return out
}

private fun shortName(t: CategoryTotal): String {
    val n = t.categoryName ?: "未分类"
    return if (n.length > 4) n.take(4) else n
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionRow(
    title: String,
    subtitle: String,
    amountText: String,
    amountColor: Color,
    emoji: String,
    pending: Boolean,
    onConfirm: () -> Unit,
    onDelete: () -> Unit,
) {
    var askDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                // 短按确认：只有待确认的记录才需要它；已确认的短按不做任何破坏性动作
                onClick = { if (pending) onConfirm() },
                // 长按删除：删除不可恢复，必须"费力"才能触发，从根上避免误触
                onLongClick = { askDelete = true },
            ),
        shape = RoundedCornerShape(22.dp),
    
            border = JellyBorder,
        ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (pending) "短按确认 · 长按删除" else "长按可删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(amountText, color = amountColor, fontWeight = FontWeight.SemiBold)
        }
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text(title + "\n" + amountText + "\n\n删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = { askDelete = false; onDelete() }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("取消") } },
        )
    }
}

/** 首页顶部的「当前全部身家」入口 —— 数字永远跟着流水走 */
@Composable
private fun FortuneCard(f: FortuneState?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(26.dp),
    
            border = JellyBorder,
        ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("当前全部身家", style = MaterialTheme.typography.labelSmall)
                Text(
                    "¥" + (f?.balanceCents ?: 0L).toYuan(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (f?.neverRecorded == true) {
                    Text(
                        "还没登记过家当，点这里去登记",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text("›", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 首页健康条。
 *
 * 覆盖三种"看着没事、其实没在记"的状态：
 *   ① 缺关键权限 —— 系统设置里就没勾
 *   ② 权限勾了但服务没被绑定 —— 国产 ROM 的经典问题，最容易被误判成"App 坏了"
 *   ③ 服务已就绪但一条通知都没收到 —— 可能是被系统限制了通知投递
 */
@Composable
private fun HealthBanner(onClick: () -> Unit) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(0) }
    var granted by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var lastEventAt by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            missing = PermissionChecker.criticalMissing(context).size
            granted = CaptureDiagnostics.systemGranted(context)
            connected = CaptureDiagnostics.serviceConnected()
            lastEventAt = PaymentNotificationListener.lastEventAt
            delay(2000)
        }
    }

    val message = when {
        missing > 0 -> "自动记账还没生效（$missing 项权限缺失）"
        granted && !connected -> "通知服务没有被系统绑定"
        connected && lastEventAt == 0L -> "服务已就绪，但还没收到过任何通知"
        else -> null
    } ?: return

    val hint = when {
        missing > 0 -> "点这里去开启，否则支付会被静默漏记"
        granted && !connected -> "系统勾了权限却没拉起服务 —— 点这里去重绑一次"
        else -> "先用支付宝付一笔试试，然后去采集调试台看有没有抓到"
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(24.dp),
    
            border = JellyBorder,
        ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚠️", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(hint, style = MaterialTheme.typography.labelSmall)
            }
            Text("›", style = MaterialTheme.typography.titleMedium)
        }
    }
}
