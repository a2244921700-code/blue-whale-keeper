package com.moneydafeiyu.ledger.capture

import android.content.Context
import android.util.Log
import com.moneydafeiyu.ledger.data.db.MoneyDatabase
import com.moneydafeiyu.ledger.data.entity.RawEvidenceEntity
import com.moneydafeiyu.ledger.data.model.Channel
import com.moneydafeiyu.ledger.data.model.ProcessState

/**
 * 所有采集通道的统一入口。
 *
 * 设计原则（沿用参考项目踩坑后的结论）：
 * 1. **先存原文，再谈解析** —— 规则改了可以拿历史样本重跑；
 * 2. 通知、无障碍、OCR 三条通道共用这一条入库路径，避免各写各的；
 * 3. 入库前的脱敏不可省略；
 * 4. **只保留最近的一小批**。这张表纯粹是调试参考资料，
 *    不该让它在用户手机上无限堆积 —— 见 KEEP_LIMIT。
 */
object EvidenceIngest {

    private const val TAG = "EvidenceIngest"

    /** 保留条数上限 */
    private const val KEEP_LIMIT = 150

    /** 每次超出时裁掉多少条 */
    private const val TRIM_BATCH = 10

    suspend fun submit(
        context: Context,
        channel: String,
        sourceKey: String,
        packageName: String,
        title: String,
        rawText: String,
        capturedAt: Long = System.currentTimeMillis(),
    ): Long {
        val maskedTitle = PiiMasker.mask(title)
        val maskedText = PiiMasker.mask(rawText)
        if (maskedText.isBlank()) return -1L

        val db = MoneyDatabase.get(context)
        val dao = db.rawEvidenceDao()
        val fp = Fingerprint.of(sourceKey, maskedText, capturedAt)

        // 指纹去重：无障碍会对同一页面探测三次，不去重会入三条一模一样的证据
        if (dao.countByFingerprint(fp) > 0) {
            Log.d(TAG, "重复证据，跳过：$fp")
            return -1L
        }

        val id = dao.insert(
            RawEvidenceEntity(
                channel = channel,
                sourceKey = sourceKey,
                packageName = packageName,
                title = maskedTitle,
                rawText = maskedText,
                capturedAt = capturedAt,
                processState = ProcessState.NEW,
                fingerprint = fp,
            )
        )
        Log.i(TAG, "证据入库 id=$id channel=$channel source=$sourceKey text=${maskedText.take(120)}")

        // 裁掉超出的部分。
        // 表纯粹是调试参考，留在手机上只有坏处（占空间、翻起来费劲），
        // 所以到量就清 —— 一次清一批，清完还超就再清一批，直到低于上限。
        runCatching { dao.enforceLimit(KEEP_LIMIT, TRIM_BATCH) }
            .onFailure { Log.w(TAG, "裁剪历史记录失败", it) }
        return id
    }
}
