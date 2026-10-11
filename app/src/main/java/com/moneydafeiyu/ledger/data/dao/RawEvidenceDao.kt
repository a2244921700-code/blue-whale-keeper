package com.moneydafeiyu.ledger.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.moneydafeiyu.ledger.data.entity.RawEvidenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RawEvidenceDao {

    @Insert
    suspend fun insert(e: RawEvidenceEntity): Long

    @Query("SELECT * FROM raw_evidence WHERE id = :id")
    suspend fun findById(id: Long): RawEvidenceEntity?

    @Query("SELECT * FROM raw_evidence ORDER BY capturedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<RawEvidenceEntity>>

    /** 调试台：看被丢弃的证据，回答"为什么这笔记没入账" */
    @Query("SELECT * FROM raw_evidence WHERE processState = 'DISCARDED' ORDER BY capturedAt DESC LIMIT :limit")
    fun observeDiscarded(limit: Int = 200): Flow<List<RawEvidenceEntity>>

    /** 规则回放：拿全部历史证据重跑新规则 */
    @Query("SELECT * FROM raw_evidence WHERE processState != 'DISCARDED' ORDER BY capturedAt DESC LIMIT :limit")
    suspend fun loadForReplay(limit: Int = 2000): List<RawEvidenceEntity>

    @Query("UPDATE raw_evidence SET processState = :state, discardReason = :reason WHERE id = :id")
    suspend fun updateState(id: Long, state: String, reason: String? = null)

    @Query(
        """
        SELECT * FROM raw_evidence
        WHERE sourceKey = :sourceKey AND capturedAt BETWEEN :from AND :to
        ORDER BY capturedAt DESC
        """
    )
    suspend fun findInWindow(sourceKey: String, from: Long, to: Long): List<RawEvidenceEntity>

    /** 跨来源窗口查询：淘宝通知 + 支付宝扣款 需要在不同 sourceKey 之间合并 */
    @Query("SELECT * FROM raw_evidence WHERE capturedAt BETWEEN :from AND :to ORDER BY capturedAt DESC")
    suspend fun findInWindowAnySource(from: Long, to: Long): List<RawEvidenceEntity>

    @Query("SELECT COUNT(*) FROM raw_evidence")
    fun observeCount(): Flow<Int>

    /** 只保留最近 N 条，防止调试期抓太多把库撑大 */
    @Query("DELETE FROM raw_evidence WHERE id NOT IN (SELECT id FROM raw_evidence ORDER BY capturedAt DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("SELECT COUNT(*) FROM raw_evidence")
    suspend fun countNow(): Int

    /**
     * 删掉最久远的 [n] 条的**关联行**。
     *
     * 必须先删关联再删主表，否则 transaction_evidence 里会留下
     * 指向不存在记录的悬空行。
     */
    @Query(
        """
        DELETE FROM transaction_evidence
        WHERE evidenceId IN (
            SELECT id FROM raw_evidence ORDER BY capturedAt ASC, id ASC LIMIT :n
        )
        """
    )
    suspend fun deleteLinksOfOldest(n: Int)

    @Query(
        """
        DELETE FROM raw_evidence
        WHERE id IN (
            SELECT id FROM raw_evidence ORDER BY capturedAt ASC, id ASC LIMIT :n
        )
        """
    )
    suspend fun deleteOldestBatch(n: Int)

    /**
     * 保持总数**低于** [max]。
     *
     * 规则：达到 [max] 条就一次清掉最久远的 [batch] 条；
     * 清完仍 ≥ [max] 就再清一批，直到低于为止。
     *
     * 这张表纯粹是排查问题用的参考资料，不该在用户手机上无限堆积。
     */
    suspend fun enforceLimit(max: Int = 150, batch: Int = 10) {
        var count = countNow()
        var guard = 0
        while (count >= max && guard < 100) {
            deleteLinksOfOldest(batch)
            deleteOldestBatch(batch)
            count -= batch
            guard++
        }
    }

    @Query("DELETE FROM raw_evidence")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM raw_evidence WHERE processState = :state")
    suspend fun countByState(state: String): Int

    @Query("SELECT COUNT(*) FROM raw_evidence WHERE fingerprint = :fp")
    suspend fun countByFingerprint(fp: String): Int
}
