// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.modules

import com.maplesu.core.data.model.Module
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * 模块后端的**模式无关端口**（`ModuleOps`）。
 *
 * LITE 端口委托现有顶层实现（`LiteScriptModuleBackend` + `MapleSuModuleBackend` 合并逻辑，
 * 即 `com.maplesu.core.ui.util.listModules()` 等，行为逐字节不变）；
 * KSU 端口由集成轮从 `KsuBackends` 注入（`ModuleBackendPorts.ksuFactory`）。
 *
 * 本文件**不 import `dev.maplesu.manager.ksu`**（`KsuSourceIsolationTest.onlyFacadesMayImportKsuBackend`
 * 的白名单只允许门面文件），因此 KSU 端口的构造不上沉到 modules 层。
 */
interface ModuleOpsPort {
    fun isAvailable(): Boolean

    /** 与现有 UI 行同形的 JSON 数组文本。 */
    fun listJson(): String

    fun toggle(id: String, enable: Boolean): Boolean

    fun uninstall(id: String): Boolean

    fun undoUninstall(id: String): Boolean

    /** KSU：`ksud module install <zip>`；LITE 的安装走 `flashModule` 流水线，不在本端口内。 */
    fun install(zip: File): Boolean
}

/**
 * 路由结果：未判定/后端不可用必须**显式**返回，禁止回退 LITE（01 §1.2/§2.2、04 §6.1）。
 * 纯数据，无 Android 依赖 → 主机可测。
 */
sealed interface ModuleRouteResult<out T> {
    data class Ok<T>(val value: T) : ModuleRouteResult<T>

    /** `ModeRuntime.active == null`：不执行任何后端（返回空列表+明确错误由调用方完成）。 */
    data object Unresolved : ModuleRouteResult<Nothing>

    /** 当前模式的后端未装配/不可用（例如 KSU 端口未注入、ksud 缺失）。 */
    data class Unavailable(val reason: String) : ModuleRouteResult<Nothing>

    /** 后端抛异常：降级为失败，不得向上冒泡成崩溃。 */
    data class Failed(val error: Throwable) : ModuleRouteResult<Nothing>
}

/**
 * 模块列表 JSON → 领域模型的可注入解析器（W13）。
 *
 * 生产实现用 `org.json`（Android 运行时）；JVM 单测**不能**用 `org.json`（android.jar 是 stub，
 * 会抛 "Method … not mocked"），故由测试注入 fake parser。这样模式路由（本类）与
 * 解析实现解耦，仓库层无需在单测里加载 Android JSON。
 */
fun interface ModuleListParser {
    fun parse(json: String): List<Module>
}

/**
 * 模块后端模式路由器（唯一分派点）。
 *
 * 分支必须显式：`LITE ->` / `KSU ->` / `null ->`（禁止 `else ->` 兜底，04 §6.1）。
 * `mode()` 抛异常同样按"未判定"处理——**探测失败 ≠ LITE**。
 */
class ModuleBackendRouter(
    private val mode: () -> WorkMode? = { ModeRuntime.active },
    private val litePort: () -> ModuleOpsPort?,
    private val ksuPort: () -> ModuleOpsPort?,
) {
    fun listJson(): ModuleRouteResult<String> = dispatch { it.listJson() }

    fun toggle(id: String, enable: Boolean): ModuleRouteResult<Boolean> = dispatch { it.toggle(id, enable) }

    fun uninstall(id: String): ModuleRouteResult<Boolean> = dispatch { it.uninstall(id) }

    fun undoUninstall(id: String): ModuleRouteResult<Boolean> = dispatch { it.undoUninstall(id) }

    fun install(zip: File): ModuleRouteResult<Boolean> = dispatch { it.install(zip) }

    /** 当前路由到的模式；null = 未判定。UI/日志只读，不参与裁决。 */
    fun routedMode(): WorkMode? = runCatching { mode() }.getOrNull()

    private inline fun <T> dispatch(operation: (ModuleOpsPort) -> T): ModuleRouteResult<T> {
        val current = runCatching { mode() }.getOrNull()
        return when (current) {
            WorkMode.LITE -> runPort("lite", litePort, operation)
            null -> ModuleRouteResult.Unresolved
        }
    }

    private inline fun <T> runPort(
        tag: String,
        factory: () -> ModuleOpsPort?,
        operation: (ModuleOpsPort) -> T,
    ): ModuleRouteResult<T> {
        val port = runCatching { factory() }.getOrNull()
            ?: return ModuleRouteResult.Unavailable("$tag backend not wired")
        if (!runCatching { port.isAvailable() }.getOrDefault(false)) {
            return ModuleRouteResult.Unavailable("$tag backend unavailable")
        }
        return runCatching { ModuleRouteResult.Ok(operation(port)) }
            .getOrElse { error -> ModuleRouteResult.Failed(error) }
    }
}

/**
 * KSU 后端端口的延迟注入点（集成轮接线；modules 层不得直接 import src/ksu）。
 *
 * 接线方式（在 `KsuSourceIsolationTest` 白名单允许的门面文件里，例如
 * `dev/maplesu/manager/workmode/backend/KsuBackendRegistry.kt`）：
 * ```kotlin
 * ModuleBackendPorts.ksuFactory = {
 *     val command = KsuBackends.command(File(ksuApp.applicationInfo.nativeLibraryDir))
 *     object : ModuleOpsPort { ... 委托 KsuBackends.modules(command) ... }
 * }
 * ```
 * 未注入时 KSU 分支返回 [ModuleRouteResult.Unavailable]，**绝不回退 LITE**。
 */
object ModuleBackendPorts {
    @Volatile
    var ksuFactory: (() -> ModuleOpsPort?)? = null
        set(value) {
            field = value
            if (value != null) {
                wiringFailureCode = null
                wiredSignal.complete(true)
            }
        }

    /** T10：最近一次 KSU 接线失败码（null = 没有失败记录）。 */
    @Volatile
    var wiringFailureCode: String? = null
        private set

    /**
     * T10：接线完成（成功/失败）的**一次性**事件。
     *
     * 冷启动时模块列表可能早于接线被预取；订阅方挂起在这里，不轮询、不常驻线程。
     */
    private var wiredSignal = CompletableDeferred<Boolean>()

    /** T10：KSU 模块端口是否已经接线（UI 只读；不触发后端）。 */
    val isWired: Boolean get() = ksuFactory != null

    /** T10：一次性接线事件。true = 已接线；false = 接线失败（不会再有事件）。 */
    suspend fun awaitWired(): Boolean = if (isWired) true else wiredSignal.await()

    /**
     * 接线失败（由 `KsuBackendRegistry` 的失败路径调用）。
     *
     * 具名 code 保留给诊断：失败必须**可见**，不能让等待方一直停在"启动中"。
     */
    fun markWiringFailed(code: String) {
        wiringFailureCode = code
        wiredSignal.complete(false)
    }

    /** 测试用：恢复未注入状态。 */
    internal fun resetForTest() {
        ksuFactory = null
        wiringFailureCode = null
        wiredSignal = CompletableDeferred()
    }
}
