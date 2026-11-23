package dev.maplesu.manager.modules

import android.app.Activity
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.maplesu.core.R
import dev.maplesu.manager.runtime.RootExec
import dev.maplesu.manager.runtime.PageReadDeadline
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns boot dispatch and cancellable foreground module operations. */
class LiteModuleBootService : Service() {
    // Preserve dispatch order; KSU service and boot-completed scripts run asynchronously.
    private val workers = Executors.newSingleThreadExecutor()
    private val started = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private var dispatchToken: Long? = null
    @Volatile private var backend: LiteScriptModuleBackend? = null
    private val tasks = mutableListOf<LiteScriptModuleBackend.BootTask>()
    private val deadline = ModuleBootDeadline(finish = { stage ->
        if (stage != null) {
            destroyed.set(true)
            workers.shutdownNow()
            Log.w("MapleSUModules", "Late module worker timed out: $stage")
        }
        // Remove the notification even if an interrupted native launch has not unwound yet.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    })

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The foreground deadline starts at startForegroundService(), even if mode
        // resolution rejects the work. Do no catalog I/O or root work before this.
        promoteToForeground()
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.module_worker_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo_vector)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.module_worker_running))
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started.get()) dispatchToken = intent?.getLongExtra(DISPATCH_TOKEN, 0L)?.takeIf { it != 0L }
        // 模式门（2026-11-01）：Lite 脚本模块的 late-boot 执行**只属于 LITE**。
        // Normal（KSU）下模块由 ksud 在内核侧 mount，这套前台服务根本不适用；
        // 以前这里只判断 MapleSuRuntime，于是 Normal 下也可能被 BOOT_COMPLETED 拉起来，
        // 和 ksud 的 post-fs-data/service 双跑。未判定同样不执行（fail-closed）。
        if (!isLiteMode()) {
            Log.i("MapleSUModules", "lite module worker skipped: active mode is not LITE")
            stopSelf()
            return START_NOT_STICKY
        }
        if (started.compareAndSet(false, true)) workers.execute {
            var preparationCompleted = false
            var retryPreparation = false
            try {
                val prepared = deadline.step("prepare", 30_000L) {
                    runBlocking(PageReadDeadline.context(30_000L)) {
                        val root = RootExec.execute(this@LiteModuleBootService, "/system/bin/id -u")
                        check(root.ok && root.output.trim() == "0") {
                            "MODULE_ROOT_PROBE_FAILED:${root.backend}/${root.code}:${root.detail}"
                        }
                        check(!destroyed.get()) { "MODULE_BOOT_CANCELLED" }
                        UltraModulePolicyRepository.prepareHook(this@LiteModuleBootService)
                        val workerBackend = LiteScriptModuleBackend.get(this@LiteModuleBootService)
                        backend = workerBackend
                        workerBackend.prepareBootTasks().also { synchronized(tasks) { tasks.addAll(it) } }
                    }
                }
                preparationCompleted = true
                val workerBackend = checkNotNull(backend)
                for (task in prepared) {
                    if (destroyed.get() || Thread.currentThread().isInterrupted) break
                    try {
                        val executeTask = {
                            workerBackend.runBootTask(task,
                                { Log.i("MapleSUModules", "${task.id}/${task.stage}: $it") },
                                { Log.w("MapleSUModules", "${task.id}/${task.stage}: $it") })
                        }
                        // KSU waits for uninstall/metamodule mount completion. Boot
                        // scripts enforce Until/NoWait inside their detached launcher.
                        if (task.stage in setOf("uninstall", "mount")) executeTask()
                        else deadline.step("${task.id}/${task.stage}", 150_000L, executeTask)
                    } catch (error: Exception) {
                        Log.w("MapleSUModules", "Late module task failed", error)
                        if (destroyed.get() || Thread.currentThread().isInterrupted) break
                        runCatching { workerBackend.abandonBootTask(task) }
                    }
                }
            } catch (error: Exception) {
                retryPreparation = !preparationCompleted && synchronized(tasks) { tasks.isEmpty() } &&
                    !destroyed.get() && !Thread.currentThread().isInterrupted &&
                    error !is CancellationException && backend?.hasScheduledBootTasks() != true
                Log.w("MapleSUModules", "Late module dispatch failed", error)
            } finally {
                startup.complete(dispatchToken, retryPreparation, isLiteMode())
                deadline.close()
                // A native prepare can return after onDestroy's first cleanup snapshot.
                if (destroyed.get() || Thread.currentThread().isInterrupted) {
                    val pending = synchronized(tasks) { tasks.toList() }
                    pending.forEach { task -> runCatching { backend?.abandonBootTask(task) } }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun isLiteMode(): Boolean = allowsRun(ModeRuntime.active)

    override fun onDestroy() {
        destroyed.set(true)
        startup.complete(dispatchToken, false, isLiteMode())
        deadline.close()
        // A worker may hold the module lock during root IPC. Never wait for it on Main.
        workers.shutdownNow()
        Thread({
            backend?.let { workerBackend ->
                runCatching { workerBackend.cancelBootTasks() }
                val pending = synchronized(tasks) { tasks.toList() }
                pending.forEach { runCatching { workerBackend.abandonBootTask(it) } }
            }
        }, "module-worker-cleanup").start()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "maplesu_module_tasks"
        private const val NOTIFICATION = 24025
        private const val DISPATCH_TOKEN = "dev.maplesu.manager.modules.BOOT_DISPATCH_TOKEN"
        private val startup = LiteModuleBootDispatch()
        private var foregroundReplayRegistered = false

        /**
         * Lite 模块服务的**唯一模式判据**：只有 LITE 才允许执行；Normal（KSU）与未判定
         * 都返回 false（绝不"猜一个模式"再跑）。纯逻辑在 [LiteModuleModePolicy]（主机可测）。
         */
        fun allowsRun(mode: WorkMode?): Boolean = LiteModuleModePolicy.allowsRun(mode)

        fun request(context: Context) {
            val application = context.applicationContext
            if (allowsRun(ModeRuntime.active)) registerForegroundReplay(application as Application)
            dispatchRequest(application, fromForeground = false)
        }

        @Synchronized
        private fun registerForegroundReplay(application: Application) {
            if (foregroundReplayRegistered) return
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPostResumed(activity: Activity) {
                    runCatching { dispatchRequest(application, fromForeground = true) }
                        .onFailure { Log.w("MapleSUModules", "Late module foreground dispatch failed", it) }
                }

                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
            foregroundReplayRegistered = true
        }

        private fun dispatchRequest(context: Context, fromForeground: Boolean) {
            // 请求侧先拦一次：Normal 下连前台服务都不该起来（onStartCommand 里还有一道门，
            // 两道都保留：request 可能来自别处，进入服务后模式也可能已判定完成）。
            val mode = ModeRuntime.active
            val token = startup.begin(allowsRun(mode), fromForeground)
            if (token == null) {
                if (!allowsRun(mode)) {
                    Log.i("MapleSUModules", "lite module worker not requested: mode=$mode")
                }
                return
            }
            var deferred = false
            var submitted = false
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, LiteModuleBootService::class.java).putExtra(DISPATCH_TOKEN, token))
                submitted = true
            } catch (error: ForegroundServiceStartNotAllowedException) {
                deferred = true
                Log.w("MapleSUModules", "Late module startup waiting for Manager foreground", error)
            } finally {
                startup.finish(token, submitted, deferred, allowsRun(ModeRuntime.active))
            }
        }
    }
}
