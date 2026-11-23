// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import android.os.Looper
import android.util.Log
import dev.maplesu.manager.modules.UltraRootKeyStore
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.ui.WorkModeSwitchSession
import dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest

/**
 * 「MapleSU_Ultra 工作环境」升级：读事实、执行升级。
 *
 * 升级 = 在运行中的环境上再安装一次（上游参考管理器给出的升级方式也是"重新安装环境"）：
 *  - 走唯一安装入口 [UltraEnvironmentInstaller]，**并且显式声明**
 *    [UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild]：Running 不是跳过安装的理由，
 *    否则「同一个上游版本的新构建」永远提交不下去（5.0.5 修复的正是这个短路）；
 *  - 自白名单与 key 备份照常附带；
 *  - **绝不先卸载**：卸载会清空授权列表并删除已安装的模块；
 *  - Boot 模式安装成功后设备会在几秒内自动重启，所以只能由用户确认后触发，从不自动执行；
 *  - 写之前在后台把事实重新核对一遍，过期的卡片不能写设备；
 *  - 每次开机最多提交一次：先落标记；仅 SDK 明确拒绝时撤回，结果不确定时等待重启核对；
 *  - 安装回执成功还不够：必须回读到「设备上就是随包这一版」才算 SUBMITTED，回读失败或
 *    摘要不符一律 FAILED（失败原因留在 [lastFailureReason]，UI 可以显示）。
 *
 * 显式修复也走相同提交链路；Unknown 另需初始化收据过期或空闲未启动的只读证据。
 * 只在 Ultra 下工作（规范 §2.2：Normal 下不读 Lite）。全部阻塞，必须在后台线程调用。
 */
object UltraEnvironmentUpgrade {

    private const val TAG = "MapleSUUltraUpgrade"
    private const val PREFS = "ultra-env-upgrade"
    private const val KEY_SUBMITTED_BOOT = "submittedBootId"

    enum class Result { SUBMITTED, FAILED, REFUSED }

    private val running = AtomicBoolean(false)
    private val observationRevision = AtomicLong()

    /** Local identity only: no SDK probe, credential migration, root command or writes. */
    fun observationScope(context: Context): String? = runCatching {
        if (ModeRuntime.active != WorkMode.LITE || UltraRootKeyStore.isSuspended(context) ||
            LiteImplantQuiesceGate.isBlocked() || WorkModeSwitchSession.executing) return null
        val credential = observationIdentity(UltraSdkCredentials.read(context),
            UltraEnvironmentProbe.currentSnapshot(context).managerOwnerIdentity) ?: return null
        val boot = UltraEnvironmentAutoSetup.currentBootId() ?: return null
        val submitted = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SUBMITTED_BOOT, null) == boot || UltraIdleEnvironmentRecovery.submittedThisBoot(context)
        val identity = listOf(credential, boot, ModeRuntime.activation.toString(), UltraSdkBuild.BUILD_ID,
            UltraSdkBuild.HELPER_SHA256, UltraSdkBuild.APP_VERSION, submitted.toString(), observationRevision.get().toString())
            .joinToString(":") { "${it.length}:$it" }
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }.getOrNull()

    internal fun observationIdentity(key: String?, owner: UltraRuntimeClient.ManagerOwnerIdentity?): String? =
        key?.takeIf(String::isNotBlank)?.let { "key:$it" }
            ?: owner?.let { "owner:${it.fullUid}:${it.epoch}:${it.hostInstance}" }

    internal fun invalidateObservation() { observationRevision.incrementAndGet() }

    /** 进程内是否有升级正在执行（界面重建时据此恢复"升级中"）。 */
    val inProgress: Boolean get() = running.get()

    /** 上一次升级失败的原因（机器码 + 一段说明）；成功后清空。UI 用它解释 FAILED。 */
    @Volatile
    var lastFailureReason: String? = null
        private set

    /** Publish the installed version before the slower identity read; upgrade decisions still use all facts. */
    @JvmOverloads
    fun readFacts(context: Context, onInstalledVersion: (String?) -> Unit = {}): UltraEnvFacts =
        readObservation(context, onInstalledVersion).first

    private fun readObservation(context: Context, onInstalledVersion: (String?) -> Unit): Pair<UltraEnvFacts, InstalledIdentity?> {
        requireBackground()
        if (ModeRuntime.active != WorkMode.LITE) return UltraEnvFacts() to null
        val app = context.applicationContext
        val state = runCatching { UltraEnvironmentProbe.refresh(app, force = false) }
            .getOrDefault(UltraEnvironmentProbe.State.Unknown)
        val scope = observationScope(app)
        val client = UltraSdkCredentials.client(app)
        val key = UltraSdkCredentials.read(app)
        val owner = if (key == null) client.queryManagerOwnerIdentity().identity else null
        val installedVersion = versionOf { client.installedEnvironmentVersion() }
        onInstalledVersion(installedVersion)
        // 身份回读失败不是致命错误：Unknown 照样进 facts，由策略判「待确认」，不猜。
        val identity = runCatching { UltraInstalledIdentityProbe.read(app, client) }
            .onFailure { Log.w(TAG, "identity read failed ${it.javaClass.simpleName}") }
            .getOrElse { InstalledIdentity.unknown() }
            .takeIf { it.source == Source.Readback }
        val bundledVersion = versionOf { client.sdkVersion() }
        val facts = UltraEnvFacts(
            running = state == UltraEnvironmentProbe.State.Running,
            // 读不到暂停标记时按"已暂停"处理：宁可不提示，也不在不确定时写环境。
            suspended = runCatching { UltraRootKeyStore.isSuspended(app) }.getOrDefault(true),
            quiescing = LiteImplantQuiesceGate.isBlocked() || WorkModeSwitchSession.executing,
            installedVersion = installedVersion,
            bundledVersion = bundledVersion,
            submittedThisBoot = submittedThisBoot(app),
            installedBuildId = identity?.buildId,
            bundledBuildId = UltraInstalledIdentityProbe.bundledBuildId(),
            installedHelperSha256 = identity?.helperSha256,
            bundledHelperSha256 = UltraInstalledIdentityProbe.bundledHelperSha256(),
            // 真实设备上身份是判定的一部分：缺哪一侧就报「待确认」，不会静默 None。
            identityKnown = true,
            installedAppVersion = UltraInstalledIdentityProbe.persistedAppVersion(app),
            bundledAppVersion = UltraSdkBuild.APP_VERSION,
            readUnknown = state == UltraEnvironmentProbe.State.Unknown || installedVersion == null || bundledVersion == null,
            observationScope = scope,
        )
        val checked = if (scope != null && scope == observationScope(app) &&
            (key != null || owner != null && owner == UltraEnvironmentProbe.currentSnapshot(app).managerOwnerIdentity &&
                owner == client.queryManagerOwnerIdentity().identity)) facts
            else facts.copy(quiescing = true, readUnknown = true, observationScope = null)
        return checked to identity
    }

    fun upgrade(context: Context): Result = submit(context, repairFault = false)

    /** Explicit recovery shares the upgrade marker, preparation and readback. */
    fun repairEnvironment(context: Context): Result = submit(context, repairFault = true)

    @Synchronized
    private fun submit(context: Context, repairFault: Boolean): Result {
        try {
            requireBackground()
        } catch (error: IllegalStateException) {
            notePreflightRefusal("preflight", "PREFLIGHT_BACKGROUND_REQUIRED")
            throw error
        }
        if (!running.compareAndSet(false, true)) {
            // A concurrent request must not overwrite the active operation's status.
            notePreflightRefusal("preflight", "PREFLIGHT_IN_PROGRESS")
            return Result.REFUSED
        }
        invalidateObservation()
        val app = context.applicationContext
        var phase = "preflight"
        try {
            recordStatus(app, phase)
            if (ModeRuntime.active != WorkMode.LITE)
                return preflightRefused(app, phase, "PREFLIGHT_MODE_NOT_LITE")
            if (LiteImplantQuiesceGate.isBlocked())
                return preflightRefused(app, phase, "PREFLIGHT_QUIESCING")
            if (WorkModeSwitchSession.executing)
                return preflightRefused(app, phase, "PREFLIGHT_SWITCH_PENDING")
            if (UltraRootKeyStore.isSuspended(app))
                return preflightRefused(app, phase, "PREFLIGHT_SUSPENDED")
            if (submittedThisBoot(app))
                return preflightRefused(app, phase, "PREFLIGHT_SUBMITTED_THIS_BOOT")
            // A newer helper cannot repair a retired executor retained in boot. Check only
            // this explicit action, before another SDK call can execute through that kernel.
            if (dev.maplesu.manager.installer.UltraKernelEnvironmentUpgrade.required(app)) {
                phase = "kernel"
                return submitKernelUpdate(app)
            }
            val installKey = UltraSdkCredentials.read(app)
            val installBoot = UltraEnvironmentAutoSetup.currentBootId()
                ?: return preflightRefused(app, phase, "PREFLIGHT_BOOT_UNAVAILABLE")
            val installActivation = ModeRuntime.activation
            val client = UltraSdkCredentials.client(app, installKey, installBoot)
            val installOwner = if (installKey == null) {
                val observedOwner = client.queryManagerOwnerIdentity()
                observedOwner.identity ?: return preflightRefused(app, phase,
                    "PREFLIGHT_OWNER_UNCONFIRMED:${observedOwner.receipt.sdkError}:${observedOwner.receipt.sdkErrorCode}")
            } else null
            UltraInstalledIdentityProbe.invalidate()
            // Available（同版本新构建）与 Unverified（身份读不到，重装写身份）都允许提交；
            // quiescing / 暂停 / 未运行 / 管理器降级 一律拒绝，不产生任何写调用。
            if (repairFault) {
                val state = UltraEnvironmentProbe.refresh(app, force = true)
                if (state != UltraEnvironmentProbe.State.Fault && state != UltraEnvironmentProbe.State.Unknown) {
                    recordStatus(app, phase, "REPAIR_STATE_${state.name}")
                    return Result.REFUSED
                }
                val (facts, identity) = readObservation(app, {})
                // Version/identity reads already used the captured credential. A diagnostic
                // kernel EXEC test is not an installation prerequisite; the installer owns
                // the actual operation and returns its failure without changing the state.
                val completedBoot = state == UltraEnvironmentProbe.State.Unknown &&
                    completedBootRepairEvidence(app, client)
                if (!faultRepairAllowed(state.name, facts, completedBoot,
                        identity, UltraInstalledIdentityProbe.installedHelperPath(installKey)
                            ?: identity?.helperPath?.takeIf { installOwner != null }, installBoot)) {
                    val reason = "REPAIR_FACTS_STATE_${state.name}:running=${facts.running}" +
                        ";suspended=${facts.suspended};quiescing=${facts.quiescing}" +
                        ";submitted=${facts.submittedThisBoot};completedBoot=$completedBoot" +
                        ";installedVersion=${facts.installedVersion};bundledVersion=${facts.bundledVersion}" +
                        ";helperMatch=${facts.installedHelperSha256 != null && facts.installedHelperSha256 == facts.bundledHelperSha256}" +
                        ";installedApp=${facts.installedAppVersion};bundledApp=${facts.bundledAppVersion}"
                    recordStatus(app, phase, reason)
                    Log.w(TAG, reason)
                    return Result.REFUSED
                }
            } else if (!upgradeAllowed(UltraEnvUpgradePolicy.decide(readFacts(app)))) {
                return Result.REFUSED
            }
            // Fact reads can take time; do not submit if a switch/pause started meanwhile.
            if (ModeRuntime.active != WorkMode.LITE || ModeRuntime.activation != installActivation ||
                LiteImplantQuiesceGate.isBlocked() || WorkModeSwitchSession.executing || UltraRootKeyStore.isSuspended(app) ||
                UltraSdkCredentials.read(app) != installKey || UltraEnvironmentAutoSetup.currentBootId() != installBoot ||
                (installOwner != null && client.queryManagerOwnerIdentity().identity != installOwner)) {
                if (repairFault) recordStatus(app, phase, "REPAIR_SCOPE_CHANGED")
                return Result.REFUSED
            }
            val bootId = installBoot
            val prefs = app.ultraPreferences(PREFS, Context.MODE_PRIVATE)
            // 标记先于安装落盘：安装途中进程被杀，本次开机也不会再提交第二次。
            if (!prefs.edit().putString(KEY_SUBMITTED_BOOT, bootId).commit())
                return preflightRefused(app, phase, "PREFLIGHT_MARKER_COMMIT_FAILED")
            phase = "install"
            recordStatus(app, phase)
            val outcome = submitMarked(install = {
                UltraEnvironmentInstaller.install(
                    app,
                    client = client,
                    // This user-confirmed action promises activation, not a file-only refresh.
                    intent = UltraEnvironmentInstaller.Intent.UpgradeToBundledBuild,
                )
            }, clear = { prefs.edit().remove(KEY_SUBMITTED_BOOT).commit() })
            if (resultOf(outcome) != Result.SUBMITTED) {
                phase = if (outcome?.ok == true) "verify" else "install"
                val reason = diagnosticReasonOf(outcome)
                recordStatus(app, phase, reason)
                Log.w(TAG, "upgrade failed phase=$phase reason=$reason")
                return Result.FAILED
            }
            // Activation and uncertain writes retain the marker until a real new boot.
            recordStatus(app, "verified")
            Log.i(TAG, "upgrade submitted build=${outcome?.buildId}")
            return Result.SUBMITTED
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            recordStatus(app, phase, "EXCEPTION:" + generateSequence(error as Throwable) { it.cause }
                .take(4).joinToString(" <- ") { it.toString() })
            return Result.FAILED
        } finally {
            running.set(false)
        }
    }

    /** Diagnostics only: no new credential, SDK, Root or boot-id reads. */
    private fun preflightRefused(context: Context, phase: String, reason: String): Result {
        val diagnostic = diagnosticText(reason)
        lastFailureReason = diagnostic
        // Diagnostic storage failure must not change the original refusal result.
        runCatching {
            context.ultraPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("lastPhase", phase).putString("lastFailure", diagnostic).commit()
        }
        notePreflightRefusal(phase, diagnostic)
        return Result.REFUSED
    }

    private fun notePreflightRefusal(phase: String, reason: String) {
        runCatching { Log.w(TAG, "upgrade refused phase=$phase reason=$reason") }
    }

    private fun submitKernelUpdate(context: Context): Result {
        if (ModeRuntime.active != WorkMode.LITE)
            return preflightRefused(context, "kernel", "KERNEL_PREFLIGHT_MODE_NOT_LITE")
        if (LiteImplantQuiesceGate.isBlocked())
            return preflightRefused(context, "kernel", "KERNEL_PREFLIGHT_QUIESCING")
        if (WorkModeSwitchSession.executing)
            return preflightRefused(context, "kernel", "KERNEL_PREFLIGHT_SWITCH_PENDING")
        if (UltraRootKeyStore.isSuspended(context))
            return preflightRefused(context, "kernel", "KERNEL_PREFLIGHT_SUSPENDED")
        val bootId = UltraEnvironmentAutoSetup.currentBootId()
            ?: return preflightRefused(context, "kernel", "KERNEL_PREFLIGHT_BOOT_UNAVAILABLE")
        val prefs = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.edit().putString(KEY_SUBMITTED_BOOT, bootId).commit())
            return preflightRefused(context, "kernel", "KERNEL_PREFLIGHT_MARKER_COMMIT_FAILED")
        val taskId = java.util.UUID.randomUUID().toString()
        recordStatus(context, "kernel")
        val result = com.maplesu.core.ui.util.installUltraKernelUpdate(taskId)
        if (result.code != 0 || result.outcome != com.maplesu.core.ui.util.FlashOutcome.WRITTEN) {
            if (result.outcome != com.maplesu.core.ui.util.FlashOutcome.RECOVERY_REQUIRED &&
                result.outcome != com.maplesu.core.ui.util.FlashOutcome.WRITTEN) {
                prefs.edit().remove(KEY_SUBMITTED_BOOT).commit()
            }
            recordStatus(context, "kernel", result.err.ifBlank { "ULTRA_KERNEL_UPGRADE_FAILED" })
            return Result.FAILED
        }
        recordStatus(context, "kernel_verified")
        val rebootRequested = runCatching {
            dev.maplesu.manager.installer.InstallerServiceClient.rebootAfterInstallation(context, taskId)
        }.getOrDefault(false)
        if (!rebootRequested) {
            recordStatus(context, "kernel_awaiting_reboot")
        }
        return Result.SUBMITTED
    }

    internal fun faultRepairAllowed(state: String?, facts: UltraEnvFacts,
                                    completedBoot: Boolean = false, identity: InstalledIdentity? = null,
                                    expectedHelperPath: String? = null, expectedBootId: String? = null): Boolean {
        if (state != "Fault" && !(state == "Unknown" && completedBoot &&
                UltraInstalledIdentityProbe.normalizeSha(facts.installedHelperSha256) != null)) return false
        if (facts.running || facts.suspended ||
            facts.quiescing || facts.submittedThisBoot) return false
        val bundled = EnvVersion.parse(facts.bundledVersion) ?: return false
        val installed = EnvVersion.parse(facts.installedVersion)
        if (installed == null) {
            val observed = UltraInstalledIdentityProbe.normalizeSha(facts.installedHelperSha256) ?: return false
            val expected = UltraInstalledIdentityProbe.normalizeSha(facts.bundledHelperSha256) ?: return false
            if (observed != expected && (identity == null || identity.source != Source.Readback ||
                    expectedHelperPath == null || expectedBootId == null ||
                    identity.helperPath != expectedHelperPath || identity.bootId != expectedBootId ||
                    identity.helperSize?.let { it > 0L } != true ||
                    UltraInstalledIdentityProbe.normalizeSha(identity.helperSha256) != observed)) return false
        } else if (installed > bundled) return false
        val installedApp = EnvVersion.parse(facts.installedAppVersion)
        val bundledApp = EnvVersion.parse(facts.bundledAppVersion)
        if (facts.installedAppVersion != null && installedApp == null ||
            facts.bundledAppVersion != null && bundledApp == null) return false
        return installedApp == null || bundledApp == null || installedApp <= bundledApp
    }

    private fun completedBootRepairEvidence(context: Context, client: UltraRuntimeClient): Boolean {
        val bootId = UltraEnvironmentAutoSetup.currentBootId() ?: return false
        val helper = UltraInstalledIdentityProbe.installedHelperPath(UltraSdkCredentials.read(context))
            ?: UltraInstalledIdentityProbe.read(context, client, force = true)
                .takeIf { it.source == Source.Readback && it.bootId == bootId }?.helperPath
            ?: return false
        val receipt = client.runRootCommand(completedBootRepairScript(helper, bootId))
        return receipt.succeeded && completedBootRepairConfirmed(bootId, client.decodeText(receipt.string("result"))) &&
            UltraEnvironmentAutoSetup.currentBootId() == bootId
    }

    /** Manual recovery accepts a stale completed helper or an idle, never-started installation. */
    internal fun completedBootRepairScript(helper: String, bootId: String): String {
        require(helper.matches(Regex("/data/[A-Za-z0-9]{16}/init")))
        require(bootId.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
        return """
            [ "${'$'}(/system/bin/getprop sys.boot_completed)" = 1 ] || exit 0
            [ "${'$'}(head -c 65 /proc/sys/kernel/random/boot_id)" = '$bootId' ] || exit 0
            h='$helper'
            [ -f "${'$'}h" ] && [ ! -L "${'$'}h" ] || exit 0
            [ -f "${'$'}h.safe_flag" ] && [ ! -L "${'$'}h.safe_flag" ] || exit 0
            flag=${'$'}(head -c 65 "${'$'}h.safe_flag") || exit 0
            current_receipt() {
                [ -f "${'$'}1" ] && [ ! -L "${'$'}1" ] && [ -r "${'$'}1" ] &&
                    [ "${'$'}(wc -c < "${'$'}1")" -le 64 ] 2>/dev/null &&
                    [ "${'$'}(head -c 65 "${'$'}1")" = '$bootId' ]
            }
            if { [ "${'$'}flag" = 2 ] || { [ ! -s "${'$'}h.safe_flag" ] &&
                    current_receipt "${'$'}h.boot_session.completed"; }; } &&
                current_receipt "${'$'}h.boot_session.work" &&
                current_receipt "${'$'}h.boot_session.duplicate" &&
                [ "${'$'}(head -c 65 "${'$'}h.safe_flag")" = "${'$'}flag" ]; then
                echo 'MAPLESU_COMPLETED_BOOT:$bootId'
                exit 0
            fi
            if [ ! -s "${'$'}h.safe_flag" ]; then
                read up rest < /proc/uptime || exit 0
                [ "${'$'}{up%%.*}" -ge 120 ] 2>/dev/null || exit 0
                for suffix in boot_fail_protect enable boot_session.work boot_session.duplicate boot_session.completed; do
                    f="${'$'}h.${'$'}suffix"
                    if [ -e "${'$'}f" ] || [ -L "${'$'}f" ]; then
                        [ -f "${'$'}f" ] && [ ! -L "${'$'}f" ] && [ -r "${'$'}f" ] && [ ! -s "${'$'}f" ] || exit 0
                    fi
                done
                [ ! -s "${'$'}h.safe_flag" ] || exit 0
                echo 'MAPLESU_COMPLETED_BOOT:$bootId'
                exit 0
            fi
            [ "${'$'}(head -c 65 "${'$'}h.safe_flag")" = 2 ] || exit 0
            if [ -e "${'$'}h.boot_session.work" ]; then
                [ -f "${'$'}h.boot_session.work" ] && [ ! -L "${'$'}h.boot_session.work" ] || exit 0
                work=${'$'}(head -c 65 "${'$'}h.boot_session.work") || exit 0
                [ "${'$'}work" != '$bootId' ] || exit 0
            elif [ -L "${'$'}h.boot_session.work" ]; then
                exit 0
            fi
            echo 'MAPLESU_COMPLETED_BOOT:$bootId'
        """.trimIndent()
    }

    internal fun completedBootRepairConfirmed(bootId: String, output: String?): Boolean =
        output?.trim() == "MAPLESU_COMPLETED_BOOT:$bootId"

    /** Survives process death, and is included in bugreports even when root is down. */
    fun persistedFailure(context: Context): String? =
        context.ultraPreferences(PREFS, Context.MODE_PRIVATE).getString("lastFailure", null)

    fun diagnostics(context: Context): String {
        val prefs = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        return "phase=${prefs.getString("lastPhase", "none")}\n" +
            "failure=${prefs.getString("lastFailure", "")}\n" +
            "build=${prefs.getString("lastBuild", "")}\n" +
            "boot=${prefs.getString("lastBoot", "")}\n"
    }

    private fun recordStatus(context: Context, phase: String, reason: String? = null) {
        val diagnostic = reason?.let(::diagnosticText)
        lastFailureReason = diagnostic
        context.ultraPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lastPhase", phase).putString("lastFailure", diagnostic)
            .putString("lastBuild", UltraSdkBuild.BUILD_ID)
            .putString("lastBoot", UltraEnvironmentAutoSetup.currentBootId()).commit()
    }

    internal fun diagnosticReasonOf(outcome: UltraEnvironmentInstaller.Outcome?): String =
        diagnosticText(failureReasonOf(outcome))

    private fun diagnosticText(reason: String?): String =
        UltraSdkLogging.redact(reason).replace(Regex("/data/[A-Za-z0-9]{16,17}(?=/|$)"), "/data/<private>")
            .replace('\n', ' ').replace('\r', ' ').take(512)

    /** Executes after the durable per-boot marker has been stored. */
    internal fun submitMarked(install: () -> UltraEnvironmentInstaller.Outcome,
                              clear: () -> Unit): UltraEnvironmentInstaller.Outcome? {
        val outcome = try {
            install()
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            UltraEnvironmentInstaller.Outcome(false, "EXCEPTION", reason = UltraSdkLogging.redact(error.toString()),
                submissionUnknown = true)
        }
        // Success without readback and transport exceptions may already have written the
        // helper. Keep the durable marker until another boot; never replay an uncertain write.
        if (!outcome.ok && !outcome.submissionUnknown && outcome.sdkError != "EXCEPTION") clear()
        return outcome
    }

    /**
     * 哪些判定允许真正写设备。
     *
     *  - [UltraEnvUpgrade.Available]：同上游版本的新构建；
     *  - [UltraEnvUpgrade.Unverified]：身份读不到（老环境没有身份记录）。用户口径是
     *    「点一次就能升级」，所以确认不了时**允许重装**——重装会写入可验证身份；只是
     *    成功仍必须过回读，绝不因为放宽入口而放宽「算成功」的标准。
     *
     * [UltraEnvUpgrade.None]（含 quiescing/暂停/未运行/降级）与
     * [UltraEnvUpgrade.Rebooting]（本 boot 已提交）都不允许再次写。
     */
    fun upgradeAllowed(decision: UltraEnvUpgrade): Boolean =
        decision is UltraEnvUpgrade.Available || decision is UltraEnvUpgrade.Unverified

    /**
     * 安装回执 → 升级结论。**只有 ok 且 verified 才算提交成功**：
     * 「SDK 说装完了」不等于「设备上已经是这一版」，后者只能靠回读摘要证明。
     */
    fun resultOf(outcome: UltraEnvironmentInstaller.Outcome?): Result = when {
        outcome == null -> Result.FAILED
        !outcome.ok -> Result.FAILED
        !outcome.verified -> Result.FAILED
        else -> Result.SUBMITTED
    }

    /** 失败原因（机器码 + 摘要），成功后为 null；不包含任何密文/密钥。 */
    fun failureReasonOf(outcome: UltraEnvironmentInstaller.Outcome?): String? = when {
        outcome == null -> "INSTALL_EXCEPTION"
        !outcome.ok -> "SDK:${outcome.sdkError}" + (outcome.reason?.let { ":$it" } ?: "") +
            (outcome.sdkErrorCode?.let { " code=$it" } ?: "") +
            (outcome.detail?.let { " $it" } ?: "")
        !outcome.verified -> outcome.detail ?: "UNVERIFIED:UNKNOWN"
        else -> null
    }

    private fun submittedThisBoot(context: Context): Boolean {
        if (UltraIdleEnvironmentRecovery.submittedThisBoot(context)) return true
        val prefs = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        val marker = prefs.getString(KEY_SUBMITTED_BOOT, null) ?: return false
        val bootId = UltraEnvironmentAutoSetup.currentBootId() ?: return false
        if (marker == bootId) return true
        // 已经重启过：标记作废，回到按版本判断。
        prefs.edit().remove(KEY_SUBMITTED_BOOT).apply()
        return false
    }

    private inline fun versionOf(read: () -> NativeReceipt): String? =
        runCatching(read).getOrNull()
            ?.takeIf { it.succeeded }
            ?.string("version")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun requireBackground() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "ULTRA_UPGRADE_BACKGROUND_THREAD_REQUIRED" }
    }
}
