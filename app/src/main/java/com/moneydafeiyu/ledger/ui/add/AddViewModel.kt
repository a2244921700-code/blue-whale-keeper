package com.moneydafeiyu.ledger.ui.add

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moneydafeiyu.ledger.MoneyDafeiyuApp
import com.moneydafeiyu.ledger.data.entity.CategoryEntity
import com.moneydafeiyu.ledger.data.entity.TransactionEntity
import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.data.model.SourceKey
import com.moneydafeiyu.ledger.data.model.TxStatus
import com.moneydafeiyu.ledger.util.toYuan
import com.moneydafeiyu.ledger.util.yuanToCents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AddViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as MoneyDafeiyuApp).repository

    var direction by mutableStateOf(Direction.EXPENSE)
        private set
    var amountText by mutableStateOf("")
        private set
    var merchant by mutableStateOf("")
    var note by mutableStateOf("")
    var categoryId by mutableStateOf<Long?>(null)
        private set

    /** 手动记账可以补记过去的账，所以时间必须可改 */
    var occurredAt by mutableStateOf(System.currentTimeMillis())
        private set

    fun updateOccurredAt(ts: Long) {
        occurredAt = ts
    }

    private val dirFlow = MutableStateFlow(Direction.EXPENSE)

    val categories: StateFlow<List<CategoryEntity>> = dirFlow
        .flatMapLatest { repo.observeCategories(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun switchDirection(d: String) {
        if (direction == d) return
        direction = d
        categoryId = null
        dirFlow.value = d
    }

    fun press(key: String) {
        when (key) {
            "⌫" -> if (amountText.isNotEmpty()) amountText = amountText.dropLast(1)
            "." -> if (!amountText.contains('.') && amountText.isNotEmpty()) amountText += "."
            else -> {
                if (amountText.contains('.') && amountText.substringAfter('.').length >= 2) return
                if (amountText.length >= 10) return
                amountText = if (amountText == "0") key else amountText + key
            }
        }
    }

    fun selectCategory(id: Long) {
        categoryId = if (categoryId == id) null else id
    }

    val amountCents: Long get() = amountText.yuanToCents() ?: 0L

    fun save(onDone: () -> Unit) {
        val cents = amountCents
        if (cents <= 0) return
        viewModelScope.launch {
            repo.addTransaction(
                TransactionEntity(
                    amountCents = cents,
                    direction = direction,
                    merchant = merchant.trim(),
                    categoryId = categoryId,
                    note = note.trim(),
                    occurredAt = occurredAt,
                    status = TxStatus.CONFIRMED,
                    confidence = 1.0,
                    sourceKey = SourceKey.MANUAL,
                    categoryLocked = categoryId != null,
                )
            )
            // 手动记完也让鲸鱼娘出来说句话
            runCatching {
                // 必须传用户实际选中的分类名，否则手动记账会永远落到「脑袋空空」兜底图上
                val pickedCategoryName = categories.value.firstOrNull { it.id == categoryId }?.name
                val rule = if (direction == Direction.INCOME) {
                    com.moneydafeiyu.ledger.feedback.FeedbackEngine.pickManualIncome(getApplication())
                } else {
                    com.moneydafeiyu.ledger.feedback.FeedbackEngine.pick(
                        context = getApplication(),
                        amountCents = cents,
                        direction = direction,
                        merchant = merchant.trim(),
                        categoryName = pickedCategoryName,
                        occurredAt = occurredAt,
                    )
                }
                if (rule != null) {
                    com.moneydafeiyu.ledger.feedback.FeedbackOverlay.show(
                        context = getApplication(),
                        rule = rule,
                        amountText = (if (direction == Direction.INCOME) "+¥" else "-¥") + cents.toYuan(),
                        subtitle = "手动记账",
                    )
                }
            }
            onDone()
        }
    }
}
