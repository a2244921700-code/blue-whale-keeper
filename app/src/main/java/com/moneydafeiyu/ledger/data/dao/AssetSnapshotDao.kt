package com.moneydafeiyu.ledger.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.moneydafeiyu.ledger.data.entity.AssetSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetSnapshotDao {

    @Insert
    suspend fun insert(s: AssetSnapshotEntity): Long

    @Query("SELECT * FROM asset_snapshots ORDER BY recordedAt DESC, id DESC")
    fun observeAll(): Flow<List<AssetSnapshotEntity>>

    /** 最近一次登记 —— 当前身家的计算基准 */
    @Query("SELECT * FROM asset_snapshots ORDER BY recordedAt DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<AssetSnapshotEntity?>

    @Query("SELECT * FROM asset_snapshots ORDER BY recordedAt DESC, id DESC LIMIT 1")
    suspend fun latest(): AssetSnapshotEntity?

    @Query("SELECT COUNT(*) FROM asset_snapshots")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM asset_snapshots WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM asset_snapshots WHERE id = :id")
    suspend fun findById(id: Long): AssetSnapshotEntity?

    /** 某个时刻**之前**最近的一次登记 —— 画历史余额曲线时的计算基准 */
    @Query("SELECT * FROM asset_snapshots WHERE recordedAt <= :time ORDER BY recordedAt DESC, id DESC LIMIT 1")
    suspend fun latestBefore(time: Long): AssetSnapshotEntity?

    /** 某个时刻**之后**最早的一次登记 —— 用于从未来倒推过去的余额 */
    @Query("SELECT * FROM asset_snapshots WHERE recordedAt > :time ORDER BY recordedAt ASC, id ASC LIMIT 1")
    suspend fun latestAfter(time: Long): AssetSnapshotEntity?

    @Query("SELECT * FROM asset_snapshots ORDER BY recordedAt ASC, id ASC")
    suspend fun loadAllAsc(): List<AssetSnapshotEntity>
}
