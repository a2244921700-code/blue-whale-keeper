package com.moneydafeiyu.ledger.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 分 → 元，始终两位小数。绝不用浮点参与计算，只在展示时转换 */
fun Long.toYuan(): String {
    val neg = this < 0
    val abs = if (neg) -this else this
    return (if (neg) "-" else "") + (abs / 100).toString() + "." + (abs % 100).toString().padStart(2, '0')
}

/** 元字符串 → 分。用字符串解析避免浮点误差（0.1 + 0.2 那类问题） */
fun String.yuanToCents(): Long? {
    val s = this.trim().replace(",", "")
    if (s.isEmpty()) return null
    if (!Regex("^\\d{0,9}(\\.\\d{0,2})?$").matches(s)) return null
    val parts = s.split('.')
    val yuan = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
    val cents = if (parts.size > 1) parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: 0 else 0
    return yuan * 100 + cents
}

private val dayFmt = SimpleDateFormat("M月d日", Locale.CHINA)
private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
private val dayTimeFmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

fun Long.asDay(): String = dayFmt.format(Date(this))
fun Long.asTime(): String = timeFmt.format(Date(this))
fun Long.asFull(): String = fullFmt.format(Date(this))

/** 流水列表用：既看得到哪天，也看得到几点几分 */
fun Long.asDayTime(): String = dayTimeFmt.format(Date(this))
