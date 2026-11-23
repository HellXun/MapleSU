package com.maplesu.core.ui.viewmodel

import dev.maplesu.manager.runtime.State
import dev.maplesu.manager.workmode.WorkMode

/**
 * 超管页数据来源的**唯一模式裁决**（W14 / 主文档 §3.3：两模式互不干扰）。
 *
 * - LITE → 现有 `DirectPolicy` / `UltraAuthorization` / `SuDelivery.statesFor` 路径（原实现）；
 * - KSU → KSU 后端（`Natives.getAppProfile` 经 KSU JNI / W14 `KsuSuperuserBackend`），
 *   **不得**调用任何 Lite runtime 方法（一次性 root 进程 / libsu RootService / DirectPolicy）；
 * - 未判定 / KSU 后端不可用 → 只读空态 + 原因，不发起 root。
 *
 * 纯函数 → 主机表驱动测试（`SuperuserSourcePolicyTest`）。
 */
enum class SuperuserSource {
    LITE_RUNTIME,
    KSU_BACKEND,
    BLOCKED_UNRESOLVED,
    BLOCKED_BACKEND_UNAVAILABLE,
}

object SuperuserSourcePolicy {

    fun resolve(mode: WorkMode?, ksuBackendAvailable: Boolean): SuperuserSource = when (mode) {
        WorkMode.LITE -> SuperuserSource.LITE_RUNTIME

        null -> SuperuserSource.BLOCKED_UNRESOLVED
    }

    /** Lite runtime 方法（DirectPolicy/statesFor/SuDelivery）只允许在 LITE 来源执行。 */
    fun allowsLiteRuntimeCalls(source: SuperuserSource): Boolean =
        source == SuperuserSource.LITE_RUNTIME

    /** 该来源是否允许发起任何 root/专属后端调用（阻断态一律 false）。 */
    fun allowsBackendCalls(source: SuperuserSource): Boolean = when (source) {
        SuperuserSource.LITE_RUNTIME,
        SuperuserSource.KSU_BACKEND -> true

        SuperuserSource.BLOCKED_UNRESOLVED,
        SuperuserSource.BLOCKED_BACKEND_UNAVAILABLE -> false
    }

    /**
     * 单行授权状态：KSU 的 allow 来自 `Natives.getAppProfile().allowSu`（KSU JNI），
     * 不经过 Lite 投递状态机；LITE 仍由 `statesFor` 给出（本函数不参与）。
     * 阻断态返回 null = 不给任何"已授权/未授权"断言（只读空态）。
     */
    fun ksuSuState(source: SuperuserSource, allowSu: Boolean): State? = when (source) {
        SuperuserSource.KSU_BACKEND -> if (allowSu) State.WIRED else State.NOT_AUTHORIZED
        SuperuserSource.LITE_RUNTIME,
        SuperuserSource.BLOCKED_UNRESOLVED,
        SuperuserSource.BLOCKED_BACKEND_UNAVAILABLE -> null
    }
}
