// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.modules

import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T10：模块列表"启动中"的**具名等待原因**（不是失败）。
 *
 * 为什么需要它（开发文档 D4）：模块列表在 Activity 组合期就被预取，而 KSU 后端只在
 * `ModeBootstrap` 的 `Activated` 观察者里接线（阶段二还有 2.5 s 延迟）。预取撞上这两个
 * 窗口时，`ModuleRepositoryImpl` 会如实返回 `Unresolved` / `Unavailable`；旧实现把它当
 * 终态失败（`loadError` + `hasLoaded=true`）⇒ **第一次失败即"永久空态"**。
 */
enum class ModuleWaitReason(val code: String) {
    /** `ModeRuntime.active == null`：本进程还没判定出工作模式。 */
    MODE_UNRESOLVED("MODE_UNRESOLVED"),

    /** 已判定为 KSU，但模块端口还没接线（`ModuleBackendPorts.ksuFactory` 仍为 null）。 */
    KSU_BACKEND_UNWIRED("KSU_BACKEND_UNWIRED"),
}

/**
 * 运行环境接线事实（只读快照：取值不得触发任何后端/root 调用）。
 */
data class ModuleRuntimeFacts(
    val mode: WorkMode?,
    val ksuBackendWired: Boolean,
    /** KSU 接线已经**明确失败**：不会再有一次事件，必须尽快给出可见错误。 */
    val ksuWiringFailed: Boolean = false,
) {
    /**
     * null = 可以立刻取列表；非空 = 必须等待的具名原因。
     *
     * LITE 的端口恒可用（`DefaultLiteModuleOpsPort.isAvailable()`），所以 LITE **永不等待**——
     * Ultra 行为逐字节不变。
     */
    val waitReason: ModuleWaitReason?
        get() = when (mode) {
            null -> ModuleWaitReason.MODE_UNRESOLVED
            WorkMode.LITE -> null
        }
}

/** 一次列表请求的决策：立刻取，还是先进入"启动中"。 */
sealed interface ModuleLoadDecision {
    /** 立刻取列表：环境已就绪，或已经等过一次，或已到终态。 */
    data object Fetch : ModuleLoadDecision

    /** 进入"启动中"（pending）：本次**不取**列表，只订阅一次性就绪事件。 */
    data class Wait(val reason: ModuleWaitReason) : ModuleLoadDecision
}

/**
 * T10 冷启动门（纯状态机：无 Android、无协程、无线程；主机可测）。
 *
 * ```
 *   onLoadRequested ──事实未就绪──▶ Wait(reason) ──一次性事件──▶ 允许自动重取**一次**
 *         │                              │
 *         └──事实就绪/已等过/已终态──▶ Fetch
 *                                        └── onTerminal() ──▶ 不再等待、不再自动重取
 * ```
 *
 * 规则（顺序敏感）：
 * 1. 终态（成功或具名失败）发布后 → 一律 [ModuleLoadDecision.Fetch]（手动刷新照常），
 *    但 [onEnvironmentReady] 永久返回 false ⇒ **不会**再自动重取（幂等）。
 * 2. 已经等过一次（`waited`）→ Fetch：绝不重复订阅、绝不轮询。
 * 3. 事实未就绪 → Wait；等待期间重复请求仍返回同一个 Wait（幂等，不重复订阅）。
 */
class ModuleStartupGate(private val facts: () -> ModuleRuntimeFacts) {

    private var waiting: ModuleWaitReason? = null
    private var waited = false
    private var settled = false

    /** 当前正在等待的具名原因；null = 不在"启动中"。 */
    val pending: ModuleWaitReason? get() = if (settled || waited) null else waiting

    /** 一次列表请求（预取 / 进页 / resume / 下拉刷新共用）。 */
    fun onLoadRequested(): ModuleLoadDecision {
        if (settled || waited) return ModuleLoadDecision.Fetch
        val reason = facts().waitReason
        if (reason == null) {
            waiting = null
            return ModuleLoadDecision.Fetch
        }
        waiting = reason
        return ModuleLoadDecision.Wait(reason)
    }

    /** 一次性就绪事件。true = 现在**自动重取一次**（每次等待最多一次）。 */
    fun onEnvironmentReady(): Boolean = consumeWait()

    /** 等待超时：true = 允许做一次"如实失败"的取列表（不得再等）。 */
    fun onWaitTimedOut(): Boolean = consumeWait()

    /** 终态发布（成功或具名失败）：之后不再进入"启动中"，也不会再自动重取。 */
    fun onTerminal() {
        settled = true
        waiting = null
    }

    private fun consumeWait(): Boolean {
        if (settled || waited) return false
        if (waiting == null) return false
        waiting = null
        waited = true
        return true
    }
}

/**
 * 运行环境事实源 + **一次性**就绪等待（T10）。
 *
 * 不轮询、不起线程：只挂起在
 * - [ModeRuntime.awaitPageMode]（已激活或检测已结束则立刻返回），与
 * - [ModuleBackendPorts.awaitWired]（接线成功/失败各完成一次）
 *
 * 两个一次性事件上；协程结束即释放。
 */
object ModuleEnvironmentWatch {

    /** 生产事实源：读三个 `@Volatile`，不触发后端。 */
    fun facts(): ModuleRuntimeFacts = ModuleRuntimeFacts(
        mode = ModeRuntime.active,
        ksuBackendWired = ModuleBackendPorts.isWired,
        ksuWiringFailed = ModuleBackendPorts.wiringFailureCode != null,
    )

    /**
     * 等"运行环境就绪"（激活 + KSU 后端接线）。
     *
     * @return true = 就绪；false = 超时 / 已确定就绪不了（接线失败）
     */
    suspend fun awaitReady(reason: ModuleWaitReason, timeoutMillis: Long): Boolean {
        val budget = timeoutMillis.coerceAtLeast(1L)
        return withTimeoutOrNull(budget) {
            if (reason == ModuleWaitReason.MODE_UNRESOLVED && ModeRuntime.active == null) {
                // A completed unresolved detection ends this page wait as well.
                ModeRuntime.awaitPageMode(budget)
            }
            val snapshot = facts()
            when (snapshot.waitReason) {
                null -> true || !snapshot.ksuWiringFailed
                ModuleWaitReason.KSU_BACKEND_UNWIRED -> {
                    if (ModuleBackendPorts.awaitWired()) facts().waitReason == null else false
                }
                // 激活事件已经发生过却仍是未判定：本进程不会再有第二次激活事件。
                ModuleWaitReason.MODE_UNRESOLVED -> false
            }
        } ?: false
    }
}
