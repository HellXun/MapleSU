package dev.maplesu.manager.workmode

/** su 后端种类。 */
enum class SuBackendKind { LITE_RUNTIME, KSU_KERNEL }

/** 模块后端种类。 */
enum class ModuleBackendKind { LITE_SCRIPT, KSU_KDSU }

/**
 * 唯一能力来源：UI 用它决定显示哪些设置/入口，不在页面里各自 if。
 * [forMode] 是纯函数 → 主机可测。
 *
 * 注意：能力矩阵只承载"该模式下设计上是否支持"，不承载"当前是否可用"（后者由
 * ModeState + 探针结论表达）。
 */
data class ModeCapabilities(
    val suBackend: SuBackendKind,
    val moduleBackend: ModuleBackendKind,
    val supportsKernelUmount: Boolean,
    val supportsSelinuxHide: Boolean,
    val supportsSulog: Boolean,
    val supportsAdbRoot: Boolean,
    val supportsShellSuAdbOnly: Boolean,
    val supportsStealth: Boolean,
    val supportsWebAdmin: Boolean,
    val supportsAppLock: Boolean,
    val supportsUltraEnvironment: Boolean,
    val supportsUltraModules: Boolean,
) {
    companion object {
        /** 未判定态的骨架矩阵：全部能力为 false（suBackend 保留 LITE_RUNTIME 仅作占位）。 */
        val NONE: ModeCapabilities = ModeCapabilities(
            suBackend = SuBackendKind.LITE_RUNTIME,
            moduleBackend = ModuleBackendKind.LITE_SCRIPT,
            supportsKernelUmount = false,
            supportsSelinuxHide = false,
            supportsSulog = false,
            supportsAdbRoot = false,
            supportsShellSuAdbOnly = false,
            supportsStealth = false,
            supportsWebAdmin = false,
            supportsAppLock = false,
            supportsUltraEnvironment = false,
            supportsUltraModules = false,
        )

        fun forMode(mode: WorkMode): ModeCapabilities = when (mode) {
            WorkMode.LITE -> ModeCapabilities(
                suBackend = SuBackendKind.LITE_RUNTIME,
                moduleBackend = ModuleBackendKind.LITE_SCRIPT,
                supportsKernelUmount = false,   // Natives.kt:78-79 恒 false
                supportsSelinuxHide = false,    // Natives.kt:80-81 恒 false
                supportsSulog = false,          // SulogHelper.kt:14 的目录在 A 下不存在
                supportsAdbRoot = false,
                supportsShellSuAdbOnly = false,
                supportsStealth = false,        // Natives.kt:55 stealthState() 恒 -1
                supportsWebAdmin = false,
                supportsAppLock = false,        // Natives.kt:52-54 桩
                supportsUltraEnvironment = true,
                supportsUltraModules = true,
            )
            WorkMode.KSU -> ModeCapabilities(
                suBackend = SuBackendKind.KSU_KERNEL,
                moduleBackend = ModuleBackendKind.KSU_KDSU,
                supportsKernelUmount = true,
                supportsSelinuxHide = true,
                supportsSulog = true,
                supportsAdbRoot = true,
                supportsShellSuAdbOnly = true,
                supportsStealth = true,
                supportsWebAdmin = true,
                supportsAppLock = true,
                supportsUltraEnvironment = false,
                supportsUltraModules = false,
            )
        }
    }
}
