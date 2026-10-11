package com.moneydafeiyu.ledger.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.moneydafeiyu.ledger.data.entity.RuleProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleProfileDao {

    @Insert
    suspend fun insert(p: RuleProfileEntity): Long

    @Query("SELECT * FROM rule_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun active(): RuleProfileEntity?

    @Query("SELECT * FROM rule_profiles WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<RuleProfileEntity?>

    @Query("SELECT * FROM rule_profiles ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<RuleProfileEntity>>

    @Query("SELECT COALESCE(MAX(version), 0) FROM rule_profiles WHERE name = :name")
    suspend fun maxVersion(name: String): Int

    @Query("UPDATE rule_profiles SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE rule_profiles SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)

    @Query("SELECT * FROM rule_profiles WHERE id = :id")
    suspend fun findById(id: Long): RuleProfileEntity?
}
