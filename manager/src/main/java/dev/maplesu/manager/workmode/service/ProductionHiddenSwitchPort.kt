package dev.maplesu.manager.workmode.service

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import com.maplesu.core.ui.security.Stealth
import com.maplesu.core.ui.security.StealthCodeStore
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.switch.HiddenRecoveryFacts
import dev.maplesu.manager.workmode.switch.HiddenRecoveryFactsProvider
import dev.maplesu.manager.workmode.switch.HiddenState
import dev.maplesu.manager.workmode.switch.HiddenStateController
import dev.maplesu.manager.workmode.switch.HiddenStateStore
import dev.maplesu.manager.workmode.switch.HiddenSwitchCoordinator
import dev.maplesu.manager.workmode.switch.HookResult

/**
 * H5 生产隐藏端口：
 * - KSU：内核 stealth（`Stealth.state()/setEnabledReporting`，三态；UNKNOWN 按"可能开着"交策略处理）；
 * - Lite：App 级隐藏尚未交付 → 状态 OFF；`setOn(LITE)` 明确失败（恢复时 fail-closed，不伪造成功）；
 * - 恢复通道：密令通道默认/自定义码由 `StealthReceiver` 构造性接受 → verified；
 *   webadmin / 外部备份当前无验证通道 → false（策略会要求用户配置，不静默）。
 */
class ProductionHiddenSwitchPort(private val context: Context) : HiddenStateController {

    override fun state(mode: WorkMode): HiddenState = when (mode) {
        // Lite App 级隐藏尚未交付：没有实现时不得假设"可能开着"，否则会永久阻断 A→B。
        WorkMode.LITE -> HiddenState.OFF
    }

    override fun setOff(mode: WorkMode): HookResult = when (mode) {
        WorkMode.LITE -> HookResult.OK
    }

    override fun setOn(mode: WorkMode): HookResult = when (mode) {
        WorkMode.LITE -> HookResult.failed("LITE_HIDDEN_UNSUPPORTED", "Ultra hidden not implemented")
    }

    private fun setStealth(enabled: Boolean): HookResult {
        val error = runCatching { Stealth.setEnabledReporting(enabled) }.getOrElse {
            return HookResult.failed("HIDDEN_SET_FAILED", it.javaClass.simpleName)
        }
        return if (error == null) HookResult.OK else HookResult.failed("HIDDEN_SET_FAILED", error)
    }
}

/** 按模式独立持久化（切回恢复用；与切换事务无关，App 私有 prefs）。 */
class PrefsHiddenStateStore(context: Context) : HiddenStateStore {
    private val prefs = context.ultraPreferences("workmode_hidden", Context.MODE_PRIVATE)

    override fun saved(mode: WorkMode): HiddenState = when (prefs.getString(key(mode), "OFF")) {
        "ON" -> HiddenState.ON
        "UNKNOWN" -> HiddenState.UNKNOWN
        else -> HiddenState.OFF
    }

    override fun save(mode: WorkMode, state: HiddenState) {
        prefs.edit().putString(key(mode), state.name).apply()
    }

    private fun key(mode: WorkMode) = "hidden_${mode.suffix}"
}

/** 生产恢复通道判定：密令可用即 verified（默认 70707 / 已配置自定义码，接收器构造性接受）。 */
class AndroidHiddenRecoveryFacts(private val context: Context) : HiddenRecoveryFactsProvider {
    override fun facts(): HiddenRecoveryFacts {
        val configuredCode = runCatching { StealthCodeStore.read() }.getOrNull()
        val secretConfigured = configuredCode != null
        // 内核侧没有自定义码时 StealthReceiver 接受默认码（DEFAULT_CODE）。
        val secretVerified = true
        return HiddenRecoveryFacts(
            secretCodeConfigured = secretConfigured || StealthCodeStore.DEFAULT_CODE.isNotEmpty(),
            secretCodeVerified = secretVerified,
            webAdminAvailable = false,
            webAdminVerified = false,
            externalRecoveryAvailable = false,
            externalRecoveryVerified = false,
        )
    }
}

/** 生产装配；任何构造失败返回 null（facade 跳过隐藏守卫 = 旧行为，不伪造隐藏判定）。 */
fun createProductionHiddenCoordinator(context: Context): HiddenSwitchCoordinator? = runCatching {
    HiddenSwitchCoordinator(
        controller = ProductionHiddenSwitchPort(context),
        store = PrefsHiddenStateStore(context),
        recovery = AndroidHiddenRecoveryFacts(context),
    )
}.getOrNull()
