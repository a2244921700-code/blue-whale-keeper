package com.moneydafeiyu.ledger.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneydafeiyu.ledger.ui.component.Assets
import kotlinx.coroutines.delay
import java.time.LocalTime

/** 开屏：按时间自动切换素材（R-01 中午 / R-02 其他时段） */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val context = LocalContext.current

    val isNoon = remember {
        val h = LocalTime.now().hour
        h in 11..13
    }
    val assetName = if (isNoon) "splash_noon.webp" else "splash_normal.webp"
    val caption = if (isNoon) "啊，有点饿了。中午该吃什么呢…" else "震撼美味"
    val bitmap = remember { Assets.image(context, assetName) }

    LaunchedEffect(Unit) {
        delay(1600)
        onFinished()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = caption,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(0.7f),
                )
            }
            Text(
                text = "管钱の大肥鱼",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
