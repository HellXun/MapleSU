package dev.maplesu.manager.runtime

import android.content.Context
import android.os.Looper
import com.topjohnwu.superuser.Shell
import dev.maplesu.manager.installer.InstallerRootSourceRegistry
import dev.maplesu.manager.modules.ModuleToolDeployment
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials
import dev.maplesu.manager.runtime.ultra.bridge.NativeBridge
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode

/**
 * Manager-side privileged operations.
 *
 * Paired SDK environments use the existing streaming root task, released when the
 * shell closes. Environments without a paired SDK read a credential.
 */
object RootManagement {
    fun start(context: Context, argv: List<String>, globalMnt: Boolean = false): Process {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MANAGEMENT_BACKGROUND_THREAD_REQUIRED" }
        return startRouted(
            mode = ModeRuntime.active,
            usesSdk = UltraEnvironmentProbe.usesUltraBackend(context),
            openSdk = { SdkRootShellClient.start(context, argv, globalMnt) },
            openCredential = { startCredential(context, argv, globalMnt) },
        )
    }

    /** Module facts are selected before RootService loses the requesting app identity. */
    internal fun startModule(context: Context, argv: List<String>): Process {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MANAGEMENT_BACKGROUND_THREAD_REQUIRED" }
        if (UltraEnvironmentProbe.usesUltraBackend(context)) {
            val environment = NativeBridge.captureModuleEnvironment(UltraSdkCredentials.read(context))
            return SdkRootShellClient.startModule(context, argv, environment)
        }
        return startCredential(context, argv, false, ModuleTaskEnvironment.captureNonSdk(hook = false))
    }

    /** Streaming module tasks use the same verified root source as management shells. */
    internal fun startRouted(
        mode: WorkMode?,
        usesSdk: Boolean,
        openSdk: () -> Process,
        openCredential: () -> Process,
    ): Process = if (usesSdk) openSdk() else openCredential()

    private fun startCredential(context: Context, argv: List<String>, globalMnt: Boolean,
        moduleEnvironment: Array<String>? = null): Process {
        val credential = MapleSuCredentialStore(context).load()
            ?: throw IllegalStateException("MANAGEMENT_CREDENTIAL_UNAVAILABLE")
        try {
            val executor = MapleSuCommandExecutor(context)
            return if (moduleEnvironment == null) executor.start(credential, argv, globalMnt = globalMnt)
                else executor.startModule(credential, argv, moduleEnvironment)
        } finally {
            // start() writes the frame synchronously, so the buffer is spent here.
            credential.fill(0)
        }
    }

    fun openShell(context: Context, globalMnt: Boolean = false): Shell {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MANAGEMENT_BACKGROUND_THREAD_REQUIRED" }
        return openShellRouted(
            mode = ModeRuntime.active,
            usesSdk = UltraEnvironmentProbe.usesUltraBackend(context),
            openSdk = { openProcessShell(context,
                SdkRootShellClient.start(context, listOf("/system/bin/sh"), globalMnt), globalMnt) },
            openCredential = { openCredentialShell(context, globalMnt) },
        )
    }

    /**
     * Root-channel routing (host-testable; no Android calls here).
     *
     * SDK failures propagate directly: the SDK identity is not a legacy credential, and a
     * submitted task is never replayed.
     */
    internal fun openShellRouted(
        mode: WorkMode?,
        usesSdk: Boolean,
        openSdk: () -> Shell,
        openCredential: () -> Shell,
    ): Shell = if (usesSdk) openSdk() else openCredential()

    /** Direct credential transport for environments without SDK pairing. */
    private fun openCredentialShell(context: Context, globalMnt: Boolean): Shell {
        return openProcessShell(context, start(context, listOf("/system/bin/sh"), globalMnt), globalMnt)
    }

    private fun openProcessShell(context: Context, process: Process, globalMnt: Boolean): Shell {
        var shell: Shell? = null
        try {
            shell = Shell.Builder.create().setContext(context.applicationContext).setTimeout(10).build(process)
            check(shell.isRoot && shell.isAlive) { "MANAGEMENT_SHELL_NOT_READY" }
            prepareShell(shell, globalMnt)
            return shell
        } catch (error: Exception) {
            runCatching { shell?.close() }
            runCatching { process.destroyForcibly() }
            listOf(process.outputStream, process.inputStream, process.errorStream).forEach { runCatching { it.close() } }
            throw error
        }
    }

    /** Post-open contract shared by all transports (PATH + global-mount validation). */
    private fun prepareShell(shell: Shell, globalMnt: Boolean) {
        // Mirror KSU `su.rs:318-319` (add_path_to_env(BINARY_DIR)): append the
        // module tool directory so module-facing commands such as `resetprop`
        // resolve MapleSU's own copy. Appended, not prepended, so platform
        // binaries keep priority.
        check(shell.newJob().add(
            "export PATH=\"\$PATH:${ModuleToolDeployment.BINARY_DIR}\"").exec().isSuccess) {
            "MANAGEMENT_SHELL_PATH_FAILED"
        }
        if (globalMnt) {
            val result = shell.newJob().add("readlink /proc/self/ns/mnt", "readlink /proc/1/ns/mnt")
                .to(ArrayList<String>(), null).exec()
            check(result.isSuccess && result.out.size == 2 && result.out[0] == result.out[1]) {
                "MANAGEMENT_GLOBAL_NAMESPACE_INVALID"
            }
        }
    }
}
