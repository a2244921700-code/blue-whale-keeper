package com.moneydafeiyu.ledger.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moneydafeiyu.ledger.MoneyDafeiyuApp
import com.moneydafeiyu.ledger.data.repo.FortuneState
import com.moneydafeiyu.ledger.data.repo.MonthSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as MoneyDafeiyuApp).repository

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month

    val snapshot: StateFlow<MonthSnapshot?> = _month
        .flatMapLatest { repo.observeMonth(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val fortune: StateFlow<FortuneState?> = repo.observeFortune()
        .catch { emit(FortuneState.EMPTY) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pendingCount: StateFlow<Int> = repo.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun prevMonth() {
        _month.value = _month.value.minusMonths(1)
    }

    fun nextMonth() {
        val next = _month.value.plusMonths(1)
        if (!next.isAfter(YearMonth.now())) _month.value = next
    }

    fun deleteTransaction(id: Long) = viewModelScope.launch { repo.softDelete(id) }

    fun changeCategory(txId: Long, categoryId: Long?) =
        viewModelScope.launch { repo.setCategory(txId, categoryId) }

    fun confirmTransaction(id: Long) = viewModelScope.launch {
        repo.setStatus(id, com.moneydafeiyu.ledger.data.model.TxStatus.CONFIRMED)
    }
}
