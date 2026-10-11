package com.moneydafeiyu.ledger.capture

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * 采集事件流水（内存态，最近 60 条）。
 *
 * 存在的唯一理由：区分"服务没被系统调用"和"调用了但被过滤掉"。
 * 这两种情况的表象一模一样（调试台空空如也），但处理方式完全相反：
 *   - 事件流为空        → 服务压根没被拉起，要在系统绑定上想办法
 *   - 事件流有但没入库  → 服务正常工作，问题在过滤或解析规则
 *
 * 刻意只放内存、不落库：Service 被系统杀掉重建后它会清空，
 * 这本身就说明了服务是否被重启过。
 */
object CaptureEventLog {

    private const val MAX = 60

    data class Event(
        val at: Long,
        val packageName: String,
        /** 是否最终入库 */
        val accepted: Boolean,
        /** 未入库的原因 */
        val reason: String,
        /** 文本预览（已截断） */
        val preview: String,
    )

    private val events = Collections.synchronizedList(mutableListOf<Event>())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    fun record(
        packageName: String,
        accepted: Boolean,
        reason: String,
        preview: String,
    ) {
        events.add(
            0,
            Event(
                at = System.currentTimeMillis(),
                packageName = packageName,
                accepted = accepted,
                reason = reason,
                preview = if (preview.length > 90) preview.take(90) + "…" else preview,
            )
        )
        while (events.size > MAX) events.removeAt(events.size - 1)
    }

    fun snapshot(): List<Event> = events.toList()

    fun clear() = events.clear()

    fun formatTime(at: Long): String = fmt.format(Date(at))
}
