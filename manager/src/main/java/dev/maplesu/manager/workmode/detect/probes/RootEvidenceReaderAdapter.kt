package dev.maplesu.manager.workmode.detect.probes

import android.content.Context
import com.topjohnwu.superuser.Shell
import dev.maplesu.manager.installer.InstallerRootSourceRegistry
import dev.maplesu.manager.runtime.RootManagement
import dev.maplesu.manager.modules.UltraRootKeyStore
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.workmode.control.ControlPaths
import dev.maplesu.manager.workmode.detect.RootChannelKind
import dev.maplesu.manager.workmode.detect.RootEvidenceReader
import dev.maplesu.manager.workmode.detect.RootEvidenceSession

/**
 * root 取证适配器（一次 root 会话内批量只读取证）。
 *
 * 接线（W04b）：
 * - `LITE_DIRECT` → `RootManagement.openShell`；`LITE_ULTRA` → 已 Running 的 SDK 只读命令；
 * - `KSU_NATIVE` → `InstallerRootSourceRegistry` 的外来检测通道（未注册则 null）；
 *
 * 无会话/打开失败一律 null = "无通道"（UNKNOWN），**不是** not_installed；
 * 判定侧因此收敛到 UNBOUND / INDETERMINATE / NOT_INSTALLED 的现有规则，绝不伪造证据。
 *
 * 主机可测：[ShellRootEvidenceSession] 只依赖 [RootShellRunner]；适配器只依赖
 * [RootShellOpener]。真机装配见 [AndroidRootShellOpener]。
 */
class RootEvidenceReaderAdapter(
    private val opener: RootShellOpener,
) : RootEvidenceReader {
    override fun open(channel: RootChannelKind): RootEvidenceSession? =
        runCatching { opener.open(channel) }.getOrNull()?.let { ShellRootEvidenceSession(it) }
}

/** 一次 root shell 命令的结果（不依赖 libsu，便于主机 fake）。 */
data class RootCommandResult(val ok: Boolean, val out: List<String>)

/** root 命令执行器（真机走 libsu，测试注入替身）。 */
fun interface RootShellRunner {
    fun run(command: String): RootCommandResult
}

/** 已打开的 root 会话句柄。 */
interface RootShellSession : AutoCloseable {
    val runner: RootShellRunner
}

/** 按通道打开 root 会话；不可用返回 null（fail-closed）。 */
fun interface RootShellOpener {
    fun open(channel: RootChannelKind): RootShellSession?
}

/**
 * 把 [RootShellSession] 适配成控制面只读证据会话。
 *
 * 命令都是只读的 `test` / `cat` / `ls -A`，路径一律单引号转义；
 * runner 抛异常 → 该项返回 null（UNKNOWN），不是 false。
 */
class ShellRootEvidenceSession(
    private val session: RootShellSession,
) : RootEvidenceSession {
    override fun readControlFile(name: String): String? {
        val path = controlPath(name)
        val result = run("cat ${quote(path)}") ?: return null
        if (!result.ok) return null
        if (result.out.isEmpty()) return ""
        return result.out.joinToString("\n")
    }

    override fun isExecutable(path: String): Boolean? = test("-x", path)

    override fun isRealDirectory(path: String): Boolean? {
        val isDirectory = test("-d", path) ?: return null
        if (!isDirectory) return false
        val isLink = test("-L", path) ?: return null
        return !isLink
    }

    override fun hasAnyEntry(path: String): Boolean? {
        val isDirectory = isRealDirectory(path) ?: return null
        if (!isDirectory) return false
        val result = run("ls -A ${quote(path)}") ?: return null
        if (!result.ok) return null
        return result.out.any { it.isNotBlank() }
    }

    override fun exists(path: String): Boolean? = test("-e", path)

    override fun close() {
        runCatching { session.close() }
    }

    private fun test(flag: String, path: String): Boolean? =
        run("test $flag ${quote(path)}")?.ok

    private fun run(command: String): RootCommandResult? = try {
        session.runner.run(command)
    } catch (_: Throwable) {
        null
    }

    private fun controlPath(name: String): String = when {
        name.startsWith("/") -> name
        name.contains('/') -> "${ControlPaths.ROOT}/$name"
        else -> "${ControlPaths.CONTROL}/$name"
    }

    companion object {
        /** POSIX 单引号转义（路径不含换行；非法字符由调用方常量保证）。 */
        fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
    }
}

/**
 * 真机装配：Lite 走凭据 shell，Ultra 走真实 SDK；KSU 走 installer 注册的 Mode B 通道。
 * 任何失败（未接线的 JNI、未注册通道、主线程约束、无凭据）→ null。
 */
class AndroidRootShellOpener(
    private val context: Context,
) : RootShellOpener {
    override fun open(channel: RootChannelKind): RootShellSession? = when (channel) {
        RootChannelKind.LITE_DIRECT ->
            runCatching { RootManagement.openShell(context.applicationContext) }
                .getOrNull()?.let(::LibsuRootShellSession)
        RootChannelKind.LITE_ULTRA -> runCatching {
            val application = context.applicationContext
            val client = UltraRuntimeClient { UltraRootKeyStore.read(application) }
            UltraRootShellSession.open(
                running = { UltraEnvironmentProbe.currentSnapshot(application).rootAvailable },
                sdk = RootShellRunner { command ->
                    val receipt = client.runRootCommand(command)
                    val output = client.decodeText(receipt.string("result"))
                    RootCommandResult(receipt.succeeded && output != null, output?.lines().orEmpty())
                },
            )?.also { session ->
                // Reuse this already UID-verified batch, rather than queue a second SDK command.
                val observation = com.maplesu.core.ui.util.captureUltraSELinuxObservation()
                val status = runCatching { session.runner.run("/system/bin/getenforce") }.getOrNull()
                if (status?.ok == true) com.maplesu.core.ui.util.rememberUltraSELinuxStatus(
                    status.out.joinToString("\n").trim(), observation)
            }
        }.getOrNull()
        RootChannelKind.KSU_NATIVE ->
            runCatching { InstallerRootSourceRegistry.require().openShell(context.applicationContext) }
                .getOrNull()?.let(::LibsuRootShellSession)
    }
}

private class LibsuRootShellSession(private val shell: Shell) : RootShellSession {
    override val runner: RootShellRunner = RootShellRunner { command ->
        val result = shell.newJob().add(command).exec()
        RootCommandResult(result.isSuccess, result.out)
    }

    override fun close() {
        runCatching { shell.close() }
    }
}
