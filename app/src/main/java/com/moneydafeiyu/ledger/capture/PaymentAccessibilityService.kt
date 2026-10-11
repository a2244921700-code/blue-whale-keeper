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

        /**
         * 触发 OCR 后等待多久再截图。
         *
         * 页面刚切换的那一瞬往往还在动画/渲染中，立刻截很容易截到上一页。
         * 实测就截到过聊天列表 —— 里面碰巧有条「已支付¥0.01」的消息预览，
         * 结果被当成真账单记了下来。
         */
        private const val OCR_CAPTURE_DELAY_MS = 400L


        // ---- 当前页面的结构指纹缓存 ----
        //
        // 用途：「记住当前页面」需要知道用户当时看的是哪一页。
        // 但用户点通知时，前台已经变成 SystemUI 了 —— 现算指纹必然算错。
        // 所以每次窗口切换都缓存一份，点通知时取缓存用。

        @Volatile
        private var cachedPkg: String = ""

        @Volatile
        private var cachedActivity: String = ""

        @Volatile
        private var cachedFingerprint: String = ""

        /** 供外部（通知点击）读取当前缓存的页面信息 */
        fun currentPage(): Triple<String, String, String> =
            Triple(cachedPkg, cachedActivity, cachedFingerprint)


        /** 类名没匹配上时的探测节流 —— 防止每次窗口切换都读树 */
        private const val UNMATCHED_PROBE_THROTTLE_MS = 800L
        @Volatile
        private var lastUnmatchedProbeAt: Long = 0L

        /** 类名日志节流 */
        private const val CLASS_LOG_THROTTLE_MS = 2_000L
        @Volatile
        private var lastClassLogAt: Long = 0L

        /**
         * 「这一屏有钱在动」的特征词。
         *
         * 刻意做得宽：微信和支付宝的结果页文案改版频繁，
         * 但「¥」「￥」「金额」这些**结构性标记**不会变。
         * （词表参考了 FridayKoi/WhereIsMyMoney 的实现）
         *
         * 放宽的代价可控 —— 提取不到金额或方向时照样会放弃，
         * 真正把住门的是后面那两步。
         */
        private val MONEY_PAGE_MARKERS = listOf(
            "支付成功", "付款成功", "交易成功", "已付款", "支付完成", "付款完成",
            "已支付", "已成功支付", "扣款成功", "已扣款", "订单已支付",
            "转账成功", "已转账", "已收钱", "已收款", "收款成功", "已收下",
            "转账说明", "转账金额", "转账待接收", "请及时查收", "向你转账",
            "微信红包", "领取红包", "拆开红包", "红包详情", "红包已领取",
            "红包已存入", "已存入零钱", "恭喜发财", "已发出红包", "红包已发出",
            "到账成功", "退款成功", "收款到账",
            "金额", "¥", "￥",
        )


        // ---- 支付页状态机（重写后新增）----

        /** 当前正处在哪个包的支付结果页（null = 不在） */
        @Volatile
        private var payPagePkg: String? = null

        /** 支付页状态有效期（离开这个时间窗就失效） */
        private const val PAY_PAGE_WINDOW_MS = 60_000L

        @Volatile
        private var payPageUntil: Long = 0L

        /**
         * event.source 抓到的文本。
         *
         * 事件自带的 source 节点是「触发这个事件的那个节点」，
         * 在支付页刚弹出的那一瞬它比遍历窗口更准，所以优先用它。
         */
        @Volatile
        private var sourceTextCache: String = ""


        /**
         * 微信内的「支付功能入口」词。
         *
         * 用途是状态机：读到这些词说明用户**正在往支付流程走**
         * （点开了扫一扫 / 收付款 / 转账 / 红包…），
         * 从这个时刻起的一段时间内，才认为后续页面可能是支付结果。
         *
         * 这比"无条件读屏"精确得多，也回答了那个问题：
         * 微信在聊天界面时，界面上不会出现这些入口词。
         */
        private val WECHAT_PAY_ENTRIES = listOf(
            "扫一扫", "收付款", "向商家付款", "付款码", "收款码",
            "转账", "红包", "零钱", "零钱通", "银行卡", "支付",
        )

        /** 读到入口词后，认为处于支付流程中的时长 */
        private const val PAY_FLOW_WINDOW_MS = 180_000L

        /** 支付流程截止时间戳（0 = 不在流程中） */
        @Volatile
        private var payFlowUntil: Long = 0L

        /** 转账 / 红包的关键词 —— 配合「有金额」构成结构判定 */
        private val TRANSFER_WORDS = listOf("转账", "红包", "收钱", "收款")

        /** 状态栏时间与当前时间允许的最大差值（分钟），超过就认为是图片里的旧截图 */
        private const val SCREEN_TIME_TOLERANCE_MIN = 5

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
        private const val OCR_THROTTLE_MS = 5_000L

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
            // 转账 / 红包 —— 这些页面同样没有"支付成功"字样，
            // 但它们都是真实的钱在动，必须收进来。
            "转账成功", "已转账", "已收款", "收款成功", "已收下",
            "转账待接收", "请及时查收", "向你转账",
            "已发出红包", "红包已发出", "红包已存入", "已领取", "领取成功",
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

        // ★ 两种事件要分开对待，这是重写后的核心。
        //
        // TYPE_WINDOW_STATE_CHANGED   → className 是 **Activity 类名**
        //                                （WalletPayResultUI / PayResultActivity…）
        //                                用它判断"是不是进了支付结果页"
        // TYPE_WINDOW_CONTENT_CHANGED → className 是 **View 类名**（LinearLayout…）
        //                                对识别支付页毫无价值，只当作"内容还在变"的信号
        //
        // 之前我把两者混在一起用同一个 className 判断，等于永远匹配不上。
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = e.className?.toString().orEmpty()

                // ---- 结构指纹：缓存 + 匹配 ----
                //
                // 微信支付页是自绘的，节点里读不到文字，但**骨架读得到**。
                // 用户教过一次之后，这里一比就知道"又是那个页面"，
                // 然后才去截图做 OCR。没教过的页面零开销。
                val fp = runCatching { StructureFingerprint.of(rootInActiveWindow) }.getOrDefault("")
                if (fp.isNotEmpty()) {
                    cachedPkg = pkg
                    cachedActivity = cls
                    cachedFingerprint = fp
                }
                // ---- 自绘页面检测 ----
                //
                // 微信的支付页读不到节点文字（自绘渲染），原生页面读得到。
                // 所以「读不到文字」本身就是「这可能是支付页」的判据 ——
                // 不需要模板，也不需要用户教。
                //
                // 教学机制（结构指纹）保留为可选的加速路径：
                // 教过的页面直接触发，没教过的靠"文本为空"兜底。
                if (sourceKey == SourceKey.WECHAT) {
                    if (fp.isNotEmpty() && PageSignatureStore.matches(this, pkg, cls, fp)) {
                        CaptureEventLog.record(pkg, true, "命中已记住的页面（教学路径）→ OCR", cls)
                        triggerOcr(sourceKey, pkg)
                        return
                    }
                    // 不在这里判断"文本是否为空" —— 那要等读屏结果，
                    // 交给下面的探测流程（它会先读文本，读不到才 OCR）
                }

                val matched = isPayResultClass(sourceKey, cls)

                if (matched) {
                    // 类名明确是支付结果页 —— 高置信，开状态机，立即读
                    payPagePkg = pkg
                    payPageUntil = System.currentTimeMillis() + PAY_PAGE_WINDOW_MS
                    handlePayPage(sourceKey, pkg, cls, e)
                } else {
                    if (payPagePkg == pkg) payPagePkg = null

                    // ★ 类名没匹配上也要读一次。
                    //
                    // 这是同一个错误第三次出现了：把"读取"挂在一个可能不成立的
                    // 前置条件上（先是预筛、再是前台闸门、现在是类名）。
                    // 微信的 Activity 类名随版本会变，我的清单必然列不全 ——
                    // 一旦某个版本改了名，支付页就**永远不会被读**。
                    //
                    // 现在的做法：类名只用来"加分"，不用来"放行"。
                    // 每次微信窗口切换都读一次，靠节流控制开销（3 秒一次），
                    // 读到的文本由后面的金额/方向提取来把关 ——
                    // 那两步才是真正能区分"支付页"和"聊天页"的地方。
                    val now = System.currentTimeMillis()
                    if (now - lastUnmatchedProbeAt > UNMATCHED_PROBE_THROTTLE_MS) {
                        lastUnmatchedProbeAt = now
                        handlePayPage(sourceKey, pkg, cls, e)
                    }
                    // 顺便把类名记下来 —— 你实测后我就能拿到真实类名补进清单
                    if (now - lastClassLogAt > CLASS_LOG_THROTTLE_MS) {
                        lastClassLogAt = now
                        CaptureEventLog.record(pkg, false, "微信窗口切换（类名未匹配支付页）", cls)
                    }
                    payPagePkg = pkg
                    payPageUntil = now + PAY_PAGE_WINDOW_MS
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 只有已经确认"在支付页"时才处理内容变化。
                // 既避免在聊天/列表页做无谓读屏，又不会漏掉支付页内的异步渲染。
                if (payPagePkg == pkg && System.currentTimeMillis() < payPageUntil) {
                    handlePayPage(sourceKey, pkg, "", e)
                }
            }

            else -> return
        }
    }

    /**
     * 处理一个疑似支付页。
     *
     * 三种文本来源，按可靠性排序：
     *   ① event.source      —— 触发事件的那个节点，最精确
     *   ② windows 按包名找  —— 主力（见 collectTextByPackage）
     *   ③ rootInActiveWindow —— 兜底，但会核对包名
     */
    private fun handlePayPage(sourceKey: String, pkg: String, className: String, e: AccessibilityEvent) {
        val key = pkg + "|" + (System.currentTimeMillis() / 800)
        if (!recentProbes.add(key)) return
        main.postDelayed({ recentProbes.remove(key) }, 1200)

        // ★ 读 event.source —— 它是「触发这个事件的那个节点」，
        //   **一定属于当前页面**，不用去猜哪个窗口是活动的。
        //
        // 之前我用「遍历所有窗口按包名找」，结果在微信上反复读错：
        // 转账页切过来了，读回来的却是「服务」页的小程序列表，
        // 因为那个窗口的节点树依然存在、依然可读。
        //
        // 而且这里**不做长度判断就缓存** —— 空的也要缓存，
        // 因为"空"本身就是关键信息（说明这页是自绘的，该走 OCR）。
        val fromSource = runCatching {
            e.source?.let { s ->
                val out = LinkedHashSet<String>()
                collectInto(s, out, 0)
                out.joinToString(" | ")
            }.orEmpty()
        }.getOrDefault("")

        for (delay in PROBE_DELAYS) {
            main.postDelayed({ probe(sourceKey, pkg, className, fromSource) }, delay)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun probe(sourceKey: String, pkg: String, className: String, eventText: String = "") {
        // ---- 前台校验（放在这里，不是事件到达时）----
        //
        // 为什么挪到这一层：事件到达的那一刻，rootInActiveWindow 常常还没更新完，
        // 在那一层校验会误杀真实支付页（这就是 v1.0.11 那次回归的原因）。
        // 但完全不校验又出了新问题 —— 有用户抓到自己 App 的界面被当成微信支付页：
        //   事件包名 = com.tencent.mm（微信的残留事件）
        //   实际前台 = 本应用（用户正在解析测试器里）
        //   读屏读回来的是本应用的文字 → 命中"支付"字样 → 误记一笔
        //
        // 探测有 120ms 的延迟，等它执行时前台早就稳定了，
        // 所以在这一层校验既准确、又不会误杀。
        // 主信号：event.source 的文本（一定属于当前页面）。
        // 只有它整个为空串时才退回遍历窗口 —— 那种情况通常是拿不到 source。
        val text = when {
            eventText.isNotEmpty() -> eventText
            else -> collectTextByPackage(pkg)
        }

        // 微信也回到**文本判定**。
        //
        // 曾经试过「认页面」（类名 或 金额+收款方结构），结果把扫码付款、转账、
        // 发红包全杀了 —— 因为那些页面的类名不一定匹配，页面结构也不一定有收款方。
        // 教训：文本判定虽然"笨"，但它是被真机验证过能工作的那条路。
        // 真正该修的是它的判定顺序（见 looksLikePaymentResult），而不是换掉它。
        // ---- 状态机：先看这一屏是不是支付入口 ----
        //
        // 读到「扫一扫」「收付款」「转账」「红包」这类入口词，
        // 就认为用户正走向支付流程，开一个时间窗。
        // 窗口内后续页面的判定会更积极一些（这才是用户要的"检测到行为再识别"）。
        val now = System.currentTimeMillis()
        val looksLikeEntry = WECHAT_PAY_ENTRIES.any { text.contains(it) } && text.length < 400
        if (looksLikeEntry) {
            payFlowUntil = now + PAY_FLOW_WINDOW_MS
        }
        val inPayFlow = now < payFlowUntil

        // 支付结果判定：文本判定为主
        // ---- 读不到文字 → 这是自绘页面，走 OCR ----
        //
        // 微信支付页的节点树里 text 全空（自绘渲染），无障碍再怎么读都是白读。
        // 而原生页面（主页、列表页）读得到文字。
        // 所以"文本为空"就等价于"这是个自绘页面，只能靠截图认"。
        //
        // 这就是「不需要模板」的原因：判据是客观事实，不是猜出来的特征。
        if (sourceKey == SourceKey.WECHAT && text.length < 6) {
            CaptureEventLog.record(
                pkg, false,
                "读不到节点文字（自绘页面）→ 触发 OCR",
                "类名=" + className + " | eventText长度=" + eventText.length,
            )
            triggerOcr(sourceKey, pkg)
            return
        }

        val hit = text.length >= 6 && (looksLikeMoneyPage(text) || looksLikePaymentResult(text))

        if (hit) {
            lastProbeAt = System.currentTimeMillis()
            val how = if (sourceKey == SourceKey.WECHAT) "微信支付结果页" else "支付结果页"
            // 把状态机的情况写进日志：这样你能看出"支付流程"有没有被正确开启。
            // 注意它**只是标注，不是拦截条件** ——
            // 流程外照样入账，否则入口词没读到就会漏记。
            val flowTag = if (inPayFlow) "支付流程中" else "流程外"
            CaptureEventLog.record(pkg, true, "读屏命中" + how + "（" + flowTag + "）类名=" + className, text)
            submit(sourceKey, pkg, text, Channel.ACCESSIBILITY)
            return
        }

        // ---- 没命中时也要留痕，否则这条通道就是个黑盒 ----
        //
        // 之前只有成功才写日志，于是「无障碍到底有没有被触发」根本看不出来。
        // 用户反馈付款码/小程序场景采不到时，完全无从判断是
        // 「服务没收到事件」还是「收到了但页面不像支付结果」。
        //
        if (now - lastMissLogAt > MISS_LOG_THROTTLE_MS) {
            lastMissLogAt = now
            val why = when {
                text.isEmpty() -> "读屏没读到任何文本（自绘页面？）类名=" + className
                text.length < 6 -> "读屏只读到 $text.length 个字，太少"
                FAILURE_MARKERS.any { text.contains(it) } -> "页面显示支付失败"
                BROWSING_MARKERS.any { text.contains(it) } -> "页面像账单浏览页，不是新交易"
                else -> "页面没有支付成功字样（文案没被收录？）类名=" + className
            }
            CaptureEventLog.record(pkg, false, "读屏未命中：$why｜" + (if (inPayFlow) "支付流程中" else "流程外"), text)
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

    /**
     * 触发截图 OCR。
     *
     * 只在**命中已记住的页面结构指纹**时才会走到这里 ——
     * 也就是说，是用户明确教过的那些自绘支付页。
     * 没教过的页面一次都不会截，开销完全可控。
     */
    private fun triggerOcr(sourceKey: String, pkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            CaptureEventLog.record(pkg, false, "系统版本低于 Android 11，不支持截图 OCR", "")
            return
        }
        // 节流暂时关掉。
        //
        // 原来的 5 秒节流是为了省电，但实测下来它挡掉了太多真实机会 ——
        // 支付页出现的时间窗很短，一旦被挡就只能等下一次页面变化，
        // 而那时用户早就点「完成」离开了。
        //
        // 现在只保留"防并发"这一条：OCR 本身要几百毫秒，
        // 允许并发会同时跑好几个截图，反而全失败。
        // 重复识别不会造成重复记账 —— 入库那一步有去重。
        if (ocrInFlight) {
            CaptureEventLog.record(pkg, false, "上一轮 OCR 还没结束，跳过", "")
            return
        }
        lastOcrAt = System.currentTimeMillis()

        // 延迟一下再截 —— 页面刚切换的瞬间往往还在动画/渲染中，
        // 立刻截很容易截到上一页（实测截到过聊天列表）。
        main.postDelayed({ doTakeScreenshot(sourceKey, pkg) }, OCR_CAPTURE_DELAY_MS)
    }

    private fun doTakeScreenshot(sourceKey: String, pkg: String) {
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    runCatching { handleScreenshot(result, sourceKey, pkg) }
                        .onFailure { ocrInFlight = false; Log.e(TAG, "处理截图失败", it) }
                }

                override fun onFailure(errorCode: Int) {
                    ocrInFlight = false
                    CaptureEventLog.record(pkg, false, "截图失败 code=" + errorCode, "")
                }
            })
        }.onFailure {
            ocrInFlight = false
            CaptureEventLog.record(pkg, false, "截图调用异常：" + it.message, "")
        }
    }

    private fun handleScreenshot(result: ScreenshotResult, sourceKey: String, pkg: String) {
        ocrInFlight = true
        val bitmap: Bitmap? = runCatching {
            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            result.hardwareBuffer.close()
            hw?.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()

        if (bitmap == null) {
            ocrInFlight = false
            CaptureEventLog.record(pkg, false, "截图解码失败", "")
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
                if (raw.isBlank()) {
                    CaptureEventLog.record(pkg, false, "OCR 没识别出文字", "")
                    return@addOnSuccessListener
                }

                // ★ 校验：这一屏到底是不是"一笔支付的结果"
                //
                // 不校验的话，截图时机稍偏就会把聊天列表、账单列表记成账 ——
                // 实测截到过聊天列表里的「已支付¥0.01」消息预览，被当成真账单。
                if (!looksLikeRealPayPage(raw)) {
                    CaptureEventLog.record(pkg, false, "OCR 被校验拦下：" + explainReject(raw), raw)
                    return@addOnSuccessListener
                }

                // 再用状态栏时间确认这一屏是**实时的**，而不是点开的旧截图
                if (!screenTimeMatchesNow(raw)) {
                    CaptureEventLog.record(pkg, false, "OCR 被校验拦下：状态栏时间与当前时间差太多（图片里的旧截图？）", raw)
                    return@addOnSuccessListener
                }

                CaptureEventLog.record(pkg, true, "OCR 识别完成并通过校验（" + raw.length + " 字）", raw)
                submit(sourceKey, pkg, raw, Channel.OCR)
            }
            .addOnFailureListener {
                runCatching { bitmap.recycle() }
                ocrInFlight = false
                CaptureEventLog.record(pkg, false, "OCR 识别失败：" + it.message, "")
            }
    }

    /**
     * OCR 结果校验 —— **必须**过了这一关才允许入账。
     *
     * ## 为什么不能只靠"节点为空"触发
     *
     * 截图是异步的，回调执行时页面可能已经变了。
     * 实测就截到过微信的**聊天列表**，而里面碰巧有一条
     * 「已支付¥0.01」的**消息预览** —— 解析器把它当成账单记了下来。
     *
     * ## 判据：强特征必须命中，干扰特征必须不命中
     *
     * 强特征来自三种真实支付页（你截图里那几张）：
     *   支付成功页  → 「支付成功」+ 金额 + 收款方
     *   支付凭证页  → 「收款方」「交易状态」「支付凭证」
     *   收款成功页  → 「你已收款」「资金已存入零钱」「收款时间」
     *
     * 干扰特征来自聊天列表和账单列表 —— 它们都有金额，但都不是一笔新交易。
     */
    private fun looksLikeRealPayPage(ocrText: String): Boolean {
        val t = ocrText.replace(Regex("\\s+"), "")

        // ① 排除：聊天列表（有联系人、有消息预览、有置顶折叠）
        val chatMarkers = listOf("折叠置顶聊天", "语音通话", "置顶聊天", "发起群聊", "添加朋友")
        if (chatMarkers.any { t.contains(it) }) return false

        // ② 排除：账单列表（底部有三个 tab，或者重复出现"查看账单详情"）
        val listTabs = listOf("我的账单", "支付服务", "摇优惠")
        if (listTabs.count { t.contains(it) } >= 2) return false
        if (t.split("查看账单详情").size > 2) return false   // 出现 2 次以上 = 列表

        // ③ 必须命中强特征 —— 这些词只会出现在**单笔**支付结果页上
        val strongMarkers = listOf(
            // 支付成功页
            "支付成功", "付款成功", "交易成功",
            // 支付凭证页（你图一里那种卡片，但**单张**时才是结果页）
            "收款方", "交易状态", "对方已收款",
            // 收款成功页（你图三）
            "你已收款", "资金已存入零钱", "收款时间", "转账时间",
            // 通用
            "支付凭证", "已存入零钱",
        )
        return strongMarkers.any { t.contains(it) }
    }

    /**
     * 说清楚"为什么这一屏被校验拦下了"。
     *
     * 只记「丢弃」两个字是没法排查的 —— 到底是撞了排除词，
     * 还是强特征一个都没认出来（OCR 把字认错了？截图截歪了？）。
     */
    private fun explainReject(ocrText: String): String {
        val t = ocrText.replace(Regex("\\s+"), "")

        val chatHit = listOf("折叠置顶聊天", "语音通话", "置顶聊天", "发起群聊", "添加朋友")
            .firstOrNull { t.contains(it) }
        if (chatHit != null) return "像聊天列表（含「" + chatHit + "」）"

        val tabs = listOf("我的账单", "支付服务", "摇优惠").filter { t.contains(it) }
        if (tabs.size >= 2) return "像账单列表（底部 tab：" + tabs.joinToString("/") + "）"
        if (t.split("查看账单详情").size > 2) return "像账单列表（多次出现「查看账单详情」）"

        val strong = listOf(
            "支付成功", "付款成功", "交易成功",
            "收款方", "交易状态", "对方已收款",
            "你已收款", "资金已存入零钱", "收款时间", "转账时间",
            "支付凭证", "已存入零钱",
        )
        val hit = strong.filter { t.contains(it) }
        if (hit.isEmpty()) {
            // 把可能的"形近字误识别"也提示出来：
            // OCR 常把「已」认成「己」、「¥」认成「Y」或「￥」
            val maybe = listOf("已收款", "己收款", "存入零钱", "支什成功", "支付成攻")
                .filter { t.contains(it) }
            val hint = if (maybe.isEmpty()) "" else "（疑似识别偏差：" + maybe.joinToString("/") + "）"
            return "强特征一个都没命中" + hint + "｜全文 " + t.length + " 字"
        }
        return "未知原因（强特征命中：" + hit.joinToString("/") + "）"
    }

    /**
     * 用**状态栏时间**判断这一屏是实时的，还是图片里的旧截图。
     *
     * ## 解决什么问题
     *
     * 用户点开一张支付成功的**截图**时，OCR 照样能读出「支付成功 ¥0.01」，
     * 但状态栏上显示的时间是**截图那一刻**的，不是现在。
     *
     * 真实页面的状态栏时间必然等于此刻；旧截图则会差出一大截。
     * 差超过 [SCREEN_TIME_TOLERANCE_MIN] 分钟就认为不是实时页面。
     *
     * 这不能百分百拦住（刚截完就点开就拦不住），但能挡掉绝大多数。
     *
     * ## 两个细节
     *
     * 1. **只在前 120 字符里找时间** —— 状态栏在屏幕最上方，OCR 也是从那儿开始读的。
     *    往后找会撞上页面里的「转账时间 19:50:42」这类字段。
     * 2. **兼容 12 小时制** —— 有人习惯 12 小时制，状态栏显示「9:05」，
     *    那它可能是上午 9:05，也可能是晚上 9:05。两种解释都算一遍，取差值最小的。
     */
    private fun screenTimeMatchesNow(ocrText: String): Boolean {
        val cal = java.util.Calendar.getInstance()
        val nowMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)

        // 只在前 120 字符里找（状态栏区域）
        val head = ocrText.take(120)
        val m = Regex("(?<![0-9])([0-9]{1,2}):([0-9]{2})(?![0-9])").find(head)
            ?: return true   // 找不到时间 → 不拦，宁可漏也不能误杀真实页面

        val h = m.groupValues[1].toIntOrNull() ?: return true
        val min = m.groupValues[2].toIntOrNull() ?: return true
        if (h > 23 || min > 59) return true

        // 12 小时制的两种可能都算一遍，取最接近当前时间的那个
        val candidates = listOf(h * 60 + min, (h + 12) * 60 + min).filter { it < 24 * 60 }
        val diff = candidates.minOf { c ->
            val d = kotlin.math.abs(c - nowMinutes)
            minOf(d, 24 * 60 - d)   // 跨午夜的情况
        }

        val ok = diff <= SCREEN_TIME_TOLERANCE_MIN
        if (!ok) {
            Log.i(TAG, "状态栏时间 $h:$min 与当前差 $diff 分钟，判定为图片里的旧截图")
        }
        return ok
    }

    private fun looksLikePaymentResult(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        if (FAILURE_MARKERS.any { compact.contains(it) }) return false
        // ① 成功标记优先 —— 支付成功页也会带单号，不能被浏览页规则误杀
        if (SUCCESS_MARKERS.any { compact.contains(it) }) return true

        // ② 转账 / 红包页的结构判定
        //
        // 光靠枚举文案一定会漏（"已转账给张三""红包已发出"…列不完），
        // 所以补一条结构规则：**页面上同时有「金额」和「转账/红包」字样**，
        // 就认为这是一笔真实的资金流动。
        //
        // 放宽的代价可控：这类页面通常没有商户名 → 分类不确定 →
        // 自动挂「待确认」，最坏情况是多一条让你拍板，而不是丢一笔。
        val hasAmount = Regex("[¥￥]\\s*[0-9]").containsMatchIn(compact) ||
            Regex("[0-9]+\\.[0-9]{2}\\s*元").containsMatchIn(compact)
        val hasTransferWord = TRANSFER_WORDS.any { compact.contains(it) }
        if (hasAmount && hasTransferWord) return true

        // ③ 没有成功标记，又像账单浏览页 → 是用户在看历史，不是新交易
        if (BROWSING_MARKERS.any { compact.contains(it) }) return false
        return false
    }

    /**
     * 按**包名**从所有窗口里取文本。
     *
     * ★ 这是整个微信识别能工作的关键，也是之前一直失败的原因。
     *
     * 原来我只用 rootInActiveWindow()，结果在微信支付页上读回来的
     * 全是系统状态栏的文字（「WLAN 信号强度满格」「电量剩余 58」）——
     * 因为 rootInActiveWindow 返回的**不一定是你想要的那个窗口**，
     * 尤其在微信这种多窗口叠加的场景下。
     *
     * 正确做法是遍历 windows 列表，**按包名挑出属于目标 App 的那个窗口**。
     * 这个思路来自参考实现 FridayKoi/WhereIsMyMoney。
     */
    private fun collectTextByPackage(pkg: String): String {
        val all = runCatching { windows }.getOrNull() ?: return ""

        // 先看一下都有哪些窗口（调试用，只在 debug 日志里）
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            all.forEachIndexed { i, w ->
                Log.d(TAG, "window[$i] pkg=${w.root?.packageName} active=${w.isActive} focused=${w.isFocused} type=${w.type}")
            }
        }

        /**
         * 挑窗口的顺序很关键，这里踩过坑：
         *
         * 最初我取「第一个包名匹配且内容非空的窗口」，结果**一直读到微信主页**——
         * 因为支付页盖在主页之上时，主页窗口的节点树依然存在且可读，
         * 遍历到它就先返回了。表现就是不管你在扫一扫还是支付成功页，
         * 读回来的永远是主页那个「发起群聊｜添加朋友｜扫一扫｜收付款」。
         *
         * 正确的判据是 **isActive**：当前真正显示在屏幕上的那个窗口。
         */
        val candidates = all.filter { w ->
            runCatching { w.root?.packageName?.toString() == pkg }.getOrDefault(false)
        }
        if (candidates.isEmpty()) return ""

        val ordered = candidates.sortedByDescending { w ->
            // active 优先，其次 focused，最后退回原顺序
            (if (w.isActive) 2 else 0) + (if (w.isFocused) 1 else 0)
        }

        // 主选：最活跃的那个窗口
        for (w in ordered) {
            val texts = LinkedHashSet<String>()
            runCatching {
                w.root?.let { root -> collectInto(root, texts, 0) }
            }
            if (texts.isNotEmpty()) return texts.joinToString(" | ")
        }
        return ""
    }

    /** 递归收集节点文本（含 contentDescription） */
    private fun collectInto(node: android.view.accessibility.AccessibilityNodeInfo, out: MutableSet<String>, depth: Int) {
        if (depth > 20 || out.size >= 300) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectInto(child, out, depth + 1)
            @Suppress("DEPRECATION") runCatching { child.recycle() }
        }
    }

    /**
     * 判断类名是不是支付结果页 —— **只对 TYPE_WINDOW_STATE_CHANGED 有意义**。
     *
     * 这一点我搞错了很久：两种事件的 className 根本不是一种东西 ——
     *   TYPE_WINDOW_STATE_CHANGED    → **Activity 类名**（如 WalletPayResultUI）✅
     *   TYPE_WINDOW_CONTENT_CHANGED  → **View 类名**（如 android.widget.LinearLayout）❌
     * 我拿后者去匹配支付页类名，永远是 false。
     *
     * 类名清单来自参考实现 FridayKoi/WhereIsMyMoney。
     */
    private fun isPayResultClass(sourceKey: String, className: String): Boolean {
        if (className.isEmpty()) return false
        val c = className.lowercase()
        return when (sourceKey) {
            SourceKey.WECHAT -> listOf(
                "payresultui", "paysuccessui", "orderpayresultui",
                "transferresultui", "remittanceui", "remittancedetailui",
                "luckymoneydetailui", "transferconfirmui",
                "walletpay", "mallwalletpay", "payresult",
            ).any { c.contains(it) }
            SourceKey.ALIPAY -> listOf(
                "payresultactivity", "paysuccessactivity", "transferresultactivity",
                "h5payresultactivity", "payresult", "payactivity",
            ).any { c.contains(it) }
            else -> false
        }
    }

    /**
     * 判断这一屏的文本是不是"一笔钱在动"。
     *
     * 判据放得很宽，参考实现里把「¥」「￥」「金额」都算作特征 ——
     * 因为微信/支付宝的结果页文案变来变去，但**金额符号必然出现**。
     * 真正决定记不记的是后面的金额提取与去重。
     */
    private fun looksLikeMoneyPage(text: String): Boolean {
        if (text.length < 4) return false
        val compact = text.replace(Regex("\\s+"), "")
        if (FAILURE_MARKERS.any { compact.contains(it) }) return false
        return MONEY_PAGE_MARKERS.any { compact.contains(it) }
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
