package dev.maplesu.manager.workmode.service

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 一次根脚本执行结果（与具体后端解耦，主机可 fake）。 */
data class SelinuxShellResult(
    val ok: Boolean,
    val output: String = "",
    val code: Int = 0,
    val detail: String = "",
)

/**
 * 「执行一段根脚本」的统一通道（P0-1 第 1 项）。
 *
 * 生产接线全部复用**既有**接口，不新增根抽象：
 * - Ultra/LITE：`RootExec.execute(script)`（`RootExecutionRouter.kt:285-289`；LITE 下 Ultra
 *   `run_root_cmd` 优先 `:147-154`，Lite 内核凭据兜底）；
 * - Normal/KSU：libsu root shell（`KsuInstallGlue.kt:206-229`：`ksud debug su` →
 *   `su -mm` → `su`，并要求 `isRoot`）。
 *
 * 注意 source-set 边界：本文件属 `src/main/java`，**不得** import
 * `dev.maplesu.manager.ksu.*`（`KsuSourceIsolationTest.onlyFacadesMayImportKsuBackend`）。
 * 因此"把两个通道接到生产实现"的装配放在白名单文件
 * [dev.maplesu.manager.workmode.service.ModeSwitchRuntime]（`productionSelinuxRestorer`），
 * 本文件只保留与 KSU 无关的抽象、实现与超时看护。
 */
fun interface SelinuxRootChannel {
    fun run(script: String): SelinuxShellResult

    companion object {
        /** 未接线通道：永远失败（绝不伪造成功）。 */
        val UNWIRED: SelinuxRootChannel = SelinuxRootChannel {
            SelinuxShellResult(false, "", -1, "SELINUX_CHANNEL_UNWIRED")
        }
    }
}

/**
 * SELinux 自动恢复结果（**真实状态**，绝不伪造）。
 *
 * @property status 恢复后的真实状态；只可能 `Enforcing`/`Permissive`/`Disabled`/`Unknown`/
 *   `""`（空 = 未接线）。
 * @property attempted 是否真的执行过根命令（`false` = 入口已是 Enforcing → 零副作用）。
 * @property rootAlive 与恢复**同一根通道**的活性探针（`id -u` == 0）；未执行根命令时无意义。
 * @property detail 诊断串（不含任何凭据/token）。
 */
data class SelinuxRestoreResult(
    val status: String,
    val attempted: Boolean = false,
    val rootAlive: Boolean = false,
    val detail: String = "",
) {
    val enforcing: Boolean get() = status == ENFORCING

    companion object {
        const val ENFORCING = "Enforcing"
        const val PERMISSIVE = "Permissive"
        const val DISABLED = "Disabled"
        const val UNKNOWN = "Unknown"

        /**
         * 未接线（空状态）哨兵。**消费者必须区分它与"恢复失败"**（M-2）：
         * - 本机已 `Enforcing` → 未接线等价于"不需要修"，继续；
         * - 本机非 `Enforcing` → 显式 `Failed(INSTALLER_SELINUX_NOT_ENFORCING)`，不静默继续。
         *
         * 见 `ModeSwitchFacade.startSwitch` 的 UNWIRED 分支。
         */
        val UNWIRED = SelinuxRestoreResult("")
    }
}

/**
 * 用户点一次切换时的 SELinux 自动恢复。
 *
 * **只允许在 `ModeSwitchFacade.startSwitch` 调用**：`canSwitch`/`deviceFacts()` 会在
 * `WorkModeViewModel` 的 `init`/每次刷新里跑（`WorkModeViewModel.kt:40-42,101`），在那里写
 * 系统状态等于「每次打开设置页都 setenforce」，违反 HANDOFF §4 P0-1 的明令。
 */
fun interface SelinuxRestorer {
    /** 立即恢复（调用方必须已切到后台线程）；不抛，失败返回真实状态。 */
    fun ensureEnforcing(): SelinuxRestoreResult

    companion object {
        /** 未接线：不恢复、也不新增阻断（= 今天的行为）。 */
        val UNWIRED: SelinuxRestorer = SelinuxRestorer { SelinuxRestoreResult.UNWIRED }
    }
}

/**
 * 生产实现：`setenforce 1`（失败兜底写 `/sys/fs/selinux/enforce`）→ **根通道回读**确认
 * → 同通道 `id -u` 活性探针。
 *
 * 硬约束：
 * 1. 已 `Enforcing` 就**不动手**（零根调用、零副作用，避免每次切换都 setenforce）；
 * 2. 回读**不能**用 `peekCachedSELinuxStatus()`（5 秒 TTL 会读到旧值，`SELinuxChecker.kt:51-60`），
 *    必须走根通道；
 * 3. 只有回读命中 `Enforcing`/`1` 才算成功；`Permissive`/`Disabled`/`Unknown`/空 一律失败；
 * 4. 脚本单行、`</dev/null`、stderr 全丢、stdout 极短（HANDOFF §6 根命令铁律）。
 */
class AndroidSelinuxRestorer(
    /** Ultra/LITE 通道（生产 = `RootExec.execute`）。 */
    private val ultra: SelinuxRootChannel = SelinuxRootChannel.UNWIRED,
    /** App 域只读探测（同一份五级探测；已 Enforcing 时用来跳过根动作）。 */
    private val readLocal: () -> String = { "" },
) : SelinuxRestorer {

    override fun ensureEnforcing(): SelinuxRestoreResult {
        // ① 已经 Enforcing：零根调用、零副作用。
        val local = runCatching { readLocal() }.getOrDefault("").trim()
        if (local == SelinuxRestoreResult.ENFORCING) {
            return SelinuxRestoreResult(SelinuxRestoreResult.ENFORCING)
        }
        // ② 只走 Ultra 通道（Normal/libsu 已随 KSU 后端删除）；成功者负责回读确认 + 活性探针。
        for (channel in listOf(ultra)) {
            val result = runCatching { channel.run(ENFORCE_SCRIPT) }.getOrElse { error ->
                SelinuxShellResult(false, "", -1, error.javaClass.simpleName)
            }
            if (!isEnforcing(result.output)) continue
            val alive = runCatching { channel.run(LIVENESS_SCRIPT) }.getOrElse { error ->
                SelinuxShellResult(false, "", -1, error.javaClass.simpleName)
            }.let { probe -> probe.ok && isRootId(probe.output) }
            return SelinuxRestoreResult(
                status = SelinuxRestoreResult.ENFORCING,
                attempted = true,
                rootAlive = alive,
                detail = if (alive) "" else "id -u != 0 after setenforce",
            )
        }
        // ③ 所有通道都没能确认 Enforcing：如实返回真实状态（可能是 Permissive/Disabled/Unknown）。
        val after = runCatching { readLocal() }.getOrDefault("").trim()
            .ifEmpty { SelinuxRestoreResult.UNKNOWN }
        return SelinuxRestoreResult(
            status = after,
            attempted = true,
            detail = "setenforce not confirmed",
        )
    }

    companion object {
        /**
         * 单行、`</dev/null`、stderr 全丢：先 `setenforce 1`，失败兜底写 enforce 文件，再回读。
         * 回读优先 `getenforce`（toybox 输出 `Enforcing`），退化 `cat enforce`（输出 `1`）。
         */
        const val ENFORCE_SCRIPT: String =
            "( setenforce 1 >/dev/null 2>&1 || echo 1 > /sys/fs/selinux/enforce 2>/dev/null; " +
                "getenforce 2>/dev/null || cat /sys/fs/selinux/enforce 2>/dev/null ) </dev/null"

        /** 活性探针：与恢复**同一根通道**，期望输出 `0`。 */
        const val LIVENESS_SCRIPT: String = "id -u </dev/null 2>/dev/null"

        /** 严格解析回读：只认 `Enforcing` 或 enforce 文件的 `1`；空输出绝不算成功。 */
        fun isEnforcing(output: String): Boolean {
            val last = output.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: return false
            return last.equals(SelinuxRestoreResult.ENFORCING, ignoreCase = true) || last == "1"
        }

        /** 活性探针解析：最后一个非空行必须是 `0`。 */
        fun isRootId(output: String): Boolean =
            output.lineSequence().lastOrNull { it.isNotBlank() }?.trim() == "0"
    }
}

/**
 * 硬超时（M-3）：把一次根调用**真的**限制在 [timeoutMs] 内。
 *
 * 为什么需要：`KsuInstallGlue.kt:204` 的 KDoc 自认"libsu 不提供单条命令超时，
 * `timeoutMs` 由调用方的进程级看护保证"，而 `KsuInstallGlue.kt:207-228` 的函数体
 * **从不引用** `timeoutMs`（死参数）。那个文件不在 wave4 允许改动范围内，所以在恢复器这一侧
 * 自己看护：调用丢到 daemon 线程，最多等 [timeoutMs]，超时立刻返回失败（**绝不伪造成功、
 * 也绝不让 `startSwitch` 永久挂在 RUNNING 状态**）。被放弃的线程是 daemon，
 * 不会阻塞进程退出；libsu 调用若不可中断，其结果被丢弃。
 */
internal fun runWithTimeout(timeoutMs: Long, block: () -> SelinuxShellResult): SelinuxShellResult {
    val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "maplesu-selinux-restore").apply { isDaemon = true }
    }
    return try {
        val future = executor.submit(Callable { block() })
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            SelinuxShellResult(false, "", -1, TIMEOUT_DETAIL)
        }
    } catch (error: Throwable) {
        SelinuxShellResult(false, "", -1, error.javaClass.simpleName)
    } finally {
        executor.shutdownNow()
    }
}

/** 超时诊断串（`detail` 会被 facade 拼进用户可见失败说明，不含任何凭据）。 */
internal const val TIMEOUT_DETAIL = "SELINUX_RESTORE_TIMEOUT"

/**
 * 根脚本硬上限。
 *
 * 与 03 报告一致：15s 内必须返回，绝不长时间占用设备。**真正生效**靠 [runWithTimeout]
 * （libsu 自己的 `timeoutMs` 参数没有任何实现，见 M-3）。
 *
 * `internal`（而非 private）：生产装配在 `ModeSwitchRuntime.kt` 的
 * `productionSelinuxRestorer`，同一 source set 内共用这个上限。
 */
internal const val NORMAL_TIMEOUT_MS = 15_000L
