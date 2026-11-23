package dev.maplesu.manager.liteweb

import dev.maplesu.manager.runtime.RootBackend
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.ModeState

/**
 * Lite webadmin 启动策略（三态，主机可测；不依赖 Android/RootService）：
 *
 * - `LITE_KERNEL` → 既有 libsu `RootService` 绑定路径；
 * - `ULTRA_ENV` / 当前启动已验证的 `HOOK` → 经既有根通道启动独立 daemon，失败 →
 *   `ROOT_BACKEND_ULTRA_ENV_DAEMON_UNSUPPORTED`（不假装已启动）；
 * - `NONE`/非 LITE → 不可用，返回明确错误码。
 *
 * **未判定（`mode == null`）的处理（2026-10-31 真机缺陷 3）**：冷启动时 `ModeRuntime.active`
 * 仍是 Unresolved，旧实现直接判 `MODE_NOT_LITE` —— 而设备上装的确实是 Ultra/Ultra 环境，
 * 用户看到的是"不是 LITE 模式"这种误导性错误。现在的规则：
 *
 * - `mode == null` **且** 有 LITE 证据（已装的 root 实现 = Ultra 内核凭据 / Ultra 环境 Running，
 *   见 [hasLiteEvidence]）→ 按已装实现选择启动路径，并给出 `LITEWEB_MODE_INFERRED_LITE` warning；
 * - `mode == null` 且无证据 → `LITEWEB_MODE_UNRESOLVED`（区分性错误码，不再谎称"模式不是 Lite"）；
 * - `mode == KSU` → 仍然硬拒绝（`MODE_NOT_LITE`），绝不因为"有 root"就把 B 模式的 daemon 放进来；
 * - 明确的 B 证据（`MODE_B_*` / `FOREIGN_SU` / `NOT_INSTALLED`）→ 也不推断成 LITE。
 */
enum class LiteWebStartStrategy {
    ROOT_SERVICE,
    ULTRA_ENV_DAEMON,
    UNAVAILABLE,
}

object LiteWebBackendPolicy {
    const val ROOT_BACKEND_UNAVAILABLE = "LITEWEB_ROOT_BACKEND_UNAVAILABLE"
    const val ULTRA_ENV_DAEMON_UNSUPPORTED = "ROOT_BACKEND_ULTRA_ENV_DAEMON_UNSUPPORTED"
    const val MODE_NOT_LITE = "LITEWEB_MODE_NOT_LITE"

    /** 未判定且**没有任何 LITE 证据**：区分性错误码（不是"模式不是 Lite"）。 */
    const val MODE_UNRESOLVED = "LITEWEB_MODE_UNRESOLVED"

    /** 未判定但按已装实现推断为 LITE：warning，不阻断。 */
    const val MODE_INFERRED_LITE = "LITEWEB_MODE_INFERRED_LITE"

    fun strategy(
        mode: WorkMode?,
        backend: RootBackend,
        liteInstalled: Boolean = false,
    ): LiteWebStartStrategy = when (mode) {
        WorkMode.LITE -> backendStrategy(backend)
        WorkMode.KSU -> LiteWebStartStrategy.UNAVAILABLE
        // 未判定：只有拿到 LITE 证据才按 LITE 处理（不放宽成"任何模式都能起 daemon"）。
        null -> if (liteInstalled) backendStrategy(backend) else LiteWebStartStrategy.UNAVAILABLE
    }

    private fun backendStrategy(backend: RootBackend): LiteWebStartStrategy = when (backend) {
        RootBackend.LITE_KERNEL -> LiteWebStartStrategy.ROOT_SERVICE
        RootBackend.ULTRA_ENV -> LiteWebStartStrategy.ULTRA_ENV_DAEMON
        // KSU 内核根不是 Lite WebUI 的合法后端（Ultra 专属功能，Normal 下不应起 daemon）。
        RootBackend.KSU -> LiteWebStartStrategy.UNAVAILABLE
        RootBackend.NONE -> LiteWebStartStrategy.UNAVAILABLE
    }

    /** 不可用时的结构化错误码（UNAVAILABLE 分支）。 */
    fun unavailableCode(
        mode: WorkMode?,
        backend: RootBackend,
        liteInstalled: Boolean = false,
    ): String = when (mode) {
        WorkMode.LITE -> when (backend) {
            RootBackend.NONE -> ROOT_BACKEND_UNAVAILABLE
            else -> ULTRA_ENV_DAEMON_UNSUPPORTED
        }
        WorkMode.KSU -> MODE_NOT_LITE
        // 有证据却没起来 = 根后端问题；没证据 = 模式未判定。两者不混为一谈。
        null -> if (liteInstalled) ROOT_BACKEND_UNAVAILABLE else MODE_UNRESOLVED
    }

    /** 未判定 + 有 LITE 证据时给出的 warning 码（空串 = 无 warning）。 */
    fun inferredWarning(mode: WorkMode?, liteInstalled: Boolean): String =
        if (mode == null && liteInstalled) MODE_INFERRED_LITE else ""

    /**
     * LITE 证据判定（纯函数）：**已装的 root 实现** + **最近一次模式判定**。
     *
     * @param installed 当前设备上"装了什么 root 实现"（[dev.maplesu.manager.runtime.RootExec.capability]）；
     *   `LITE_KERNEL` = Ultra 内核（libmaplesu_runtime + 凭据都在），`ULTRA_ENV` = Ultra 环境 Running。
     *   这里**不发起任何 root 执行**，只是读既有能力缓存。
     * @param state 最近一次 `ModeDetector` 结果；`null` = 还没判定完（冷启动窗口）。
     */
    fun hasLiteEvidence(installed: RootBackend, state: ModeState?): Boolean = when (state) {
        // 明确的 B / 外来 root / 未安装：不能推断成 LITE（否则会破坏模式隔离）。
        ModeState.MODE_B_ACTIVE,
        ModeState.MODE_B_KERNEL_ONLY,
        ModeState.FOREIGN_SU,
        ModeState.NOT_INSTALLED,
        -> false
        // A 已装/正在跑：有 LITE 证据，但仍要求有一个可用的已装实现。
        ModeState.MODE_A_ACTIVE,
        ModeState.MODE_A_INSTALLED_UNBOUND,
        -> installed == RootBackend.LITE_KERNEL || installed == RootBackend.ULTRA_ENV
        // 未判定 / 读不到：按"当前安装的 root 实现"推断（Ultra 内核或 Ultra 环境存在即算证据）。
        ModeState.INDETERMINATE,
        null,
        -> installed == RootBackend.LITE_KERNEL || installed == RootBackend.ULTRA_ENV
    }
}
