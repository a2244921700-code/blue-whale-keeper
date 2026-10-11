package com.moneydafeiyu.ledger.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自记录：把未捕获异常的完整堆栈写到私有目录，
 * App 下次启动时直接摊在界面上 —— 不用连电脑也能看到死因。
 */
object CrashLogger {

    private const val FILE_NAME = "last_crash.txt"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                file(app).writeText(
                    buildString {
                        appendLine("时间: $ts")
                        appendLine("线程: ${thread.name}")
                        appendLine("机型: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                        appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                        appendLine("App : ${app.packageName}")
                        appendLine()
                        appendLine(error.stackTraceToString())
                    }
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun readLast(context: Context): String? =
        file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
