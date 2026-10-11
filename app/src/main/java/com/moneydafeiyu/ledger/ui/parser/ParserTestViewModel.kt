package com.moneydafeiyu.ledger.ui.parser

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moneydafeiyu.ledger.capture.CategoryClassifier
import com.moneydafeiyu.ledger.capture.IngestPipeline
import com.moneydafeiyu.ledger.capture.PageTextParser
import com.moneydafeiyu.ledger.capture.PaymentTextParser
import com.moneydafeiyu.ledger.data.model.Channel
import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.data.model.SourceKey
import com.moneydafeiyu.ledger.data.model.TxStatus
import kotlinx.coroutines.launch

data class TestResult(
    val ok: Boolean,
    val detail: String,
    val amountText: String = "",
    val directionLabel: String = "",
    val isIncome: Boolean = false,
    val merchant: String = "",
    val rule: String = "",
    val confidence: String = "",
    val categoryName: String? = null,
    // ---- 反馈诊断：直接回答「为什么弹的是这张图」 ----
    val feedbackCode: String = "",
    val feedbackCaption: String = "",
    val feedbackAsset: String = "",
    val feedbackSound: String = "",
    /** 该规则引用的图片在 assets 里是否真的存在 */
    val assetExists: Boolean = false,
    /** 同情景的候选图有几个（大于 1 说明随机应该生效） */
    val variantCount: Int = 0,
)

/**
 * 解析测试器。
 *
 * 「只试解析」不会碰数据库；「真的记一笔」会走完整流水线（含去重与弹窗），
 * 这样调规则时可以放心大胆地试，不用担心污染账本。
 */
class ParserTestViewModel(app: Application) : AndroidViewModel(app) {

    var result by mutableStateOf<TestResult?>(null)
        private set

    fun clear() {
        result = null
    }

    fun test(context: Context, sourceKey: String, channel: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val parsed = parse(sourceKey, channel, text)
            if (parsed == null) {
                result = TestResult(
                    ok = false,
                    detail = unparsableReason(sourceKey, channel, text),
                )
                return@launch
            }
            // 必须和 IngestPipeline 用同一套逻辑：
            // 商户名为空时退回用原文撞词库。
            // 之前这里只传商户名，「只试解析」就会显示「其他」，
            // 而「真的记一笔」因为走了完整流水线，分类又是对的 —— 两处显示不一致。
            val classifyText = if (parsed.merchant.isNotBlank()) parsed.merchant else text
            val catId = CategoryClassifier.classify(context, classifyText, parsed.direction)
            val cat = catId
            val catName = cat?.let { id ->
                com.moneydafeiyu.ledger.data.db.MoneyDatabase.get(context)
                    .categoryDao().loadAll().firstOrNull { it.id == id }?.name
            }
            result = withFeedbackDiag(
                context = context,
                base = TestResult(
                    ok = true,
                    detail = "解析出 ${parsed.amountCents} 分（¥${parsed.amountCents / 100.0}）",
                    amountText = (parsed.amountCents / 100.0).toString(),
                    directionLabel = Direction.label(parsed.direction),
                    isIncome = parsed.direction == Direction.INCOME,
                    merchant = parsed.merchant,
                    rule = parsed.rule,
                    confidence = parsed.confidence.toString(),
                    categoryName = catName,
                ),
                amountCents = parsed.amountCents,
                direction = parsed.direction,
                merchant = parsed.merchant,
                categoryName = catName,
            )
        }
    }

    fun ingest(context: Context, sourceKey: String, channel: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            // 先解析一遍，把商户/规则/分类算出来 —— 入账结果里也要显示这些，
            // 否则界面上的「商户（没有）」会误导人以为没解析出来（实际只是没回填）。
            val parsed = parse(sourceKey, channel, text)
            val classifyText = if (parsed != null && parsed.merchant.isNotBlank()) parsed.merchant else text
            val catId = parsed?.let { CategoryClassifier.classify(context, classifyText, it.direction) }
            val catName = catId?.let { id ->
                com.moneydafeiyu.ledger.data.db.MoneyDatabase.get(context)
                    .categoryDao().loadAll().firstOrNull { it.id == id }?.name
            }

            // evidenceId = 0：测试产生的记录不关联真实证据
            when (val outcome = IngestPipeline.process(
                context = context,
                evidenceId = 0,
                sourceKey = sourceKey,
                rawText = text,
                occurredAt = System.currentTimeMillis(),
                channel = channel,
            )) {
                is IngestPipeline.Outcome.Recorded -> result = TestResult(
                    ok = true,
                    detail = "已真的记了一笔（id=${outcome.transactionId}），回首页能看到。",
                    amountText = (outcome.amountCents / 100.0).toString(),
                    directionLabel = Direction.label(outcome.direction),
                    isIncome = outcome.direction == Direction.INCOME,
                    merchant = parsed?.merchant.orEmpty(),
                    rule = parsed?.rule.orEmpty(),
                    confidence = parsed?.confidence?.toString().orEmpty(),
                    categoryName = catName,
                ).let { base ->
                    if (parsed == null) base
                    else withFeedbackDiag(context, base, parsed.amountCents, parsed.direction, parsed.merchant, catName)
                }
                is IngestPipeline.Outcome.Merged -> result = TestResult(
                    ok = true,
                    detail = "与已有记录融合（id=${outcome.transactionId}），没有新建重复账目。",
                )
                is IngestPipeline.Outcome.Duplicate -> result = TestResult(
                    ok = false,
                    detail = "判定为重复（已有 id=${outcome.existingId}），没有新建。这是去重在起作用。",
                )
                IngestPipeline.Outcome.Unparsable -> result = TestResult(
                    ok = false,
                    detail = unparsableReason(sourceKey, channel, text),
                )
                IngestPipeline.Outcome.Unsupported -> result = TestResult(
                    ok = false,
                    detail = "该来源不在支持列表里。",
                )
            }
        }
    }

    /**
     * 反馈诊断。
     *
     * 直接调用真正的规则引擎，把「会弹哪条规则、哪张图、哪个音效」摊开给用户看，
     * 并顺手确认那张图在 assets 里到底存不存在 ——
     * 图片资源缺失是「弹错图」最容易被忽略的一种原因。
     */
    private suspend fun withFeedbackDiag(
        context: Context,
        base: TestResult,
        amountCents: Long,
        direction: String,
        merchant: String,
        categoryName: String?,
    ): TestResult {
        val rule = com.moneydafeiyu.ledger.feedback.FeedbackEngine.pick(
            context = context,
            amountCents = amountCents,
            direction = direction,
            merchant = merchant,
            categoryName = categoryName,
        ) ?: return base.copy(feedbackCode = "（没有匹配到任何反馈规则）")

        val assetOk = runCatching {
            context.assets.open("feedback/${rule.assetName}").close()
            true
        }.getOrDefault(false)

        // 数一下同情景有几张候选图，>1 说明随机应该生效
        val variants = com.moneydafeiyu.ledger.data.db.MoneyDatabase.get(context)
            .feedbackRuleDao().loadEnabled()
            .count {
                it.matchType == rule.matchType && it.matchValue == rule.matchValue &&
                    it.extraMatchType == rule.extraMatchType && it.extraMatchValue == rule.extraMatchValue
            }

        return base.copy(
            feedbackCode = rule.code,
            feedbackCaption = rule.caption,
            feedbackAsset = rule.assetName,
            feedbackSound = rule.soundName,
            assetExists = assetOk,
            variantCount = variants,
        )
    }

    private fun parse(sourceKey: String, channel: String, text: String) =
        if (channel == Channel.ACCESSIBILITY || channel == Channel.OCR) {
            PageTextParser.parse(text)
        } else {
            PaymentTextParser.parse(sourceKey, text)
        }

    /** 给出「为什么没记」的具体原因，而不是笼统说解析失败 */
    private fun unparsableReason(sourceKey: String, channel: String, text: String): String {
        val compact = text.replace(Regex("\\s+"), "")
        return when {
            sourceKey == SourceKey.BANK && compact.contains("提现") ->
                "提现属于账户之间的转移（支付宝少了、银行卡多了），总身家没变，所以不记。"
            sourceKey == SourceKey.BANK && listOf("支出", "消费", "扣款", "转出").any { compact.contains(it) } &&
                !listOf("收入", "到账", "转入", "存入").any { compact.contains(it) } ->
                "银行支出。它与支付宝/微信的支出多半是同一笔，所以只在没有任何其他来源时才记。"
            listOf("将于", "即将", "明日", "明天", "预计", "待扣", "请保证").any { compact.contains(it) } ->
                "这是「还没扣款」的提醒，不是已发生的交易，记进去会凭空多一笔支出。"
            !listOf("支出", "收入", "支付", "付款", "扣款", "到账", "消费").any { compact.contains(it) } ->
                "文本里没有收支动作，也没有金额。"
            sourceKey in listOf(SourceKey.PDD, SourceKey.JD, SourceKey.TAOBAO) &&
                listOf("收入", "收款", "到账", "获得", "领取").any { compact.contains(it) } ->
                "拼多多/淘宝这类 App 的「收款到账」多是诱导点击的广告，所以收入只认银行、微信、支付宝。"
            else -> "没有匹配到任何可用句式，或者金额/方向信息不完整。"
        }
    }
}
