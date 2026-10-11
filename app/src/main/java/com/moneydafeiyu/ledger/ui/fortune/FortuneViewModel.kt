package com.moneydafeiyu.ledger.ui.fortune

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moneydafeiyu.ledger.MoneyDafeiyuApp
import com.moneydafeiyu.ledger.data.entity.CategoryEntity
import com.moneydafeiyu.ledger.data.model.Direction
import com.moneydafeiyu.ledger.data.repo.FortuneAdjustment
import com.moneydafeiyu.ledger.data.repo.FortuneState
import com.moneydafeiyu.ledger.data.repo.LedgerRepository.BalancePoint
import com.moneydafeiyu.ledger.data.repo.LedgerRepository.Granularity as RepoGranularity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FortuneViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as MoneyDafeiyuApp).repository

    val fortune: StateFlow<FortuneState?> = repo.observeFortune()
        .catch { emit(FortuneState.EMPTY) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val expenseCategories: StateFlow<List<CategoryEntity>> = repo.observeCategories(Direction.EXPENSE)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val incomeCategories: StateFlow<List<CategoryEntity>> = repo.observeCategories(Direction.INCOME)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 登记家当。adjustment 非空时，差额会作为一笔带分类的正式流水写进账本 */
    fun record(
        amountCents: Long,
        recordedAt: Long,
        note: String,
        adjustment: FortuneAdjustment?,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            repo.recordFortune(amountCents, recordedAt, note, adjustment)
            loadSeries(granularity)
            onDone()
        }
    }

    fun deleteSnapshot(id: Long) = viewModelScope.launch { repo.deleteSnapshot(id) }

    /** 供对账差额弹窗取某一方向的分类列表 */
    fun categoriesFor(direction: String) =
        if (direction == "INCOME") incomeCategories.value else expenseCategories.value

    // ---------------- 余额曲线 ----------------

    /** 当前粒度：当月每日 / 当年每月 / 每年 */
    var granularity by mutableStateOf(RepoGranularity.DAY_OF_MONTH)
        private set

    /**
     * 曲线看哪个指标。
     *
     * BALANCE（余额总量）是默认 —— 它回答"我现在有多少钱"，
     * 这是打开这一页最想知道的事。
     * DELTA（收支增减）回答的是"这段时间赚了/花了多少"。
     */
    enum class Metric { BALANCE, DELTA }

    var metric by mutableStateOf(Metric.BALANCE)
        private set

    private val _series = mutableStateOf<List<BalancePoint>>(emptyList())
    val series: List<BalancePoint> get() = _series.value

    var seriesLoading by mutableStateOf(false)
        private set

    init {
        loadSeries(RepoGranularity.DAY_OF_MONTH)
    }

    fun switchGranularity(g: RepoGranularity) {
        if (granularity == g) return
        granularity = g
        loadSeries(g)
    }

    fun switchMetric(m: Metric) {
        if (metric == m) return
        metric = m
        loadSeries(granularity)
    }

    fun loadSeries(g: RepoGranularity) {
        seriesLoading = true
        viewModelScope.launch {
            _series.value = runCatching {
                if (metric == Metric.BALANCE) repo.balanceSeries(g) else repo.deltaSeries(g)
            }.getOrDefault(emptyList())
            seriesLoading = false
        }
    }

    /** 曲线画完后顺手刷新一次，保证登记后立刻反映 */
    fun refreshSeries() = loadSeries(granularity)
}
