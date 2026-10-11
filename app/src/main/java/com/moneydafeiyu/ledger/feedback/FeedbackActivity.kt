package com.moneydafeiyu.ledger.feedback

import android.content.Context
import android.content.Intent
import android.os.Build
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneydafeiyu.ledger.capture.CaptureSettings
import com.moneydafeiyu.ledger.ui.component.Assets
import kotlinx.coroutines.delay

/**
 * 鲸鱼娘全屏反馈。
 *
 * 为什么最终选了 Activity 而不是悬浮窗：
 * 悬浮窗（TYPE_APPLICATION_OVERLAY）受各家 ROM 的后台窗口策略影响，
 * 实际测试中经常"权限开了也弹不出来"。而 Activity 一定在最上层，最可靠。
 *
 * 能后台启动的关键：应用持有「显示在其他应用上层」权限时，
 * Android 官方**豁免**了"禁止后台启动 Activity"的限制 ——
 * 所以只要用户开了那个权限，这条路就是通的；没开则降级为通知。
 */
class FeedbackActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ASSET = "asset"
        const val EXTRA_CAPTION = "caption"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_SOUND = "sound"

        fun start(
            context: Context,
            assetName: String,
            caption: String,
            amountText: String,
            subtitle: String,
            soundName: String = "",
        ) {
            val intent = Intent(context, FeedbackActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(EXTRA_ASSET, assetName)
                putExtra(EXTRA_CAPTION, caption)
                putExtra(EXTRA_AMOUNT, amountText)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_SOUND, soundName)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        val asset = intent.getStringExtra(EXTRA_ASSET).orEmpty()
        val caption = intent.getStringExtra(EXTRA_CAPTION).orEmpty()
        val amount = intent.getStringExtra(EXTRA_AMOUNT).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        val sound = intent.getStringExtra(EXTRA_SOUND).orEmpty()

        // 音效只放一遍，和插画同时开始
        playSound(this, sound)

        setContent {
            FeedbackContent(
                assetName = asset,
                caption = caption,
                amountText = amount,
                subtitle = subtitle,
                onDismiss = { finish() },
            )
        }
    }

    /**
     * 播放 assets/sounds 里的配音。
     *
     * 用 openFd 而不是 open —— mp3 在 APK 里是不压缩存放的，
     * 走文件描述符可以直接交给 MediaPlayer 解码，不用先读进内存。
     * 没有对应音效的情景（开屏、手动登记）传空串，这里直接跳过。
     */
    private fun playSound(context: Context, name: String) {
        if (name.isBlank()) return

        // 尊重用户的静音意愿：手机调到振动/静音时不发声（插画照常弹）。
        // 想让它无论如何都说话，去设置里打开「静音时也播放音效」。
        val am = context.getSystemService(AudioManager::class.java)
        val silent = am?.ringerMode != AudioManager.RINGER_MODE_NORMAL
        if (silent && !CaptureSettings.isSoundWhenSilent(context)) {
            Log.i("FeedbackActivity", "手机处于静音/振动模式，跳过音效")
            return
        }

        runCatching {
            val afd = context.assets.openFd("sounds/$name")
            val player = MediaPlayer()
            // 明确声明走**媒体流**：
            // 媒体流只受"媒体音量"控制，不会因为用户关掉通知音量就哑掉，
            // 行为最可预期。（静音与否由上面的判断决定，不交给系统流类型。）
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            player.setOnCompletionListener { runCatching { it.release() } }
            player.setOnErrorListener { mp, _, _ -> runCatching { mp.release() }; true }
            player.prepare()
            player.start()
        }.onFailure { Log.w("FeedbackActivity", "音效播放失败: $name", it) }
    }
}

@Composable
private fun FeedbackContent(
    assetName: String,
    caption: String,
    amountText: String,
    subtitle: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap = remember(assetName) { Assets.image(context, assetName) }

    // 停留一会儿自动收起，不用用户操作
    LaunchedEffect(Unit) {
        delay(2800)
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2101820))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = caption,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(0.86f),
                )
            }
            if (caption.isNotBlank()) {
                Text(
                    caption,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
            if (amountText.isNotBlank()) {
                Text(
                    amountText,
                    color = Color(0xFF8FD3FF),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = Color(0xFFB0BEC5),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                "点任意位置收起",
                color = Color(0xFF78909C),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
