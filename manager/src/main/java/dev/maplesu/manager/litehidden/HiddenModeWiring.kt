package dev.maplesu.manager.litehidden

import android.content.Context
import android.util.Log
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode

/**
 * 启动接线：按 [ModeRuntime.active] 决定是否把生产后端装进 [HiddenModeBackends.current]。
 *
 * 两条铁律：
 * - **未就绪就保持 null**：模式未判定 → 不装（UI 得到 BACKEND_UNAVAILABLE，不假装可用）；
 * - **不支持就保持 null**：装配失败（缺 loader / 拿不到 APK 路径/版本号）→ 不装，并清空旧值。
 *
 * 模式在 `Application.onCreate` 之后才由 `ModeBootstrap` 异步判定，因此这里做**有界等待**
 * （守护线程，最多 10s），绝不在主线程等 root、也绝不默认 LITE。
 */
object HiddenModeWiring {

    private const val TAG = "MapleSUHidden"
    private const val WAIT_STEPS = 40
    private const val WAIT_MS = 250L

    @Volatile
    private var watcherStarted = false

    /** Application 启动时调用一次：已判定则立即装配，否则有界后台等待。 */
    fun installOnStartup(context: Context) {
        val app = context.applicationContext
        HiddenModeBackends.ensureProvider = {
            if (installNow(app)) HiddenModeBackends.current else null
        }
        if (ModeRuntime.active != null) {
            installNow(app)
            return
        }
        if (watcherStarted) return
        watcherStarted = true
        val worker = Thread {
            var attempt = 0
            var settled = false
            while (!settled && attempt < WAIT_STEPS) {
                if (ModeRuntime.active != null) {
                    installNow(app)
                    settled = true
                } else {
                    runCatching { Thread.sleep(WAIT_MS) }
                    attempt++
                }
            }
            if (!settled) {
                Log.i(TAG, "hidden backend unavailable: mode unresolved after ${WAIT_STEPS * WAIT_MS}ms")
            }
        }
        worker.name = "maplesu-hidden-wiring"
        worker.isDaemon = true
        worker.start()
    }

    /** 立即装配（模式已判定时）。返回是否真的装上了。 */
    fun installNow(context: Context): Boolean {
        val mode = ModeRuntime.active
        if (mode != WorkMode.LITE && true) {
            HiddenModeBackends.current = null
            return false
        }
        val backend = runCatching { ProductionHiddenEnv.create(context) }.getOrNull()
        if (backend == null) {
            HiddenModeBackends.current = null
            Log.w(TAG, "hidden backend unavailable mode=${mode.suffix}")
            return false
        }
        HiddenModeBackends.current = backend
        Log.i(TAG, "hidden backend installed mode=${mode.suffix}")
        return true
    }

    /** 模式切换/进程重启路径上的显式清理（fail-closed，不沿用旧后端）。 */
    fun uninstall() {
        HiddenModeBackends.current = null
    }

    internal fun resetForTest() {
        HiddenModeBackends.current = null
        HiddenModeBackends.ensureProvider = null
        watcherStarted = false
    }
}
