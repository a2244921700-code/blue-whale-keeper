package com.moneydafeiyu.ledger

import android.app.Application
import android.util.Log
import com.moneydafeiyu.ledger.data.DataSeeder
import com.moneydafeiyu.ledger.data.db.MoneyDatabase
import com.moneydafeiyu.ledger.data.repo.LedgerRepository
import com.moneydafeiyu.ledger.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MoneyDafeiyuApp : Application() {

    val repository: LedgerRepository by lazy { LedgerRepository(MoneyDatabase.get(this), this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)

        // 用户手机上最常见的问题是「系统勾了通知使用权却没真正绑定服务」。
        // 每次冷启动主动请求一次重绑，比让用户自己去设置里关开权限省事得多。
        appScope.launch {
            kotlinx.coroutines.delay(800)
            runCatching {
                com.moneydafeiyu.ledger.capture.CaptureDiagnostics.requestRebind(this@MoneyDafeiyuApp)
                Log.i("MoneyDafeiyu", "已请求系统重新绑定通知监听服务")
            }
        }

        // 预热：把建库与内置数据导入放到冷启动阶段完成，
        // 避免用户点进首页时才第一次开库，把风险暴露在 UI 路径上。
        appScope.launch {
            runCatching {
                val db = MoneyDatabase.get(this@MoneyDafeiyuApp)
                DataSeeder.seed(this@MoneyDafeiyuApp, db)
                // 上次抓到的证据如果还没解析（比如用户升级到了带解析器的版本），
                // 冷启动时自动补跑一遍，用户不用手动去点"重放"
                val pending = repository.unparsedEvidenceCount()
                if (pending > 0) {
                    val r = repository.replayEvidence()
                    Log.i("MoneyDafeiyu", "自动重放 $pending 条旧证据 → 新入账 ${r.recorded} 笔")
                }
                Log.i("MoneyDafeiyu", "启动初始化完成")
            }.onFailure {
                Log.e("MoneyDafeiyu", "启动初始化失败", it)
            }
        }
    }
}
