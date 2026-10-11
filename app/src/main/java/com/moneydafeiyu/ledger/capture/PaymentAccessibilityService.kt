package com.moneydafeiyu.ledger.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.moneydafeiyu.ledger.callguard.CallScreenWatcher
import com.moneydafeiyu.ledger.data.model.Channel
import com.moneydafeiyu.ledger.data.model.SourceKey
import com.moneydafeiyu.ledger.data.model.SourcePackages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * 无障碍 + 截图 OCR 采集通道。
 *
 * 两条路配合，缺一不可：
 *   1. **无障碍读节点** —— 拿商户名的主力，大多数支付结果页都能读到；
 *   2. **截图 OCR** —— 兜底。微信支付页有可能是自绘的、拿不到文字节点，
 *      这时只能截图做离线识别（ML Kit 中文模型已打包进 APK，不依赖 GMS）。
 *
 * 真机经验（照抄参考项目的结论，别重新踩）：
 * - 支付结果页**异步渲染**，单次读取十有八九读到半截 → 120/380/900ms 三次探测；
 * - OCR 代价高（截图 + 识别要几百毫秒），所以要**严格节流**：12 秒内只做一次，
 *   而且只在微信上做；
 * - 事件回调极频繁，同一 App 2 秒内只探一次，否则手机立刻变卡。
 */
class PaymentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PaymentA11y"

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var lastProbeAt: Long = 0L
            private set

        @Volatile
        var lastOcrAt: Long = 0L
            private set

        private val PROBE_DELAYS = longArrayOf(120L, 380L, 900L)
        private val OCR_DELAYS = longArrayOf(1200L, 3200L)

        /** OCR 节流：12 秒内最多识别一次 */
        private const val OCR_THROTTLE_MS = 12_000L

        private val SUCCESS_MARKERS = listOf(
            "支付成功", "付款成功", "交易成功", "已付款", "支付完成", "付款完成",
            "收款成功", "到账成功", "退款成功", "支付结果", "交易结果",
        )

        private val FAILURE_MARKERS = listOf(
            "支付失败", "付款失败", "交易失败", "支付已取消", "付款已取消", "已关闭",
        )

        private val BROWSING_MARKERS = listOf(
            "交易详情", "账单详情", "交易单号", "商户单号", "全部会话", "消息盒子",
            "使用零钱支付", "使用零钱通支付", "点击查看全部消息",
        )

        /** 微信支付页的类名特征 —— 决定要不要动用 OCR 这张重牌 */
        private val WECHAT_PAY_CLASS_MARKERS = listOf(
            "walletpay", "mallwalletpay", "payresult", "wxpayentry", "remittance", "transfer",
        )

        private val WECHAT_PAY_HINTS = listOf("微信支付", "支付", "付款", "收款", "转账")

        /**
         * 事件预筛词。
         *
         * 用途：**在遍历无障碍节点树之前**先拿事件自带的文本做一次廉价判断。
         *
         * 为什么这一步最值钱：event.text 是系统**已经提取好**的字符串，
         * 读它几乎不花 CPU；而 collectVisibleText() 要递归遍历整棵树（深度 12、上限 400 个节点）。
         * 淘宝列表滑一下、支付宝页面刷新一次都会触发 contentChanged 事件，
         * 这些事件里绝大多数跟支付毫无关系 —— 先筛掉它们，能省掉绝大部分开销。
         *
         * 词表刻意做得**宽**：宁可多读几次树，也不能漏掉真实支付。
         */
        private val QUICK_HINTS = listOf(
            "支付", "付款", "收款", "转账", "红包", "消费", "扣款", "到账", "退款",
            "交易", "账单", "已付", "支出", "收入", "缴费", "充值", "订单",
            "余额", "零钱", "花呗", "白条", "钱包", "成功", "完成",
            "¥", "￥", "元",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val recentProbes: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** 通话防护：读屏判断来电显示名 */
    private val callWatcher by lazy { CallScreenWatcher(this) }

    private var textRecognizer: TextRecognizer? = null
    private var ocrInFlight = false
    private var ocrSucceeded = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        // 顺带把通话防护的响铃监听挂上
        runCatching { callWatcher.start() }
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        isConnected = false
        main.removeCallbacksAndMessages(null)
        runCatching { textRecognizer?.close() }
        textRecognizer = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return

        // 电话应用走通话防护那条路：读屏 → 匹配关键词 → 挂断。
        // 放在最前面，因为它和支付采集完全无关。
        if (CallScreenWatcher.isPhonePackage(pkg)) {
            val screenText = collectVisibleText()
            if (screenText.isNotBlank()) runCatching { callWatcher.onScreenText(screenText) }
            return
        }

        val sourceKey = SourcePackages.of(pkg) ?: return

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> Unit
            else -> return
        }

        // ---- 事件文本预筛（省电的关键一步）----
        //
        // event.text 是系统已经提取好的，读它零成本；
        // 而下面 collectVisibleText() 要递归整棵节点树，代价高得多。
        //
        // 所以：**先看事件自带的文本像不像支付**，不像就直接返回，树都不碰。
        //
        // 唯一例外是「事件文本为空」—— 那是窗口切换事件的常态，
        // 内容还没渲染出来，必须读树才知道有什么，所以放行。
        val quickText = (
            e.text.joinToString(" ") + " " +
                (e.contentDescription?.toString() ?: "")
            ).replace(Regex("\\s+"), " ").trim()

        if (quickText.isNotEmpty() && !QUICK_HINTS.any { quickText.contains(it) }) {
            return
        }

        val key = pkg + "|" + (System.currentTimeMillis() / 2000)
        if (!recentProbes.add(key)) return
        main.postDelayed({ recentProbes.remove(key) }, 3000)

        // 主路：读节点
        for (delay in PROBE_DELAYS) {
            main.postDelayed({ probe(sourceKey, pkg, e.className?.toString().orEmpty()) }, delay)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun probe(sourceKey: String, pkg: String, className: String) {
        val text = collectVisibleText()
        if (text.length >= 6 && looksLikePaymentResult(text)) {
            lastProbeAt = System.currentTimeMillis()
            submit(sourceKey, pkg, text, Channel.ACCESSIBILITY)
            return
        }
        // 主路没读到内容 —— 可能是自绘页面，考虑动用 OCR 兜底
        maybeOcr(sourceKey, pkg, className, text)
    }

    /** 微信之类的自绘页面拿不到节点时，截屏做离线识别 */
    private fun maybeOcr(sourceKey: String, pkg: String, className: String, visible: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // OCR 只给微信留着：其他 App 的通知通道已经够用，没必要付出截图的代价
        if (sourceKey != SourceKey.WECHAT) return
        if (!looksLikeWechatPayPage(className, visible)) return

        val now = System.currentTimeMillis()
        if (now - lastOcrAt < OCR_THROTTLE_MS) return
        lastOcrAt = now
        ocrSucceeded = false

        for (delay in OCR_DELAYS) {
            main.postDelayed({ if (!ocrSucceeded) takeScreenshotAndRecognize(pkg) }, delay)
        }
    }

    private fun looksLikeWechatPayPage(className: String, visible: String): Boolean {
        val compact = visible.replace(Regex("\\s+"), "")
        if (BROWSING_MARKERS.any { compact.contains(it) }) return false
        if (compact.contains("微信支付")) return true
        val lower = className.lowercase()
        val payClass = WECHAT_PAY_CLASS_MARKERS.any { lower.contains(it) }
        // 类名像支付页，且页面上有支付相关字样
        return payClass && (compact.isEmpty() || WECHAT_PAY_HINTS.any { compact.contains(it) })
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotAndRecognize(pkg: String) {
        if (ocrInFlight) return
        ocrInFlight = true
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    handleScreenshot(result, pkg)
                }

                override fun onFailure(errorCode: Int) {
                    ocrInFlight = false
                    Log.w(TAG, "截屏失败 code=$errorCode")
                }
            })
        }.onFailure {
            ocrInFlight = false
            Log.e(TAG, "截屏异常", it)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun handleScreenshot(result: AccessibilityService.ScreenshotResult, pkg: String) {
        val bitmap: Bitmap? = runCatching {
            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            result.hardwareBuffer.close()
            hw?.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()

        if (bitmap == null) {
            ocrInFlight = false
            return
        }

        val recognizer = textRecognizer ?: TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        ).also { textRecognizer = it }

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                val raw = visionText.text.trim()
                runCatching { bitmap.recycle() }
                ocrInFlight = false
                if (raw.length >= 6 && looksLikePaymentResult(raw)) {
                    ocrSucceeded = true
                    Log.i(TAG, "OCR 命中支付结果页，识别到 ${raw.length} 字")
                    submit(SourceKey.WECHAT, pkg, raw, Channel.OCR)
                }
            }
            .addOnFailureListener {
                runCatching { bitmap.recycle() }
                ocrInFlight = false
                Log.e(TAG, "OCR 识别失败", it)
            }
    }

    private fun submit(sourceKey: String, pkg: String, text: String, channel: String) {
        scope.launch {
            runCatching {
                val evidenceId = EvidenceIngest.submit(
                    context = applicationContext,
                    channel = channel,
                    sourceKey = sourceKey,
                    packageName = pkg,
                    title = "",
                    rawText = text,
                    capturedAt = System.currentTimeMillis(),
                )
                if (evidenceId > 0) {
                    IngestPipeline.process(
                        context = applicationContext,
                        evidenceId = evidenceId,
                        sourceKey = sourceKey,
                        rawText = text,
                        occurredAt = System.currentTimeMillis(),
                        channel = channel,
                    )
                }
            }.onFailure { Log.e(TAG, "证据处理失败", it) }
        }
    }

    private fun looksLikePaymentResult(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        if (FAILURE_MARKERS.any { compact.contains(it) }) return false
        if (BROWSING_MARKERS.any { compact.contains(it) }) return false
        return SUCCESS_MARKERS.any { compact.contains(it) }
    }

    private fun collectVisibleText(): String {
        val texts = LinkedHashSet<String>()
        rootInActiveWindow?.let { root ->
            collect(root, texts, 0)
            @Suppress("DEPRECATION") runCatching { root.recycle() }
        }
        runCatching {
            windows?.forEach { w ->
                w?.root?.let { r ->
                    collect(r, texts, 0)
                    @Suppress("DEPRECATION") runCatching { r.recycle() }
                }
            }
        }
        return texts.joinToString(" ")
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableSet<String>, depth: Int) {
        if (node == null || depth > 12 || out.size >= 400) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }
}
