package com.maplesu.core.ui.screen.home

import androidx.compose.runtime.Immutable
import dev.maplesu.manager.litehidden.LiteHiddenState
import dev.maplesu.manager.workmode.WorkMode

/**
 * 首页「切换工作模式」入口**为什么**不显示/不可用（[NONE] = 可用）。
 *
 * 用户实测反馈（2026-10-30 第二轮）两条真实缺陷：
 *  1. 旧判据只看 `ModeRuntime.active != null` —— **未安装**的机器、或**隐藏/隐身开着**的机器
 *     照样画出入口。`模式已判定` ≠ `装好了`，更 ≠ `可以露出来`；
 *  2. 旧代码 `masked = if (active == KSU) Stealth.looksStealthy() else false` 是错误修补：
 *     Ultra 下把隐身信号硬置 false，于是 Lite 隐藏开着也照样显示入口（自欺欺人）。
 *
 * 现在的唯一判据：`active != null` **且** 已安装/真实管理器态 **且** 没有任何隐藏/隐身信号。
 */
enum class HomeWorkModeSwitchBlock {
    /** 可用（模式已判定、确实已安装、没有隐藏、没有未完成的切换）。 */
    NONE,

    /** 上一次切换还没收尾（`switch.json` 非终态）：入口保留但禁用。 */
    SWITCH_PENDING,


    /** 当前模式还没判定（`ModeRuntime.active == null`）：不显示入口。 */
    MODE_UNDETERMINED,

    /** B：KSU 内核 stealth 开着（含读不到的 UNKNOWN）：任何模式信息都不得出现。 */
    STEALTH_MASKED,

    /** A：Lite 隐藏开着（含读不到的 UNKNOWN）：App 整体按「未安装」呈现。 */
    HIDDEN_ACTIVE,

    /** 未安装 / 已安装证据拿不到 / 判定中：不显示入口（绝不靠"模式已判定"冒充已安装）。 */
    NOT_INSTALLED,
}

/**
 * 「已安装 / 真实管理器态」三态（与首页状态同源）。
 *
 * - [INSTALLED]：`Natives.isManager`（KSU 认主 / Lite 下 Ultra Running 或本地可信运行时就绪）、
 *   Ultra 环境 Running、或本地 Lite 安装证据至少一条成立；
 * - [NOT_INSTALLED]：以上证据全部不成立（判定已落定）；
 * - [UNKNOWN]：还没判定、或读证据本身失败 —— **一律按"不能确认已安装"处理**（不画入口）。
 */
enum class HomeInstallSignal {
    INSTALLED,
    NOT_INSTALLED,
    UNKNOWN,
    ;

    val isInstalled: Boolean get() = this == INSTALLED
}

/**
 * 「隐藏/隐身」信号（A: Lite 隐藏；B: KSU 内核 stealth）。
 *
 * [UNKNOWN] = 读不到 → **保守按开启**（宁可少画，不可泄漏）。只有明确 [OFF] 才允许显示入口。
 */
enum class HomeHiddenSignal {
    OFF,
    ON,
    UNKNOWN,
    ;

    /** 是否必须把 App 当作"隐藏中"（UNKNOWN 也算）。 */
    val masks: Boolean get() = this != OFF

    companion object {
        /** 布尔折叠：true = 明确开启。 */
        fun of(masking: Boolean): HomeHiddenSignal = if (masking) ON else OFF

        /**
         * A 通道：Lite 隐藏状态 → 信号。
         *
         * [observed] 为 false（进程内还没拿到可信状态，例如 prefs 种子读不到）→ UNKNOWN：
         * 旧实现把"还没读过"的默认 OFF 当事实，正是隐藏开启后仍显示入口的来源之一。
         */
        fun ofLiteHidden(state: LiteHiddenState, observed: Boolean): HomeHiddenSignal = when {
            !observed -> UNKNOWN
            state == LiteHiddenState.OFF -> OFF
            state == LiteHiddenState.ON -> ON
            // 读失败 / 坏写：保守按 ON。
            else -> UNKNOWN
        }
    }
}

/**
 * 首页入口的**全部**输入事实（纯数据 → JVM 可测；生产侧由
 * [HomeWorkModeSwitchFactsSource] 注入，测试直接构造）。
 */
@Immutable
data class HomeWorkModeSwitchFacts(
    /** 当前激活模式；null = 还在判定中。 */
    val active: WorkMode?,
    /** 已安装 / 真实管理器态。 */
    val installed: HomeInstallSignal,
    /** A：Lite 隐藏信号（默认 OFF = 不适用/明确关闭）。 */
    val liteHidden: HomeHiddenSignal = HomeHiddenSignal.OFF,
    /** B：KSU 内核 stealth 信号（默认 OFF = 不适用/明确关闭）。 */
    val ksuStealth: HomeHiddenSignal = HomeHiddenSignal.OFF,
    /** 上一次切换未收尾（入口保留但禁用）。 */
    val switchPending: Boolean = false,
) {
    /** 任意一路隐藏/隐身开启（含 UNKNOWN）→ 整个 App 必须呈"未安装"，入口不可见。 */
    val hiddenMasked: Boolean get() = liteHidden.masks || ksuStealth.masks
}

/**
 * 首页「切换工作模式」入口的渲染事实（纯 Kotlin，无 Android 依赖 → 主机单测）。
 *
 * 只有 [visible] 为 true 才画；[enabled] 为 false 时按钮禁用，原因由 [blocked] 映射成文案。
 */
@Immutable
data class HomeWorkModeSwitchState(
    val visible: Boolean,
    val enabled: Boolean,
    /** 当前激活模式；[visible] 时必非空（用于「当前：Ultra / 当前：Normal」小字）。 */
    val current: WorkMode?,
    val blocked: HomeWorkModeSwitchBlock,
) {
    companion object {
        /** 首帧/未判定：不画。 */
        val HIDDEN = HomeWorkModeSwitchState(
            visible = false,
            enabled = false,
            current = null,
            blocked = HomeWorkModeSwitchBlock.MODE_UNDETERMINED,
        )
    }
}

/**
 * 唯一裁决（纯函数）：`模式已判定 + 确实已安装 + 没有任何隐藏 + 未完成切换 → 入口可见/可用`。
 *
 * 顺序敏感（安全优先）：
 *  1. **隐藏/隐身**最优先 —— 即使模式已判定、已安装也一律不画（A/B 同一判据）；
 *  2. 模式未判定 → 不画；
 *  3. **没有真实的"已安装/管理器"证据** → 不画（不再把"模式已判定"当已安装）；
 *  4. 上一次切换未收尾 → 画出来但禁用（让用户找得到进度/恢复）。
 *
 * 不可见时 [HomeWorkModeSwitchState.current] 一律为 null：入口都没画，就不能有任何
 * "当前是 Ultra/Normal"的信息残留在状态里。
 */
fun homeWorkModeSwitchState(facts: HomeWorkModeSwitchFacts): HomeWorkModeSwitchState = when {
    facts.liteHidden.masks -> hiddenState(HomeWorkModeSwitchBlock.HIDDEN_ACTIVE)
    facts.ksuStealth.masks -> hiddenState(HomeWorkModeSwitchBlock.STEALTH_MASKED)

    facts.active == null -> HomeWorkModeSwitchState(
        visible = false,
        enabled = false,
        current = null,
        blocked = HomeWorkModeSwitchBlock.MODE_UNDETERMINED,
    )

    !facts.installed.isInstalled -> HomeWorkModeSwitchState(
        visible = false,
        enabled = false,
        current = null,
        blocked = HomeWorkModeSwitchBlock.NOT_INSTALLED,
    )


    facts.switchPending -> HomeWorkModeSwitchState(
        visible = true,
        enabled = false,
        current = facts.active,
        blocked = HomeWorkModeSwitchBlock.SWITCH_PENDING,
    )

    else -> HomeWorkModeSwitchState(
        visible = true,
        enabled = true,
        current = facts.active,
        blocked = HomeWorkModeSwitchBlock.NONE,
    )
}

private fun hiddenState(block: HomeWorkModeSwitchBlock): HomeWorkModeSwitchState =
    HomeWorkModeSwitchState(
        visible = false,
        enabled = false,
        current = null,
        blocked = block,
    )
