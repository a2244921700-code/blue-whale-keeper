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

        /** 读屏未命中的日志节流 —— 页面滚动会疯狂触发事件，不节流会刷爆日志 */
        private const val MISS_LOG_THROTTLE_MS = 5_000L
        @Volatile
        private var lastMissLogAt: Long = 0L

        private val PROBE_DELAYS = longArrayOf(120L, 380L, 900L)
        private val OCR_DELAYS = longArrayOf(1200L, 3200L)

        /** OCR 节流：12 秒内最多识别一次 */
        private const val OCR_THROTTLE_MS = 12_000L

        /** 转账待接收页 —— 钱还没到，但值得记一笔待确认 */
        private val TRANSFER_PAGE_MARKERS = listOf(
            "向你转账", "向我转账", "向您转账", "转账给你", "待接收", "请及时查收",
        )

        private val SUCCESS_MARKERS = listOf(
            "支付成功", "付款成功", "交易成功", "已付款", "支付完成", "付款完成",
            "收款成功", "到账成功", "退款成功", "支付结果", "交易结果",
            // 下面这些是补充的 —— 原来的列表漏掉了大量真实文案。
            // 尤其「已支付」：微信付款码被扫之后显示的就是这句，
            // 而它不在原列表里，导致整个场景采不到。
            "已支付", "已成功支付", "扣款成功", "已扣款", "付款已成功",
            "订单已支付", "已支付成功", "支付已成功", "付款金额", "支付金额",
            "交易已完成", "支付完成度", "恭喜付款", "支付凭证",
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

        /**
         * 微信「支付结果页」的类名特征。
         *
         * 这是识别微信支付的**主判据**，而不是页面上写了什么字。
         *
         * 想清楚一件事：微信的支付入口五花八门 ——
         *   出示付款码被扫 / 主动扫码 / 小程序内付款 / 游戏内购 / 公众号支付 …
         * 但它们**全都会收敛到同一个支付完成页**。
         * 所以与其枚举「支付成功」「已支付」「付款成功」这些永远列不全的文案，
         * 不如直接认这个页面本身。文案会随版本改，类名稳定得多。
         */
        /**
         * 余额页 / 零钱页的特征词。
         *
         * 这类页面同样「只有金额」，但它们**不是交易** —— 记进去会让账本凭空多一笔。
         * 判据取「同时命中两个以上」：支付结果页也可能带「零钱」当付款方式，
         * 单个词不足以定性。
         */
        private val BALANCE_PAGE_MARKERS = listOf(
            "零钱", "余额", "充值", "提现", "银行卡", "钱包", "总资产", "账单",
        )

        private val WECHAT_RESULT_CLASSES = listOf(
            "walletpay",        // WalletPayUI / WalletPayResultUI —— 付款与结果页
            "payresult",        // 支付结果
            "mallwalletpay",    // 商城/小程序内支付
            "wxpayentry",       // 支付入口
            "remittance",       // 转账 RemittanceResultUI
            "payorderdetail",   // 订单详情
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

        // ---- 前置闸门：这个 App 必须真的在前台 ----
        //
        // 踩过的坑：用户下拉通知栏时，因为通知栏里有微信的消息，
        // 无障碍事件的 packageName 被算成了 com.tencent.mm，
        // 但当前真正在前台的是 SystemUI —— 于是读屏读回来一堆
        // 「WLAN 信号强度满格」「电量剩余 74」这种状态栏文字，
        // 既白费开销，又把调试台刷得没法看。
        //
        // rootInActiveWindow 读的是"此刻屏幕上真正显示的是谁"，
        // 用它和事件包名对一下就能挡住这类误触发。
        // 这里刻意**不写日志** —— 这不是异常情况，是正常现象，
        // 记下来只会淹没真正有用的信息。
        val foregroundPkg = rootInActiveWindow?.packageName?.toString()
        if (foregroundPkg != pkg) return

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

        // 微信走专用判定：认「支付结果页」这个页面本身，
        // 而不是去猜页面上写了哪句成功文案。
        // 付款码/扫码/小程序/游戏内购最终都会落到这个页面上。
        val hit = if (sourceKey == SourceKey.WECHAT) {
            text.length >= 4 && isWechatPayResultPage(className, text)
        } else {
            text.length >= 6 && looksLikePaymentResult(text)
        }

        if (hit) {
            lastProbeAt = System.currentTimeMillis()
            val how = if (sourceKey == SourceKey.WECHAT) "微信支付结果页" else "支付结果页"
            CaptureEventLog.record(pkg, true, "读屏命中" + how + "（" + className + "）", text)
            submit(sourceKey, pkg, text, Channel.ACCESSIBILITY)
            return
        }

        // ---- 没命中时也要留痕，否则这条通道就是个黑盒 ----
        //
        // 之前只有成功才写日志，于是「无障碍到底有没有被触发」根本看不出来。
        // 用户反馈付款码/小程序场景采不到时，完全无从判断是
        // 「服务没收到事件」还是「收到了但页面不像支付结果」。
        //
        // 节流 5 秒：页面滚动会疯狂触发事件，不节流会把日志刷爆。
        val now = System.currentTimeMillis()
        if (now - lastMissLogAt > MISS_LOG_THROTTLE_MS) {
            lastMissLogAt = now
            val why = when {
                text.isEmpty() -> "读屏没读到任何文本（自绘页面？）类名=" + className
                text.length < 6 -> "读屏只读到 $text.length 个字，太少"
                FAILURE_MARKERS.any { text.contains(it) } -> "页面显示支付失败"
                BROWSING_MARKERS.any { text.contains(it) } -> "页面像账单浏览页，不是新交易"
                else -> "页面没有支付成功字样（文案没被收录？）类名=" + className
            }
            CaptureEventLog.record(pkg, false, "读屏未命中：$why", text)
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

    /**
     * 判断这一屏是不是「支付结果页」。
     *
     * ★ 判定顺序很关键，这里踩过一个真实的坑：
     *
     * 原来的顺序是「先排除浏览页，再看成功标记」。但**支付成功页上同样有
     * 交易单号、商户单号**这些字样（它们是订单详情的一部分），
     * 于是真实的支付成功页被 BROWSING_MARKERS 一票否决 ——
     * 表现为付款码被扫、小程序内付款这些场景**完全采集不到**。
     *
     * 改成成功标记优先：只要看到明确的成功文案就直接认，
     * 浏览页规则只在**没有成功标记**时才用来排除（那才是用户主动翻旧账的场景）。
     */
    /**
     * 微信支付结果页判定 —— 类名优先，页面结构兜底。
     *
     * 为什么不只靠文案：支付页的提示语每次改版都可能变，
     * 而「金额 + 收款方」这个信息结构是稳定的 ——
     * 用户想知道自己付了多少钱给谁，这个页面就必须同时给出这两样。
     */
    private fun isWechatPayResultPage(className: String, text: String): Boolean {
        val lower = className.lowercase()

        // ① 类名命中 —— 最可靠，直接认
        if (WECHAT_RESULT_CLASSES.any { lower.contains(it) }) return true

        val hasAmount = Regex("[¥￥]\\s*[0-9]").containsMatchIn(text) ||
            Regex("[0-9]+\\.[0-9]{2}\\s*元").containsMatchIn(text)
        val hasPayee = text.contains("收款方") || text.contains("商户") ||
            text.contains("付款给") || text.contains("支付给") || text.contains("收款人")

        // ② 金额 + 收款方齐了 —— 强信号
        if (hasAmount && hasPayee) return true

        // ③ 只有金额也认。
        //
        // 有些付款弹窗确实只显示一个金额（小额免密、部分内购弹窗），
        // 硬要求"必须有收款方"会把它们全漏掉。
        //
        // 放宽是安全的，因为：**没有商户名 → 分类无法确定 → 系统自动挂【待确认】**。
        // 也就是说最坏情况是多一条待确认记录让用户拍板，而不是把噪音写进账本。
        //
        // 但要挡住余额页/零钱页这类同样"只有金额"的页面 ——
        // 它们才是真正会污染账本的东西。判据是**同时出现两个以上余额特征词**，
        // 单个词不算（支付结果页也可能带"零钱"作为付款方式）。
        if (hasAmount) {
            val balanceSignals = BALANCE_PAGE_MARKERS.count { text.contains(it) }
            if (balanceSignals >= 2) return false   // 余额页/零钱页，明确排除

            // 类名像支付 —— 高置信，认
            val payishClass = lower.contains("pay") || lower.contains("wallet") ||
                lower.contains("dialog") || lower.contains("popup")
            if (payishClass) return true

            // 类名是通用容器（android.widget.FrameLayout 之类）也认 ——
            // 因为**弹窗的根容器经常就是它**，付款弹窗不会有什么特别的类名。
            //
            // 靠"文本很短"来区分：支付弹窗就那么几个字（金额 + 完成按钮），
            // 而账单页、商品页、聊天记录都会铺满一屏。
            if (text.replace(Regex("\\s+"), "").length <= 30) return true
        }

        return false
    }

    private fun looksLikePaymentResult(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        if (FAILURE_MARKERS.any { compact.contains(it) }) return false
        // 成功标记优先 —— 支付成功页也会带单号，不能被浏览页规则误杀
        if (SUCCESS_MARKERS.any { compact.contains(it) }) return true
        // 转账待接收页：页面上写着「张某某向你转账 ¥100.00 [接收][退还]」。
        // 它没有"支付成功"字样，但确实是一笔值得记录的交易（先挂待确认）。
        if (TRANSFER_PAGE_MARKERS.any { compact.contains(it) }) return true
        // 没有成功标记，又像账单浏览页 → 是用户在看历史，不是新交易
        if (BROWSING_MARKERS.any { compact.contains(it) }) return false
        return false
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
