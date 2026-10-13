package com.moneydafeiyu.ledger.data.repo

import com.moneydafeiyu.ledger.data.db.MoneyDatabase
import androidx.room.withTransaction
import com.moneydafeiyu.ledger.capture.IngestPipeline
import com.moneydafeiyu.ledger.data.model.ProcessState
import com.moneydafeiyu.ledger.data.entity.AssetSnapshotEntity
import com.moneydafeiyu.ledger.data.entity.CategoryEntity
import com.moneydafeiyu.ledger.data.entity.RawEvidenceEntity
import com.moneydafeiyu.ledger.data.entity.TransactionEntity
import com.moneydafeiyu.ledger.data.model.CategoryTotal
import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.data.model.DailyTotal
import com.moneydafeiyu.ledger.data.model.Overview
import com.moneydafeiyu.ledger.data.model.SourceKey
import com.moneydafeiyu.ledger.data.model.TxStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import java.time.YearMonth
import java.time.ZoneId

/** 一个月的完整快照。UI 只订阅它，任何底层改动都会自动重新发射 */
data class MonthSnapshot(
    val month: YearMonth,
    val overview: Overview,
    val daily: List<DailyTotal>,
    val byCategory: List<CategoryTotal>,
    val transactions: List<TransactionEntity>,
    val categories: List<CategoryEntity>,
)

/**
 * 「当前全部身家」的快照状态。
 *
 * 说明：baseCents 之后发生的每一笔收支都会改变 balanceCents，
 * 所以这个数字永远是「上次登记的家当 ± 之后的流水」，不需要用户维护账户期初余额。
 */
data class FortuneState(
    val latest: AssetSnapshotEntity?,
    val snapshots: List<AssetSnapshotEntity>,
    val baseCents: Long,
    /** 自最近一次登记以来的净收支。注意这是全部流水的净额，用于展示"这段时间花了多少" */
    val deltaCents: Long,
    val balanceCents: Long,
    val neverRecorded: Boolean,
) {
    companion object {
        /** 查询异常时的兜底状态：不显示任何数字，但界面不崩 */
        val EMPTY = FortuneState(
            latest = null,
            snapshots = emptyList(),
            baseCents = 0L,
            deltaCents = 0L,
            balanceCents = 0L,
            neverRecorded = true,
        )
    }
}

/**
 * 对账差额。登记家当时若与系统算出的余额不符，差额就是漏记的收支。
 * direction 由差额正负决定：多了是收入，少了是支出。
 */
data class FortuneAdjustment(
    val direction: String,
    val amountCents: Long,
    val categoryId: Long?,
    val note: String = "",
)

data class ReplayResult(
    val total: Int,
    val recorded: Int,
    val duplicate: Int,
    val unparsable: Int,
)

class LedgerRepository(
    private val db: MoneyDatabase,
    private val appContext: android.content.Context,
) {

    private val txDao = db.transactionDao()
    private val categoryDao = db.categoryDao()
    private val accountDao = db.accountDao()
    private val evidenceDao = db.rawEvidenceDao()
    private val assetDao = db.assetSnapshotDao()

    fun monthRange(ym: YearMonth): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    /**
     * 订阅一个月的数据。
     *
     * 关键点：统计永远是「现算」而不是存快照 —— 所以你改一笔交易的分类，
     * 柱状图与占比图会立即跟着变，无需刷新，也不会出现两处数字对不上。
     */
    fun observeMonth(ym: YearMonth): Flow<MonthSnapshot> {
        val (start, end) = monthRange(ym)
        return safeCombine(ym, start, end)
    }

    fun observeCategories(direction: String): Flow<List<CategoryEntity>> =
        categoryDao.observeByDirection(direction)

    fun observeAllCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    suspend fun loadCategories(): List<CategoryEntity> = categoryDao.loadAll()

    // ---------------- 当前全部身家 ----------------

    /**
     * 家当流向：最近一次登记的家当 + 此后净收支。
     * 从未登记过时退化为「历史全部收支净额」，UI 会提示去登记一次。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeFortune(): Flow<FortuneState> =
        assetDao.observeLatest()
            .catch { emit(null) }
            .flatMapLatest { latest ->
                // 必须先拿到"最近一次登记的时刻"，才能算出此后发生的净额。
                // 直接用全部净额是错的：那样登记家当之后新记的账不会影响余额。
                val since = latest?.recordedAt ?: -1L
                combine(
                    txDao.observeNetChangeSince(since).catch { emit(0L) },
                    assetDao.observeAll().catch { emit(emptyList()) },
                ) { delta, all ->
                    FortuneState(
                        latest = latest,
                        snapshots = all,
                        baseCents = latest?.amountCents ?: 0L,
                        deltaCents = delta,
                        // 当前身家 = 上次登记的家当 + 此后净收支
                        balanceCents = (latest?.amountCents ?: 0L) + delta,
                        neverRecorded = latest == null,
                    )
                }
            }

    /**
     * 登记一次「当前家当」。
     *
     * [adjustment] 是「对账差额」：用户登记的家当与系统算出的当前余额不一致时，
     * 说明期间有漏记的收支。差额会作为一笔正式流水写进账本（带用户选定的分类），
     * 而不是让它凭空消失 —— 这样流水永远完整，能回答「钱到底去哪了」。
     *
     * 关键：差额流水的 occurredAt 必须**早于**快照的 recordedAt。
     * 因为余额 = 快照 + 快照之后(occurredAt > recordedAt)的净额，
     * 若两者时间相同或更晚，这笔差额会被重复计入一次，余额就错了。
     */
    suspend fun recordFortune(
        amountCents: Long,
        recordedAt: Long,
        note: String,
        adjustment: FortuneAdjustment? = null,
    ): Long = db.withTransaction {
        if (adjustment != null && adjustment.amountCents > 0) {
            txDao.insert(
                TransactionEntity(
                    amountCents = adjustment.amountCents,
                    direction = adjustment.direction,
                    merchant = if (adjustment.direction == Direction.INCOME) "家当对账·漏记收入" else "家当对账·漏记支出",
                    categoryId = adjustment.categoryId,
                    note = adjustment.note.ifBlank { "登记家当时发现的未记录收支" },
                    occurredAt = recordedAt - 1,
                    status = TxStatus.CONFIRMED,
                    confidence = 1.0,
                    sourceKey = SourceKey.MANUAL,
                    categoryLocked = adjustment.categoryId != null,
                )
            )
        }
        assetDao.insert(
            AssetSnapshotEntity(
                amountCents = amountCents,
                recordedAt = recordedAt,
                note = note,
            )
        )
    }

    suspend fun deleteSnapshot(id: Long) = assetDao.delete(id)

    // ---------------- 余额曲线 ----------------

    /**
     * 任意时刻的余额。
     *
     * 算法就是当初定义的那个式子的逆用：
     *   余额(t) = t 之前最近一次登记的金额 + 那次登记到 t 之间的净收支
     * 没有登记过则以 0 为基准。
     */
    suspend fun balanceAt(time: Long): Long {
        val before = assetDao.latestBefore(time)
        if (before != null) {
            return before.amountCents + txDao.netChangeBetween(before.recordedAt, time)
        }
        // 这个时刻之前没登记过 —— 但这不代表算不出来。
        // 只要**之后**登记过，就能从那个点往回倒推：
        //   余额(t) = 后来登记的金额 − (t 到登记时刻之间的净额)
        //
        // 之前漏了这一步，导致「10 月 10 日登记了 8820 元」时，
        // 10 月 1 日的余额被算成 0，整条曲线塌在地上。
        val after = assetDao.latestAfter(time)
        if (after != null) {
            return after.amountCents - txDao.netChangeBetween(time, after.recordedAt)
        }
        return txDao.netChangeBetween(Long.MIN_VALUE, time)
    }

    /** 曲线的时间粒度 */
    enum class Granularity { DAY_OF_MONTH, MONTH_OF_YEAR, YEAR }

    data class BalancePoint(val label: String, val cents: Long)

    /**
     * 余额曲线数据。
     *
     * 三种粒度共用一个算法：**先算出区间起点的余额，再按时间顺序逐笔累加**，
     * 每跨过一个时间桶就记录一次当时的值。
     * 比每个点都去查一次数据库快得多，而且天然处理"区间内多次登记"的情况。
     */
    /**
     * 收支**增减量**序列（每个时间桶的净额）。
     *
     * 和 [balanceSeries] 的区别，用个例子说最清楚：
     *   第 1 个月净赚 3000，第 2 个月净赚 2000
     *   → 增减量曲线：3000 然后 2000（看着像"变少了"）
     *   → 余额总量曲线：3000 然后 5000（这才是真实情况）
     * 两个都对，回答的是不同问题。所以两条曲线都要给用户看到。
     */
    suspend fun deltaSeries(granularity: Granularity): List<BalancePoint> {
        val zone = ZoneId.systemDefault()
        val now = java.time.LocalDate.now(zone)

        val buckets: List<Triple<Long, Long, String>> = when (granularity) {
            Granularity.DAY_OF_MONTH -> {
                val ym = YearMonth.from(now)
                (1..ym.lengthOfMonth()).map { d ->
                    val day = ym.atDay(d)
                    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    Triple(start, end, "$d")
                }
            }
            Granularity.MONTH_OF_YEAR -> {
                val y = now.year
                (1..12).map { m ->
                    val first = java.time.LocalDate.of(y, m, 1)
                    val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = first.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    Triple(start, end, "${m}月")
                }
            }
            Granularity.YEAR -> {
                val earliest = txDao.earliestTxAt() ?: System.currentTimeMillis()
                val snap = assetDao.loadAllAsc().firstOrNull()
                val startYear = java.time.Instant.ofEpochMilli(
                    minOf(earliest, snap?.recordedAt ?: earliest)
                ).atZone(zone).year
                (startYear..maxOf(startYear, now.year)).map { y ->
                    val first = java.time.LocalDate.of(y, 1, 1)
                    val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = first.plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    Triple(start, end, "${y}")
                }
            }
        }
        if (buckets.isEmpty()) return emptyList()

        val txs = txDao.loadBetweenSync(buckets.first().first, buckets.last().second)
        var idx = 0
        val out = ArrayList<BalancePoint>(buckets.size)
        for ((_, end, label) in buckets) {
            var sum = 0L
            while (idx < txs.size && txs[idx].occurredAt < end) {
                val t = txs[idx]
                sum += if (t.direction == Direction.INCOME) t.amountCents else -t.amountCents
                idx++
            }
            out.add(BalancePoint(label, sum))
        }
        return out
    }

    suspend fun balanceSeries(granularity: Granularity): List<BalancePoint> {
        val zone = ZoneId.systemDefault()
        val now = java.time.LocalDate.now(zone)

        // 分桶边界 + 标签
        val buckets: List<Pair<Long, String>> = when (granularity) {
            Granularity.DAY_OF_MONTH -> {
                val ym = YearMonth.from(now)
                val days = ym.lengthOfMonth()
                (1..days).map { d ->
                    val end = ym.atDay(d).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    end to "$d"
                }
            }
            Granularity.MONTH_OF_YEAR -> {
                val y = now.year
                (1..12).map { m ->
                    val end = java.time.LocalDate.of(y, m, 1).plusMonths(1)
                        .atStartOfDay(zone).toInstant().toEpochMilli()
                    end to "${m}月"
                }
            }
            Granularity.YEAR -> {
                val startYear = run {
                    val earliest = txDao.earliestTxAt() ?: System.currentTimeMillis()
                    val snap = assetDao.loadAllAsc().firstOrNull()
                    val t = minOf(earliest, snap?.recordedAt ?: earliest)
                    java.time.Instant.ofEpochMilli(t).atZone(zone).year
                }
                val endYear = maxOf(startYear, now.year)
                (startYear..endYear).map { y ->
                    val end = java.time.LocalDate.of(y + 1, 1, 1)
                        .atStartOfDay(zone).toInstant().toEpochMilli()
                    end to "${y}"
                }
            }
        }

        if (buckets.isEmpty()) return emptyList()

        // 逐点算余额。关键改进：每个点都走完整的 balanceAt 逻辑，
        // **包括从未来的登记点倒推**。之前是「先取一个起点余额然后逐笔累加」，
        // 一旦起点落在第一次登记之前，整条曲线就全错了。
        //
        // 用内存计算而不是逐点查库：快照和流水都一次性读出来，
        // 几百条数据下这比 31 次数据库往返快得多。
        val snapshots = assetDao.loadAllAsc()
        val allTxs = txDao.loadAllSync()

        fun netBetween(from: Long, to: Long): Long {
            var sum = 0L
            for (t in allTxs) {
                if (t.occurredAt > from && t.occurredAt <= to) {
                    sum += if (t.direction == Direction.INCOME) t.amountCents else -t.amountCents
                }
            }
            return sum
        }

        fun balanceAtTime(time: Long): Long {
            val before = snapshots.lastOrNull { it.recordedAt <= time }
            if (before != null) return before.amountCents + netBetween(before.recordedAt, time)
            val after = snapshots.firstOrNull { it.recordedAt > time }
            if (after != null) return after.amountCents - netBetween(time, after.recordedAt)
            return netBetween(Long.MIN_VALUE, time)
        }

        return buckets.map { (bucketEnd, label) ->
            BalancePoint(label, balanceAtTime(bucketEnd))
        }
    }

    fun observePendingCount(): Flow<Int> = txDao.observePendingCount()

    // ---------------- 采集证据（调试台）----------------

    fun observeRecentEvidence(limit: Int = 300): Flow<List<RawEvidenceEntity>> =
        evidenceDao.observeRecent(limit)

    fun observeEvidenceCount(): Flow<Int> = evidenceDao.observeCount()

    /** 还没解析过的证据数量 —— 冷启动时用它决定要不要自动重放 */
    suspend fun unparsedEvidenceCount(): Int = evidenceDao.countByState(ProcessState.NEW)

    suspend fun clearEvidence() = evidenceDao.clearAll()

    /**
     * 重新解析**全部历史证据**。
     *
     * 这是「规则回放调试」的雏形，也是把老数据盘活的关键：
     * 规则改了、或者刚写好解析器时，不必让用户重新支付一遍 ——
     * 直接拿库里已有的原文重跑，立刻就能看到新规则的效果。
     *
     * 幂等：流水线内部有去重，重复跑不会重复记账。
     */
    suspend fun replayEvidence(limit: Int = 2000): ReplayResult {
        val list = evidenceDao.loadForReplay(limit)
        var recorded = 0
        var duplicate = 0
        var unparsable = 0
        for (e in list) {
            when (IngestPipeline.process(
                context = appContext,
                evidenceId = e.id,
                sourceKey = e.sourceKey,
                rawText = e.rawText,
                occurredAt = e.occurredAt ?: e.capturedAt,
                channel = e.channel,
                silent = true,
            )) {
                is IngestPipeline.Outcome.Recorded -> {
                    recorded++
                    evidenceDao.updateState(e.id, ProcessState.PARSED)
                }
                is IngestPipeline.Outcome.Duplicate -> {
                    duplicate++
                    evidenceDao.updateState(e.id, ProcessState.MERGED)
                }
                is IngestPipeline.Outcome.Merged -> {
                    duplicate++
                    evidenceDao.updateState(e.id, ProcessState.MERGED)
                }
                IngestPipeline.Outcome.Unparsable -> {
                    unparsable++
                    evidenceDao.updateState(e.id, ProcessState.DISCARDED, "解析不出金额")
                }
                IngestPipeline.Outcome.Unsupported -> {
                    unparsable++
                    evidenceDao.updateState(e.id, ProcessState.DISCARDED, "来源不支持")
                }
            }
        }
        return ReplayResult(list.size, recorded, duplicate, unparsable)
    }

    suspend fun addTransaction(tx: TransactionEntity): Long = txDao.insert(tx)

    suspend fun updateTransaction(tx: TransactionEntity) = txDao.update(tx)

    suspend fun setCategory(txId: Long, categoryId: Long?) = txDao.updateCategory(txId, categoryId)

    /** 用户改了关键词库之后调用，让分类缓存失效 */
    fun invalidateClassifier() = com.moneydafeiyu.ledger.capture.CategoryClassifier.invalidate()

    suspend fun setStatus(txId: Long, status: String) = txDao.updateStatus(txId, status)

    /**
     * 删除一条记录。
     *
     * ## 为什么删记录还要动证据表
     *
     * 这是「删掉的记录会在更新软件后又回来」的根因所在。
     *
     * 删除本身只是把 status 标成 DELETED（软删除，记录仍在库里不显示）。
     * 但 App 冷启动时有一段**自动重放**逻辑：
     *
     *     if (unparsedEvidenceCount() > 0) replayEvidence()
     *
     * 它的初衷是好的 —— 用户升级到解析规则更强的版本后，让之前没解析成功的
     * 旧证据自动补跑一遍。但副作用是：**它会拿你早已删掉的那条证据重新建一笔**。
     *
     * 所以删除时必须连证据一起处理：把关联的原始证据标记成 DISCARDED，
     * 而 loadForReplay 会跳过 DISCARDED —— 重放就再也碰不到它了。
     */
    suspend fun softDelete(txId: Long) {
        txDao.updateStatus(txId, TxStatus.DELETED)

        // 把这条记录对应的原始证据也标记掉，断掉自动重放重建它的可能
        runCatching {
            val ids = db.transactionEvidenceDao().evidenceIdsOf(txId)
            ids.forEach { eid ->
                evidenceDao.updateState(eid, ProcessState.DISCARDED, "用户已删除该记录")
            }
        }
    }

    suspend fun maxExpenseCents(): Long = txDao.maxExpenseCents() ?: 0L

    /**
     * 带兜底的 combine：任何一路查询抛异常（例如数据库暂时被锁）都不会让
     * 协程崩溃，而是回落到一份空快照，UI 保持可用。
     */
    private fun safeCombine(ym: YearMonth, start: Long, end: Long): Flow<MonthSnapshot> = combine(
        txDao.observeOverview(start, end).catch { emit(Overview(0, 0, 0)) },
        txDao.observeDailyTotals(start, end).catch { emit(emptyList()) },
        txDao.observeCategoryTotals(start, end).catch { emit(emptyList()) },
        txDao.observeBetween(start, end).catch { emit(emptyList()) },
        categoryDao.observeAll().catch { emit(emptyList()) },
    ) { overview, daily, byCategory, txs, cats ->
        MonthSnapshot(ym, overview, daily, byCategory, txs, cats)
    }

    /** 同额同向的疑似重复（跨 App 去重与重复闸门用） */
    suspend fun findDuplicates(direction: String, amountCents: Long, around: Long, windowMs: Long = 90_000) =
        txDao.findDuplicates(direction, amountCents, around - windowMs, around + windowMs)
}
