package com.moneydafeiyu.ledger.ui.fortune

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneydafeiyu.ledger.data.repo.LedgerRepository.Granularity
import com.moneydafeiyu.ledger.ui.component.LineChart
import com.moneydafeiyu.ledger.ui.theme.ExpenseRed
import com.moneydafeiyu.ledger.ui.theme.IncomeGreen
import com.moneydafeiyu.ledger.ui.theme.WhaleBlue
import com.moneydafeiyu.ledger.ui.theme.JellyBorder
import com.moneydafeiyu.ledger.util.asFull
import com.moneydafeiyu.ledger.util.toYuan

/**
 * 当前全部身家。
 *
 * 这页只放两样东西：**此刻有多少钱** 和 **它是怎么变过来的**。
 * 「这个数怎么算的」「登记历史」都收进右上角菜单 —— 它们是需要时才翻的资料，
 * 不该占着主屏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FortuneScreen(onBack: () -> Unit, vm: FortuneViewModel = viewModel()) {
    val fortune by vm.fortune.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var showRecord by remember { mutableStateOf(false) }
    // 对账差额流程的状态
    var showAdjust by remember { mutableStateOf(false) }
    var pendingAmount by remember { mutableStateOf<Long?>(null) }
    var pendingNote by remember { mutableStateOf("") }
    var pendingDiff by remember { mutableStateOf(0L) }
    var showExplain by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("当前全部身家") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("登记当前家当") },
                                onClick = { menuOpen = false; showRecord = true },
                            )
                            DropdownMenuItem(
                                text = { Text("这个数怎么算的") },
                                onClick = { menuOpen = false; showExplain = true },
                            )
                            DropdownMenuItem(
                                text = { Text("家当登记历史") },
                                onClick = { menuOpen = false; showHistory = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val f = fortune
        if (f == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("加载中…")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BalanceCard(f) }
            item { BalanceChartCard(vm) }
        }
    }

    if (showRecord) {
        RecordFortuneDialog(
            onDismiss = { showRecord = false },
            onConfirm = { cents, note ->
                val f = fortune
                val diff = if (f != null && !f.neverRecorded) cents - f.balanceCents else 0L
                if (diff != 0L) {
                    pendingAmount = cents
                    pendingNote = note
                    pendingDiff = diff
                    showRecord = false
                    showAdjust = true
                } else {
                    vm.record(cents, System.currentTimeMillis(), note, null) { showRecord = false }
                }
            },
        )
    }

    // 对账差额：登记的家当和账本余额不一致时，让用户指明这笔差额属于什么类型
    if (showAdjust) {
        val amount = pendingAmount
        if (amount == null) {
            showAdjust = false
        } else {
            val diff = pendingDiff
            AdjustmentDialog(
                diffCents = diff,
                categories = vm.let { v ->
                    // 复用 ViewModel 里已经订阅好的分类流
                    val f = fortune
                    if (f == null) emptyList() else v.categoriesFor(if (diff > 0) "INCOME" else "EXPENSE")
                },
                onConfirm = { categoryId ->
                    vm.record(
                        amount,
                        System.currentTimeMillis(),
                        pendingNote,
                        com.moneydafeiyu.ledger.data.repo.FortuneAdjustment(
                            direction = if (diff > 0) "INCOME" else "EXPENSE",
                            amountCents = kotlin.math.abs(diff),
                            categoryId = categoryId,
                            note = "",
                        ),
                    ) { showAdjust = false; pendingAmount = null }
                },
                onCancel = { showAdjust = false; showRecord = true },
            )
        }
    }

    // 「这个数怎么算的」
    if (showExplain) {
        val f = fortune
        AlertDialog(
            onDismissRequest = { showExplain = false },
            title = { Text("这个数是怎么算的") },
            text = {
                Column {
                    if (f == null) {
                        Text("加载中…")
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("上次登记的家当")
                            Text("¥" + f.baseCents.toYuan())
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("此后全部收支净额")
                            Text(
                                (if (f.deltaCents >= 0) "+" else "") + f.deltaCents.toYuan(),
                                color = if (f.deltaCents >= 0) IncomeGreen else ExpenseRed,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "没有账户期初余额的概念 —— 漏记了也不用一笔笔补，" +
                                "重新登记一次家当就能把账对齐。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExplain = false }) { Text("知道了") } },
        )
    }

    // 「家当登记历史」
    if (showHistory) {
        val list = fortune?.snapshots.orEmpty()
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("家当登记历史") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (list.isEmpty()) {
                        Text("还没有登记过")
                    } else {
                        list.forEach { s ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("¥" + s.amountCents.toYuan(), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        s.recordedAt.asFull() + (if (s.note.isNotBlank()) " · " + s.note else ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { vm.deleteSnapshot(s.id) }) { Text("删除") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun BalanceCard(f: com.moneydafeiyu.ledger.data.repo.FortuneState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp),
    
            border = JellyBorder,
        ) {
        Column(Modifier.padding(22.dp)) {
            Text("当前全部身家", style = MaterialTheme.typography.bodyMedium)
            Text(
                "¥" + f.balanceCents.toYuan(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            if (f.neverRecorded) {
                Text(
                    "还没登记过家当，这个数字只是「记过的收入减支出」，不等于你真实拥有的钱。",
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Text(
                    "上次登记 " + (f.latest?.recordedAt?.asFull() ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** 余额曲线。三档粒度回答三个不同的问题：这个月怎么花的、今年怎么走的、这些年攒了多少 */
@Composable
private fun BalanceChartCard(vm: FortuneViewModel) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            border = JellyBorder,
        ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (vm.metric == FortuneViewModel.Metric.BALANCE) "家当总量" else "收支增减",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = vm.granularity == Granularity.DAY_OF_MONTH,
                        onClick = { vm.switchGranularity(Granularity.DAY_OF_MONTH) },
                        label = { Text("当月每日") },
                    )
                    FilterChip(
                        selected = vm.granularity == Granularity.MONTH_OF_YEAR,
                        onClick = { vm.switchGranularity(Granularity.MONTH_OF_YEAR) },
                        label = { Text("当年每月") },
                    )
                    FilterChip(
                        selected = vm.granularity == Granularity.YEAR,
                        onClick = { vm.switchGranularity(Granularity.YEAR) },
                        label = { Text("每年") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // 第一栏：看哪个指标。余额总量是默认 —— 打开这页最想知道"我有多少钱"
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = vm.metric == FortuneViewModel.Metric.BALANCE,
                    onClick = { vm.switchMetric(FortuneViewModel.Metric.BALANCE) },
                    label = { Text("余额总量") },
                )
                FilterChip(
                    selected = vm.metric == FortuneViewModel.Metric.DELTA,
                    onClick = { vm.switchMetric(FortuneViewModel.Metric.DELTA) },
                    label = { Text("收支增减") },
                )
            }

            Spacer(Modifier.height(8.dp))
            if (vm.seriesLoading) {
                Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                    Text("计算中…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LineChart(
                    points = vm.series.map { it.cents },
                    labels = vm.series.map { it.label },
                    lineColor = WhaleBlue,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (vm.metric == FortuneViewModel.Metric.BALANCE)
                    "余额总量 = 上次登记的家当 + 之后的净收支。没有登记过的日子靠流水倒推，" +
                        "所以漏记会让曲线悄悄偏移，重新登记一次就能拉回来。"
                else
                    "收支增减 = 每个时间段赚了多少、花了多少（只看这段时间，不含以前攒下的）。" +
                        "想知道「钱是变多了还是变少了」，要看余额总量那一栏。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
