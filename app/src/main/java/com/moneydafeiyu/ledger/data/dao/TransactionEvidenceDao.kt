package com.moneydafeiyu.ledger.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.moneydafeiyu.ledger.data.entity.TransactionEvidenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionEvidenceDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: TransactionEvidenceEntity)

    @Query("SELECT evidenceId FROM transaction_evidence WHERE transactionId = :txId")
    suspend fun evidenceIdsOf(txId: Long): List<Long>

    @Query("SELECT transactionId FROM transaction_evidence WHERE evidenceId = :evidenceId")
    suspend fun transactionsOf(evidenceId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM transaction_evidence WHERE transactionId = :txId")
    fun observeEvidenceCount(txId: Long): Flow<Int>
}
