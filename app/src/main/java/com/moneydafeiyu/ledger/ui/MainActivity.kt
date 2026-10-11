package com.moneydafeiyu.ledger.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.moneydafeiyu.ledger.ui.theme.MoneyDafeiyuTheme
import com.moneydafeiyu.ledger.util.CrashLogger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoneyDafeiyuTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav()
                }
                CrashGate()
            }
        }
    }
}

/** 上次跑崩过就把死因摊开 —— 方便截图反馈，确认后清掉记录 */
@Composable
private fun CrashGate() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { text = CrashLogger.readLast(ctx) }
    text?.let {
        CrashReportDialog(text = it) {
            CrashLogger.clear(ctx)
            text = null
        }
    }
}
