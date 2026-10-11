package com.moneydafeiyu.ledger.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.moneydafeiyu.ledger.data.entity.TransactionEntity
import com.moneydafeiyu.ledger.data.model.CategoryTotal
import com.moneydafeiyu.ledger.data.model.DailyTotal
import com.moneydafeiyu.ledger.data.model.Overview
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Insert
    suspend fun insertAll(list: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(tx: TransactionEntity)

    @Delete
    suspend fun delete(tx: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: Long): Flow<TransactionEntity?>

    /** 流水列表：按时间倒序。status 传 null 表示不过滤 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE status != 'DELETED'
          AND (:onlyPending = 0 OR status = 'PENDING')
          AND (:direction IS NULL OR direction = :direction)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY occurredAt DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun observeList(
        onlyPending: Boolean,
        direction: String?,
        categoryId: Long?,
        limit: Int = 500,
        offset: Int = 0,
    ): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE status != 'DELETED' AND occurredAt >= :start AND occurredAt < :end
        ORDER BY occurredAt DESC, id DESC
        """
    )
    fun observeBetween(start: Long, end: Long): Flow<List<TransactionEntity>>

    /** 待确认数量，首页角标用 */
    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    // ---------- 统计聚合：全部走 SQL，不在内存里算 ----------

    /**
     * 按天聚合。用设备本地时区分组（strftime localtime），
     * 避免 UTC 跨日导致「凌晨记的账算到前一天」。
     */
    @Query(
        """
        SELECT strftime('%Y-%m-%d', occurredAt / 1000, 'unixepoch', 'localtime') AS day,
               direction AS direction,
               SUM(amountCents) AS totalCents
        FROM transactions
        WHERE status != 'DELETED' AND occurredAt >= :start AND occurredAt < :end
          AND direction IN ('EXPENSE', 'INCOME')
        GROUP BY day, direction
        ORDER BY day
        """
    )
    fun observeDailyTotals(start: Long, end: Long): Flow<List<DailyTotal>>

    /** 按分类聚合。LEFT JOIN 保证没有交易的分类也能出现（值为 0） */
    @Query(
        """
        SELECT t.categoryId AS categoryId,
               c.name AS categoryName,
               t.direction AS direction,
               SUM(t.amountCents) AS totalCents,
               COUNT(t.id) AS txCount
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        WHERE t.status != 'DELETED' AND t.occurredAt >= :start AND t.occurredAt < :end
          AND t.direction IN ('EXPENSE', 'INCOME')
        GROUP BY t.categoryId, t.direction
        ORDER BY totalCents DESC
        """
    )
    fun observeCategoryTotals(start: Long, end: Long): Flow<List<CategoryTotal>>

    /** 区间总览 */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN direction = 'INCOME'  THEN amountCents ELSE 0 END), 0) AS totalIncomeCents,
               COALESCE(SUM(CASE WHEN direction = 'EXPENSE' THEN amountCents ELSE 0 END), 0) AS totalExpenseCents,
               COUNT(*) AS txCount
        FROM transactions
        WHERE status != 'DELETED' AND occurredAt >= :start AND occurredAt < :end
        """
    )
    fun observeOverview(start: Long, end: Long): Flow<Overview>

    /** 分类改名/改方向后，统计会自动跟着变 —— 因为统计永远现算，不存快照 */
    @Query("UPDATE transactions SET categoryId = :categoryId, categoryLocked = 1 WHERE id = :id")
    suspend fun updateCategory(id: Long, categoryId: Long?)

    @Query("UPDATE transactions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /** 时间窗内同额同向的记录，供跨 App 去重与重复检测使用 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE status != 'DELETED' AND direction = :direction AND amountCents = :amountCents
          AND occurredAt BETWEEN :from AND :to
        """
    )
    suspend fun findDuplicates(direction: String, amountCents: Long, from: Long, to: Long): List<TransactionEntity>

    /**
     * 自某时刻起的净变化（收入 - 支出）。
     * 「当前全部身家」= 最近一次登记的家当 + 这个增量。
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN direction = 'INCOME' THEN amountCents ELSE -amountCents END), 0)
        FROM transactions
        WHERE status != 'DELETED' AND direction IN ('INCOME', 'EXPENSE') AND occurredAt > :since
        """
    )
    fun observeNetChangeSince(since: Long): Flow<Long>

    /** 从未登记过家当时的兜底：全部净额 */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN direction = 'INCOME' THEN amountCents ELSE -amountCents END), 0)
        FROM transactions
        WHERE status != 'DELETED' AND direction IN ('INCOME', 'EXPENSE')
        """
    )
    fun observeNetChangeAll(): Flow<Long>

    /** 区间净额（左开右闭），用于倒推任意时刻的余额 */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN direction = 'INCOME' THEN amountCents ELSE -amountCents END), 0)
        FROM transactions
        WHERE status != 'DELETED' AND direction IN ('INCOME', 'EXPENSE')
          AND occurredAt > :from AND occurredAt <= :to
        """
    )
    suspend fun netChangeBetween(from: Long, to: Long): Long

    /** 某区间的流水，按时间正序 —— 画曲线时逐笔累加 */
    @Query(
        """
        SELECT * FROM transactions
        WHERE status != 'DELETED' AND direction IN ('INCOME', 'EXPENSE')
          AND occurredAt >= :from AND occurredAt <= :to
        ORDER BY occurredAt ASC, id ASC
        """
    )
    suspend fun loadBetweenSync(from: Long, to: Long): List<TransactionEntity>

    /** 全量流水，供余额曲线做内存计算（数据量小，比逐点查库快得多） */
    @Query(
        """
        SELECT * FROM transactions
        WHERE status != 'DELETED' AND direction IN ('INCOME', 'EXPENSE')
        ORDER BY occurredAt ASC, id ASC
        """
    )
    suspend fun loadAllSync(): List<TransactionEntity>

    @Query("SELECT MIN(occurredAt) FROM transactions WHERE status != 'DELETED'")
    suspend fun earliestTxAt(): Long?

    @Query("SELECT MAX(amountCents) FROM transactions WHERE direction = 'EXPENSE' AND status != 'DELETED'")
    suspend fun maxExpenseCents(): Long?
}
