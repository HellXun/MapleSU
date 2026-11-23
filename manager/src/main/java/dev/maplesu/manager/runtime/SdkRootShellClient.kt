package dev.maplesu.manager.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Reuses the official libsu streaming task through the selected root backend. */
internal object SdkRootShellClient {
    fun startModule(context: Context, argv: List<String>, environment: Array<String>,
        backend: RootBackend = RootBackend.ULTRA_ENV): Process =
        startInternal(context, argv, false, backend, environment)

    fun start(context: Context, argv: List<String>, globalMnt: Boolean,
        backend: RootBackend = RootBackend.ULTRA_ENV): Process =
        startInternal(context, argv, globalMnt, backend, null)

    private fun startInternal(context: Context, argv: List<String>, globalMnt: Boolean,
        backend: RootBackend, moduleEnvironment: Array<String>?): Process {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MANAGEMENT_BACKGROUND_THREAD_REQUIRED" }
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val connected = CompletableFuture<IBinder>()
        val pending = CompletableFuture<Shell.Task?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(binder) }
            override fun onServiceDisconnected(name: ComponentName) {
                connected.completeExceptionally(IllegalStateException("MANAGEMENT_SERVICE_DISCONNECTED"))
            }
            override fun onNullBinding(name: ComponentName) {
                connected.completeExceptionally(IllegalStateException("MANAGEMENT_SERVICE_NULL_BINDING"))
            }
            override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
        }
        val unbind = { main.post { RootService.unbind(connection) }; Unit }
        try {
            main.post {
                try {
                    pending.complete(RootService.bindOrTask(Intent(app, SdkRootShellService::class.java),
                        app.mainExecutor, connection))
                } catch (error: Exception) { pending.completeExceptionally(error) }
            }
            pending.get(5, TimeUnit.SECONDS)?.let { launch ->
                val script = ByteArrayOutputStream()
                launch.run(script, ByteArrayInputStream(ByteArray(0)), ByteArrayInputStream(ByteArray(0)))
                val result = RootExec.executeServiceLaunch(app, script.toString(Charsets.UTF_8.name()))
                check(result.ok && result.backend == backend) {
                    when (backend) {
                        RootBackend.KSU -> "MANAGEMENT_KSU_LAUNCH_FAILED:${result.detail}"
                        else -> "MANAGEMENT_SDK_LAUNCH_FAILED"
                    }
                }
            }
            val service = IRootShellService.Stub.asInterface(connected.get(20, TimeUnit.SECONDS))
            return RootManagementProcess.start({ owner ->
                if (moduleEnvironment == null) service.start(argv.toTypedArray(), owner, globalMnt)
                else service.startModule(argv.toTypedArray(), owner, moduleEnvironment)
            }, unbind)
        } catch (error: Exception) {
            unbind()
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }
    }
}
