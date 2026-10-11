package com.moneydafeiyu.ledger.capture

/**
 * 原始文本脱敏。抓到的通知里常带手机号、银行卡号，
 * 这些不该原样落进数据库（哪怕是本地库）。
 */
object PiiMasker {

    private val PHONE = Regex("(?<![0-9])1[3-9][0-9]{9}(?![0-9])")
    private val CARD = Regex("(?<![0-9])[0-9]{13,19}(?![0-9])")
    private val ID_CARD = Regex("(?<![0-9])[1-9][0-9]{5}(19|20)[0-9]{2}(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9])")

    fun mask(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        s = ID_CARD.replace(s, "[身份证]")
        s = PHONE.replace(s, "[手机号]")
        s = CARD.replace(s, "[卡号]")
        // 单条证据不必无限长，截断避免数据库被异常长文本撑爆
        return if (s.length > 2000) s.take(2000) + "…" else s
    }
}
