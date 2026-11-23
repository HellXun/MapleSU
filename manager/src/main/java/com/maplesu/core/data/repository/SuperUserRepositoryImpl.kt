package com.maplesu.core.data.repository

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.maplesu.core.BuildConfig
import com.maplesu.core.IKsuInterface
import com.maplesu.core.Natives
import com.maplesu.core.data.model.AppInfo
import com.maplesu.core.data.model.WEBVIEW_ZYGOTE_PROFILE_KEY
import com.maplesu.core.data.model.WEBVIEW_ZYGOTE_UID
import com.maplesu.core.ksuApp
import com.maplesu.core.ui.KsuService
import com.maplesu.core.ui.util.KsuCli
import dev.maplesu.manager.runtime.readResult
import dev.maplesu.manager.runtime.authorization.AndroidApplicationIdentities
import dev.maplesu.manager.runtime.authorization.SuperuserControl
import dev.maplesu.manager.runtime.ultra.UltraAuthorization
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.boot.ModeBootstrap
import kotlin.coroutines.resume

class SuperUserRepositoryImpl : SuperUserRepository {

    companion object {
        private const val TAG = "SuperUserRepository"
        private val appReads = dev.maplesu.manager.runtime.BoundedPageRead<Result<Pair<List<AppInfo>, List<Int>>>>()
        private val profileReads = dev.maplesu.manager.runtime.BoundedPageRead<Result<List<AppInfo>>>()
    }

    /**
     * 超管数据源按模式显式分派（主文档 §3.3；W14）：
     * - LITE → 原实现（Ultra 白名单 / KSU-lite binder / DirectPolicy 路径）逐字节不变；
     * - KSU → 只读 KSU 后端（`Natives.getAppProfile` 为 KSU JNI 分派），**不碰任何 Lite runtime**；
     * - 未判定 → 显式失败（只读空态+原因），绝不发起 root、绝不回退 Lite。
     */
    override suspend fun getAppList(): Result<Pair<List<AppInfo>, List<Int>>> {
        val requestedMode = ModeRuntime.active
        return appReads.readResult(requestedMode) {
            check(ModeRuntime.active == requestedMode) { "PAGE_QUERY_CONTEXT_CHANGED" }
            val result = withContext(Dispatchers.IO) { readAppList() }
            check(ModeRuntime.active == requestedMode) { "PAGE_QUERY_CONTEXT_CHANGED" }
            result
        }
    }

    private suspend fun readAppList(): Result<Pair<List<AppInfo>, List<Int>>> =
        when (ModeRuntime.active) {
            WorkMode.LITE -> loadLiteAppList()
            null -> {
                ModeBootstrap.detectAfterRootGranted(ksuApp, delayMillis = 0L)
                Result.failure(SuperUserModeUnresolvedException())
            }
        }

    /** 已验证身份只选择只读后端；授权状态仍必须由本次 SDK 列表回执确认。 */
    private fun usesReadyUltraBackend(): Boolean {
        if (dev.maplesu.manager.runtime.authorization.ManagerAuthorizations.usesManagerAuthority(ksuApp)) return false
        if (UltraEnvironmentProbe.usesUltraBackend(ksuApp)) return true
        val state = runCatching { UltraEnvironmentProbe.refresh(ksuApp, force = false) }
            .getOrDefault(UltraEnvironmentProbe.State.Unknown)
        if (UltraEnvironmentProbe.usesUltraBackend(ksuApp)) return true
        check(state == UltraEnvironmentProbe.State.NoKey) { "ULTRA_ENV_NOT_READY_RETRY" }
        return false
    }

    /** LITE 根据已验证实现身份分流，SDK 暂时不可用时不启动旧 direct/RootService。 */
    private suspend fun loadLiteAppList(): Result<Pair<List<AppInfo>, List<Int>>> = withContext(Dispatchers.IO) {
        runCatching {
            if (dev.maplesu.manager.runtime.authorization.ManagerAuthorizations.usesManagerAuthority(ksuApp)) {
                return@runCatching loadHookAppList()
            }
            // 🟢 Ultra 环境走 [loadUltraAppList]：应用清单来自 PackageManager，授权状态来自
            //    MapleSU SDK 的 su_auth 白名单。既有的 KSU 分支需要 `KsuCli.SHELL.isRoot`
            //    与 libsu RootService（ksud binder），Ultra 内核上这两样都不存在，而且
            //    libmaplesu_runtime 那条路会重启系统 —— 所以必须在最前面就分流。
            if (usesReadyUltraBackend()) {
                return@runCatching loadUltraAppList()
            }
            // ══════════════════════════════════════════════════════════════════
            // 🟢 v2.16：**换回上游 KernelSU 的做法**（逐字对齐上游提交 7759d878）。
            //
            // 上游提交信息原文：
            //   "libsu bindOrTask() doesn't check shell uid, so app_process will crash
            //    because of loading a writable dex."
            //
            // 也就是说：`RootService.bindOrTask()` 本身**不看 shell 的 uid**。libsu 会把
            // 自带的 `main.jar` 拷进 App 的 cache（= 一个**可写 dex**），再用当前这个
            // shell 跑 `app_process`；shell 没有 root 时 app_process 就以 **App 自己的
            // uid** 加载那个可写 dex → Android 10+ 的 ART 拒绝 →
            // `Check failed: system_class_loader != nullptr` → `abort()`
            // → **signal 6 (SIGABRT), code -1 (SI_QUEUE)**，进程名
            // `com.maplesu.core:root:<userId>`。
            //
            // 上游的修法就是下面这三行：**没 root 就别去 bind**。
            // `KsuCli.SHELL.isRoot` 是 libsu 自己的结论 —— `ShellImpl` 的构造函数会
            // 同步跑一次 `echo SHELL_TEST` + `id`，输出含 `uid=0` 才把 status 置成
            // ROOT_SHELL（见 libsu 6.0.0 `ShellImpl.shellCheck()` 字节码），
            // **`build()` 返回时它已经写好**，并且进程已死会直接抛
            // `IOException("Created process has terminated")`。所以这个判据是可靠的，
            // 本项目自己的 `rootAvailable()`（KsuCli.kt）用的也正是它。
            //
            // ⚠️ v2.15 我们曾经在这里自创过一套 `RootServiceGate`（枚举 + 两个 KsuCli
            //    新 API + 自己跑 `id -u` 的 1500ms 探针），v2.16 已整段删除：
            //    它做的正是 libsu 构造期已经做过的事，而且和 `rootAvailable()` 的判据
            //    自相矛盾。**保持和上游一致，别再长回来。**
            // ══════════════════════════════════════════════════════════════════
            if (!KsuCli.SHELL.isRoot) {
                return@withContext Result.failure(
                    IllegalStateException("Root access is required")
                )
            }

            val result = connectKsuService {
                Log.w(TAG, "KsuService disconnected")
            }

            var currentBinder = result.first
            var currentConnection = result.second

            try {
                suspend fun reconnect(): IKsuInterface {
                    withContext(Dispatchers.Main) {
                        RootService.unbind(currentConnection)
                    }
                    val retry = connectKsuService { Log.w(TAG, "KsuService disconnected") }
                    currentBinder = retry.first
                    currentConnection = retry.second
                    return IKsuInterface.Stub.asInterface(currentBinder)
                }

                val pm = ksuApp.packageManager
                val start = SystemClock.elapsedRealtime()

                var iface = IKsuInterface.Stub.asInterface(currentBinder)
                val idsArray = try {
                    iface.userIds
                } catch (_: Exception) {
                    iface = reconnect()
                    iface.userIds
                }

                val slice = try {
                    iface.getPackages(0)
                } catch (_: Exception) {
                    iface = reconnect()
                    iface.getPackages(0)
                }

                val packages = slice.list
                val newApps = packages.filter {
                    val ai = it.applicationInfo ?: return@filter false
                    ai.uid != WEBVIEW_ZYGOTE_UID &&
                            (ai.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
                }.mapNotNull {
                    // 🟠（v2.19）`applicationInfo` 是平台类型、会被系统裁剪，`!!` 一碰就 NPE
                    //（与 data/model/AppInfo.kt 的 `?: -1` 同一个坑）。上面的 filter 已经保证
                    // 非空，这里再兜一次：真为 null 就跳过这条，语义不变（不会少一条正常数据）。
                    val appInfo = it.applicationInfo ?: return@mapNotNull null
                    val profile = Natives.getAppProfile(it.packageName, appInfo.uid)
                    AppInfo(
                        label = appInfo.loadLabel(pm).toString(),
                        packageInfo = it,
                        profile = profile,
                    )
                }.toMutableList()

                // WebView Zygote is a single system UID, not a per-user package. Reuse the system icon.
                val systemInfo = ApplicationInfo(pm.getApplicationInfo("android", 0)).apply {
                    uid = WEBVIEW_ZYGOTE_UID
                }
                val placeholder = PackageInfo().apply {
                    packageName = ""
                    applicationInfo = systemInfo
                }
                newApps += AppInfo(
                    label = "WebView Zygote",
                    packageInfo = placeholder,
                    profile = Natives.getAppProfile(WEBVIEW_ZYGOTE_PROFILE_KEY, WEBVIEW_ZYGOTE_UID),
                    profileKey = WEBVIEW_ZYGOTE_PROFILE_KEY,
                    special = true,
                )

                Log.i(TAG, "load cost: ${SystemClock.elapsedRealtime() - start}")
                Pair(newApps, idsArray.toList())
            } finally {
                withContext(Dispatchers.Main) {
                    RootService.unbind(currentConnection)
                }
            }
        }
    }

    /** Hook publishes a restricted SU endpoint; application discovery uses PackageManager. */
    private fun loadHookAppList(): Pair<List<AppInfo>, List<Int>> {
        val grants = readHookSnapshot()
        val pm = ksuApp.packageManager
        val apps = pm.getInstalledPackages(0).filter { info ->
            val ai = info.applicationInfo ?: return@filter false
            ai.uid != WEBVIEW_ZYGOTE_UID && (ai.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
        }.map { info ->
            val ai = requireNotNull(info.applicationInfo)
            AppInfo(label = ai.loadLabel(pm).toString(), packageInfo = info,
                profile = Natives.Profile(info.packageName, currentUid = ai.uid,
                    allowSu = grants.isGranted(info.packageName, ai.uid)),
                authorizationStale = grants.isStale(info.packageName, ai.uid), authorizationPending = grants.pendingApply)
        }
        return apps to listOf(ksuApp.applicationInfo.uid / 100_000)
    }

    private fun readHookSnapshot(): HookAppListSnapshot {
        val identities = AndroidApplicationIdentities(ksuApp)
        return HookAppListSnapshot.read(
            snapshot = { SuperuserControl.snapshot(ksuApp) },
            identityOf = identities::read,
        )
    }

    /**
     * KSU 模式：应用清单来自 PackageManager，授权状态来自 KSU 后端
     * （`Natives.getAppProfile().allowSu` —— Natives 门面在 KSU 下只走 KSU JNI / W14 适配）。
     *
     * **不调用** 任何 Lite runtime：不问 `KsuCli.SHELL`、不 bind libsu RootService、
     * 不走 `DirectPolicy`/`SuDelivery`；KSU 后端不可用时显式失败，不回退 Lite。
     */
    private fun loadKsuAppList(): Result<Pair<List<AppInfo>, List<Int>>> = runCatching {
        val ksuAvailable = runCatching { Natives.version > 0 && Natives.isManager }.getOrDefault(false)
        if (!ksuAvailable) throw SuperUserKsuBackendUnavailableException()
        val pm = ksuApp.packageManager
        val apps = pm.getInstalledPackages(0).filter { info ->
            val ai = info.applicationInfo ?: return@filter false
            ai.uid != WEBVIEW_ZYGOTE_UID && (ai.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
        }.mapNotNull { info ->
            val ai = info.applicationInfo ?: return@mapNotNull null
            AppInfo(
                label = ai.loadLabel(pm).toString(),
                packageInfo = info,
                profile = Natives.getAppProfile(info.packageName, ai.uid),
            )
        }.toMutableList()

        // WebView Zygote 与 LITE/Ultra 分支保持同一特殊行（allowSu 恒 false）。
        runCatching { pm.getApplicationInfo("android", 0) }.getOrNull()?.let { system ->
            val systemInfo = ApplicationInfo(system).apply { uid = WEBVIEW_ZYGOTE_UID }
            val placeholder = PackageInfo().apply {
                packageName = ""
                applicationInfo = systemInfo
            }
            apps += AppInfo(
                label = "WebView Zygote",
                packageInfo = placeholder,
                profile = Natives.getAppProfile(WEBVIEW_ZYGOTE_PROFILE_KEY, WEBVIEW_ZYGOTE_UID),
                profileKey = WEBVIEW_ZYGOTE_PROFILE_KEY,
                special = true,
            )
        }
        Pair(apps, listOf(0))
    }

    /**
     * Ultra 分支：应用清单只依赖 PackageManager，授权状态来自 MapleSU SDK 的 su_auth 白名单。
     *
     * 比 KSU 分支少两样东西，而且都必须少：
     *  - 不问 `KsuCli.SHELL.isRoot`、不 bind libsu `RootService`（Ultra 内核上没有 ksud
     *    binder，且这两条最终都会落到 libmaplesu_runtime 上 —— 在 Ultra 内核会重启系统）；
     *  - 不逐包走 `DirectPolicy`（一次性 root 进程），改为读 [UltraAuthorization] 的内存缓存。
     *
     * PackageManager枚举当前Context所属Android user；授权投影使用完整UID，
     * 当前枚举不冒充已取得其他user的完整应用清单。
     */
    @Suppress("DEPRECATION")
    private suspend fun loadUltraAppList(): Pair<List<AppInfo>, List<Int>> {
        val application = ksuApp
        val pm = application.packageManager
        val start = SystemClock.elapsedRealtime()
        val (packages, grants) = UltraAppListSnapshot.withPackages(
            packages = {
                val installed = traceUltraListStage("pm_enumerate") { pm.getInstalledPackages(0) }
                traceUltraListStage("labels") {
                    installed.mapNotNull { info ->
                        val ai = info.applicationInfo ?: return@mapNotNull null
                        if (ai.uid == WEBVIEW_ZYGOTE_UID || (ai.flags and ApplicationInfo.FLAG_HAS_CODE) == 0)
                            return@mapNotNull null
                        info to ai.loadLabel(pm).toString()
                    }
                }
            },
            refresh = { traceUltraListStage("sdk_refresh") { UltraAuthorization.refresh(application) } },
            grantedPackages = { UltraAuthorization.grantedPackages() },
            grantedUids = { UltraAuthorization.uidProfiles().filterValues { it.allow }.keys },
        )
        val newApps = traceUltraListStage("profile_projection") {
            packages.map { (info, label) ->
                val ai = requireNotNull(info.applicationInfo)
                AppInfo(
                    label = label,
                    packageInfo = info,
                    profile = Natives.Profile(info.packageName, currentUid = ai.uid,
                        allowSu = grants.isGranted(info.packageName, ai.uid)),
                )
            }
        }.toMutableList()

        // WebView Zygote 与 KSU 分支保持同一行（特殊行，allowSu 恒为 false）。
        runCatching { pm.getApplicationInfo("android", 0) }.getOrNull()?.let { system ->
            val systemInfo = ApplicationInfo(system).apply { uid = WEBVIEW_ZYGOTE_UID }
            val placeholder = PackageInfo().apply {
                packageName = ""
                applicationInfo = systemInfo
            }
            newApps += AppInfo(
                label = "WebView Zygote",
                packageInfo = placeholder,
                profile = Natives.Profile(WEBVIEW_ZYGOTE_PROFILE_KEY, currentUid = WEBVIEW_ZYGOTE_UID,
                    allowSu = false),
                profileKey = WEBVIEW_ZYGOTE_PROFILE_KEY,
                special = true,
            )
        }

        Log.i(TAG, "ultra load cost: ${SystemClock.elapsedRealtime() - start} apps=${newApps.size} " +
            "granted=${grants.size}")
        return newApps to newApps.map { it.uid / 100_000 }.distinct().sorted()
    }

    override suspend fun refreshProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>> {
        val requestedMode = ModeRuntime.active
        val applications = currentApps.toList()
        return profileReads.readResult(requestedMode to applications) {
            check(ModeRuntime.active == requestedMode) { "PAGE_QUERY_CONTEXT_CHANGED" }
            val result = readProfiles(applications)
            check(ModeRuntime.active == requestedMode) { "PAGE_QUERY_CONTEXT_CHANGED" }
            result
        }
    }

    private suspend fun readProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        // 未判定：只读空态 + 明确错误，不发起任何后端/profile 调用。
        if (ModeRuntime.active == null) {
            ModeBootstrap.detectAfterRootGranted(ksuApp, delayMillis = 0L)
            return@withContext Result.failure(SuperUserModeUnresolvedException())
        }
        runCatching {
            if (currentApps.isEmpty()) return@runCatching emptyList()

            if (ModeRuntime.active == WorkMode.LITE && dev.maplesu.manager.runtime.authorization.ManagerAuthorizations.usesManagerAuthority(ksuApp)) {
                val grants = readHookSnapshot()
                return@runCatching currentApps.map { app ->
                    app.copy(profile = Natives.Profile(app.profileKey, currentUid = app.uid,
                        allowSu = grants.isGranted(app.profileKey, app.uid)),
                        authorizationStale = grants.isStale(app.profileKey, app.uid), authorizationPending = grants.pendingApply)
                }
            }

            if (ModeRuntime.active == WorkMode.LITE && usesReadyUltraBackend()) {
                val grants = traceUltraListStage("sdk_refresh_profiles") {
                    UltraAppListSnapshot.read(
                        refresh = { UltraAuthorization.refresh(ksuApp) },
                        grantedPackages = { UltraAuthorization.grantedPackages() },
                        grantedUids = { UltraAuthorization.uidProfiles().filterValues { it.allow }.keys },
                    )
                }
                return@runCatching traceUltraListStage("profile_projection") {
                    currentApps.map { app ->
                        app.copy(profile = Natives.Profile(app.profileKey, currentUid = app.uid,
                            allowSu = grants.isGranted(app.profileKey, app.uid)), authorizationStale = false, authorizationPending = false)
                    }
                }
            }

            currentApps.map {
                // KSU 模式下 Natives.getAppProfile 只走 KSU JNI；LITE 下保持原实现。
                val profile = Natives.getAppProfile(it.profileKey, it.uid)
                it.copy(profile = profile, authorizationStale = false, authorizationPending = false)
            }
        }
    }

    private inline fun <T> traceUltraListStage(stage: String, block: () -> T): T {
        val start = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() else 0L
        return try {
            block()
        } finally {
            if (BuildConfig.DEBUG) Log.i("MapleSUStartup",
                "stage=superusers_$stage event=end elapsedMs=${SystemClock.elapsedRealtime() - start}")
        }
    }

    private suspend inline fun connectKsuService(
        crossinline onDisconnect: () -> Unit = {}
    ): Pair<IBinder, ServiceConnection> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceDisconnected(name: ComponentName?) {
                    onDisconnect()
                }

                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (cont.isActive) {
                        cont.resume(binder as IBinder to this)
                    }
                }
            }

            cont.invokeOnCancellation {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    RootService.unbind(connection)
                } else {
                    Handler(Looper.getMainLooper()).post {
                        RootService.unbind(connection)
                    }
                }
            }

            val intent = Intent(ksuApp, KsuService::class.java)

            val task = RootService.bindOrTask(
                intent,
                Shell.EXECUTOR,
                connection,
            )
            val shell = KsuCli.SHELL
            task?.let { shell.execTask(it) }
        }
    }
}
