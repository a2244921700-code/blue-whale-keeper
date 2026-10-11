package com.moneydafeiyu.ledger.ui.component

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** 从 assets 读取图片素材。加载失败的图不缓存，下次还会重试 */
object Assets {
    private val cache = HashMap<String, ImageBitmap>()

    /** 鲸鱼娘反馈图（assets/feedback/） */
    fun image(context: Context, name: String): ImageBitmap? = load(context, "feedback/$name")

    /** 背景图（assets/backgrounds/） */
    fun background(context: Context, name: String): ImageBitmap? = load(context, "backgrounds/$name")

    fun load(context: Context, path: String): ImageBitmap? {
        cache[path]?.let { return it }
        return runCatching {
            context.assets.open(path).use { input ->
                BitmapFactory.decodeStream(input)?.asImageBitmap()
            }
        }.getOrNull()?.also { cache[path] = it }
    }
}
