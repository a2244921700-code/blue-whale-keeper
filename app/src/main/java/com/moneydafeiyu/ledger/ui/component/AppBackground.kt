package com.moneydafeiyu.ledger.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 页面背景容器。
 *
 * 铺一张背景图，上层内容全部走 70% 半透明的卡片 ——
 * 这样背景能透出来，又不会干扰阅读文字。
 *
 * 素材缺失时自动退化为纯色（不会白屏）。
 */
/** 图片两侧留白处的底色。淡蓝白，和素材的冷色调接得上 */
private val BackdropColor = Color(0xFFF2F6FB)

@Composable
fun AppBackground(
    assetName: String,
    /**
     * 允许的最大放大倍数。
     *
     * 默认 1（只缩小不放大）—— 大多数素材分辨率够，放大只会变糊。
     * 例外是那些分辨率明显偏低的图（家当背景只有 196px 宽），
     * 1:1 显示会小得像个图标，这时可以单独放宽，比如 3 倍。
     */
    maxScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val bitmap = remember(assetName) { Assets.background(context, assetName) }

    Box(
        Modifier.fillMaxSize().background(BackdropColor),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            // 三条原则，按踩过的坑排序：
            //
            // ① **只缩小，不放大**。素材像素密度差异极大 ——
            //    主页背景是 1024 宽，家当背景只有 196 宽。
            //    统一按屏幕宽度缩放的话，后者要被拉大 5.5 倍，糊得没法看。
            //    所以只在"原图比屏幕宽"时才缩小，否则 1:1 显示。
            //
            // ② **居中**，不铺满。素材多是方形/竖方，屏幕是细长条，
            //    铺满必然裁掉大半内容（主角的脸和字）。留白交给底色。
            //
            // ③ 不拉伸变形 —— 按原始宽高比算尺寸。
            val density = LocalDensity.current
            val screenWidthPx = with(density) {
                LocalConfiguration.current.screenWidthDp.dp.toPx()
            }
            val scale = minOf(maxScale, screenWidthPx / bitmap.width.toFloat())
            val wDp = with(density) { (bitmap.width * scale).toDp() }
            val hDp = with(density) { (bitmap.height * scale).toDp() }

            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(wDp, hDp),
            )
        }
        content()
    }
}
