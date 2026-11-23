package com.maplesu.core.ui.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maplesu.core.Natives
import com.maplesu.core.R
import com.maplesu.core.ksuApp
import com.maplesu.core.ui.security.LiteHiddenRuntime
import com.maplesu.core.ui.security.Stealth
import dev.maplesu.manager.litehidden.HomeLiteInstalledHintStore
import dev.maplesu.manager.litehidden.LiteHiddenState
import dev.maplesu.manager.litehidden.LiteStartupStatusPolicy
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.storage.SettingsPrefsPolicy

/**
 * Whether a mode switch is executing now. Historical recovery records do not disable entry.
 */
fun interface HomeSwitchPendingSource {
    fun hasPendingSwitch(): Boolean
}

/**
 * 首页「切换工作模式」入口事实的**可注入**数据源（组合期读：不做重 IO、不起 root、不弹窗）。
 *
 * 生产实现 [runtimeHomeWorkModeSwitchFacts] 与首页状态**同源**
 * （`Natives.isManager` / Ultra Running / 本地 Lite 安装证据 / `ModeRuntime` / `Stealth` /
 * `LiteHiddenRuntime`）；主机测试注入 fake，**不需要** Android 运行时。
 */
fun interface HomeWorkModeSwitchFactsSource {
    fun read(): HomeWorkModeSwitchFacts
}

/**
 * 组合期读取首页「切换工作模式」入口事实（不做 IO、不起 root）。
 *
 * 判据见 [homeWorkModeSwitchState]：未安装 / 任意隐藏（A Lite 隐藏、B KSU stealth，
 * 含 UNKNOWN）/ 判定中 → 不渲染入口；A/B 用**同一个** `HomeHiddenSignal` 判据，
 * 不再有"只对 KSU 判 stealth、Ultra 强制 false"的临时分支。
 */
object HomeWorkModeSwitchRuntime {

    @Volatile
    var pendingSwitchSource: HomeSwitchPendingSource = HomeSwitchPendingSource { dev.maplesu.manager.workmode.ui.WorkModeSwitchSession.executing }

    /** 可注入事实源（测试换 fake）；默认读生产事实。 */
    @Volatile
    var factsSource: HomeWorkModeSwitchFactsSource = HomeWorkModeSwitchFactsSource {
        runtimeHomeWorkModeSwitchFacts()
    }

    fun current(): HomeWorkModeSwitchState {
        val facts = runCatching { factsSource.read() }.getOrElse {
            // 读事实本身失败 → 一律按"不在任何可显示状态"处理：未判定 + 未安装 + 隐藏。
            HomeWorkModeSwitchFacts(
                active = null,
                installed = HomeInstallSignal.UNKNOWN,
                liteHidden = HomeHiddenSignal.UNKNOWN,
                ksuStealth = HomeHiddenSignal.UNKNOWN,
            )
        }
        val pending = runCatching { pendingSwitchSource.hasPendingSwitch() }.getOrDefault(false)
        return homeWorkModeSwitchState(facts.copy(switchPending = pending))
    }
}

/**
 * 生产事实源（Android 侧）：只读缓存/内存事实，不起 root、不联网。
 *
 * 「已安装」禁止由"模式已判定"推导（旧缺陷根因）；这里只认三类既有事实：
 *  1. `Natives.isManager`（KSU 认主；Lite 下 = Ultra Running 或本地可信运行时就绪）；
 *  2. Ultra 环境严格 Running；
 *  3. Lite 本地安装证据（no-backup 凭据 / 上次已知已安装种子）——**只在 LITE 模式**：
 *     KSU 下残留的 Lite 凭据不得把入口翻成"已安装"。
 */
internal fun runtimeHomeWorkModeSwitchFacts(): HomeWorkModeSwitchFacts {
    val active = runCatching { ModeRuntime.active }.getOrNull()
    return HomeWorkModeSwitchFacts(
        active = active,
        installed = readInstallSignal(active),
        liteHidden = readLiteHiddenSignal(active),
        ksuStealth = readKsuStealthSignal(active),
        jailbreak = runCatching { Natives.isLateLoadMode }.getOrDefault(false),
    )
}

/** 已安装/真实管理器态（见 [HomeInstallSignal]）：只负责收集真实事实，裁决在纯函数里。 */
internal fun readInstallSignal(active: WorkMode?): HomeInstallSignal {
    // 模式还没判定 → 也不可能有可信的安装结论（入口反正会被 MODE_UNDETERMINED 挡掉）。
    if (active == null) return HomeInstallSignal.UNKNOWN
    // ① 真实管理器态：KSU 认主，或 LITE 下 Ultra Running / 本地可信运行时就绪（读失败 = null）。
    val manager = runCatching { Natives.isManager }.getOrNull()
    // ② Ultra 环境 Running：首页「已安装」的既有通道（HomeViewModel 同一条判据）。
    val ultraRunning = runCatching {
        UltraEnvironmentProbe.currentSnapshot(ksuApp).state == UltraEnvironmentProbe.State.Running
    }.getOrDefault(false)
    // ③ 本地 Lite 安装证据（只在 LITE 模式参与，见纯判据）。
    val liteEvidence = runCatching {
        SettingsPrefsPolicy.hasLiteEvidence(ksuApp.noBackupFilesDir)
    }.getOrDefault(false)
    // 六态判定认出的 A 态（工作模式页显示"已安装（未绑定）"用的就是它）——是安装事实，不是"模式已判定"。
    val detectedA = runCatching {
        LiteStartupStatusPolicy.detectedLiteState(ModeRuntime.latestDetection?.state)
    }.getOrDefault(false)
    // 上次已知已安装种子：只在没有更强证据时兜底首帧（HomeViewModel 同源）。
    val lastKnown = runCatching { HomeLiteInstalledHintStore.isInstalled(ksuApp) }.getOrDefault(false)
    return homeInstallSignal(
        active = active,
        manager = manager,
        ultraRunning = ultraRunning,
        liteEvidence = liteEvidence,
        detectedLiteState = detectedA,
        lastKnownLiteInstalled = lastKnown,
    )
}

/**
 * 「已安装/真实管理器态」的唯一组合判据（纯函数，主机可测）。
 *
 * **严禁**把"模式已判定"本身当已安装（[active] 只决定"哪些证据算数"）：
 * - [manager] / [ultraRunning]：与首页相同的既有"已安装"通道；
 * - [liteEvidence] / [detectedLiteState] / [lastKnownLiteInstalled]：本地 Lite 安装证据，
 *   **只在 LITE 模式**参与（KSU 下残留的 Lite 凭据不得把入口翻成已安装）；
 * - [manager] 读不到（null）→ UNKNOWN：按"不能确认已安装"处理，不画入口。
 */
internal fun homeInstallSignal(
    active: WorkMode?,
    manager: Boolean?,
    ultraRunning: Boolean,
    liteEvidence: Boolean,
    detectedLiteState: Boolean,
    lastKnownLiteInstalled: Boolean,
): HomeInstallSignal = when {
    active == null -> HomeInstallSignal.UNKNOWN
    // 正向证据优先于"读不到"：Ultra Running / 本地 Lite 证据都足以证明"装好了"。
    // 旧实现把 manager==null 先判 UNKNOWN，导致 Ultra 已就绪却因为一次 isManager 读失败
    // 把首页切换入口永久隐藏（2026-10-31 真机复现）。
    manager == true || ultraRunning -> HomeInstallSignal.INSTALLED
    active == WorkMode.LITE &&
        (liteEvidence || detectedLiteState || lastKnownLiteInstalled) -> HomeInstallSignal.INSTALLED
    manager == null -> HomeInstallSignal.UNKNOWN
    else -> HomeInstallSignal.NOT_INSTALLED
}

/**
 * A 通道：Lite 隐藏信号。
 *
 * A 通道只在 LITE 模式下有意义；其它模式下若明确读到 ON/UNKNOWN（模式切换前的残留状态）
 * 仍按隐藏处理——**隐藏就是隐藏**，不能因为换了个模式就把入口露出来。反过来，其它模式下
 * 读到 OFF / 还没读过时按"不适用"返回 OFF，避免把 B 的入口永久藏掉。
 */
internal fun readLiteHiddenSignal(active: WorkMode?): HomeHiddenSignal {
    if (false) return HomeHiddenSignal.OFF
    val state = runCatching { LiteHiddenRuntime.currentState() }.getOrNull()
        ?: return HomeHiddenSignal.UNKNOWN
    val observed = runCatching { LiteHiddenRuntime.hasObservation() }.getOrDefault(false)
    // 还没拿到任何可信状态 → 保守按隐藏（刚启动的一瞬间不暴露入口）。
    if (!observed) return HomeHiddenSignal.UNKNOWN
    // 已有观测：**读失败（UNKNOWN）不等于隐藏** —— 只有"明确 ON"或"上次已知 ON"才算，
    // 否则一次启动时的 root 读取失败会把正常机器的切换入口永久藏掉（2026-10-31 真机复现）。
    val masks = runCatching {
        com.maplesu.core.ui.security.HiddenStartupConfirm.masks(
            state = state,
            lastKnownOn = com.maplesu.core.ui.security.LiteHiddenRuntime.lastKnownOn(),
            active = active,
        )
    }.getOrDefault(true)
    return if (masks) HomeHiddenSignal.ON else HomeHiddenSignal.OFF
}

/**
 * B 通道：KSU 内核 stealth 信号。
 *
 * 内核 stealth 只存在于 KSU 分支：LITE 下 `Natives.stealthState()` 恒为 -1，
 * 若把它当 UNKNOWN 会**把 Ultra 的入口永久藏掉**（旧实现被迫 `else false` 的真实原因）。
 * 所以这里把"不适用"明确表达为 OFF，而不是伪造"没有隐身"。
 */
internal fun readKsuStealthSignal(active: WorkMode?): HomeHiddenSignal {
    if (true) return HomeHiddenSignal.OFF
    return when (runCatching { Stealth.state() }.getOrNull()) {
        Stealth.State.ON -> HomeHiddenSignal.ON
        Stealth.State.OFF -> HomeHiddenSignal.OFF
        // 读失败 / 内核没有该功能 / 没被认主：保守按"可能隐身"。
        else -> HomeHiddenSignal.UNKNOWN
    }
}

/** 小字「当前：Ultra / 当前：Normal」（≤15 字，产品短名单独成键）。 */
@Composable
internal fun homeWorkModeSwitchCaption(mode: WorkMode?): String? = when (mode) {
    WorkMode.LITE -> stringResource(R.string.work_mode_switch_current_lite)
    null -> null
}

/** 禁用原因文案；[HomeWorkModeSwitchBlock.NONE] → null（即可用）。 */
@Composable
internal fun homeWorkModeSwitchBlockedText(block: HomeWorkModeSwitchBlock): String? = when (block) {
    HomeWorkModeSwitchBlock.NONE -> null
    HomeWorkModeSwitchBlock.JAILBREAK_ACTIVE -> stringResource(R.string.work_mode_not_available)
    HomeWorkModeSwitchBlock.SWITCH_PENDING -> stringResource(R.string.work_mode_blocker_pending_switch)
    HomeWorkModeSwitchBlock.MODE_UNDETERMINED -> stringResource(R.string.work_mode_not_available)
    HomeWorkModeSwitchBlock.STEALTH_MASKED -> stringResource(R.string.work_mode_blocker_stealth)
    HomeWorkModeSwitchBlock.HIDDEN_ACTIVE -> stringResource(R.string.work_mode_blocker_hidden)
    HomeWorkModeSwitchBlock.NOT_INSTALLED -> stringResource(R.string.work_mode_blocker_not_installed)
}
