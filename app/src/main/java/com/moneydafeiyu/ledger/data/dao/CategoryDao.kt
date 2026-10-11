package com.moneydafeiyu.ledger.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.moneydafeiyu.ledger.data.entity.CategoryEntity
import com.moneydafeiyu.ledger.data.entity.CategoryKeywordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    // ---- 分类 ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<CategoryEntity>): List<Long>

    @Update
    suspend fun update(c: CategoryEntity)

    @Query("SELECT * FROM categories WHERE direction = :direction ORDER BY sortOrder, id")
    fun observeByDirection(direction: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY direction, sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY direction, sortOrder, id")
    suspend fun loadAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE name = :name AND direction = :direction LIMIT 1")
    suspend fun findByName(name: String, direction: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("DELETE FROM category_keywords WHERE categoryId = :id")
    suspend fun deleteKeywordsOf(id: Long)

    /** 删分类时把相关交易置为未分类，绝不能连带删掉账目 */
    @Query("UPDATE transactions SET categoryId = NULL WHERE categoryId = :id")
    suspend fun detachTransactions(id: Long)

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :id AND status != 'DELETED'")
    suspend fun transactionCountOf(id: Long): Int

    @Query("SELECT * FROM category_keywords WHERE categoryId = :categoryId ORDER BY tier, word")
    suspend fun keywordsOfCategory(categoryId: Long): List<CategoryKeywordEntity>

    // ---- 关键词 ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKeyword(k: CategoryKeywordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKeywords(list: List<CategoryKeywordEntity>)

    @Query("SELECT * FROM category_keywords WHERE categoryId = :categoryId ORDER BY tier, word")
    fun observeKeywords(categoryId: Long): Flow<List<CategoryKeywordEntity>>

    @Query("SELECT * FROM category_keywords WHERE categoryId = :categoryId AND tier = :tier")
    suspend fun keywordsOf(categoryId: Long, tier: String): List<CategoryKeywordEntity>

    @Query("SELECT COUNT(*) FROM category_keywords")
    suspend fun keywordCount(): Int

    /** 某个分类已有多少关键词 —— 增量导入时用它判断"这个分类补过没有" */
    @Query("SELECT COUNT(*) FROM category_keywords WHERE categoryId = :categoryId")
    suspend fun countKeywords(categoryId: Long): Int

    @Query("DELETE FROM category_keywords WHERE id = :id")
    suspend fun deleteKeyword(id: Long)

    @Query("UPDATE category_keywords SET enabled = :enabled WHERE id = :id")
    suspend fun setKeywordEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE category_keywords SET hitCount = hitCount + 1 WHERE id = :id")
    suspend fun bumpHit(id: Long)
}
