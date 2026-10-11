package com.moneydafeiyu.ledger.ui.parser

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneydafeiyu.ledger.capture.ParserTestSamples
import com.moneydafeiyu.ledger.data.model.Channel
import com.moneydafeiyu.ledger.data.model.SourceKey
import com.moneydafeiyu.ledger.ui.theme.ExpenseRed
import com.moneydafeiyu.ledger.ui.theme.IncomeGreen
import com.moneydafeiyu.ledger.ui.theme.JellyBorder

/**
 * 解析测试器。
 *
 * 做这个工具的原因：真实支付要花钱、真实通知要等，
 * 而规则调优需要反复试几十次。这里可以粘贴任意文本立刻看到解析结果，
 * 还能选择「只试不入账」或「真的记一笔」。
 *
 * 比用 QQ 小号发模拟消息强的地方：不受第三方 App 影响、能测任何来源、
 * 而且不会因为 QQ 不在白名单里而压根收不到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParserTestScreen(onBack: () -> Unit, vm: ParserTestViewModel = viewModel()) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var sourceKey by remember { mutableStateOf(SourceKey.ALIPAY) }
    var channel by remember { mutableStateOf(Channel.NOTIFICATION) }

    val sources = listOf(SourceKey.ALIPAY, SourceKey.WECHAT, SourceKey.PDD, SourceKey.JD, SourceKey.BANK, SourceKey.TAOBAO)
    val channels = listOf(Channel.NOTIFICATION, Channel.ACCESSIBILITY, Channel.OCR)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("解析测试器") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("来源", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            sources.forEach { s ->
                                Chip(selected = sourceKey == s, label = SourceKey.label(s)) { sourceKey = s }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("通道", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            channels.forEach { c ->
                                Chip(selected = channel == c, label = Channel.label(c)) { channel = c }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("把通知原文粘在这里") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.test(context, sourceKey, channel, text) },
                                enabled = text.isNotBlank(),
                            ) { Text("只试解析") }
                            TextButton(
                                onClick = { vm.ingest(context, sourceKey, channel, text) },
                                enabled = text.isNotBlank(),
                            ) { Text("真的记一笔") }
                        }
                    }
                }
            }

            // 解析结果
            vm.result?.let { r ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (r.ok) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(22.dp),
                    
            border = JellyBorder,
        ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                if (r.ok) "✅ 解析成功" else "⏭ 不会记账",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(r.detail, style = MaterialTheme.typography.bodyMedium)
                            if (r.ok) {
                                Text(
                                    "金额 ¥" + r.amountText + "   方向 " + r.directionLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (r.isIncome) IncomeGreen else ExpenseRed,
                                )
                                Text("商户 " + (r.merchant.ifBlank { "（没有，会归到其他并标待确认）" }), style = MaterialTheme.typography.bodyMedium)
                                Text("命中规则 " + r.rule + "   置信度 " + r.confidence, style = MaterialTheme.typography.labelSmall)
                                Text("推断分类 " + (r.categoryName ?: "其他"), style = MaterialTheme.typography.labelSmall)
                            }
                            if (r.feedbackCode.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text("会弹的反馈", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    if (r.feedbackCode.startsWith("（"))
                                        "⛔ " + r.feedbackCode
                                    else
                                        "✅ " + r.feedbackCode + "：「" + r.feedbackCaption + "」",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (!r.feedbackCode.startsWith("（")) {
                                    Text(
                                        "图片 " + r.feedbackAsset + (if (r.assetExists) " ✅存在" else " ❌缺失（这就是弹不出图的原因）"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (r.assetExists) IncomeGreen else ExpenseRed,
                                    )
                                    if (r.feedbackSound.isNotBlank()) {
                                        Text("音效 " + r.feedbackSound, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(
                                        "同情景候选图 " + r.variantCount + " 张" + (if (r.variantCount > 1) "（应该随机）" else "（只有这一张，不会变）"),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "测试样本（点一下填进输入框）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
                Text(
                    "全部按真机通知的实际措辞写。带「应放弃」的是必须不能记账的。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(ParserTestSamples.all) { s ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        text = s.text
                        sourceKey = s.sourceKey
                        channel = s.channel
                        vm.clear()
                    },
                    shape = RoundedCornerShape(28.dp),
                
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                s.group + " · " + s.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(Channel.label(s.channel), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(s.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(24.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
