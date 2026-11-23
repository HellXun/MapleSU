package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import android.os.Looper
import android.os.Process
import android.util.Log
import android.provider.Settings
import dev.maplesu.manager.litehidden.RecoveryIdentity
import dev.maplesu.manager.modules.UltraRootKeyStore
import dev.maplesu.manager.runtime.UltraRootCommandReceipt
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import java.util.Base64
import java.util.UUID
import java.io.File

/** Temporary credentials never enter UltraRootKeyStore or the permanent recovery backup. */
internal object UltraSdkSession {
    private const val PREFS = "maplesu-boot-sdk"
    private const val RECORD = "session"
    private const val BOOTSTRAP_SUBMITTED = "bootstrapSubmitted"

    /** Explicit startup path; availability/page reads never initialize a gateway. */
    fun bootstrap(context: Context, bootId: String): UltraBootSdkGateway.Result =
        bootstrap(context, bootId) { true }

    @Synchronized
    internal fun bootstrap(context: Context, bootId: String, isCurrent: () -> Boolean): UltraBootSdkGateway.Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "BOOT_SDK_BACKGROUND_THREAD_REQUIRED" }
        val preferences = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        fun allowed() = isCurrent() && UltraEnvironmentAutoSetup.currentBootId() == bootId &&
            !Thread.currentThread().isInterrupted && UltraRootGateway.available(context) &&
            UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) == null
        if (!isCurrent()) return UltraBootSdkGateway.Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
        val result = UltraBootSdkGateway.connect(preferences.getString(BOOTSTRAP_SUBMITTED, null) == bootId,
            object : UltraBootSdkGateway.Port {
                override fun current() = allowed()
                override fun query() = gateway(context, bootId, isCurrent)
                override fun markSubmitted() = allowed() && preferences.edit()
                    .putString(BOOTSTRAP_SUBMITTED, bootId).commit()
                override fun start(): String? {
                    if (!allowed()) return "SESSION_CHANGED"
                    val result = UltraRootGateway.run(context, gatewayCommand(context, start = true))
                    return if (!isCurrent()) "SDK_CREDENTIAL_SCOPE_CHANGED" else if (result.ok) null else UltraBootSdkGateway.commandFailure(result.code,
                        result.output + "\n" + result.detail)
                }
                override fun bind(receipt: UltraBootSdkGateway.Receipt) =
                    allowed() && bind(context, bootId, receipt.key, receipt.kernelBase, isCurrent)
            })
        return if (isCurrent()) result else result.copy(bound = false, reason = "SDK_CREDENTIAL_SCOPE_CHANGED")
    }

    private fun gatewayCommand(context: Context, start: Boolean): String {
        val source = File(context.applicationInfo.nativeLibraryDir, "libmaplesu_policy.so")
        val quoted = "'${source.absolutePath.replace("'", "'\\''")}'"
        return "exec $quoted " + if (start) "boot-sdk-start" else "boot-sdk-info"
    }

    private fun gateway(context: Context, bootId: String, isCurrent: () -> Boolean = { true }): UltraBootSdkGateway.Receipt? {
        if (!isCurrent() || Thread.currentThread().isInterrupted || UltraEnvironmentAutoSetup.currentBootId() != bootId ||
            UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) != null) return null
        if (!UltraRootGateway.available(context) || !isCurrent()) return null
        val result = UltraRootGateway.run(context, gatewayCommand(context, start = false))
        return if (result.ok && isCurrent() && UltraEnvironmentAutoSetup.currentBootId() == bootId)
            UltraBootSdkGateway.parse(result.output, bootId) else null
    }

    fun current(context: Context): UltraBootSdkSession? {
        val saved = UltraBootSdkSession.parse(context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(RECORD, null)) ?: return null
        return saved.takeIf { it.current(UltraEnvironmentAutoSetup.currentBootId()) }
    }

    internal fun captureOwnerMarker(context: Context): () -> Boolean {
        val preferences = context.ultraPreferences(PREFS, Context.MODE_PRIVATE)
        val record = preferences.getString(RECORD, null)
        val session = UltraBootSdkSession.parse(record)
        var marker = record to true
        return {
            val observed = preferences.getString(RECORD, null)
            val previous = marker
            if (observed == previous.first) previous.second else {
                val sameIdentity = UltraBootSdkSession.parse(observed).let {
                    it?.key == session?.key && it?.bootId == session?.bootId
                }
                marker = observed to sameIdentity
                sameIdentity
            }
        }
    }

    /** Called with the key returned by a real, resident SDK gateway, never an L3 token. */
    fun bind(context: Context, bootId: String, key: String, expectedKernelBase: String): Boolean =
        bind(context, bootId, key, expectedKernelBase) { true }

    @Synchronized
    private fun bind(context: Context, bootId: String, key: String, expectedKernelBase: String,
                     isCurrent: () -> Boolean): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "BOOT_SDK_BACKGROUND_THREAD_REQUIRED" }
        if (!isCurrent()) return false
        val candidate = UltraBootSdkSession.create(bootId, key) ?: return false
        if (!candidate.current(UltraEnvironmentAutoSetup.currentBootId()) || Thread.currentThread().isInterrupted ||
            UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) != null ||
            !UltraRootGateway.available(context)) return false
        val previous = current(context)
        // A second bootstrap must keep the resident gateway's original identity and guard.
        if (previous != null && (previous.bootId != bootId || previous.key != key)) return false
        val client = UltraRuntimeClient { key.takeIf {
            isCurrent() && candidate.current(UltraEnvironmentAutoSetup.currentBootId()) && !Thread.currentThread().isInterrupted &&
                UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) == null
        } }
        if (!client.testBasics("Channel").succeeded) return false
        val kernelBase = client.testBasics("KernelBase")
        if (!kernelBase.succeeded || !UltraBootSdkGateway.kernelBaseMatches(
                client.decodeText(kernelBase.string("info")), expectedKernelBase) ||
            !isCurrent() || !candidate.current(UltraEnvironmentAutoSetup.currentBootId()) || Thread.currentThread().isInterrupted ||
            UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) != null) return false
        if (previous != null) return true
        val stored = context.ultraPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(RECORD, candidate.encode()).commit()
        if (stored) UltraEnvironmentProbe.invalidateAvailability()
        return stored && isCurrent()
    }

    /** Read-only probes do not call this. It belongs to an explicit bootstrap operation. */
    fun ensure(context: Context): UltraBootSdkInstall.Result = ensure(context) { true }

    internal fun ensure(context: Context, isCurrent: () -> Boolean): UltraBootSdkInstall.Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "BOOT_SDK_BACKGROUND_THREAD_REQUIRED" }
        if (!isCurrent()) return UltraBootSdkInstall.Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
        val session = current(context) ?: return UltraBootSdkInstall.Result(false, false, "SDK_SESSION_ABSENT")
        return ensure(context, session, isCurrent)
    }

    @Synchronized
    internal fun ensure(context: Context, session: UltraBootSdkSession,
                        isCurrent: () -> Boolean): UltraBootSdkInstall.Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "BOOT_SDK_BACKGROUND_THREAD_REQUIRED" }
        fun allowed() = isCurrent() && usable(context, session)
        if (!allowed()) return UltraBootSdkInstall.Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
        val hasRoot = UltraRootGateway.available(context)
        if (!allowed()) return UltraBootSdkInstall.Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
        if (hasRoot) {
            val gateway = gateway(context, session.bootId, ::allowed)
                ?: return UltraBootSdkInstall.Result(false, false,
                    if (allowed()) "SDK_GATEWAY_UNAVAILABLE" else "SDK_CREDENTIAL_SCOPE_CHANGED")
            if (!allowed()) return UltraBootSdkInstall.Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
            if (!gateway.ready || gateway.key != session.key)
                return UltraBootSdkInstall.Result(false, false, "SDK_GATEWAY_IDENTITY_CHANGED")
            if (!gateway.environment)
                return UltraBootSdkInstall.Result(false, false, "SDK_ENVIRONMENT_ABI_UNAVAILABLE")
        } else if (!session.installSubmitted) {
            return UltraBootSdkInstall.Result(false, false, "SDK_GATEWAY_UNAVAILABLE")
        }
        if (!allowed()) return UltraBootSdkInstall.Result(false, false, "SDK_CREDENTIAL_SCOPE_CHANGED")
        val client = client(context, session).scoped(session.key, isCurrent)
        var preparationReason: String? = null
        return UltraBootSdkInstall.ensure(session.installSubmitted, object : UltraBootSdkInstall.Port {
            override fun current() = allowed()
            override fun channelVerified() = client.testBasics("Channel").succeeded
            override fun kernelBaseVerified() = client.testBasics("KernelBase").succeeded
            override fun environmentState(): String? = client.environmentState()
                .takeIf { it.succeeded }?.string("state")
            override fun prepare(): Boolean {
                if (!allowed()) return false
                val failure = UltraPolicyBackendDeployment.prepare(context, client)
                preparationReason = failure?.takeIf {
                    Regex("(?:MODULE_(?:POLICY_)?TOOLS|POLICY_BACKEND)_[A-Z_]+" +
                        "(?::(?:ERR_|MAPLESU_)[A-Z0-9_]{1,64}|:-?[0-9]{1,10}(?::[a-z-]{1,32})?)?")
                        .matches(it)
                }
                return failure == null && allowed()
            }
            override fun markSubmitted(): Boolean = allowed() &&
                context.ultraPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(RECORD, session.submitted().encode()).commit()
            override fun submit() = client.installEnvironment("HotLoad", "").also { receipt ->
                UltraSdkLogging.logSafely(if (receipt.succeeded) Log.INFO else Log.WARN,
                    "HotLoad operation=${receipt.operation} sdkError=${receipt.sdkError} code=${receipt.sdkErrorCode}")
            }
            override fun rootVerified() = UltraIdentityRecovery.uid(client) == 0
            override fun whitelistVerified(): Boolean {
                val result = UltraSelfWhitelist.ensureCurrentAuthority(context, client, ::allowed)
                return result.succeeded
            }
            override fun managerSuVerified(): Boolean {
                val su = UltraSuChannel()
                return UltraSelfWhitelist.verifyExec(
                    current = ::allowed,
                    probe = { allowed() && su.rootUid() == 0 && allowed() },
                    rootVerified = { UltraIdentityRecovery.uid(client) == 0 },
                    refresh = { UltraSelfWhitelist.ensureCurrentAuthority(context, client, ::allowed).succeeded },
                )
            }
            override fun moduleListVerified(): Boolean {
                val receipt = client.moduleList()
                return receipt.succeeded && receipt.array("modules") != null
            }
        }).let { result ->
            if (!allowed()) result.copy(ready = false, reason = "SDK_CREDENTIAL_SCOPE_CHANGED")
            else if (result.reason == "HOTLOAD_PREPARATION_FAILED" && preparationReason != null)
                result.copy(reason = "${result.reason}:$preparationReason")
            else result
        }.also { UltraEnvironmentProbe.invalidateAvailability() }
    }

    fun client(context: Context, session: UltraBootSdkSession = requireNotNull(current(context))): UltraRuntimeClient =
        UltraRuntimeClient { session.key }.scoped(session.key) { usable(context, session) }

    /** Ordinary-UID failure permits one guarded UID refresh, followed by a fresh exec. */
    fun verifyManagerSu(context: Context, session: UltraBootSdkSession, probe: () -> Boolean): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "BOOT_SDK_BACKGROUND_THREAD_REQUIRED" }
        val client = client(context, session)
        return UltraSelfWhitelist.verifyExec(
            current = { usable(context, session) && UltraRootGateway.available(context) },
            probe = probe,
            rootVerified = { UltraIdentityRecovery.uid(client) == 0 },
            refresh = {
                val refreshed = UltraSelfWhitelist.ensureCurrentAuthority(context, client) {
                    usable(context, session) && UltraRootGateway.available(context)
                }
                refreshed.succeeded
            },
        )
    }

    private fun same(context: Context, session: UltraBootSdkSession): Boolean = current(context)?.let {
        it.bootId == session.bootId && it.key == session.key
    } == true

    private fun usable(context: Context, session: UltraBootSdkSession): Boolean =
        same(context, session) && !Thread.currentThread().isInterrupted &&
            UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) == null

    /** Explicit startup recovery; a present boot record never falls through to permanent pairing. */
    @Synchronized
    fun restore(context: Context): String? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "BOOT_SDK_BACKGROUND_THREAD_REQUIRED" }
        if (current(context) != null) return "BOOT_SDK_CURRENT"
        if (UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) != null) return null
        val su = UltraSuChannel(timeoutMillis = 700L)
        val path = UltraBootSdkRecovery.PATH
        val read = su.runRoot("""
            [ "${'$'}(id -u)" = 0 ] || exit 1
            if [ ! -e '$path' ] && [ ! -L '$path' ]; then echo MAPLESU_BOOT_SDK_ABSENT; exit 0; fi
            [ ! -L /data/adb/maplesu ] && [ ! -L /data/adb/maplesu/recovery ] && [ ! -L '$path' ] || exit 2
            [ "${'$'}(stat -c '%a %u %g' /data/adb/maplesu/recovery)" = '700 0 0' ] || exit 3
            [ "${'$'}(stat -c '%a %u %g' '$path')" = '600 0 0' ] || exit 4
            [ -f '$path' ] && [ "${'$'}(wc -c < '$path')" -le 8192 ] || exit 5
            printf 'MAPLESU_BOOT_SDK_MATERIAL '; cat '$path'
        """.trimIndent(), timeout = 2000L) ?: return null
        if (!read.ok) return "BOOT_SDK_RECOVERY_UNREADABLE"
        if (read.output.trim() == "MAPLESU_BOOT_SDK_ABSENT") return null
        val material = read.output.removePrefix("MAPLESU_BOOT_SDK_MATERIAL ").trim()
        val identity = RecoveryIdentity.of(runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull(), context.packageName, Process.myUid()) ?: return "BOOT_SDK_RECOVERY_IDENTITY_MISSING"
        val result = UltraBootSdkRestore.restore(material, identity, object : UltraBootSdkRestore.Port {
            override fun currentBootId() = UltraEnvironmentAutoSetup.currentBootId()
            override fun verified(session: UltraBootSdkSession): Boolean {
                val client = UltraRuntimeClient { session.key.takeIf {
                    session.current(currentBootId()) && !Thread.currentThread().isInterrupted &&
                        UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) == null
                } }
                if (!client.testBasics("Channel").succeeded) return false
                val metadata = client.testBasics("KernelBase")
                if (!metadata.succeeded || UltraBootSdkGateway.kernelBase(client.decodeText(metadata.string("info"))) == null ||
                    client.environmentState().let { !it.succeeded || it.string("state") != "Running" } ||
                    UltraIdentityRecovery.uid(client) != 0) return false
                val whitelist = client.suAuthSnapshot()
                if (!whitelist.receipt.succeeded || whitelist.receipt.array("packages") == null ||
                    context.packageName !in whitelist.packages) return false
                return client.moduleList().let { it.succeeded && it.array("modules") != null }
            }
            override fun store(session: UltraBootSdkSession): Boolean =
                session.current(currentBootId()) && !Thread.currentThread().isInterrupted &&
                    UltraEnvironmentAutoSetup.blockedReason(context, bootSession = true) == null &&
                    context.ultraPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(RECORD, session.encode()).commit()
        })
        if (result == "BOOT_SDK_RESTORED") UltraEnvironmentProbe.invalidateAvailability()
        return result
    }

    /** Persist independent, boot-bound material before the hidden backend modifies a host. */
    fun prepareRecovery(context: Context): Boolean {
        val session = current(context) ?: return false
        if (!ensure(context).ready || !usable(context, session)) return false
        val identity = RecoveryIdentity.of(runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull(), context.packageName, Process.myUid()) ?: return false
        val sealed = UltraBootSdkRecovery.seal(session, identity) ?: return false
        val path = UltraBootSdkRecovery.PATH
        val temporary = "$path.${UUID.randomUUID()}.tmp"
        val encoded = Base64.getEncoder().encodeToString(sealed.toByteArray(Charsets.UTF_8))
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(sealed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val script = """
            set -eu
            umask 077
            for d in /data/adb /data/adb/maplesu /data/adb/maplesu/recovery; do
              [ ! -L "${'$'}d" ] || exit 81
            done
            mkdir -p /data/adb/maplesu/recovery
            chmod 700 /data/adb/maplesu/recovery
            [ "${'$'}(stat -c '%a %u %g' /data/adb/maplesu/recovery)" = '700 0 0' ] || exit 82
            [ ! -L '$path' ] || exit 83
            trap 'rm -f "$temporary"' EXIT
            (set -C; printf '%s' '$encoded' | base64 -d > '$temporary')
            chmod 600 '$temporary'
            [ "${'$'}(stat -c '%a %u %g' '$temporary')" = '600 0 0' ] || exit 84
            mv -f '$temporary' '$path'
            [ "${'$'}(stat -c '%a %u %g' '$path')" = '600 0 0' ] || exit 85
            [ "${'$'}(sha256sum '$path' | cut -d ' ' -f 1)" = '$hash' ] || exit 86
            echo MAPLESU_BOOT_SDK_RECOVERY_OK
        """.trimIndent()
        if (!usable(context, session)) return false
        // Ultra-only：恢复只走 SDK 回执通道（越狱 hook 分支已移除）。
        val output = run {
            val command = UltraRootCommandReceipt(script)
            val client = client(context, session)
            val receipt = client.runRootCommand(command.command)
            if (!receipt.succeeded) return false
            val result = command.decode(client.decodeText(receipt.string("result")).orEmpty())
            if (!result.ok) return false
            result.output
        }
        return usable(context, session) && output.lineSequence().any { it == "MAPLESU_BOOT_SDK_RECOVERY_OK" }
    }
}

/** One owner for module, authorization and recovery calls. Hook ownership blocks stale keys. */
internal object UltraSdkCredentials {
    fun read(context: Context): String? {
        if (ModeRuntime.active == WorkMode.KSU) return UltraRootKeyStore.read(context)
        UltraSdkSession.current(context)?.let { return it.key }
        return UltraRootKeyStore.read(context)
    }

    fun client(context: Context): UltraRuntimeClient {
        val session = UltraSdkSession.current(context)
        return if (session != null && ModeRuntime.active != WorkMode.KSU) UltraSdkSession.client(context, session)
        else {
            val key = read(context)
            val bootId = UltraEnvironmentAutoSetup.currentBootId()
            UltraRuntimeClient { key }.scoped(key) {
                bootId != null && UltraEnvironmentAutoSetup.currentBootId() == bootId && read(context) == key
            }
        }
    }

    /** Policy requests keep the identity captured before queueing; a new owner is never adopted. */
    fun client(context: Context, expectedKey: String?, expectedBootId: String?): UltraRuntimeClient {
        val session = UltraSdkSession.current(context)
        return if (session != null && ModeRuntime.active != WorkMode.KSU) {
            check(session.key == expectedKey && session.bootId == expectedBootId) { "SDK_CREDENTIAL_SCOPE_CHANGED" }
            UltraSdkSession.client(context, session)
        } else {
            UltraRuntimeClient { expectedKey }.scoped(expectedKey) {
                expectedBootId != null && UltraEnvironmentAutoSetup.currentBootId() == expectedBootId &&
                    read(context) == expectedKey
            }
        }
    }

    /**
     * Ultra-only：越狱 hook 形态已随 provider 一并移除，此判据恒为 false。
     * 保留函数签名只是为了让仍在调用它的 UI/仓储层在本次重构中保持编译；
     * 调用方会逐步收敛（见 S3 的 WorkMode 清理）。
     */
    fun hookWithoutSdk(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

    fun suspended(context: Context): Boolean =
        UltraSdkSession.current(context) == null && UltraRootKeyStore.isSuspended(context)
}
