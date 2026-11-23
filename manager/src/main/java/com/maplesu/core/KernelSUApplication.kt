package com.maplesu.core

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.system.Os
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.maplesu.core.core.crash.CrashEvidenceClassifier
import com.maplesu.core.data.repository.SettingsRepositoryImpl
import dev.maplesu.manager.runtime.MapleSuRuntime
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.gating.ModeComponentIds
import dev.maplesu.manager.workmode.gating.ModeGate
import dev.maplesu.manager.workmode.gating.ModeGateFormat
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.ensureActive

private const val TAG = "KernelSU"

// ══════════════════════════════════════════════════════════════════════
// 崩溃上报（2026-09-19 新增）
//
// 背景：出现过一次「部分机型打开就闪退」，当时没有任何上报能力，只能靠用户口述排查，
//      花了很久。补上这套最小可用的上报：
//
//   1. 会话标记：onCreate 里读 filesDir/.session_running —— 存在 = 上次进程没正常收尾；
//      读完立刻把标记写上（本次会话开始），MainActivity 正常收尾时删掉它。
//   2. 后台抓取：标记存在时，在**后台线程**上用 root shell 抓 tombstone + logcat；
//      **只有文本里确实出现我们自己的痕迹**才写进 filesDir/last_crash.txt，
//      没命中就什么都不写（避免把别人的崩溃/普通重启误报成我们的）。
//   3. Java 崩溃：装 Thread.setDefaultUncaughtExceptionHandler，把异常栈追加进同一个
//      文件，然后照常交回原来的默认 handler（绝不吞掉）。
//
// 铁律：所有 IO / root 调用 / 解析全部 runCatching 包住；主线程只做一次
//      「读标记 + 写标记」的极小磁盘操作，绝不在这里等 root、绝不阻塞首帧。
// ══════════════════════════════════════════════════════════════════════

/** 会话标记文件名（放 filesDir）。存在 = 上次进程没有正常收尾。 */
private const val SESSION_MARKER_NAME = ".session_running"

/** 崩溃报告文件名（放 filesDir）。UI 展示完会删掉它。 */
private const val CRASH_FILE_NAME = "last_crash.txt"

/** 报告文件上限：超过就先删旧再写，免得反复崩溃把文件撑爆。 */
private const val CRASH_FILE_MAX_BYTES = 256L * 1024L

/**
 * 崩溃证据的**分类器**已抽到 `core/crash/CrashEvidenceClassifier.kt`（v2.14）。
 *
 * 抽出去的理由有两条：
 *   ① 那个文件**不 import 任何 Android 类** ⇒ 可以在宿主机（JVM）上直接单测
 *      —— 本版"ksud 子进程被 SIGSYS 杀掉不算 App 崩溃"这条规则就是这么验的；
 *   ② 判据（真崩溃特征 / 本进程身份 / 内核探测被拦）集中在一处，不会再出现
 *      "抓取用的正则"和"判定用的正则"各写一份、改了一处忘了另一处。
 *
 * 抓取阶段仍然需要"哪些行算崩溃相关"，用 [CrashEvidenceClassifier.isCrashHintLine]。
 */

/** 只接受 tombstone 目录里正常的文件名，防止把奇怪名字拼进 shell 命令。 */
private val SAFE_FILE_NAME_REGEX = Regex("[A-Za-z0-9._-]{1,64}")

/**
 * 跑 root 命令用的线程池。只在后台线程上调用。
 *
 * 用 cached pool 而不是单线程：万一某条命令真的卡住（例如 su 弹窗没人点），
 * 卡死的只是一个 daemon 线程，后面的命令不会被它堵住，也不会拖住进程退出。
 */
private val crashShellExecutor by lazy {
    Executors.newCachedThreadPool { r ->
        Thread(r, "ksu-crash-shell").apply { isDaemon = true }
    }
}

/** 跑一条 root 命令拿 stdout。任何异常/超时都返回空串，调用方永远拿到字符串、永远不抛。 */
private fun runCrashShellCommand(cmd: String, timeoutSeconds: Long = 20L): String {
    // mode-gate: app.crash_shell (04-ui-contract §1.3) —— 崩溃取证走 Lite root shell，
    // 只允许已激活的 LITE 执行；KSU/未判定一律拒绝，返回空串（调用方已按"没抓到"处理）。
    when (ModeRuntime.active) {
        WorkMode.LITE -> Unit
        WorkMode.KSU -> {
            ModeGate.deny(ModeComponentIds.APP_CRASH_SHELL, ModeGateFormat.Reason.KSU)
            return ""
        }

        null -> {
            ModeGate.deny(ModeComponentIds.APP_CRASH_SHELL, ModeGateFormat.Reason.UNDETERMINED)
            return ""
        }
    }
    return runCatching {
        val task = crashShellExecutor.submit(
            Callable {
                // Diagnostics must not deploy clients, bind services or start module tasks.
                MapleSuRuntime.openShell(ksuApp).use { shell ->
                    shell.newJob().add(cmd).to(ArrayList<String>(), null).exec().out.joinToString("\n")
                }
            }
        )
        task.get(timeoutSeconds, TimeUnit.SECONDS)
    }.getOrDefault("")
}

private fun crashTimestamp(): String = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}.getOrDefault("")

/** 时间 / 机型 / Android 版本 / 版本名 —— 报告开头的固定信息。 */
private fun crashDeviceSummary(): String = runCatching {
    "时间: ${crashTimestamp()}\n" +
        "机型: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n" +
        "系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
        "版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}.getOrDefault("")

/**
 * 后台抓取：最新 1~2 个 tombstone + logcat 最近 3000 行里崩溃相关的行，拼成一段文本。
 * 抓不到（没 root / 命令报错 / 目录为空）就返回空串，调用方按「没命中」处理。
 */
private fun collectCrashEvidence(): String {
    val sb = StringBuilder()
    runCatching {
        // 1) tombstone：只看最新 1~2 个
        //    ⚠️ 实机（联想 Y700）上 `ls -t /data/tombstones/` 是 `tombstone_06`、`tombstone_06.pb`
        //       交替出现的 —— `.pb` 是同一次崩溃的 protobuf 二进制副本，会把名额占掉。
        //       这里排除掉，保证真的能拿到 1~2 个**文本** tombstone。
        val listed = runCrashShellCommand("ls -t /data/tombstones/ 2>/dev/null | head -5")
        val names = listed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && SAFE_FILE_NAME_REGEX.matches(it) && !it.endsWith(".pb") }
            .take(2)
            .toList()
        for (name in names) {
            val content = runCrashShellCommand("cat /data/tombstones/$name 2>/dev/null")
            if (content.isBlank()) continue
            // 只留「和我们有关」的行，免得把别的 App 的墓碑整篇塞进来
            val related = content.lineSequence()
                .filter { CrashEvidenceClassifier.isCrashHintLine(it) || it.contains(BuildConfig.APPLICATION_ID) }
                .toList()
            if (related.isEmpty()) continue
            sb.append("----- /data/tombstones/$name -----\n")
            related.forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }

        // 2) logcat：先整段拉回来，再在本地按关键词筛（避免 shell 引号/转义踩坑）
        val log = runCrashShellCommand("logcat -d -t 3000 2>/dev/null")
        val logHits = log.lineSequence().filter { CrashEvidenceClassifier.isCrashHintLine(it) }.toList()
        if (logHits.isNotEmpty()) {
            sb.append("----- logcat -d -t 3000（只留崩溃相关行）-----\n")
            logHits.forEach { sb.append(it).append('\n') }
        }
    }.onFailure { Log.w(TAG, "抓取崩溃日志失败(忽略): ", it) }
    return sb.toString()
}

/**
 * 把抓到的证据交给 [CrashEvidenceClassifier] 分类（纯 Kotlin，可在宿主机上单测）。
 *
 * 返回值的用法见 [collectAndStorePreviousCrash]：
 *   OUR_CRASH → 写报告 + 弹窗；KERNEL_PROBE_BLOCKED → 只记日志；NOT_OURS → 什么都不做。
 */
private fun classifyEvidence(text: String): CrashEvidenceClassifier.Verdict =
    CrashEvidenceClassifier.classify(text, BuildConfig.APPLICATION_ID)

/** 追加写报告；失败只记一行日志，绝不抛。 */
private fun appendCrashReport(context: Context, text: String) {
    runCatching {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        if (file.isFile && file.length() > CRASH_FILE_MAX_BYTES) {
            runCatching { file.delete() }
        }
        file.appendText(if (text.endsWith("\n")) text else "$text\n")
    }.onFailure { Log.w(TAG, "写崩溃报告失败(忽略,不影响启动): ", it) }
}

/**
 * 后台抓取本次「上次崩溃」的证据。全程 runCatching：
 * 没 root、命令报错、解析失败 —— 一律静默放弃，不写文件、不弹任何东西。
 *
 * v2.14：结论是**三分类**（见 [classifyPreviousCrash]）——
 *   · [CrashEvidenceClassifier.Verdict.OUR_CRASH]            → 写 `last_crash.txt`（标题「自动抓取」），下次启动弹窗
 *   · [CrashEvidenceClassifier.Verdict.KERNEL_PROBE_BLOCKED] → **只写一行日志**，不落盘、不弹窗
 *   · [CrashEvidenceClassifier.Verdict.NOT_OURS]             → 什么都不做
 */
private fun collectAndStorePreviousCrash(context: Context) {
    runCatching {
        val evidence = collectCrashEvidence()
        when (classifyEvidence(evidence)) {
            CrashEvidenceClassifier.Verdict.OUR_CRASH -> Unit
            CrashEvidenceClassifier.Verdict.KERNEL_PROBE_BLOCKED -> {
                // 🔴 v2.14：这不是 App 崩了 —— 是 ksud / 内核探测的**子进程**被系统
                // （seccomp/SIGSYS 之类）拦下或杀掉。App 进程活得好好的。
                // 以前这里会写 last_crash.txt 并弹「崩溃上报」，用户就以为"闪退"。
                // 现在只留一行日志（真机上 `logcat -s maplesu` 能看到），不打扰用户。
                Log.i(TAG, "崩溃上报:确认是内核探测子进程被系统拦下(非 App 崩溃),只记日志不上报")
                return@runCatching
            }
            CrashEvidenceClassifier.Verdict.NOT_OURS -> {
                Log.i(TAG, "崩溃上报:上次会话没正常收尾,但没抓到我们自己的崩溃痕迹,不上报")
                return@runCatching
            }
        }
        // 用户可能已经看过/复制过这份报告（UI 已经删了文件），那就别再写回去 ——
        // 否则会出现「关掉弹窗后它又冒出来、下次启动再弹一次」。
        if (CrashReporter.isDismissedByUser()) return@runCatching
        appendCrashReport(
            context,
            "===== 崩溃上报（自动抓取）=====\n${crashDeviceSummary()}\n\n$evidence",
        )
        Log.i(TAG, "崩溃上报:已写入 ${File(context.filesDir, CRASH_FILE_NAME).absolutePath}")
    }.onFailure { Log.w(TAG, "后台抓取崩溃日志失败(忽略): ", it) }
}

/** Java 未捕获异常的文本（时间 / 机型 / Android 版本 / 版本名 / 线程名 / 完整栈）。 */
private fun buildJavaCrashText(thread: Thread, throwable: Throwable): String = buildString {
    append("===== 崩溃上报（Java 未捕获异常）=====\n")
    append(crashDeviceSummary()).append('\n')
    append("线程: ").append(thread.name).append('\n')
    append("异常: ").append(throwable.javaClass.name)
    throwable.message?.let { append(": ").append(it) }
    append('\n')
    append(Log.getStackTraceString(throwable))
    append('\n')
}

@Volatile
private var crashHandlerInstalled = false

/** 装默认异常处理器：先记盘，再把异常**原样交回**原来的 handler（千万别吞掉）。 */
private fun installCrashHandler(context: Context) {
    if (crashHandlerInstalled) return
    crashHandlerInstalled = true
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        // ⚠️ handler 自己不许抛：记录这一步整段 runCatching
        runCatching { appendCrashReport(context, buildJavaCrashText(thread, throwable)) }
            .onFailure { Log.w(TAG, "记录 Java 崩溃失败(忽略): ", it) }
        // 交回原来的默认 handler，让系统照常杀进程 / 弹系统崩溃框
        previous?.uncaughtException(thread, throwable)
    }
}

/**
 * 会话检测：
 *   标记存在  → 说明上次进程没正常收尾（可能崩了）→ 起后台线程抓取
 *   然后不管怎样都把标记写上（表示本次会话开始）
 *
 * 只在「用户已解锁」之后调用（见 onCreate 的 directBootAware 早返回）。
 */
private fun startCrashSession(context: Context) {
    val marker = File(context.filesDir, SESSION_MARKER_NAME)
    val previousSessionEndedBadly = runCatching { marker.isFile }.getOrDefault(false)

    // 🟡（v2.18）**写盘 + 抓取挪到后台线程**：这段是在 `Application.onCreate` 的主线程上跑的，
    // `marker.writeText` 是一次磁盘写（StrictMode 违规；慢盘/低端机拖首帧）。
    // 读取保留在主线程（一次 stat，极快）—— 这样"先读上次、再写本次"的顺序语义完全不变。
    runCatching {
        thread(name = "ksu-crash-session", isDaemon = true) {
            // 先写上本次会话的标记；MainActivity 正常收尾时会删掉它
            runCatching { marker.writeText(crashTimestamp()) }
                .onFailure { Log.w(TAG, "写会话标记失败(忽略): ", it) }

            if (!previousSessionEndedBadly) return@thread

            Log.i(TAG, "崩溃上报:上次会话没有正常收尾,后台抓取崩溃痕迹")
            runCatching { collectAndStorePreviousCrash(context) }
        }
    }.onFailure { Log.w(TAG, "起崩溃会话线程失败(忽略): ", it) }
}

/**
 * 崩溃报告的读写入口（UI 层用）。
 *
 * 放在 Application 这个文件里，是为了让「收集」和「展示」共用同一份路径/上限常量，
 * 既不用新增文件、也不用动 KsuCli。
 */
object CrashReporter {

    /** 报告文件（filesDir/last_crash.txt）。 */
    fun reportFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)

    /** 读报告全文；没有 / 为空 / 读失败 → null。绝不抛。 */
    fun read(context: Context): String? = runCatching {
        val file = reportFile(context)
        if (!file.isFile) return@runCatching null
        file.readText().ifBlank { null }
    }.getOrNull()

    /**
     * 用户已经看过 / 复制过这份报告：删掉文件，并打上「别再写回来」的标记。
     * 否则后台抓取还在跑的时候会把文件重新写出来 → 关掉又弹出 / 下次启动再弹一次。
     */
    fun clear(context: Context) {
        userDismissed = true
        runCatching { reportFile(context).delete() }
    }

    /** 后台抓取落盘前问一句：用户是不是已经处理过这份报告了。 */
    internal fun isDismissedByUser(): Boolean = userDismissed

    /** 正常收尾：删掉会话标记（下次启动就不会认为是崩溃退出）。绝不抛。 */
    fun clearSessionMarker(context: Context) {
        runCatching { File(context.filesDir, SESSION_MARKER_NAME).delete() }
    }

    @Volatile
    private var userDismissed = false
}

lateinit var ksuApp: KernelSUApplication

class KernelSUApplication : Application(), ViewModelStoreOwner {
    override fun attachBaseContext(base: android.content.Context) {
        val providerProcess = Application.getProcessName() == base.packageName
        rikka.shizuku.ShizukuProvider.enableMultiProcessSupport(providerProcess)
        super.attachBaseContext(base)
        if (!providerProcess) {
            runCatching { rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this) }
        }
    }

    companion object {
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
                val applicationInfoClass = ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }.onFailure { Log.w(TAG, "Predictive back preference unavailable", it) }
        }
    }

    /**
     * ⚠️ 2026-09-19 修:`lateinit var` → `by lazy`。
     *
     * 它原来只在 `onCreate` 的**末尾**被赋值,而 `onCreate` 在
     * directBootAware(锁屏早启动)那条路径上会**直接 return** ——
     * 那条路径下 `okhttpClient` 永远没有初始化;等用户解锁、同一个进程里
     * 任何地方用到它(`ksuApp.okhttpClient`:模块仓库、模板仓库、下载器、WebUI 都在用)
     * 就会抛 `UninitializedPropertyAccessException` → 闪退。
     *
     * 改成惰性初始化后:不管启动时走到 `onCreate` 的哪一步,第一次真正用到它时才创建,
     * 因此**任何路径都不会再有未初始化**。类型和读法完全不变(仍是 `ksuApp.okhttpClient`),
     * 调用方零改动。
     */
    val okhttpClient: OkHttpClient by lazy { buildOkHttpClient() }

    private fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor { block ->
                block.proceed(
                    block.request().newBuilder()
                        .header("User-Agent", "KernelSU/${BuildConfig.VERSION_CODE}")
                        .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                )
            }
        // ⚠️ 2026-09-19 修:`Cache(File)` 构造会做磁盘 I/O,失败(目录不可写等)不该把 App 带崩。
        //    拿不到磁盘缓存就退化成"无缓存",网络功能照旧。
        runCatching { builder.cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024)) }
            .onFailure { Log.w(TAG, "okhttp 磁盘缓存不可用,改用无缓存: ", it) }
        return builder.build()
    }

    private val appViewModelStore by lazy { ViewModelStore() }

    private fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked == true

    private val ultraRecoveryRequests by lazy {
        dev.maplesu.manager.runtime.ultra.UltraRecoveryRequests(
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
            recover = { recoverUltraEnvironment() },
            failed = { Log.w(TAG, "Ultra automatic recovery pending", it) },
        )
    }
    private val ultraStartupTriggeredAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val ultraRecoveryRequestedAt = java.util.concurrent.atomic.AtomicLong(0L)

    /** Startup, foreground and explicit refreshes share one event-driven recovery path. */
    fun requestUltraRecovery() {
        if (Application.getProcessName() != packageName || !isUserUnlocked()) return
        ultraStartupTriggeredAt.compareAndSet(0L, android.os.SystemClock.elapsedRealtime())
        ultraRecoveryRequestedAt.compareAndSet(0L, android.os.SystemClock.elapsedRealtime())
        ultraRecoveryRequests.request()
    }

    private suspend fun recoverUltraEnvironment() {
        val recoveryContext = kotlinx.coroutines.currentCoroutineContext()
        val passStarted = android.os.SystemClock.elapsedRealtime()
        val requestedAt = ultraRecoveryRequestedAt.getAndSet(0L).takeIf { it > 0L } ?: passStarted
        fun <T> stage(name: String, action: () -> T): T {
            val started = android.os.SystemClock.elapsedRealtime()
            return try { action() } finally {
                val now = android.os.SystemClock.elapsedRealtime()
                Log.i(TAG, "Ultra startup phase=$name elapsedMs=${now - started} " +
                    "startupElapsedMs=${now - ultraStartupTriggeredAt.get()} requestElapsedMs=${now - requestedAt}")
            }
        }
        fun ensureActive() {
            recoveryContext.ensureActive()
            if (Thread.currentThread().isInterrupted) {
                throw java.util.concurrent.CancellationException("Ultra recovery interrupted")
            }
        }
        fun failed(message: String, error: Throwable) {
            if (error is java.util.concurrent.CancellationException) throw error
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.util.concurrent.CancellationException("Ultra recovery interrupted").apply { initCause(error) }
            }
            if (error !is Exception) throw error
            Log.w(TAG, message, error)
        }
        var startupReady = false
        var terminalReason = "STARTUP_INTERRUPTED"
        var startupKind = "unconfirmed"
        try {
        ensureActive()
        // Detection consumes this same live SDK evidence. Waiting for its completion
        // here makes the identity/Root handshake depend on its own consumer.
        stage("live-observation") {
            runCatching { dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.refresh(this, force = false) }
                .onFailure { failed("Ultra live observation pending", it) }
        }
        val live = dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.currentSnapshot(this)
        val detection = ModeRuntime.latestDetection
        var bootReadiness: Boolean? = null
        var managerReason: String? = null
        var setupReason: String? = null
        val pendingInstall = dev.maplesu.manager.runtime.ultra.UltraInstallCredentialRecovery.hasPendingInstallCredential(this)
        startupKind = if (live.rootAvailable && live.state == dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.State.Running)
            "healthy" else if (pendingInstall) "installation" else "recovery"
        val warmup = dev.maplesu.manager.runtime.ultra.UltraStartupWarmup.run(
            dev.maplesu.manager.runtime.ultra.UltraStartupWarmup.Input(
                activeMode = ModeRuntime.active,
                detectionSettled = ModeRuntime.detectionComplete && ModeRuntime.detectionFailure == null,
                ksuResponded = detection?.trace?.firstOrNull { it.id == "S1" }?.ok,
                pendingInstallCredential = pendingInstall,
                suspended = dev.maplesu.manager.runtime.ultra.UltraSdkCredentials.suspended(this),
                quiescing = dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate.isBlocked(),
                switchPending = dev.maplesu.manager.workmode.ui.WorkModeSwitchSession.executing,
                postBootUltraSwitchRecovery = dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup
                    .hasPostBootUltraSwitchRecovery(this),
                verifiedUltraIdentity = dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup
                    .hasVerifiedUltraIdentity(this),
                liveEnvironmentReady = live.rootAvailable &&
                    live.state == dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.State.Running &&
                    dev.maplesu.manager.runtime.ultra.UltraSdkSession.current(this) == null,
                conflictingRoot = detection?.state == dev.maplesu.manager.workmode.detect.ModeState.FOREIGN_SU,
            ),
            setupEnvironment = {
                ensureActive()
                stage("environment") { runCatching { dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup.ensure(this) }
                    .onSuccess { result ->
                        setupReason = result.reason
                        if (dev.maplesu.manager.runtime.ultra.UltraSdkSession.current(this) != null) {
                            bootReadiness = result.installed
                        }
                        Log.i(TAG, "Ultra 环境自动配置: $result")
                    }
                    .onFailure { failed("系统环境自动配置失败(忽略,不影响启动): ", it) } }
            },
            pairEnvironment = {
                ensureActive()
                stage("manager-su") { runCatching { dev.maplesu.manager.runtime.ultra.UltraAutoPair.ensure(this, maintenance = false) }
                    .onSuccess { result ->
                        managerReason = result.reason
                        Log.i(TAG, "Ultra 自动就绪: paired=${result.paired} state=${result.state} reason=${result.reason}")
                    }
                    .onFailure { failed("系统环境预热/自动就绪失败(忽略,不影响启动): ", it) }
                    .getOrNull()?.paired == true }
            },
            retryDetection = {
                ensureActive()
                dev.maplesu.manager.workmode.boot.ModeBootstrap.retryEnvironmentDetection()
            },
            recoverIdentity = {
                ensureActive()
                stage("identity") { runCatching {
                    val result = dev.maplesu.manager.runtime.ultra.UltraIdentityRecovery.recover(this)
                    dev.maplesu.manager.runtime.ultra.UltraStartupWarmup.identityRecovered(result)
                }.onFailure { failed("Ultra startup identity recovery pending", it) }
                    .getOrDefault(false) }
            },
            bootSessionReady = {
                ensureActive()
                bootReadiness ?: stage("boot-session") { dev.maplesu.manager.runtime.ultra.UltraSdkSession.current(this)?.let {
                    dev.maplesu.manager.runtime.ultra.UltraSdkSession.ensure(this).ready
                } }
            },
        )
        terminalReason = if (warmup.paired) warmup.reason else managerReason ?: setupReason ?: warmup.reason
        if (!warmup.paired && terminalReason in setOf("MANAGER_SU_UNVERIFIED", "AUTHORIZATION_INITIALIZATION_PENDING")) {
            startupKind = "authorization-unverified"
        }
        Log.i(TAG, "Ultra startup warmup: $warmup")
        if (warmup.paired) stage("capabilities") {
            ensureActive()
            val activation = ModeRuntime.activation
            val bootId = dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup.currentBootId()
            val key = dev.maplesu.manager.runtime.ultra.UltraSdkCredentials.read(this)
            val rootCurrent = dev.maplesu.manager.modules.UltraRootKeyStore.captureOwnerMarker(this)
            val sessionCurrent = dev.maplesu.manager.runtime.ultra.UltraSdkSession.captureOwnerMarker(this)
            val owner = dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.currentSnapshot(this).managerOwnerIdentity
            val client = dev.maplesu.manager.runtime.ultra.UltraSdkCredentials.client(this)
            val modules = client.moduleList()
            val version = client.installedEnvironmentVersion()
            val authorization = client.suUidProfiles()
            val moduleReady = modules.succeeded && modules.array("modules") != null
            val versionReady = version.succeeded && !version.string("version").isNullOrBlank()
            val scopeCurrent = ModeRuntime.activation === activation && bootId != null &&
                bootId == dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup.currentBootId() &&
                key == dev.maplesu.manager.runtime.ultra.UltraSdkCredentials.read(this) &&
                rootCurrent() && sessionCurrent() &&
                (owner == null || client.queryManagerOwnerIdentity().identity == owner)
            val ready = moduleReady && versionReady && authorization.ok && scopeCurrent
            startupReady = ready
            if (!ready) terminalReason = "CAPABILITIES_UNVERIFIED"
            Log.i(TAG, "Ultra startup result ready=$ready managerSu=true modules=$moduleReady " +
                "version=$versionReady authorization=${authorization.ok} scopeCurrent=$scopeCurrent " +
                "path=${if (live.rootAvailable) "live" else "recovery"} " +
                "passMs=${android.os.SystemClock.elapsedRealtime() - passStarted} " +
                "requestElapsedMs=${android.os.SystemClock.elapsedRealtime() - requestedAt} " +
                "startupElapsedMs=${android.os.SystemClock.elapsedRealtime() - ultraStartupTriggeredAt.get()}")
        }
        if (warmup.paired && dev.maplesu.manager.runtime.ultra.UltraSdkSession.current(this) == null) {
            dev.maplesu.manager.modules.UltraRootKeyStore.read(this)?.let {
                dev.maplesu.manager.runtime.ultra.UltraPairingMaintenance.request(this, it)
            }
        }
        if (warmup.ran && dev.maplesu.manager.runtime.ultra.UltraSdkSession.current(this) != null) {
            ensureActive()
            dev.maplesu.manager.runtime.RuntimeDeployment.resumeBootSdk(this)
        }
        if (warmup.ran) {
            ModeGate.allow(ModeComponentIds.APP_ULTRA_AUTOPAIR)
        } else {
            ModeGate.skip(ModeComponentIds.APP_ULTRA_AUTOPAIR, ModeGateFormat.Reason.NOT_APPLICABLE)
        }
        } catch (error: Throwable) {
            startupReady = false
            terminalReason = "STARTUP_FAILED:${error.javaClass.simpleName}"
            throw error
        } finally {
            val ended = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "Ultra startup terminal ready=$startupReady kind=$startupKind reason=$terminalReason " +
                "passMs=${ended - passStarted} requestElapsedMs=${ended - requestedAt} " +
                "startupElapsedMs=${ended - ultraStartupTriggeredAt.get()}")
        }
    }

    override fun onCreate() {
        ultraStartupTriggeredAt.compareAndSet(0L, android.os.SystemClock.elapsedRealtime())
        super.onCreate()
        ksuApp = this

        if (!isUserUnlocked()) {
            return
        }

        // Set the window's back policy before Activity creates and registers its callbacks.
        runCatching {
            setEnableOnBackInvokedCallback(applicationInfo, SettingsRepositoryImpl().enablePredictiveBack)
        }.onFailure { Log.w(TAG, "Predictive back preference unavailable", it) }

        runCatching {
            dev.maplesu.manager.runtime.RootExec.install(
                dev.maplesu.manager.runtime.RootExecutionRouter.production(this),
            )
        }.onFailure { Log.w(TAG, "RootExec router unavailable (fail-closed)", it) }
        // KSU 后端日志出口（Normal，2026-11-01）：`KsuLog` 默认只写内存环形缓冲，
        // 这里接到 logcat，让 `ksu.unavailable` / `ksu.rejected` 在设备上可见
        // （只挂 sink，不触碰任何后端、无 root 调用；LITE 下等价于多一条日志转发）。
        runCatching { dev.maplesu.manager.workmode.backend.KsuBackendRegistry.installLogging() }
            .onFailure { Log.w(TAG, "KSU log sink unavailable (ignored)", it) }
        // 隐藏模式生产后端接线（2026-10-30）：模式已判定才装，未判定/不支持 → 保持 null
        // （UI 得到 BACKEND_UNAVAILABLE，不假装可用）。依赖上面刚装好的 RootExec（B 通道），
        // 因此必须排在这之后；内部有界等待模式判定，不阻塞首帧、不起 root。
        runCatching { dev.maplesu.manager.litehidden.HiddenModeWiring.installOnStartup(this) }
            .onFailure { Log.w(TAG, "hidden backend unavailable (fail-closed)", it) }
        dev.maplesu.manager.workmode.boot.ModeBootstrap.start(this)
        // 阶段二（01 §2.5 同意规则）：Post 到主线程延迟 ~2.5s 后在后台线程执行一次
        // AFTER_ROOT_GRANTED 判定（allowRootProbe/allowLiteExecutor=true）。Lite 设备的可用性
        // 证据只能在这一阶段取得；没有它 Lite 会停在 MODE_A_INSTALLED_UNBOUND 被门控关死。
        // 幂等：重复调用只有第一次真正探测；已激活则跳过且绝不默认 LITE。
        dev.maplesu.manager.workmode.boot.ModeBootstrap.detectAfterRootGranted(this)
        // W19 植入登记 + 可恢复性证据：必须在切换后端装配之前安装，之后 SuDelivery/SuPathWiring
        // 的每次植入都会登记原件备份；失败则不安装（S0-15 由 NoOp hooks fail-closed 阻断）。
        val w19Hooks = runCatching {
            val sharedDir = java.io.File(
                dev.maplesu.manager.workmode.control.ControlPaths.SHARED,
            )
            val hooks = dev.maplesu.manager.workmode.superuser.createLiteImplantQuiesceHooks(sharedDir, this)
            val baseRecorder =
                dev.maplesu.manager.workmode.superuser.installLiteImplantRecorder(sharedDir, this)
            // 静默期拒绝新登记：guarded 包装必须覆盖**生产 recorder**，否则 quiesce 只挡住
            // hooks 自身路径，SuDelivery/SuPathWiring 仍可登记（W19 写者静默不完整）。
            dev.maplesu.manager.workmode.superuser.LiteImplantRecorderProvider.install(
                hooks.guardedRecorder(baseRecorder),
            )
            hooks
        }.getOrElse { error ->
            Log.w(TAG, "W19 implant hooks unavailable (S0-15 stays fail-closed)", error)
            null
        }
        // W08 UI 切换后端装配（r3 ModeSwitchRuntime）：未判定态安全、无副作用；失败不阻塞启动。
        // 装配完成后 SwitchFacadeProvider（默认 lambda）即可取到 facadeOrNull()。
        // P0：S1 快照写控制面（/data/adb/maplesu-workmode/snapshots，清数据不丢）+ 真实回滚恢复；
        // 控制面不可得时快照 fail-closed，绝不静默退回 filesDir。
        runCatching {
            dev.maplesu.manager.workmode.service.ModeSwitchRuntime.initialize(
                this,
                hooks = w19Hooks,
                snapshots = dev.maplesu.manager.workmode.snapshot.controlPlaneModeSwitchSnapshots(this),
            )
        }.onFailure { Log.w(TAG, "ModeSwitchRuntime init failed (ignored)", it) }
        // W20–W22 外部 Root 迁移生产 facade 装配：只读探针 + 设备端口；
        // 无 root/探针不可读时保持 Unavailable（诚实降级），失败不阻塞启动。
        runCatching {
            dev.maplesu.manager.workmode.migration.MigrationRuntime.initialize(this)
        }.onFailure { Log.w(TAG, "MigrationRuntime init failed (ignored)", it) }

        // ── 崩溃上报（2026-09-19 新增）──────────────────────────────────
        // ⚠️ 位置很讲究：必须在这条 directBootAware 早返回**之后**。
        //    锁屏早启动时 filesDir 还在 CE 加密里（读不了也写不了标记），而且那个进程
        //    多半马上被系统重起 —— 那种情况直接跳过抓取，不添乱。
        //    正常路径下这里只做「读一次标记 + 写一次标记」的极小磁盘操作；
        //    真正的抓取（root shell / tombstone / logcat）全部丢到后台线程。
        runCatching { startCrashSession(this) }
            .onFailure { Log.w(TAG, "崩溃上报:会话检测失败(忽略,不影响启动): ", it) }
        runCatching { installCrashHandler(this) }
            .onFailure { Log.w(TAG, "崩溃上报:安装异常兜底失败(忽略,不影响启动): ", it) }

        // ── Boot 安装事务残留清理（2026-10-29）─────────────────────────
        // 设置页不再展示「Boot 安装事务」卡（Ultra 内核上读不到状态 ⇒ 只会红字报错）。
        // 这里只做一次性清理：删掉早期版本写下的 `installer/lastTaskId`，并立自己的标记位，
        // 以后刷写功能复用同一个键时不会再被抹掉。任何异常都不能影响启动。
        runCatching { dev.maplesu.manager.installer.InstallerServiceClient.retireLegacyTaskId(this) }
            .onFailure { Log.w(TAG, "安装事务:残留任务清理失败(忽略,不影响启动): ", it) }

        // MapleSU does not migrate or rewrite another root provider's data.

        // ── Ultra 环境预热 + 自动就绪（2026-10-29）───────────────────────
        // 等检测证据完成；已激活 Ultra、有待验证安装凭据或匹配的已验证身份时才预热。
        // 首次刷入内核后 SDK 可能是 NotInstalled：先走唯一 Boot 配置入口，每 boot 最多一次，
        // 再由 SDK 真实 Running 完成配对并请求重新检测。凭据本身不激活工作模式。
        // 暂停、切换、KSU/外来 Root 冲突均跳过；JNI 留在同一个后台线程，失败不影响首帧。
        dev.maplesu.manager.runtime.ultra.UltraApplicationStartup.start(
            processName = Application.getProcessName(),
            packageName = packageName,
            warmup = { requestUltraRecovery() },
        )

        // ── SOTER lane（app 侧，2026-10-30）─────────────────────────────────
        // 模块 daemon 是裸 app_process，AMS 拒绝它的 bindService
        // （"Unable to find app for caller IApplicationThread ... uid0"）。
        // 这里由管理器用真实 app 身份绑定 com.tencent.soter.soterserver，把 13 个
        // ISoterService 事务码的应答放在本进程（SoterInterceptor），再把拦截器注册给
        // 注入在 soterserver 里的 libTEESimulator.so。开关跟随模块的 soter_forge；
        // 关闭时完全不 bind。线程自持、失败只记一行、绝不影响启动。
        runCatching { dev.maplesu.manager.soter.SoterLane.start(this) }
            .onFailure { Log.w(TAG, "SOTER lane unavailable (ignored)", it) }

        // ══════════════════════════════════════════════════════════════════
        // 🔴 v2.15：**Application 启动路径不发任何 root shell / ksud 调用**。
        //
        // 原来这里有一句
        //   `ViewModelProvider(this)[SuperUserViewModel::class.java].loadAppList()`
        // —— 它是"无条件"的：不管这台机器有没有 root、内核认不认我们，一启动就去
        // 建 root shell + 绑 libsu 的 `RootService`（`KsuService`）。
        // 而 libsu 的 RootService 会往 App 的 cache 里写一个**可写 dex**
        // （`cache/main.jar`）再用这个 shell 跑 app_process；shell 没 root 时
        // app_process 就以 App 的 uid 加载它 → ART 拒绝 → **abort()（SIGABRT/SI_QUEUE）**
        //   —— 这就是"打开就闪退"那条链的头。
        //
        // 超级用户页自己会加载（`SuperUserScreen.kt` 的 LaunchedEffect →
        // `viewModel.loadAppList()`），所以删掉这里**不丢功能**，只是把
        // 「这件事什么时候做」从"启动时无条件做"改成"用户真进那一页才做"。
        // ══════════════════════════════════════════════════════════════════

        val webroot = File(dataDir, "webroot")
        if (!webroot.exists()) {
            webroot.mkdir()
        }

        // Provide working env for rust's temp_dir()
        // ⚠️ 2026-09-19 修:`Os.setenv` 会抛 ErrnoException(例如受限 ROM 不给改环境变量),
        //    它在 onCreate 里,一抛就是启动即闪退。包起来后最坏只是 rust 的 temp_dir() 用默认值。
        runCatching { Os.setenv("TMPDIR", cacheDir.absolutePath, true) }
            .onFailure { Log.w(TAG, "设置 TMPDIR 失败(忽略): ", it) }
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
