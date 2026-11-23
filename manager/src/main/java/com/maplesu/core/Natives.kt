package com.maplesu.core

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import com.maplesu.core.Natives.Profile.RootProfileFlag
import dev.maplesu.manager.runtime.MapleSuRuntime
import dev.maplesu.manager.runtime.TrustedRuntime
import dev.maplesu.manager.runtime.authorization.DirectPolicy
import dev.maplesu.manager.runtime.authorization.ManagerAuthorizations
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraAuthorization

/**
 * 管理器读取 root 能力的唯一门面。
 *
 * 上游这里按 `NativesFacade.target()` 三分支分派（Lite / KSU / Blocked）。本分支只发布
 * Ultra，KSU 门面与 `dev.maplesu.manager.ksu` 包已整体删除，因此每个成员只保留原来
 * Lite 分支的表达式——逐字保留，不改语义。Normal 专属的成员（app lock、内核 umount、
 * SELinux 隐藏开关等）保留常量形态，因为设置页仍然读它们来渲染「本构建不支持」。
 *
 * @author weishu
 * @date 2022/12/8.
 */
object Natives {
    // 这两个 **const 保持静态字段原样**（审计要求：逐字节语义 + Java 调用点）。
    const val MINIMAL_SUPPORTED_KERNEL = MapleSuRuntime.API_VERSION
    const val KERNEL_SU_DOMAIN = ""
    const val ROOT_UID = 0
    const val ROOT_GID = 0

    val version: Int
        get() = if (isManager) MapleSuRuntime.API_VERSION else -1

    val isSafeMode: Boolean
        get() = ultraBackendSelected() && UltraEnvironmentProbe.isSafeMode(ksuApp)

    val isLkmMode: Boolean get() = false

    val isLkmBundled: Boolean get() = false

    // Ultra-only：越狱 late-load 运行时已从本构建移除，该状态恒为 false。
    val isLateLoadMode: Boolean get() = false

    // Readiness follows the transport that actually exists: the live root channel of this boot.
    //
    // Ultra 兜底（2026-10-29）：另一条真实存在的传输是 MapleSU Ultra 环境。它在 Ultra 内核上
    // Running 就代表「管理器 + root 可用」，UI 不该再显示未激活。判据只用
    // [UltraEnvironmentProbe] 的**缓存**（纯内存读，不碰 JNI、不起进程）：本 getter 被
    // `isFullFeatured` 及其下游大量读取，绝不能因为兜底反而触发 root shell。
    // 顺序是先看 Ultra：Running 时连 TrustedRuntime 都不问，少一次可能失败的探测。
    val isManager: Boolean
        get() = runCatching {
            if (ultraBackendSelected()) UltraEnvironmentProbe.isRootAvailable(ksuApp) else TrustedRuntime.ready(ksuApp)
        }.getOrDefault(false)

    val isPrBuild: Boolean get() = false

    val kernelUAPIVersion: Int
        get() = if (isManager) MapleSuRuntime.API_VERSION else -1

    val managerUAPIVersion: Int
        get() = MapleSuRuntime.API_VERSION

    // ---- Normal 专属能力：本构建不支持 --------------------------------------

    fun uidShouldUmount(uid: Int): Boolean = false

    fun appLockState(): Int = -1

    fun appLockAuth(hash: ByteArray): Boolean = false

    fun appLockSet(hash: ByteArray, disable: Boolean): Boolean = false

    fun stealthState(): Int = -1

    fun stealthSet(enabled: Boolean): Boolean = false

    fun isKernelUmountEnabled(): Boolean = false

    fun setKernelUmountEnabled(enabled: Boolean): Boolean = false

    fun isSelinuxHideEnabled(): Boolean = false

    fun setSelinuxHideEnabled(enabled: Boolean): Int = -1

    fun setDefaultUmountModules(umountModules: Boolean): Boolean = false

    fun isDefaultUmountModules(): Boolean = false

    // ---- 授权档案 ----------------------------------------------------------

    fun getAppProfile(key: String?, uid: Int): Profile? = runCatching {
        ManagerAuthorizations.profile(ksuApp, key, uid)
    }.getOrNull()

    internal fun getAppProfileFresh(key: String?, uid: Int): Profile? = runCatching {
        ManagerAuthorizations.profile(ksuApp, key, uid, forceRefresh = true)
    }.getOrNull()

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

    internal fun setAppProfileDetailed(profile: Profile?): ProfileWriteResult = when {
        profile == null -> ProfileWriteResult(false, code = "PROFILE_REQUIRED")
        else -> runCatching { ManagerAuthorizations.saveProfileDetailed(ksuApp, profile) }
            .getOrElse {
                ProfileWriteResult(
                    false, commitUnknown = true,
                    code = "PROFILE_WRITE_EXCEPTION", detail = it.javaClass.simpleName,
                )
            }
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
    fun isSuEnabled(): Boolean = if (ManagerAuthorizations.usesManagerAuthority(ksuApp)) {
        ManagerAuthorizations.policy(ksuApp).isEnabled
    } else if (ultraBackendSelected()) {
        ultraEnvironmentRunning()
    } else {
        isManager && runCatching { DirectPolicy.isEnabled(ksuApp) ?: false }.getOrDefault(false)
    }

    fun setSuEnabled(enabled: Boolean): Boolean = if (ManagerAuthorizations.usesManagerAuthority(ksuApp)) {
        val result = dev.maplesu.manager.runtime.authorization.SuperuserControl.setEnabled(ksuApp, enabled)
        result.ok && !result.pendingApply
    } else if (ultraBackendSelected()) {
        ultraEnvironmentRunning()
    } else {
        isManager && runCatching { DirectPolicy.setEnabled(ksuApp, enabled) != null }.getOrDefault(false)
    }

    fun getUserName(uid: Int): String? = runCatching {
        ksuApp.packageManager.getNameForUid(uid)
    }.getOrNull()

    fun getSuperuserCount(): Int = if (ManagerAuthorizations.usesManagerAuthority(ksuApp)) {
        runCatching { ManagerAuthorizations.policy(ksuApp).currentGrantCount() }.getOrDefault(-1)
    } else if (ultraBackendSelected()) {
        // Last count the Ultra page actually read from get_su_auth_list; 0 until then.
        UltraAuthorization.grantedPackages().size
    } else {
        if (isManager) runCatching { DirectPolicy.grantCount(ksuApp) ?: -1 }.getOrDefault(-1) else -1
    }

    fun isFullFeatured(): Boolean = isManager

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
        val context: String = KERNEL_SU_DOMAIN,
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
