package com.moneydafeiyu.ledger.ui.permission

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.moneydafeiyu.ledger.capture.PermissionChecker
import com.moneydafeiyu.ledger.capture.PermissionItem
import com.moneydafeiyu.ledger.ui.theme.ExpenseRed
import com.moneydafeiyu.ledger.ui.theme.IncomeGreen
import com.moneydafeiyu.ledger.ui.theme.JellyBorder

/**
 * 权限体检页。
 *
 * 关键体验：每次回到这个页面都**重新检测**，用户去系统设置里改完再回来，
 * 状态立刻就变绿 —— 不用重启 App，也不用猜自己到底开对了没有。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit, onOpenEvidence: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var items by remember { mutableStateOf(PermissionChecker.checkAll(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                items = PermissionChecker.checkAll(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val criticalMissing = items.count { it.critical && !it.granted }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限与采集") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = onOpenEvidence) { Text("采集调试台") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (criticalMissing > 0) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ),
                    shape = RoundedCornerShape(24.dp),
                
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (criticalMissing > 0) "还有 $criticalMissing 项关键权限没开" else "权限齐全，自动记账可以正常工作",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (criticalMissing > 0)
                                "没开的项会导致部分支付被静默漏记 —— 你以为在记，其实什么都没记。"
                            else
                                "去系统的「应用启动管理」里把本应用设为手动管理并全开，可以进一步减少被杀。",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            items(items, key = { it.key }) { item ->
                PermissionCard(item) {
                    item.settingsIntent?.let { ctx ->
                        runCatching { context.startActivity(ctx) }
                    }
                }
            }

            item {
                Text(
                    "提示：改完系统设置后回到本页会自动重新检测，状态会立刻更新。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(item: PermissionItem, onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            border = JellyBorder,
        ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        !item.detectable -> "ℹ️"
                        item.granted -> "✅"
                        item.critical -> "❌"
                        else -> "⚠️"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                if (!item.detectable) {
                    Text(
                        "需你自行确认",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (!item.granted) {
                    Text(
                        if (item.critical) "必需" else "建议",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.critical) ExpenseRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(item.purpose, style = MaterialTheme.typography.bodyMedium)
            Text(
                item.consequence,
                style = MaterialTheme.typography.labelSmall,
                color = if (item.granted && item.detectable) IncomeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!item.granted && item.romHint.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    item.romHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!item.jumpReliable && item.settingsIntent != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "注意：这个页面各厂商都不一样，点击后如果没跳到正确位置，" +
                        "请照着上面的路径手动找 —— 系统没有标准接口，只能尽力猜。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.settingsIntent != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onOpenSettings) {
                    Text(
                        when {
                            !item.jumpReliable -> "尝试打开设置"
                            item.granted -> "去检查设置"
                            else -> "去开启"
                        }
                    )
                }
            }
        }
    }
}
