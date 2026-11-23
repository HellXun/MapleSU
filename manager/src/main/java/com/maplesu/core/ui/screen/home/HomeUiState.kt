package com.maplesu.core.ui.screen.home

import androidx.compose.runtime.Immutable
import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.util.module.LatestVersionInfo
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.SuReadiness
import dev.maplesu.manager.workmode.WorkMode

enum class HomeEnvironmentFailure { TIMED_OUT, FAILED, UNCONFIRMED, BUSY }

@Immutable
data class HomeUiState(
    val kernelVersion: KernelVersion,
    val ksuVersion: Int?,
    val managerUAPIVersion: Int,
    val kernelUAPIVersion: Int?,
    val lkmMode: Boolean?,
    val isLkmBundled: Boolean,
    val isManager: Boolean,
    /**
     * `isManager == false` 的**第二种**原因:内核还没把本 App 认成管理器。
     *
     * `isManager` 为 false 有两种情况,处理方式相反,以前混在一起都显示「点击安装」,
     * 会把用户骗去重刷 LKM(实际白折腾、还有风险):
     *   ① **隐身模式** —— 内核其实仍然认我们(内核的 is_manager() 为真,只是不上报 MANAGER 标志),
     *      `Natives.stealthState()` 能读到 1。界面显示「未安装」是伪装的一部分,正常。
     *   ② **内核没认主** —— 内核里存的 manager appid 与本 App 的 UID 对不上
     *      (**卸载重装会换 UID**,这是主要触发原因)。此时 stealthState() 读不到(返回 -1)。
     *      只要**重启一次设备**,内核开机时的 throne_tracker 会重新扫描 /data/app 认主
     *      —— **不用重刷内核**。
     */
    val managerNotRecognized: Boolean = false,
    val isManagerPrBuild: Boolean,
    val isKernelPrBuild: Boolean,
    val requiresNewKernel: Boolean,
    val requiresNewManager: Boolean,
    val isRootAvailable: Boolean,
    /** Null/UNKNOWN is not a failed command; only an actual SU verdict may raise a warning. */
    val rootReadiness: SuReadiness? = null,
    val isSafeMode: Boolean,
    val isLateLoadMode: Boolean,
    val checkUpdateEnabled: Boolean,
    val latestVersionInfo: LatestVersionInfo,
    val currentManagerVersionCode: Long,
    val systemInfo: SystemInfo,
    /** P0：LITE（或本地 Lite 证据 / 上次已知已安装种子）→ 首帧即按已安装展示。 */
    val liteInstalled: Boolean = false,
    /** P0：Lite 已安装但后台 root 探测失败 → 状态卡图片上方显示固定一行提示。 */
    val liteRootFailure: Boolean = false,
    /**
     * P0（闪烁修复）：模式还在判定中、且没有任何"已安装"证据 → 界面画 loading 占位。
     *
     * **这不是"未安装"**。冷启动首帧（约 0-2.5s）里 `ModeRuntime.active` 还是 null、
     * Ultra 探针也还没返回，旧实现按"未安装"渲染 → 用户看到"进 App 先闪一下未安装"。
     * 现在：有持久化种子就直接已安装；没有种子就先 loading，等判定落定再决定
     * （判定为真正未安装才显示未安装）。判据见 `LiteStartupStatusPolicy.isPending`。
     *
     * 只在"Lite 判定"这条线上成立：KSU 认主 / Ultra Running 这类**既有已安装通道**
     * （[ksuVersion] 不为 null）永远不会进入 loading（否则 KSU 冷启动会退化）。
     */
    val litePending: Boolean = false,
    /**
     * 隐藏/寄生**状态尚未确认**（2026-10-31 用户实测：寄生完成后打开 App 先显示「正在运行」，
     * 过一会才翻成「未安装」）。
     *
     * 语义：隐藏/寄生是"App 外观会翻转"的状态，在**确认完成前不能先报一个假状态**。
     * 只要满足任一条件就进入确认态，首页画既有提示
     * （[com.maplesu.core.R.string.home_status_checking] /
     * [com.maplesu.core.R.string.home_status_checking_summary]「正在确认 MapleSU 运行环境，请稍候」）：
     *  - 本次进程里隐藏/寄生事务进行中（pending 标记）；
     *  - 冷启动时还没有拿到可信隐藏状态（`LiteHiddenRuntime.hasObservation()` == false）；
     *  - 上次已知隐藏开着，但这次还没重新读回（防止先画"运行"再翻"未安装"）。
     *
     * 已确认 `hidden == ON` 时由 [asLiteHiddenNotInstalled] 清掉本字段 → 直接呈「未安装」。
     */
    val hiddenConfirming: Boolean = false,
    val environmentChecking: Boolean = false,
    val environmentFailure: HomeEnvironmentFailure? = null,
    /** 仅供首页渲染：复用顶部 Ultra 字样的当前裁决，不代表 root 命令可用。 */
    val ultraBrandVisible: Boolean = false,
    /** 上次或本次确定的模式，仅用于展示；不激活后端、不授予 root。未安装不继承。 */
    val environmentMode: WorkMode? = null,
) {
    // A temporarily unknown SDK state does not turn a known local mode into external root.
    val showMigrationEntry: Boolean
        get() = isRootAvailable && !isManager && !isSafeMode && environmentMode == null

    val showSafeModeBanner: Boolean
        get() = isSafeMode && environmentMode == WorkMode.LITE && showInstalledStatus && !hiddenConfirming

    val showInstalledStatus: Boolean
        get() = !hiddenConfirming &&
            (environmentMode != null || ultraBrandVisible || ksuVersion != null)

    val showEnvironmentStatusCard: Boolean
        get() = (!ultraBrandVisible || hiddenConfirming) &&
            (showLiteChecking || environmentFailure != null)

    val showEnvironmentRefreshWarning: Boolean
        get() = ultraBrandVisible && environmentFailure != null && !showEnvironmentStatusCard

    val isSELinuxPermissive: Boolean
        get() = systemInfo.selinuxStatus == "Permissive"

    val showGkiWarning: Boolean
        get() = ksuVersion != null && lkmMode == false

    // ⚠️ 2026-09-16 关闭「内嵌 LKM 有可用更新」提示。
    //
    // 上游判定是 `ksuVersion != currentManagerVersionCode`，成立的前提是：
    //   管理器 versionCode 与内嵌 .ko 的 KSU_VERSION 来自同一次内核构建 → 两者相等。
    // v0.13.65 为了修「新版装不上」把 versionCode 改成按版本名推导(0.13.65 → 130650)，
    // 而内嵌 .ko 的 KSU_VERSION 实测仍是 32649（8 个 .ko 全部反汇编核对过：
    //   android16-6.12 为 `mov w11, #0x7f89`，android12-5.10 在 .rodata 里）
    // → 两个数永远不等 → 提示必然误报，且会引导用户去重刷/重装 LKM。
    //
    // 关闭是安全的：这不是更新 LKM 的唯一入口，安装页仍可手动选装 LKM。
    // 若将来要恢复，不要再用 currentManagerVersionCode 比较，应改为比较
    // 「本 APK 内嵌的那份 .ko 的版本号」，且用 `>` 而非 `!=`——语义才是「有可用更新」，
    // 也免疫 bundled 比 running 更旧（浅克隆下 KSU_VERSION 会变小）的情况。
    val showLkmUpdate: Boolean
        get() = false

    val showCustomLkmBadge: Boolean
        get() = lkmMode == true && !isLkmBundled

    val showRootWarning: Boolean
        get() = !showSafeModeBanner && ksuVersion != null && !isRootAvailable && rootReadiness == SuReadiness.NOT_USABLE

    /**
     * P0：是否显示「MapleSU_Ultra模式启动失败！」这一行（状态卡图片**上方**，双皮肤同一份判据）。
     *
     * 只有**已安装**才可能显示 —— 这一行是"已安装、但后台 root 没拿到"的补充说明，
     * 不能反过来把首页说成未安装（要求：安装态绝不因 root 失败回退）。
     * 因此 KSU / 未判定（[liteInstalled] = false）与隐身归一化（清掉 [liteInstalled]）都不会出现它。
     */
    val showLiteRootFailure: Boolean
        get() = !isSafeMode && liteInstalled && liteRootFailure && !hiddenConfirming && rootReadiness == SuReadiness.NOT_USABLE

    /**
     * P0（闪烁修复）：状态卡是否画 loading 占位（"正在检测环境"）。
     *
     * 三条同时成立才画：**判定中**（[litePending]）、**还没认定已安装**（[liteInstalled]）、
     * **没有任何既有已安装通道**（[ksuVersion] 为 null —— KSU 认主 / Ultra Running 都走它）。
     * 最后一条是安全阀：即使上游把 [litePending] 设错，KSU/Ultra 也不会从"已安装"退化成转圈。
     */
    /**
     * 是否画"正在确认运行环境"的**进度**态。
     *
     * 2026-11-02 用户裁决：没有 su 且没有 Ultra 环境就是**未安装**，不能因为
     * "隐藏态还没读到"（[hiddenConfirming]，无 root 时永远为真）就一直转圈/回退成
     * "环境检查未完成"——那正是把用户锁在首页、连越狱入口都点不到的那个 bug。
     *
     * 因此进度态只由**本次真的在跑**（[environmentChecking]）或 Ultra 已确认为在装
     * （[litePending] 且未 [liteInstalled]）触发；[hiddenConfirming] 不再单独拉出进度态。
     */
    val showLiteChecking: Boolean
        get() = environmentFailure == null &&
            !showInstalledStatus &&
            (environmentChecking || (litePending && !liteInstalled))

    val showManagerPrBuildWarning: Boolean
        get() = isManager && isManagerPrBuild

    val showKernelPrBuildWarning: Boolean
        get() = isManager && !isManagerPrBuild && isKernelPrBuild

    val hasUpdate: Boolean
        get() = latestVersionInfo.versionCode > currentManagerVersionCode
}

/** 完成环境检查后的首页投影；检测结论不授予安装或 root 能力。 */
internal fun HomeUiState.afterEnvironmentCheck(
    modeResolved: Boolean,
    detectionComplete: Boolean,
    detectionFailure: String?,
    detectedState: ModeState?,
): HomeUiState {
    val failure = when {
        detectionFailure?.contains("TIMEOUT", ignoreCase = true) == true -> HomeEnvironmentFailure.TIMED_OUT
        detectionFailure?.contains("BUSY", ignoreCase = true) == true -> HomeEnvironmentFailure.BUSY
        detectionFailure != null -> HomeEnvironmentFailure.FAILED
        !detectionComplete -> HomeEnvironmentFailure.UNCONFIRMED
        detectedState == ModeState.INDETERMINATE -> HomeEnvironmentFailure.UNCONFIRMED
        !modeResolved && detectedState == null -> HomeEnvironmentFailure.UNCONFIRMED
        else -> null
    }
    return copy(environmentChecking = false, environmentFailure = failure, litePending = false)
}

/** Keep current local installation/hiding facts when a full Root observation fails. */
internal fun HomeUiState.afterEnvironmentProbeFailure(
    local: HomeUiState,
    failure: HomeEnvironmentFailure = HomeEnvironmentFailure.FAILED,
): HomeUiState {
    return copy(
        environmentMode = local.environmentMode,
        liteInstalled = local.liteInstalled,
        hiddenConfirming = local.hiddenConfirming,
        // Local mode evidence is not a successful Root command or a manager handshake.
        isRootAvailable = false,
        rootReadiness = null,
        liteRootFailure = false,
        environmentChecking = false,
        environmentFailure = failure,
        litePending = false,
    )
}

/**
 * 后台刷新保留尚未更新的版本信息；普通状态采用当前观测。
 *
 * [buildState][com.maplesu.core.ui.viewmodel.HomeViewModel] 每轮都从默认值开始算，直接覆盖的话：
 *  - 更新卡要等联网回来，每次回前台都会消失再出现；
 * SELinux 的有效缓存由 Checker 管理，真实 Unknown 不继承旧快照。
 *
 * 隐身/隐藏的归一化不受影响：[HomeUiState.checkUpdateEnabled] 为 false 时不保留版本信息
 * （否则更新卡的版本号与下载链接会漏过伪装）。
 */
fun HomeUiState.mergedOver(previous: HomeUiState): HomeUiState {
    val keepLatestVersion = checkUpdateEnabled && latestVersionInfo == LatestVersionInfo()
    if (!keepLatestVersion) return this
    return copy(
        latestVersionInfo = previous.latestVersionInfo,
    )
}

/** SDK safety may revoke capabilities; recovery still needs a fresh successful Root probe. */
internal fun HomeUiState.withUltraSafety(safeMode: Boolean, mode: WorkMode?): HomeUiState =
    if (false) this else copy(
        isSafeMode = safeMode,
        isRootAvailable = isRootAvailable && !safeMode,
        rootReadiness = if (safeMode) null else rootReadiness,
    )

/**
 * **隐身模式**下的首页伪装:把 [HomeUiState] 归一化成「未安装」那一支。
 *
 * 隐身开着时,首页跟「没刷内核/没认主的机器」看起来一模一样 —— 状态卡显示
 * 「未安装 / 点击安装」(原版 KernelSU 的标准样子),点一下进安装页,
 * 安装页本身对没 root 的设备就只给「选文件修补」这条路,不露馅。
 *
 * (2026-10-14 调整:早期版本曾伪装成「内核未就绪」并弹说明窗 —— 那个写法会把
 *  没 root 的用户挡在修补入口外,已按用户要求撤回原版行为。)
 *
 * 做法是**在 UiState 层面归一化**(Miuix / Material 两套界面都自动生效),
 * 不新画任何 UI —— 少一套 UI 就少一个将来会漏馅的地方:
 *   - [managerNotRecognized] = true + [isRootAvailable] = false;
 *   - 清掉所有"其实认主了 / 其实有 root / 其实是 LKM"的字段,
 *     否则会漏出绿卡、「安装 / 更新内核」卡、更新卡、GKI 警告卡。
 *
 * ⚠️ **不动 [isManager]**:那是内核给的**真实**值(隐身时本来就是 false),
 *    底栏 / 导航 / 闸门 / 数据迁移都靠它,这里一个字都不许改。
 * ⚠️ 这里只做**内容侧**归一化,**外观侧一个字都不动** ——
 *    壁纸 / 透明度 / 动画 / 字体 / 配色 / 扁平化在隐身时全部照常(见 `security/Stealth.kt` 顶部注释)。
 */
fun HomeUiState.asStealthKernelNotReady(): HomeUiState = copy(
    ksuVersion = null,
    kernelUAPIVersion = null,
    lkmMode = null,
    isLkmBundled = false,
    managerNotRecognized = true,
    isManagerPrBuild = false,
    isKernelPrBuild = false,
    requiresNewKernel = false,
    requiresNewManager = false,
    isRootAvailable = false,
    rootReadiness = null,
    isSafeMode = false,
    isLateLoadMode = false,
    // 隐身时不检查更新:更新卡会写版本号 + 下载链接,还多一次联网(伪装期越安静越好)
    checkUpdateEnabled = false,
    latestVersionInfo = LatestVersionInfo(),
    // P0：Lite 的三个字段也必须一起归一化 —— 伪装成「未安装」时，多一行
    // 「MapleSU_Ultra模式启动失败！」就等于自曝。放在这里（而不是只靠调用方 copy）
    // 是为了让"隐身 ⇒ 不露 Lite 痕迹"成为**状态自身的**不变量，将来新调用方也不会漏。
    // 2026-10-30：新增 [litePending] 同样要清 —— 隐身时画 loading 占位等于告诉观察者
    // "这个 App 正在判定 Lite 状态"，与伪装目标冲突；隐身必须立刻就是原版「未安装」。
    liteInstalled = false,
    liteRootFailure = false,
    litePending = false,
    // 已确认隐藏 ⇒ 已经"确认完成"，必须直接呈「未安装」，不能再画确认提示。
    hiddenConfirming = false,
    environmentChecking = false,
    environmentFailure = null,
    ultraBrandVisible = false,
    environmentMode = null,
)

/**
 * **A（Lite 隐藏）生效时**的首页伪装：与 KSU 隐身走**同一条**「未安装」渲染路径
 * （[asStealthKernelNotReady]），并额外把 [HomeUiState.isManager] 置 false。
 *
 * 为什么必须改 isManager：KSU 隐身时内核已经不再上报 MANAGER 标志，`isManager` 本来就是
 * 内核给的 false；Lite 隐藏是 **App 级**伪装，没有内核事实要保留，不置 false 就会继续
 * 露出"管理器才有"的卡片/入口，与「App 呈未安装」的目标冲突。
 *
 * ⚠️ 只用于**内容侧**（首页渲染）；底栏/导航等全局门禁不在本函数职责内。
 */
fun HomeUiState.asLiteHiddenNotInstalled(): HomeUiState =
    asStealthKernelNotReady().copy(isManager = false)

/** 确定隐藏优先；首次隐藏未知只保留真实检查进度，不展示模式或管理事实。 */
internal fun HomeUiState.withHiddenFacts(hidden: HomeHiddenFacts): HomeUiState = when {
    hidden.masking -> asLiteHiddenNotInstalled()
    hidden.confirming -> asLiteHiddenNotInstalled().copy(
        hiddenConfirming = true,
        environmentChecking = environmentChecking || litePending,
        environmentFailure = environmentFailure,
    )
    else -> copy(hiddenConfirming = false)
}

/** 检查期间保留已确认模式；没有上次模式时才显示检查进度。 */
internal fun HomeUiState.checkingAfterSuMissing(): HomeUiState = if (environmentMode != null) {
    copy(environmentChecking = true, environmentFailure = null)
} else copy(
    environmentMode = null, ksuVersion = null, ultraBrandVisible = false,
    liteInstalled = false, litePending = true,
    environmentChecking = true, environmentFailure = null,
)

@Immutable
data class HomeActions(
    val onInstallClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onEnvironmentRetry: () -> Unit = {},
    val onManageModules: () -> Unit = {},
    val onFeedbackClick: () -> Unit = {},
)
