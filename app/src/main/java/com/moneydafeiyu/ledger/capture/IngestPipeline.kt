package com.moneydafeiyu.ledger.capture

import android.content.Context
import android.util.Log
import com.moneydafeiyu.ledger.data.db.MoneyDatabase
import com.moneydafeiyu.ledger.data.entity.TransactionEntity
import com.moneydafeiyu.ledger.data.entity.TransactionEvidenceEntity
import com.moneydafeiyu.ledger.data.model.Channel
import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.data.model.SourceKey
import com.moneydafeiyu.ledger.data.model.TxStatus
import com.moneydafeiyu.ledger.feedback.FeedbackEngine
import com.moneydafeiyu.ledger.feedback.FeedbackOverlay
import com.moneydafeiyu.ledger.util.toYuan

/**
 * 入库流水线：原始证据 → 解析 → 去重/融合 → 正式账目。
 *
 * 四个刻意的设计选择：
 *
 * 1. **宁可漏记，不可记错**。解析不出金额就只留证据不入账。
 *
 * 2. **证据融合而不是简单去重**。同一笔支付会产生多条证据
 *    （银行扣款通知 + 支付宝通知 + 支付结果页），它们不是"重复"，
 *    而是同一笔的不同侧面：银行有金额没商户，页面有商户没方向。
 *    所以后到的证据如果信息更全，就**补到已有记录上**，而不是丢弃。
 *
 * 3. **商户名用来分类**。有商户名就去撞关键词库；没有就退到「其他」并标待确认，
 *    让鲸鱼娘说一句「脑袋空空」——这正是那两张图存在的意义。
 *
 * 4. **银行支出降级为待确认**。它多半与支付宝/微信重复，但也可能是
 *    只走银行卡的自动续费。收下来标待确认，用户一眼能看出并删掉。
 */
object IngestPipeline {

    private const val TAG = "IngestPipeline"

    /**
     * 同额同向多久内视为同一笔。
     *
     * 取 60 秒是权衡后的结果：
     *   - 太短：支付软件和银行的通知可能隔十几秒才到，去不掉重复；
     *   - 太长：**真实的同金额消费会被吞掉** ——
     *     每天都吃同一家的同一份饭，金额当然一样。
     * 所以宁可偶尔留一条重复让用户删，也不能把真实消费吃掉。
     */
    private const val DEDUP_WINDOW_MS = 60_000L

    /**
     * 银行通知的比对窗口放宽到 10 分钟。
     *
     * 银行短信/推送经常延迟几分钟才到，用 60 秒根本来不及比对，
     * 结果就是「支付宝一笔 + 银行一笔」两条重复记录。
     * 放宽窗口的代价只是偶尔多一条待确认，比重复记账可控。
     */
    private const val BANK_DEDUP_WINDOW_MS = 600_000L

    /**
     * 允许记录**收入**的来源。
     * 拼多多/淘宝这类 App 的"收款到账"广告极多，且都是诱导点击的假消息，
     * 所以收入只认银行、微信、支付宝 —— 真实收入必然在其中之一留下记录。
     * 支出不设此限制（先用后付扣款、订单支付都是真的）。
     */
    private val INCOME_ALLOWED_SOURCES = setOf(SourceKey.BANK, SourceKey.WECHAT, SourceKey.ALIPAY)

    private const val FALLBACK_EXPENSE = "其他支出"
    private const val FALLBACK_INCOME = "其他收入"

    sealed class Outcome {
        data class Recorded(val transactionId: Long, val amountCents: Long, val direction: String) : Outcome()
        data class Merged(val transactionId: Long) : Outcome()
        data class Duplicate(val existingId: Long) : Outcome()
        data object Unparsable : Outcome()
        data object Unsupported : Outcome()
    }

    suspend fun process(
        context: Context,
        evidenceId: Long,
        sourceKey: String,
        rawText: String,
        occurredAt: Long,
        channel: String = Channel.NOTIFICATION,
        silent: Boolean = false,
    ): Outcome {
        if (!PaymentTextParser.supports(sourceKey)) return Outcome.Unsupported

        val parsed = when (channel) {
            Channel.ACCESSIBILITY, Channel.OCR -> PageTextParser.parse(rawText)
            else -> PaymentTextParser.parse(sourceKey, rawText)
        } ?: return Outcome.Unparsable

        // 收入只信这三家。
        // 拼多多、淘宝之类的 App 会弹「恭喜获得 XX 元」这种虚假收款广告，
        // 目的就是骗用户点击。真按它记账，账本会被广告污染。
        // 真实收入在银行/微信/支付宝那边一定有对应记录，漏不掉。
        if (parsed.direction == Direction.INCOME && sourceKey !in INCOME_ALLOWED_SOURCES) {
            Log.i(TAG, "来源 $sourceKey 不在收入白名单，放弃：${parsed.amountCents}分")
            return Outcome.Unsupported
        }

        val db = MoneyDatabase.get(context)
        val txDao = db.transactionDao()

        // 先算分类：融合时也要用它。
        //
        // 分类信号不只在商户名里：银行到账通知压根没有"商户"，
        // 但正文里写着「收入工资」——「工资」就是分类线索。
        // 所以商户名为空时退回用原文来撞词库。
        val classifyText = if (parsed.merchant.isNotBlank()) parsed.merchant else rawText
        val classifiedId = CategoryClassifier.classify(context, classifyText, parsed.direction)
        val category = if (classifiedId != null) {
            db.categoryDao().loadAll().firstOrNull { it.id == classifiedId }
        } else {
            db.categoryDao().findByName(
                if (parsed.direction == Direction.INCOME) FALLBACK_INCOME else FALLBACK_EXPENSE,
                parsed.direction,
            )
        }

        // 找时间窗内的同额同向记录。银行通知投递慢，窗口放宽。
        val window = if (sourceKey == SourceKey.BANK) BANK_DEDUP_WINDOW_MS else DEDUP_WINDOW_MS
        val candidates = txDao.findDuplicates(
            parsed.direction,
            parsed.amountCents,
            occurredAt - window,
            occurredAt + window,
        )

        // 关键一步：**商户名不同就不算同一笔**。
        // 之前只比金额和方向，导致「A 店花 20」会把「B 店花 20」当成重复吞掉 ——
        // 而每天都吃同一家的同一份饭，金额相同是常态。
        // 只有「两边都有名字且不一样」才排除；
        // 一方为空时仍视为可能重复（银行通知就没有商户名）。
        val existing = candidates.firstOrNull { c ->
            val a = c.merchant.trim()
            val b = parsed.merchant.trim()
            !(a.isNotEmpty() && b.isNotEmpty() && a != b)
        }

        if (existing != null) {

            // 融合：新证据更完整就补上去。典型场景是
            // 银行先到（只有金额）→ 支付宝后到（有商户名）→ 补上商户与分类。
            if (parsed.merchant.isNotBlank() && existing.merchant.isBlank()) {
                txDao.update(
                    existing.copy(
                        merchant = parsed.merchant,
                        categoryId = category?.id ?: existing.categoryId,
                        // 来源换成信息更权威的那个（页面 > 支付宝 > 银行）
                        sourceKey = if (existing.sourceKey == SourceKey.BANK) sourceKey else existing.sourceKey,
                        confidence = maxOf(existing.confidence, parsed.confidence),
                        status = if (parsed.merchant.isNotBlank()) TxStatus.PENDING else existing.status,
                    )
                )
                if (evidenceId > 0) {
                    db.transactionEvidenceDao().insert(
                        TransactionEvidenceEntity(transactionId = existing.id, evidenceId = evidenceId, role = "SUPPORTING")
                    )
                }
                Log.i(TAG, "融合到已有记录 id=${existing.id}（补上商户 ${parsed.merchant}）")
                return Outcome.Merged(existing.id)
            }

            Log.i(TAG, "判定为重复，跳过：${parsed.amountCents}分 ${parsed.direction}")
            return Outcome.Duplicate(existing.id)
        }

        val isBankDebit = sourceKey == SourceKey.BANK && parsed.direction == Direction.EXPENSE

        val id = txDao.insert(
            TransactionEntity(
                amountCents = parsed.amountCents,
                direction = parsed.direction,
                merchant = parsed.merchant,
                categoryId = category?.id,
                note = if (isBankDebit) "银行扣款。若与微信/支付宝的记录重复，请长按删除。" else "",
                occurredAt = occurredAt,
                // 缺商户名 → 分类无法确认 → 待确认；银行支出同理
                status = if (parsed.merchant.isBlank() || isBankDebit) TxStatus.PENDING else TxStatus.CONFIRMED,
                confidence = parsed.confidence,
                sourceKey = sourceKey,
            )
        )

        if (evidenceId > 0) {
            db.transactionEvidenceDao().insert(
                TransactionEvidenceEntity(transactionId = id, evidenceId = evidenceId, role = "PRIMARY")
            )
        }

        Log.i(TAG, "已入账 id=$id ${parsed.amountCents}分 ${parsed.direction} 规则=${parsed.rule}")

        val locked = ScreenState.isLocked(context)
        if (!silent && !locked) {
            runCatching {
                val rule = FeedbackEngine.pick(
                    context = context,
                    amountCents = parsed.amountCents,
                    direction = parsed.direction,
                    merchant = parsed.merchant,
                    categoryName = category?.name,
                    occurredAt = occurredAt,
                )
                if (rule != null) {
                    FeedbackOverlay.show(
                        context = context,
                        rule = rule,
                        amountText = (if (parsed.direction == Direction.INCOME) "+¥" else "-¥") + parsed.amountCents.toYuan(),
                        subtitle = SourceKey.label(sourceKey) +
                            (if (parsed.merchant.isNotBlank()) " · " + parsed.merchant else ""),
                    )
                }
            }.onFailure { Log.e(TAG, "反馈显示失败", it) }
        }

        return Outcome.Recorded(id, parsed.amountCents, parsed.direction)
    }
}
