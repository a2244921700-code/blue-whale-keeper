package com.moneydafeiyu.ledger.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.moneydafeiyu.ledger.data.entity.FeedbackRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedbackRuleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<FeedbackRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: FeedbackRuleEntity): Long

    @Query("SELECT * FROM feedback_rules WHERE code = :code LIMIT 1")
    suspend fun findByCode(code: String): FeedbackRuleEntity?

    @Update
    suspend fun update(rule: FeedbackRuleEntity)

    /** 按优先级升序取全部启用规则，第一条命中即用 */
    @Query("SELECT * FROM feedback_rules WHERE enabled = 1 ORDER BY priority, id")
    fun observeEnabled(): Flow<List<FeedbackRuleEntity>>

    @Query("SELECT * FROM feedback_rules WHERE enabled = 1 ORDER BY priority, id")
    suspend fun loadEnabled(): List<FeedbackRuleEntity>

    @Query("SELECT * FROM feedback_rules ORDER BY priority, id")
    suspend fun loadAll(): List<FeedbackRuleEntity>

    @Query("SELECT * FROM feedback_rules ORDER BY priority, id")
    fun observeAll(): Flow<List<FeedbackRuleEntity>>

    @Query("SELECT COUNT(*) FROM feedback_rules")
    suspend fun count(): Int
}
