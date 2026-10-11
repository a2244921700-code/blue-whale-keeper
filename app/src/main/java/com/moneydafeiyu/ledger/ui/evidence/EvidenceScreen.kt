package com.moneydafeiyu.ledger.ui.evidence

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneydafeiyu.ledger.capture.CaptureDiagnostics
import com.moneydafeiyu.ledger.capture.CaptureEventLog
import com.moneydafeiyu.ledger.capture.CaptureSettings
import com.moneydafeiyu.ledger.capture.PaymentNotificationListener
import com.moneydafeiyu.ledger.data.model.Channel
import com.moneydafeiyu.ledger.data.model.SourceKey
import com.moneydafeiyu.ledger.ui.theme.ExpenseRed
import com.moneydafeiyu.ledger.ui.theme.IncomeGreen
import com.moneydafeiyu.ledger.ui.theme.JellyBorder
import com.moneydafeiyu.ledger.util.asFull
import kotlinx.coroutines.delay

/**
 * 采集调试台。
 *
 * 顶部三段体检回答「链路通不通」，中间的事件流回答「服务有没有被调用」，
 * 底部证据列表回答「到底抓到了什么」。三者合起来能定位任何采集失败。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidenceScreen(
    onBack: () -> Unit,
    onOpenParserTest: () -> Unit = {},
    vm: EvidenceViewModel = viewModel(),
) {
    val context = LocalContext.current
    val list by vm.recent.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(CaptureDiagnostics.systemGranted(context)) }
    var connected by remember { mutableStateOf(CaptureDiagnostics.serviceConnected()) }
    var lastAt by remember { mutableStateOf(PaymentNotificationListener.lastEventAt) }
    var lastPkg by remember { mutableStateOf(PaymentNotificationListener.lastEventPackage) }
    var createCount by remember { mutableStateOf(PaymentNotificationListener.createCount) }
    var catchAll by remember { mutableStateOf(CaptureSettings.isCatchAll(context)) }
    var events by remember { mutableStateOf(CaptureEventLog.snapshot()) }

    LaunchedEffect(Unit) {
        while (true) {
            granted = CaptureDiagnostics.systemGranted(context)
            connected = CaptureDiagnostics.serviceConnected()
            lastAt = PaymentNotificationListener.lastEventAt
            lastPkg = PaymentNotificationListener.lastEventPackage
            createCount = PaymentNotificationListener.createCount
            events = CaptureEventLog.snapshot()
            delay(1500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("采集调试台") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = onOpenParserTest) { Text("解析测试器") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                DiagnosticCard(
                    granted = granted,
                    connected = connected,
                    lastAt = lastAt,
                    lastPkg = lastPkg,
                    createCount = createCount,
                    eventCount = events.size,
                    onRebind = { CaptureDiagnostics.requestRebind(context) },
                )
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(22.dp),
                
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("重新解析历史证据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "把库里已抓到的原文按**当前规则**重跑一遍。规则改了不用重新支付，" +
                                "点这里就能立刻看到新效果（内部有去重，不会重复记账）。",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        val r = vm.lastReplay
                        if (r != null) {
                            Text(
                                "上次重放：共 ${r.total} 条 → 新入账 ${r.recorded} 笔，" +
                                    "重复跳过 ${r.duplicate} 条，无法解析 ${r.unparsable} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = IncomeGreen,
                            )
                        }
                        TextButton(onClick = { vm.replayAll() }, enabled = !vm.replaying) {
                            Text(if (vm.replaying) "正在重放…" else "开始重放")
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("宽集模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "打开后记录**所有** App 的通知，不再过滤。只用来确认「服务通不通」，平时请关掉。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = catchAll,
                                onCheckedChange = {
                                    catchAll = it
                                    CaptureSettings.setCatchAll(context, it)
                                },
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "采集事件流水（${events.size} 条，仅内存）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { CaptureEventLog.clear(); events = emptyList() }) { Text("清空") }
                }
                Text(
                    "每一次通知到达都会记在这里（包括被过滤掉的）。空 = 服务压根没被系统调用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (events.isEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(28.dp),
                    
            border = JellyBorder,
        ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("服务从未被调用过", fontWeight = FontWeight.SemiBold)
                            Text(
                                "这说明系统没有把通知送进来。请依次尝试：" +
                                    "① 点上面「重新绑定监听服务」；" +
                                    "② 去系统设置把通知使用权关掉再打开；" +
                                    "③ 重启手机。",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            items(events) { e ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                (if (e.accepted) "✅ " else "⛔ ") + e.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (e.accepted) IncomeGreen else ExpenseRed,
                            )
                            Text(
                                CaptureEventLog.formatTime(e.at),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("原因：${e.reason}", style = MaterialTheme.typography.labelSmall)
                        if (e.preview.isNotBlank()) {
                            Text(e.preview, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                Text(
                    "已入库证据（${list.size} 条，最新在上）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }

            if (list.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("还没有入库的证据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            items(list, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                SourceKey.label(e.sourceKey) + " · " + Channel.label(e.channel),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                e.capturedAt.asFull(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (e.title.isNotBlank()) {
                            Text(e.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Text(e.rawText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCard(
    granted: Boolean,
    connected: Boolean,
    lastAt: Long,
    lastPkg: String,
    createCount: Int,
    eventCount: Int,
    onRebind: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted && connected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(24.dp),
    
            border = JellyBorder,
        ) {
        Column(Modifier.padding(16.dp)) {
            Text("采集链路体检", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            StepRow("①", granted, "系统授权", if (granted) "已勾选通知使用权" else "设置里还没勾选本应用")
            StepRow("②", connected, "服务绑定", if (connected) "系统已把监听服务拉起" else "系统没绑定服务 —— 点下面重试")
            StepRow(
                "③",
                eventCount > 0,
                "通知投递",
                if (eventCount > 0) "已收到 $eventCount 次通知回调（最近 ${lastAt.asFull()}，$lastPkg）"
                else "服务起来后一次通知都没送到",
            )
            if (createCount > 1) {
                Text(
                    "（服务已被系统重建 $createCount 次 —— 说明它被反复杀掉，后台保活需要加强）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRebind) { Text("重新绑定监听服务") }
            Text(
                "不需要 root：系统有时勾了权限却没拉起服务，重绑一次通常就好。App 每次启动也会自动重绑一次。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepRow(index: String, ok: Boolean, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(index, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        Text(if (ok) "✅" else "❌", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = if (ok) IncomeGreen else ExpenseRed)
        }
    }
}
