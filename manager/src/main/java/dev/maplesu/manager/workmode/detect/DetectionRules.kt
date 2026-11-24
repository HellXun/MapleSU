package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.UltraOnlyPolicy
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.schema.currentMode

/**
 * 六态判定的唯一被测函数：**纯函数**，禁止 Android / IO / 日志。
 *
 * 裁决顺序必须逐行照 §2.7 的顺序表（顺序敏感：KSU 内核证据永远优先于 A 磁盘证据）。
 */
object DetectionRules {

    /** 规则 id 供 evidence/测试报告引用，禁止在 UI 里硬编码字符串。 */
    enum class Rule {
        R1_KSU_ACTIVE,
        R2_KSU_KERNEL_ONLY,
        R3_KSU_BRANCH_UNTRUSTED,
        R4_FOREIGN_KSU,
        R5_A_HANDSHAKE,
        R6_A_ULTRA,
        R6_A_ULTRA_SAFE_MODE,
        R6_A_ULTRA_KERNEL_ONLY,
        R6_A_ULTRA_OWNER_ONLY,
        R8_A_DISK_STRONG_UNBOUND,
        R9_A_DISK_WEAK_UNBOUND,
        R10_FOREIGN_ROOT,
        R11_FRESH_INSTALL,
        R12_INDETERMINATE,
    }

    /** 机器可读原因码（UI 不得硬编码）。 */
    object Reason {
        const val KSU_BRANCH_UNTRUSTED = "KSU_BRANCH_UNTRUSTED"
        const val FOREIGN_KSU_KERNEL = "FOREIGN_KSU_KERNEL"
        const val FOREIGN_ROOT_MARKERS = "FOREIGN_ROOT_MARKERS"
        const val NO_ROOT_CHANNEL = "NO_ROOT_CHANNEL"
        const val FRESH_INSTALL = "FRESH_INSTALL"
    }

    /**
     * 主裁决。逐行对应 01 §2.7 的顺序表：
     * 1/2)B 内核认主；3/4)未认主；5/6/7)A 可用传输；8)A 磁盘强证据；
     * 9)外来 root；10)A 弱痕迹无通道；11)新装窗口；12)保守兜底。
     */
    fun decide(facts: DetectionFacts): DetectionDecision {
        val ksu = facts.ksu
        val lite = facts.lite
        val root = facts.root
        val local = facts.local

        // Ultra-only 构建：KSU 证据一律视为不存在，裁决链只会落到 A（Ultra）分支。
        // 上游的 B 态判断全部保留在下方，把 UltraOnlyPolicy.ENABLED 置 false 即可恢复双模式。
        val ultraOnly = UltraOnlyPolicy.ENABLED

        // §2.3 必要条件：B 态要求 version>0（responded 且版本可读）。
        val ksuKernelPresent = !ultraOnly && ksu.responded && ksu.version > 0
        val ksuOwner = !ultraOnly && ksuKernelPresent && ksu.isManager
        val ksuBranchUntrusted =
            !ultraOnly && ksu.responded && !ksu.isManager && ksu.uapi == facts.ksuBranchUapi

        val state: ModeState
        val rule: Rule
        val reasons: List<String>
        when {
            ksuOwner && root != null &&
                root.ksudExecutable == true && root.ksuDirHasContent == true -> {
                state = ModeState.MODE_B_ACTIVE
                rule = Rule.R1_KSU_ACTIVE
                reasons = emptyList()
            }

            ksuOwner -> {
                state = ModeState.MODE_B_KERNEL_ONLY
                rule = Rule.R2_KSU_KERNEL_ONLY
                reasons = emptyList()
            }

            ksuBranchUntrusted -> {
                state = ModeState.INDETERMINATE
                rule = Rule.R3_KSU_BRANCH_UNTRUSTED
                reasons = listOf(Reason.KSU_BRANCH_UNTRUSTED)
            }

            !ultraOnly && ksu.responded && !ksu.isManager -> {
                state = ModeState.FOREIGN_SU
                rule = Rule.R4_FOREIGN_KSU
                reasons = listOf(Reason.FOREIGN_KSU_KERNEL)
            }

            lite.handshakeOk == true -> {
                state = ModeState.MODE_A_ACTIVE
                rule = Rule.R5_A_HANDSHAKE
                reasons = emptyList()
            }

            lite.ultraState == UltraState.RUNNING -> {
                state = ModeState.MODE_A_ACTIVE
                rule = Rule.R6_A_ULTRA
                reasons = emptyList()
            }

            lite.ultraState == UltraState.FAULT -> {
                // Keep the installed Ultra backend available for module recovery only.
                state = ModeState.MODE_A_ACTIVE
                rule = Rule.R6_A_ULTRA_SAFE_MODE
                reasons = listOf("ULTRA_SAFE_MODE")
            }

            lite.ultraKernelRootVerified -> {
                state = ModeState.MODE_A_ACTIVE
                rule = Rule.R6_A_ULTRA_KERNEL_ONLY
                reasons = listOf("ULTRA_USERSPACE_NOT_READY")
            }


            lite.managerOwnerBound -> {
                state = ModeState.MODE_A_INSTALLED_UNBOUND
                rule = Rule.R6_A_ULTRA_OWNER_ONLY
                reasons = listOf("ULTRA_SIGNED_OWNER_ROOT_NOT_VERIFIED")
            }

            root?.aAuthorizationTable == true ||
                root?.aInstallerRecord == true ||
                root?.aPublishedDirectory == true ||
                (lite.credentialPresent && lite.handshakeOk == false) -> {
                state = ModeState.MODE_A_INSTALLED_UNBOUND
                rule = Rule.R8_A_DISK_STRONG_UNBOUND
                reasons = emptyList()
            }

            root?.foreignManagerMarkers == true -> {
                state = ModeState.FOREIGN_SU
                rule = Rule.R10_FOREIGN_ROOT
                reasons = listOf(Reason.FOREIGN_ROOT_MARKERS)
            }

            root == null && (
                lite.credentialPresent || lite.ultraKeyPresent ||
                    local.installerLastTaskId != null || local.lastKnownMode != null
                ) -> {
                state = ModeState.MODE_A_INSTALLED_UNBOUND
                rule = Rule.R9_A_DISK_WEAK_UNBOUND
                reasons = listOf(Reason.NO_ROOT_CHANNEL)
            }

            root == null && local.managerFirstInstallAgeMs <= local.freshInstallWindowMs -> {
                state = ModeState.NOT_INSTALLED
                rule = Rule.R11_FRESH_INSTALL
                reasons = listOf(Reason.FRESH_INSTALL)
            }

            else -> {
                state = ModeState.INDETERMINATE
                rule = Rule.R12_INDETERMINATE
                reasons = emptyList()
            }
        }

        return DetectionDecision(
            state = state,
            rule = rule,
            defaultSelectedMode = defaultSelectedMode(state),
            recovery = recoveryOf(facts),
            reasons = reasons,
        )
    }

    /** 首次进入工作模式页/安装页时"默认选中"谁（只影响界面默认值，不启动任何后端）。 */
    fun defaultSelectedMode(state: ModeState): WorkMode? = when (state) {
        // Ultra-only 下 B 态不可达；万一读到残留状态，默认也回落到 Ultra。
        ModeState.MODE_B_ACTIVE, ModeState.MODE_B_KERNEL_ONLY -> WorkMode.LITE
        ModeState.MODE_A_ACTIVE, ModeState.MODE_A_INSTALLED_UNBOUND -> WorkMode.LITE
        ModeState.FOREIGN_SU, ModeState.NOT_INSTALLED, ModeState.INDETERMINATE -> null
    }

    /** 从 root 会话已读到的控制面事实推导"清数据后可恢复性"摘要（只读，不写）。 */
    private fun recoveryOf(facts: DetectionFacts): RecoveryReadiness {
        val modes = facts.root?.modesJson
        val mode = modes?.currentMode()
            ?: modes?.last?.let(WorkMode::fromWire)
            ?: facts.local.lastKnownMode?.let(WorkMode::fromWire)
        val entry = mode?.let { m -> modes?.modes?.firstOrNull { it.suffix == m.suffix } }
        val blockers = buildList {
            if (facts.root == null) add(RecoveryReadiness.NO_ROOT_CHANNEL)
            if (facts.root != null && modes == null) add(RecoveryReadiness.CONTROL_MODES_MISSING)
            if (facts.lite.credentialPresent && facts.lite.handshakeOk == false) {
                add(RecoveryReadiness.LITE_CREDENTIAL_UNUSABLE)
            }
        }
        return RecoveryReadiness(
            controlPlaneReachable = modes != null,
            restorableMode = mode,
            restorableSettingsRevision = entry?.settingsRevision,
            restorableSuperuserRevision = modes?.sharedRevision,
            hasSnapshot = entry?.snapshot != null,
            blockers = blockers,
        )
    }
}

data class DetectionDecision(
    val state: ModeState,
    val rule: DetectionRules.Rule,
    val defaultSelectedMode: WorkMode?,
    val recovery: RecoveryReadiness = RecoveryReadiness.NONE,
    /** 机器可读原因码，例如 KSU_BRANCH_UNTRUSTED。 */
    val reasons: List<String> = emptyList(),
)
