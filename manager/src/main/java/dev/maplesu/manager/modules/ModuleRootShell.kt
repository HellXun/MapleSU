package dev.maplesu.manager.modules

import android.content.Context
import com.topjohnwu.superuser.Shell
import dev.maplesu.manager.runtime.RootExec
import dev.maplesu.manager.runtime.RootExecResult
import dev.maplesu.manager.runtime.RootManagement
import dev.maplesu.manager.runtime.UltraRootCommandReceipt
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentAutoSetup
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate
import dev.maplesu.manager.workmode.ui.WorkModeSwitchSession

/** 模块脚本的一次执行结果（与 libsu 解耦；host 可注 fake）。 */
data class ModuleScriptOutput(
    val code: Int,
    val out: String = "",
    val err: String = "",
) {
    val isSuccess: Boolean get() = code == 0
}

/** 模块脚本执行通道：Lite 下经 RootExecutionRouter（Ultra run_root_cmd 优先，Lite 凭据兜底）。 */
fun interface ModuleScriptRunner {
    fun run(script: String): ModuleScriptOutput

    /** Generated read-only inventories need no streaming service. Other runners retain their path. */
    fun readSnapshot(script: String): ModuleScriptOutput = run(script)
}

/**
 * 生产实现：`RootExec.execute(script)`。
 * NONE/失败 → 非 0 code + detail（fail-closed，绝不假装脚本成功）。
 */
object RootExecModuleScriptRunner : ModuleScriptRunner {
    override fun run(script: String): ModuleScriptOutput = output(RootExec.execute(script))

    /** Cold UI/worker processes must not depend on Application router installation order. */
    fun run(context: Context, script: String): ModuleScriptOutput = output(RootExec.execute(context, script))

    /** Policy operations belong to this Hook session, never a fallback KSU/SDK backend. */
    fun runHook(context: Context, script: String): ModuleScriptOutput = output(RootExec.execute(context, script))

    private fun output(result: RootExecResult): ModuleScriptOutput {
        return if (result.ok) {
            ModuleScriptOutput(0, result.output, result.detail)
        } else {
            ModuleScriptOutput(
                code = if (result.code != 0) result.code else -1,
                out = result.output,
                err = result.detail.ifEmpty { "ROOT_BACKEND_UNAVAILABLE" },
            )
        }
    }
}

/** Mutations use the authenticated streaming shell; bounded snapshots use one exclusive SDK request. */
internal class ManagementModuleScriptRunner(context: Context) : ModuleScriptRunner {
    private val app = context.applicationContext
    private var shell: Shell? = null
    private var key: String? = null
    private var boot: String? = null

    @Synchronized
    override fun run(script: String): ModuleScriptOutput {
        if (isQuiescing()) {
            closeShell()
            return ModuleScriptOutput(-1, err = "MODULE_ROOT_QUIESCING")
        }
        if (!UltraEnvironmentProbe.usesUltraBackend(app)) {
            closeShell()
            return RootExecModuleScriptRunner.run(app, script)
        }
        return try {
            val nextKey = UltraSdkCredentials.read(app)
            val nextBoot = UltraEnvironmentAutoSetup.currentBootId()
            if (key != nextKey || boot != nextBoot || shell?.isAlive != true) {
                closeShell()
                shell = RootManagement.openShell(app)
                key = nextKey
                boot = nextBoot
            }
            // Each command gets its own shell flags and cwd. A failed `set -e`
            // script must not kill the transport before libsu receives its exit code.
            val command = "/system/bin/sh -c '" + script.replace("'", "'\\''") + "'"
            val result = checkNotNull(shell).newJob().add(command)
                .to(ArrayList<String>(), ArrayList<String>()).exec()
            ModuleScriptOutput(result.code, result.out.joinToString("\n"), result.err.joinToString("\n"))
        } catch (error: Exception) {
            closeShell()
            if (error is InterruptedException) Thread.currentThread().interrupt()
            // A submitted mutation is never replayed through another transport.
            ModuleScriptOutput(-1, err = "MODULE_ROOT_TRANSPORT:${error.javaClass.simpleName}")
        }
    }

    override fun readSnapshot(script: String): ModuleScriptOutput {
        // Hook and SDK snapshots do not use the streaming shell. A running
        // service/action must not hold inventory behind its command monitor.
        if (isQuiescing()) return ModuleScriptOutput(-1, err = "MODULE_ROOT_QUIESCING")
        if (false) return run(script)
        if (!UltraEnvironmentProbe.usesUltraBackend(app)) return RootExecModuleScriptRunner.run(app, script)
        return try {
            // A bounded inventory needs only one authenticated SDK request, not a cold
            // streaming service. SDK write admission and the shell exit receipt remain.
            val client = UltraSdkCredentials.client(app)
            val frame = UltraRootCommandReceipt(script)
            val receipt = client.runRootCommand(frame.command)
            if (!receipt.succeeded) {
                ModuleScriptOutput(-1, err = "MODULE_SNAPSHOT_SDK:${receipt.sdkError}")
            } else {
                val result = frame.decode(client.decodeText(receipt.string("result")).orEmpty())
                ModuleScriptOutput(result.code, result.output, result.detail)
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            ModuleScriptOutput(-1, err = "MODULE_ROOT_TRANSPORT:${error.javaClass.simpleName}")
        }
    }

    private fun isQuiescing() = LiteImplantQuiesceGate.isBlocked() ||
        WorkModeSwitchSession.executing

    private fun closeShell() {
        runCatching { shell?.close() }
        shell = null
        key = null
        boot = null
    }
}

/**
 * `LiteScriptModuleBackend` 的 root shell 抽象（替代 libsu `Shell`）：
 * 只实现其现有调用点用到的 `newJob().add(...).to(out, err).exec()` 形态。
 */
class ModuleRootShell(
    private val runner: ModuleScriptRunner = RootExecModuleScriptRunner,
) : AutoCloseable {
    fun newJob(): ModuleRootJob = ModuleRootJob(runner)

    override fun close() = Unit
}

class ModuleRootJob internal constructor(
    private val runner: ModuleScriptRunner,
) {
    private val commands = mutableListOf<String>()
    private var outSink: MutableList<String>? = null
    private var errSink: MutableList<String>? = null

    fun add(command: String): ModuleRootJob {
        commands += command
        return this
    }

    /** 与 libsu 的 `to(out, err)` 保持同形；exec 时把行写入调用方列表。 */
    fun to(out: MutableList<String>, err: MutableList<String>): ModuleRootJob {
        outSink = out
        errSink = err
        return this
    }

    fun exec(): ModuleShellResult {
        if (commands.isEmpty()) return ModuleShellResult(true, 0, emptyList(), emptyList())
        val script = commands.joinToString("\n")
        val output = runner.run(script)
        fun lines(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            val result = text.lineSequence().toList()
            return if (text.last() == '\n' || text.last() == '\r') result.dropLast(1) else result
        }
        val outLines = lines(output.out)
        val errLines = lines(output.err)
        outSink?.addAll(outLines)
        errSink?.addAll(errLines)
        return ModuleShellResult(
            isSuccess = output.isSuccess,
            code = output.code,
            out = outLines,
            err = if (errLines.isEmpty() && !output.isSuccess) outLines else errLines,
        )
    }
}

data class ModuleShellResult(
    val isSuccess: Boolean,
    val code: Int,
    val out: List<String>,
    val err: List<String>,
)

/** 安装/执行通道（安装前置检查、脚本执行、工具部署共用同一 router 通道）。 */
class LiteScriptModuleRootChannel(
    private val runner: ModuleScriptRunner = RootExecModuleScriptRunner,
) {
    fun syntaxCheck(scriptPath: String): ModuleShellResult =
        ModuleRootShell(runner).newJob()
            .add("/system/bin/sh -n '$scriptPath'")
            .to(ArrayList(), ArrayList())
            .exec()

    fun runScript(script: String): ModuleShellResult =
        ModuleRootShell(runner).newJob().add(script).to(ArrayList(), ArrayList()).exec()

    fun runCommand(command: String): ModuleShellResult =
        ModuleRootShell(runner).newJob().add(command).to(ArrayList(), ArrayList()).exec()
}
