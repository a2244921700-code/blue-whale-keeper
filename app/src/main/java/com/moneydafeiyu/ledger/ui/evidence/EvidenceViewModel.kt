package com.moneydafeiyu.ledger.ui.evidence

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moneydafeiyu.ledger.MoneyDafeiyuApp
import com.moneydafeiyu.ledger.data.entity.RawEvidenceEntity
import com.moneydafeiyu.ledger.data.repo.ReplayResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EvidenceViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as MoneyDafeiyuApp).repository

    val recent: StateFlow<List<RawEvidenceEntity>> = repo.observeRecentEvidence()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var replaying by mutableStateOf(false)
        private set
    var lastReplay by mutableStateOf<ReplayResult?>(null)
        private set

    /** 拿库里已有的原文重跑一遍解析规则 */
    fun replayAll() {
        if (replaying) return
        replaying = true
        viewModelScope.launch {
            lastReplay = runCatching { repo.replayEvidence() }.getOrNull()
            replaying = false
        }
    }
}
