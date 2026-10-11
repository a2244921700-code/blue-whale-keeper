package com.moneydafeiyu.ledger.ui.callguard

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.moneydafeiyu.ledger.callguard.CallGuardSettings
import com.moneydafeiyu.ledger.callguard.CallRules
import com.moneydafeiyu.ledger.ui.theme.ExpenseRed
import com.moneydafeiyu.ledger.ui.theme.IncomeGreen
import com.moneydafeiyu.ledger.ui.theme.JellyBorder

/**
 * 通话防护设置。
 *
 * 你指定的两条（座机、关键词）默认开启；其余规则默认关闭 ——
 * 因为服务号里有 955xx 银行客服、虚拟号段和国际来电也都有正常用户，
 * 默认打开会误杀正经电话。开关都在这儿，用一周数据后再决定要不要开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallGuardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var master by remember { mutableStateOf(CallGuardSettings.isEnabled(context)) }
    var reader by remember { mutableStateOf(CallGuardSettings.isScreenReaderEnabled(context)) }
    var ruleStates by remember {
        mutableStateOf(CallRules.DEFAULTS.keys.associateWith { CallGuardSettings.isRuleEnabled(context, it) })
    }
    var keywordsText by remember { mutableStateOf(CallGuardSettings.keywordsAsText(context)) }
    var notifyBlocked by remember { mutableStateOf(CallGuardSettings.isNotifyBlocked(context)) }
    var showLog by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(CallGuardSettings.callLog(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通话防护") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = {
                        log = CallGuardSettings.callLog(context)
                        showLog = !showLog
                    }) { Text(if (showLog) "看设置" else "看记录") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showLog) {
                item {
                    Text(
                        "来电记录（最近 " + log.size + " 条）",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "包含放行和拦截的全部来电。用一周再回来看，就知道规则准不准了。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (log.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            border = JellyBorder,
        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text("还没有任何来电记录", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "如果用了几天还是空的，说明系统没有把来电交给本应用处理 —— " +
                                        "需要去系统设置里把「来电筛选」角色授予本应用。",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                items(log) { line ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            border = JellyBorder,
        ) {
                        Text(line, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(10.dp))
                    }
                }
                item {
                    TextButton(onClick = {
                        CallGuardSettings.clearCallLog(context)
                        log = emptyList()
                    }) { Text("清空记录") }
                }
                return@LazyColumn
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(22.dp),
                
            border = JellyBorder,
        ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("通话防护总开关", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "关掉后所有来电一律放行",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Switch(checked = master, onCheckedChange = {
                            master = it
                            CallGuardSettings.setEnabled(context, it)
                        })
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            border = JellyBorder,
        ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("拦截后提醒我", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "静默通知：不响铃、不振动，但会在通知栏留一条记录。" +
                                    "通知上带「这是正常电话」按钮，点一下就把号码加进白名单。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = notifyBlocked, onCheckedChange = {
                            notifyBlocked = it
                            CallGuardSettings.setNotifyBlocked(context, it)
                        })
                    }
                }
            }

            item {
                Text("第一层 · 号码规则（响铃前静默拦截）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "这一层被拦的电话你根本不会知道：不响铃、不弹窗、不留未接记录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(CallRules.DEFAULTS.keys.toList()) { rule ->
                val on = ruleStates[rule] ?: false
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            border = JellyBorder,
        ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ruleTitle(rule), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                ruleDesc(rule),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (rule == CallRules.SERVICE_NUMBER || rule == CallRules.VIRTUAL || rule == CallRules.INTERNATIONAL)
                                    ExpenseRed else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = on, onCheckedChange = {
                            ruleStates = ruleStates + (rule to it)
                            CallGuardSettings.setRuleEnabled(context, rule, it)
                        })
                    }
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                Text("第二层 · 关键词（需要读屏，会响一两声）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "来电名是手机联网识别的结果，第三方拿不到，只能把屏幕读下来判断。" +
                        "代价是来电会响一两声才挂断。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("启用关键词判断", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Switch(checked = reader, onCheckedChange = {
                                reader = it
                                CallGuardSettings.setScreenReaderEnabled(context, it)
                            })
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = keywordsText,
                            onValueChange = {
                                keywordsText = it
                                CallGuardSettings.setKeywords(context, it.split('\n'))
                            },
                            label = { Text("一行一个关键词") },
                            minLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "来电名里包含任意一个词就挂断。注意别加太泛的词，" +
                                "比如「科技」「服务」会误伤正经来电。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(28.dp),
                
            border = JellyBorder,
        ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("⚠️ 两个前提", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "① 系统设置里要把「来电筛选」角色授予本应用，否则第一层完全不会被执行；",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            "② 第二层依赖无障碍服务 + 电话权限。没开的话只有第一层生效。",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "通讯录里的号码永远放行；外卖快递用的是手机号，本来就不会碰到座机规则。",
                            style = MaterialTheme.typography.labelSmall,
                            color = IncomeGreen,
                        )
                    }
                }
            }
        }
    }
}

private fun ruleTitle(rule: String): String = when (rule) {
    CallRules.LANDLINE -> "座机号码"
    CallRules.HIDDEN -> "隐藏号码"
    CallRules.ABNORMAL -> "号码位数异常"
    CallRules.SERVICE_NUMBER -> "400 / 95 / 100 服务号"
    CallRules.VIRTUAL -> "虚拟运营商号段"
    CallRules.INTERNATIONAL -> "国际来电"
    else -> rule
}

private fun ruleDesc(rule: String): String = when (rule) {
    CallRules.LANDLINE -> "手机号都是 11 位、1 开头；不符合这个形状的短号都算座机。你指定的规则"
    CallRules.HIDDEN -> "对方隐藏了号码，正常来电极少这么做"
    CallRules.ABNORMAL -> "少于 5 位或多于 15 位的号码"
    CallRules.SERVICE_NUMBER -> "⚠️ 默认关：银行客服 955xx 也在这个号段，开了会拦掉正经电话"
    CallRules.VIRTUAL -> "⚠️ 默认关：170/171 是骚扰重灾区，但也有很多正常用户"
    CallRules.INTERNATIONAL -> "⚠️ 默认关：骚扰常伪装境外号，但你有海外亲友就会误杀"
    else -> ""
}
