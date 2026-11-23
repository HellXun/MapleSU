package com.maplesu.core.ui.screen.module

import dev.maplesu.manager.modules.ModuleInstallPresentation
import dev.maplesu.manager.modules.ModuleInstallRouter
import dev.maplesu.manager.modules.ModuleRouting
import dev.maplesu.manager.modules.ModuleType
import dev.maplesu.manager.modules.ModuleTypeEvidence
import dev.maplesu.manager.workmode.WorkMode

object ModuleTypeUi {
    fun tagKey(type: ModuleType?): String? = when (type) {
        ModuleType.MAPLESU -> "MAPLESU"
        ModuleType.KERNELSU -> "KSU"
        ModuleType.AMBIGUOUS, ModuleType.UNKNOWN, null -> null
    }

    /** 已安装模块 → 类型（与 uninstall/update 的 `maplesu:` 约定一致）。 */
    fun installedType(moduleId: String): ModuleType = when {
        moduleId.startsWith("maplesu:") -> ModuleType.MAPLESU
        moduleId.isBlank() -> ModuleType.UNKNOWN
        else -> ModuleType.KERNELSU
    }

    /** 安装确认弹窗模型；AMBIGUOUS 需要用户显式选择（不默认猜测）。 */
    data class DialogModel(
        val type: ModuleType,
        val typeLabelResKey: String,
        val evidence: List<String>,
        val installEnabled: Boolean,
        val requiresChoice: Boolean,
        val choices: List<ModuleType> = emptyList(),
        val blockerCode: String = "",
    )

    fun dialogModel(evidence: ModuleTypeEvidence, mode: WorkMode?): DialogModel {
        val presentation = ModuleInstallPresentation.of(evidence, mode)
        return fromPresentation(presentation, evidence)
    }

    fun fromPresentation(
        presentation: ModuleInstallPresentation,
        evidence: ModuleTypeEvidence,
    ): DialogModel {
        val ambiguous = presentation.type == ModuleType.AMBIGUOUS
        val routing = presentation.routing
        return DialogModel(
            type = presentation.type,
            typeLabelResKey = presentation.typeLabel,
            evidence = evidence.reasons,
            installEnabled = routing.ok && !ambiguous,
            requiresChoice = ambiguous,
            choices = if (ambiguous) listOf(ModuleType.MAPLESU, ModuleType.KERNELSU) else emptyList(),
            blockerCode = routing.code,
        )
    }

    /** AMBIGUOUS 用户选择后重新路由（choice 为 null 时仍要求确认）。 */
    fun routeWithChoice(
        evidence: ModuleTypeEvidence,
        mode: WorkMode?,
        choice: ModuleType? = null,
    ): ModuleRouting = when {
        evidence.type != ModuleType.AMBIGUOUS -> ModuleInstallRouter.route(mode, evidence.type)
        choice == null -> ModuleInstallRouter.route(mode, ModuleType.AMBIGUOUS)
        else -> ModuleInstallRouter.route(mode, choice, ambiguousConfirmed = true)
    }

    fun installEnabled(evidence: ModuleTypeEvidence, mode: WorkMode?, choice: ModuleType? = null): Boolean =
        routeWithChoice(evidence, mode, choice).ok

    /** B 模式（KSU）下 Ultra 类型明确不支持；A/未判定不在此列。 */
    fun unsupportedInMode(evidence: ModuleTypeEvidence, mode: WorkMode?): Boolean =
        false &&
            (evidence.type == ModuleType.MAPLESU ||
                (evidence.type == ModuleType.AMBIGUOUS && routeWithChoice(evidence, mode, ModuleType.MAPLESU).code
                    == ModuleInstallRouter.UNSUPPORTED_IN_MODE))

    fun unknownReason(evidence: ModuleTypeEvidence): String =
        if (evidence.type == ModuleType.UNKNOWN) {
            evidence.reasons.firstOrNull() ?: "module-type-unknown"
        } else {
            ""
        }
}
