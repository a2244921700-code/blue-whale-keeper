package com.moneydafeiyu.ledger.ui.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moneydafeiyu.ledger.MoneyDafeiyuApp
import com.moneydafeiyu.ledger.data.db.MoneyDatabase
import com.moneydafeiyu.ledger.data.entity.CategoryEntity
import com.moneydafeiyu.ledger.data.entity.CategoryKeywordEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 规则编辑器。
 *
 * 这是整个 App 里唯一"用户能直接改变识别行为"的地方 ——
 * 词库改了，下一笔支付就按新规则分类，不需要更新 App。
 * 改完必须让分类缓存失效，否则内存里那份旧词表会继续生效。
 */
class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as MoneyDafeiyuApp).repository
    private val db = MoneyDatabase.get(app)

    val categories: StateFlow<List<CategoryEntity>> = repo.observeAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _keywords = MutableStateFlow<List<CategoryKeywordEntity>>(emptyList())
    val keywords: StateFlow<List<CategoryKeywordEntity>> = _keywords.asStateFlow()

    private val _keywordCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val keywordCounts: StateFlow<Map<Long, Int>> = _keywordCounts.asStateFlow()

    private val _editing = MutableStateFlow<CategoryEntity?>(null)
    val editing: StateFlow<CategoryEntity?> = _editing.asStateFlow()

    init {
        refreshCounts()
    }

    fun refreshCounts() {
        viewModelScope.launch {
            val map = HashMap<Long, Int>()
            for (c in db.categoryDao().loadAll()) map[c.id] = db.categoryDao().countKeywords(c.id)
            _keywordCounts.value = map
        }
    }

    fun openCategory(c: CategoryEntity) {
        _editing.value = c
        viewModelScope.launch { _keywords.value = db.categoryDao().keywordsOfCategory(c.id) }
    }

    fun closeCategory() {
        _editing.value = null
        _keywords.value = emptyList()
    }

    fun addKeyword(word: String, tier: String) {
        val cat = _editing.value ?: return
        val w = word.trim()
        if (w.isEmpty()) return
        viewModelScope.launch {
            db.categoryDao().insertKeyword(CategoryKeywordEntity(categoryId = cat.id, word = w, tier = tier))
            _keywords.value = db.categoryDao().keywordsOfCategory(cat.id)
            repo.invalidateClassifier()
            refreshCounts()
        }
    }

    fun deleteKeyword(k: CategoryKeywordEntity) {
        val cat = _editing.value ?: return
        viewModelScope.launch {
            db.categoryDao().deleteKeyword(k.id)
            _keywords.value = db.categoryDao().keywordsOfCategory(cat.id)
            repo.invalidateClassifier()
            refreshCounts()
        }
    }

    fun toggleKeyword(k: CategoryKeywordEntity) {
        val cat = _editing.value ?: return
        viewModelScope.launch {
            db.categoryDao().setKeywordEnabled(k.id, !k.enabled)
            _keywords.value = db.categoryDao().keywordsOfCategory(cat.id)
            repo.invalidateClassifier()
        }
    }

    /** 新增分类：以后有新的消费类型，你自己加就行，不用等我改代码 */
    fun addCategory(name: String, direction: String, icon: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch {
            db.categoryDao().insert(
                CategoryEntity(
                    name = n,
                    direction = direction,
                    icon = icon.ifBlank { "🏷" },
                    color = "#78909C",
                    sortOrder = 60,
                    isSystem = false,
                )
            )
            refreshCounts()
        }
    }

    /**
     * 删除分类。底下有交易时**不允许删** ——
     * 账目比分类重要，硬删会让那些笔变成"未分类"，用户回头根本不知道少了什么。
     */
    fun deleteCategory(c: CategoryEntity, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val used = db.categoryDao().transactionCountOf(c.id)
            if (used > 0) { onResult(false); return@launch }
            db.categoryDao().deleteKeywordsOf(c.id)
            db.categoryDao().deleteCategory(c.id)
            repo.invalidateClassifier()
            refreshCounts()
            onResult(true)
        }
    }
}
