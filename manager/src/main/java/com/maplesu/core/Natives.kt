package com.maplesu.core

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import com.maplesu.core.Natives.Profile.RootProfileFlag
import dev.maplesu.manager.ksu.NativesFacade
import dev.maplesu.manager.ksu.NativesFacadeTarget
import dev.maplesu.manager.ksu.KsuRuntimeState
import dev.maplesu.manager.runtime.MapleSuRuntime
import dev.maplesu.manager.runtime.TrustedRuntime
import dev.maplesu.manager.runtime.authorization.DirectPolicy
import dev.maplesu.manager.runtime.authorization.ManagerAuthorizations
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraAuthorization
import dev.maplesu.manager.workmode.backend.KsuBackendRegistry

/**
 * @author weishu
 * @date 2022/12/8.
 */
object Natives {
    // 门面分派（D5 / 03 分册 §3.4）：FQN 与签名全仓保持，方法体按模式分派。
    //   LITE  → 下面每个成员里的「原表达式」（逐字节保留，禁止改写语义）；
    //   KSU   → dev.maplesu.manager.ksu.NativesFacade.ksuPort()（JNI 后端）；
    //   未判定 → BlockedNativesFacadePort（静默任务）/ 显式 Lite 安装请求继续原 Lite 实现。
    // 硬约束：禁止 `?: WorkMode.LITE` 之类的兜底；三分支必须写全（03 §3.3）。
    //
    // 这两个 **const 保持静态字段原样**（审计要求：LITE 逐字节语义 + Java 调用点）。
    // KSU 分支的对应值（32513 / "u:r:ksu:s0"）只经 NativesFacade 的新访问器使用
    // （Profile.context 默认值走 kernelDomainForProfile()），不改变旧 FQN 的静态语义。
    const val MINIMAL_SUPPORTED_KERNEL = MapleSuRuntime.API_VERSION
    const val KERNEL_SU_DOMAIN = ""
    const val ROOT_UID = 0
    const val ROOT_GID = 0

    val version: Int
        get() = when (NativesFacade.target()) {
            NativesFacadeTarget.LITE -> if (isManager) MapleSuRuntime.API_VERSION else -1
            NativesFacadeTarget.KSU -> NativesFacade.ksuPort().version
            NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().version
        }
    val isSafeMode: Boolean
        get() = when (NativesFacade.target()) {
            NativesFacadeTarget.LITE -> ultraBackendSelected() && UltraEnvironmentProbe.isSafeMode(ksuApp)
            NativesFacadeTarget.KSU -> NativesFacade.ksuPort().isSafeMode
            NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().isSafeMode
        }
    val isLkmMode: Boolean
        get() = when (NativesFacade.target()) {
            NativesFacadeTarget.LITE -> false
            NativesFacadeTarget.KSU -> NativesFacade.ksuPort().isLkmMode
            NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().isLkmMode
        }
    val isLkmBundled: Boolean
        get() = when (NativesFacade.target()) {
            NativesFacadeTarget.LITE -> false
            NativesFacadeTarget.KSU -> NativesFacade.ksuPort().isLkmBundled
            NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().isLkmBundled
        }
    // Ultra-only：越狱 late-load 运行时已从本构建移除，该状态恒为 false。
    val isLateLoadMode: Boolean
        get() = when (NativesFacade.target()) {
            NativesFacadeTarget.LITE -> false
            NativesFacadeTarget.KSU -> NativesFacade.ksuPort().isLateLoadMode
            NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().isLateLoadMode
        }
    // Readiness follows the transport that actually exists: the normal-mode
    // credential or the live provider lease of this boot.
    //
    // 🟢 Ultra 兜底（2026-10-29）：另一条真实存在的传输是 MapleSU Ultra 环境。它在 Ultra 内核上
    //    Running 就代表「管理器 + root 可用」，UI 不该再显示未激活。判据只用
    //    [UltraEnvironmentProbe] 的**缓存**（纯内存读，不碰 JNI、不起进程）：本 getter 被
    //    `isFullFeatured` 及其下游大量读取，绝不能因为兜底反而触发 root shell。
    //    顺序是先看 Ultra：Running 时连 TrustedRuntime 都不问，少一次可能失败的探测。
    val isManager: Boolean
        get() = when (NativesFacade.target()) {
            NativesFacadeTarget.LITE -> runCatching {
                if (ultraBackendSelected()) UltraEnvironmentProbe.isRootAvailable(ksuApp) else TrustedRuntime.ready(ksuApp)
            }.getOrDefault(false)
            NativesFacadeTarget.KSU -> NativesFacade.ksuPort().isManager
            NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().isManager
        }
    val isPrBuild: Boolean
        get() = when (NativesFacade.target()) {
            NativesFacadeTarget.LITE -> false
            NativesFacadeTarget.KSU -> NativesFacade.ksuPort().isPrBuild
            NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().isPrBuild
        }
    val kernelUAPIVersion: Int
        get() = when (NativesFacade.target()) {
            NativesFacadeTarget.LITE -> if (isManager) MapleSuRuntime.API_VERSION else -1
            NativesFacadeTarget.KSU -> NativesFacade.ksuPort().kernelUAPIVersion
            NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().kernelUAPIVersion
        }
    val managerUAPIVersion: Int
        get() = when (NativesFacade.target()) {
            NativesFacadeTarget.LITE -> MapleSuRuntime.API_VERSION
            NativesFacadeTarget.KSU -> NativesFacade.ksuPort().managerUAPIVersion
            NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().managerUAPIVersion
        }

    /**
     * Normal 模式的 **KSU 运行时状态**（开发文档 T3；启动链到底跑没跑）。
     *
     * 为什么必须经这里：`src/main` 只有白名单文件能 import `dev.maplesu.manager.ksu.*`
     * （§9.1 R3），首页/模块页直接 import 会红。本 getter 就是那条唯一通道。
     *
     * 语义（与 §9.4 接入断言一致）：
     * - LITE（Ultra）→ `NotApplicable`，且**零** ksud/root 调用；
     * - 未接线 → `Unknown`（**不会**冒充 `Ready`）；
     * - 读取本身是只读的（文件 mtime / 只读命令），带 10 s 缓存，可直接在组合期读。
     */
    val ksuRuntimeStatus: KsuRuntimeState
        get() = KsuBackendRegistry.runtimeStatus()

    fun uidShouldUmount(uid: Int): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> false
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().uidShouldUmount(uid)
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().uidShouldUmount(uid)
    }

    fun appLockState(): Int = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> -1
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().appLockState()
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().appLockState()
    }

    fun appLockAuth(hash: ByteArray): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> false
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().appLockAuth(hash)
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().appLockAuth(hash)
    }

    fun appLockSet(hash: ByteArray, disable: Boolean): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> false
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().appLockSet(hash, disable)
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().appLockSet(hash, disable)
    }

    fun stealthState(): Int = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> -1
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().stealthState()
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().stealthState()
    }

    fun stealthSet(enabled: Boolean): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> false
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().stealthSet(enabled)
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().stealthSet(enabled)
    }

    fun getAppProfile(key: String?, uid: Int): Profile? = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> runCatching {
            ManagerAuthorizations.profile(ksuApp, key, uid)
        }.getOrNull()
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().getAppProfile(key, uid)
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().getAppProfile(key, uid)
    }

    internal fun getAppProfileFresh(key: String?, uid: Int): Profile? = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> runCatching {
            ManagerAuthorizations.profile(ksuApp, key, uid, forceRefresh = true)
        }.getOrNull()
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().getAppProfile(key, uid)
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().getAppProfile(key, uid)
    }

    internal data class ProfileWriteResult(
        val decisionCommitted: Boolean,
        val pendingApply: Boolean = false,
        val commitUnknown: Boolean = false,
        val code: String? = null,
        val detail: String = "",
    ) {
        val succeeded: Boolean get() = decisionCommitted && !pendingApply && !commitUnknown
    }

    fun setAppProfile(profile: Profile?): Boolean = setAppProfileDetailed(profile).succeeded

    internal fun setAppProfileDetailed(profile: Profile?): ProfileWriteResult = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> when {
            profile == null -> ProfileWriteResult(false, code = "PROFILE_REQUIRED")
            else -> runCatching { ManagerAuthorizations.saveProfileDetailed(ksuApp, profile) }
                .getOrElse { ProfileWriteResult(false, commitUnknown = true,
                    code = "PROFILE_WRITE_EXCEPTION", detail = it.javaClass.simpleName) }
        }
        NativesFacadeTarget.KSU -> ProfileWriteResult(NativesFacade.ksuPort().setAppProfile(profile))
        NativesFacadeTarget.BLOCKED -> ProfileWriteResult(NativesFacade.blockedPort().setAppProfile(profile))
    }

    // Reads and writes both go through the one-shot tool (DirectPolicy), so listing
    // applications or toggling the switch never starts a root service. An earlier round
    // reverted the write path believing it crashed the caller; that was external
    // instrumentation force-stopping the app ("due to start instr"), and
    // DirectPolicyWriteAndroidTest now proves set-enabled/grant/revoke work.
    // 读/写开关与角标计数在 Ultra 环境下**不能**走 DirectPolicy：它每次都会起一个一次性
    // root 进程（`DirectRoot` → `MapleSuCommandExecutor` → `libmaplesu_runtime.so`），
    // 那条路在 Ultra 内核上会导致系统重启。Ultra 的授权真相在 SDK 的 su_auth 白名单里，
    // 由 Ultra 超级用户页（getSuAuthList / addSuAuth / removeSuAuth）维护。
    fun isSuEnabled(): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> if (ManagerAuthorizations.usesManagerAuthority(ksuApp)) {
            ManagerAuthorizations.policy(ksuApp).isEnabled
        } else if (ultraBackendSelected()) ultraEnvironmentRunning() else isManager && runCatching {
            DirectPolicy.isEnabled(ksuApp) ?: false
        }.getOrDefault(false)
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().isSuEnabled()
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().isSuEnabled()
    }

    fun setSuEnabled(enabled: Boolean): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> if (ManagerAuthorizations.usesManagerAuthority(ksuApp)) {
            val result = dev.maplesu.manager.runtime.authorization.SuperuserControl.setEnabled(ksuApp, enabled)
            result.ok && !result.pendingApply
        } else if (ultraBackendSelected()) ultraEnvironmentRunning() else isManager && runCatching {
            DirectPolicy.setEnabled(ksuApp, enabled) != null
        }.getOrDefault(false)
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().setSuEnabled(enabled)
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().setSuEnabled(enabled)
    }

    fun isKernelUmountEnabled(): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> false
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().isKernelUmountEnabled()
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().isKernelUmountEnabled()
    }

    fun setKernelUmountEnabled(enabled: Boolean): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> false
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().setKernelUmountEnabled(enabled)
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().setKernelUmountEnabled(enabled)
    }

    fun isSelinuxHideEnabled(): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> false
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().isSelinuxHideEnabled()
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().isSelinuxHideEnabled()
    }

    fun setSelinuxHideEnabled(enabled: Boolean): Int = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> -1
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().setSelinuxHideEnabled(enabled)
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().setSelinuxHideEnabled(enabled)
    }

    fun getUserName(uid: Int): String? = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> runCatching {
            ksuApp.packageManager.getNameForUid(uid)
        }.getOrNull()
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().getUserName(uid) ?: runCatching {
            ksuApp.packageManager.getNameForUid(uid)
        }.getOrNull()
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().getUserName(uid)
    }

    fun getSuperuserCount(): Int = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> {
            if (ManagerAuthorizations.usesManagerAuthority(ksuApp)) {
                runCatching { ManagerAuthorizations.policy(ksuApp).currentGrantCount() }.getOrDefault(-1)
            } else if (ultraBackendSelected()) {
                // Last count the Ultra page actually read from get_su_auth_list; 0 until then.
                UltraAuthorization.grantedPackages().size
            } else {
                if (isManager) runCatching { DirectPolicy.grantCount(ksuApp) ?: -1 }.getOrDefault(-1) else -1
            }
        }
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().getSuperuserCount()
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().getSuperuserCount()
    }

    fun setDefaultUmountModules(umountModules: Boolean): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> false
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().setDefaultUmountModules(umountModules)
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().setDefaultUmountModules(umountModules)
    }

    fun isDefaultUmountModules(): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> false
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().isDefaultUmountModules()
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().isDefaultUmountModules()
    }

    fun isFullFeatured(): Boolean = when (NativesFacade.target()) {
        NativesFacadeTarget.LITE -> isManager
        NativesFacadeTarget.KSU -> NativesFacade.ksuPort().isFullFeatured()
        NativesFacadeTarget.BLOCKED -> NativesFacade.blockedPort().isFullFeatured()
    }

    /**
     * One cached fact: the MapleSU Ultra environment was last observed Running.
     *
     * Cache-only on purpose. Every use of this helper replaces a path that would otherwise
     * build a root process on a Ultra kernel, so it must never do JNI itself.
     */
    private fun ultraEnvironmentRunning(): Boolean =
        runCatching { UltraEnvironmentProbe.currentSnapshot(ksuApp).state == UltraEnvironmentProbe.State.Running }.getOrDefault(false)

    private fun ultraBackendSelected(): Boolean = runCatching {
        UltraEnvironmentProbe.usesUltraBackend(ksuApp)
    }.getOrDefault(false)

    @Keep
    @Immutable
    @Parcelize
    @Serializable
    data class Profile(
        // and there is a default profile for root and non-root
        val name: String,
        // current uid for the package, this is convivent for kernel to check
        // if the package name doesn't match uid, then it should be invalidated.
        val currentUid: Int = 0,

        // if this is true, kernel will grant root permission to this package
        val allowSu: Boolean = false,

        // these are used for root profile
        val rootUseDefault: Boolean = true,
        val rootTemplate: String? = null,
        val uid: Int = ROOT_UID,
        val gid: Int = ROOT_GID,
        val groups: List<Int> = mutableListOf(),
        val capabilities: List<Int> = mutableListOf(),
        // 原实现是 `KERNEL_SU_DOMAIN`（= ""）。KSU 模式新建 Profile 需要 u:r:ksu:s0，
        // 因此默认值改走模式访问器；LITE（及未判定）仍返回 ""，旧行为不变。
        val context: String = NativesFacade.kernelDomainForProfile(),
        val namespace: Int = Namespace.INHERITED.ordinal,

        val nonRootUseDefault: Boolean = true,
        val umountModules: Boolean = true,
        var rules: String = "", // this field is save in ksud!!

        val flags: Long = FLAG_KSU_NO_NEW_PRIVS,
    ) : Parcelable {
        @Keep
        enum class RootProfileFlag(val display: String, val desc: Int) {
            NO_NEW_PRIVS(
                "NO_NEW_PRIVS",
                R.string.profile_flags_desc_no_new_privs
            )
        }

        enum class Namespace {
            INHERITED,
            GLOBAL,
            INDIVIDUAL,
        }

        constructor() : this("")
    }

    const val FLAG_KSU_NO_NEW_PRIVS = 1L
}

fun List<RootProfileFlag>.toRawFlags(): Long =
    fold(0L) { acc, flag -> acc.or(1L.shl(flag.ordinal)) }

fun List<RootProfileFlag>.toOrdinalList(): List<Int> =
    map { it.ordinal }

fun Long.toRootProfileFlags(): List<RootProfileFlag> =
    RootProfileFlag.entries.filter { 1L.shl(it.ordinal).and(this) != 0L }.toList()
