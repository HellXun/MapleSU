package dev.maplesu.manager.workmode

/**
 * Ultra-only 构建的单一开关。
 *
 * 上游 MapleSU 同时携带两套后端：Ultra（[WorkMode.LITE]，内核补丁 SU）与
 * Normal（[WorkMode.KSU]，KernelSU）。本构建只发布 Ultra，Normal 的代码与
 * 资产可以保留（保证编译期引用完整），但**运行期一律不可达**。
 *
 * 所有「模式是否可用」的判断都必须回到这里，不要在 UI 或 ViewModel 里
 * 各写一个 if，否则后续同步上游时会出现判断不一致。
 *
 * 改回双模式只需把 [ENABLED] 置 false；其余调用点无需改动。
 */
object UltraOnlyPolicy {

    /** 本构建是否为 Ultra-only。 */
    const val ENABLED: Boolean = true

    /** 本构建发布的模式集合。 */
    val availableModes: List<WorkMode> =
        if (ENABLED) listOf(WorkMode.LITE) else WorkMode.entries.toList()

    /** 该模式是否允许出现在本构建里。 */
    fun allows(mode: WorkMode): Boolean = !ENABLED || mode == WorkMode.LITE

    /** 把任意来源的模式值收敛到本构建允许的范围；不允许的返回 null。 */
    fun coerce(mode: WorkMode?): WorkMode? =
        if (!ENABLED) mode else mode?.takeIf { it == WorkMode.LITE }

    /**
     * 切换前的准入判断。返回 null 表示允许，否则是拒绝原因码。
     * UI 隐藏入口之外再加一道，防止深链或恢复流程绕过界面直接切模式。
     */
    fun rejectSwitch(target: WorkMode): String? =
        if (ENABLED && target != WorkMode.LITE) "ULTRA_ONLY_BUILD" else null
}
