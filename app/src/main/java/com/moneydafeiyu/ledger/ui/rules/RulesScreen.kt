package com.moneydafeiyu.ledger.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneydafeiyu.ledger.data.entity.CategoryEntity
import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.data.model.KeywordTier
import com.moneydafeiyu.ledger.ui.theme.ExpenseRed
import com.moneydafeiyu.ledger.ui.theme.JellyBorder

/**
 * 分类与识别规则。
 *
 * 两层结构：分类列表 → 某个分类的关键词。
 * 改完立刻写库并让分类缓存失效，下一笔支付就会用新规则 ——
 * 这是「规则外置」的兑现：以后调整识别行为不需要重新发版。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(onBack: () -> Unit, vm: RulesViewModel = viewModel()) {
    val editing by vm.editing.collectAsStateWithLifecycle()
    val current = editing
    if (current != null) {
        KeywordEditor(category = current, vm = vm, onBack = { vm.closeCategory() })
    } else {
        CategoryList(vm = vm, onBack = onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryList(vm: RulesViewModel, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val categories by vm.categories.collectAsStateWithLifecycle()
    val counts by vm.keywordCounts.collectAsStateWithLifecycle()
    val direction = if (tab == 0) Direction.EXPENSE else Direction.INCOME
    val list = categories.filter { it.direction == direction }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类与识别规则") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Text("＋") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("支出分类") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("收入分类") })
            }
            LazyColumn(
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "点分类进去改关键词。词汇命中越多，分类越准；" +
                            "「排除词」用来防止跨类误判（比如购物里排除「外卖」）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(list, key = { it.id }) { c ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { vm.openCategory(c) },
                        shape = RoundedCornerShape(22.dp),
                    
            border = JellyBorder,
        ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.icon.ifBlank { "🏷" }, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    (counts[c.id] ?: 0).toString() + " 个关键词" +
                                        if (c.isSystem) "" else " · 自定义",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("›", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddCategoryDialog(
            directionLabel = if (tab == 0) "支出" else "收入",
            onDismiss = { showAdd = false },
            onConfirm = { name, icon ->
                vm.addCategory(name, direction, icon)
                showAdd = false
            },
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("删不了") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCategoryDialog(
    directionLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("🏷") }
    val presets = listOf("🏷", "🚗", "✈", "🎓", "👶", "🐱", "💄", "🎁", "📚", "🏋", "🎵", "☕")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增" + directionLabel + "分类") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名称（如「交通」「教育」）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("选个图标", style = MaterialTheme.typography.labelSmall)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presets.forEach { p ->
                        Box(
                            Modifier
                                .background(
                                    if (icon == p) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { icon = p }
                                .padding(8.dp),
                        ) { Text(p) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "建好后进去加关键词，比如「地铁」「公交」「打车」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, icon) }, enabled = name.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun KeywordEditor(category: CategoryEntity, vm: RulesViewModel, onBack: () -> Unit) {
    val keywords by vm.keywords.collectAsStateWithLifecycle()
    var newWord by remember { mutableStateOf("") }
    var tier by remember { mutableStateOf(KeywordTier.STRONG) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(category.icon + " " + category.name) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 添加区
            Card(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(22.dp),
            border = JellyBorder,
        ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newWord,
                            onValueChange = { newWord = it },
                            label = { Text("加一个关键词") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                vm.addKeyword(newWord, tier)
                                newWord = ""
                            },
                            enabled = newWord.isNotBlank(),
                        ) { Text("添加") }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        KeywordTier.all.forEach { t ->
                            Box(
                                Modifier
                                    .background(
                                        if (tier == t) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(24.dp),
                                    )
                                    .clickable { tier = t }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) { Text(KeywordTier.label(t), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KeywordTier.all.forEach { t ->
                    val group = keywords.filter { it.tier == t }
                    if (group.isEmpty()) return@forEach
                    item {
                        Text(
                            KeywordTier.label(t) + "（" + group.size + "）",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                        )
                    }
                    items(group, key = { it.id }) { k ->
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            border = JellyBorder,
        ) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    k.word,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (k.enabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { vm.toggleKeyword(k) }) {
                                    Text(if (k.enabled) "停用" else "启用", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { vm.deleteKeyword(k) }) {
                                    Text("删除", style = MaterialTheme.typography.labelSmall, color = ExpenseRed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
