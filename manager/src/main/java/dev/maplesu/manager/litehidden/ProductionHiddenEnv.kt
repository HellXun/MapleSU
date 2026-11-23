package dev.maplesu.manager.litehidden

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dev.maplesu.manager.recovery.RecoveryBundle
import dev.maplesu.manager.recovery.RecoveryCommandResult
import dev.maplesu.manager.recovery.RecoveryFs
import dev.maplesu.manager.recovery.RecoveryPaths
import dev.maplesu.manager.runtime.RootExec
import dev.maplesu.manager.runtime.ultra.UltraAuthorization
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials
import dev.maplesu.manager.runtime.ultra.UltraSdkSession
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * 生产装配：把**既有**的 root 通道、PackageManager、Ultra `su_auth`、KSU stealth 与
 * recovery 备份接到 [ProductionHiddenModeBackend] 上。这里不做策略判定（全部在纯层），
 * 只做「不新增 su/libsu、不绕过既有授权」的胶水。
 *
 * 装配失败（缺 loader、拿不到 APK 路径/版本号）→ 返回 null，`HiddenModeBackends.current`
 * 保持 null（fail-closed，UI 得到 BACKEND_UNAVAILABLE）。
 */
object ProductionHiddenEnv {

    fun create(context: Context): HiddenModeBackend? = runCatching {
        val app = context.applicationContext
        val apkPath = app.applicationInfo?.sourceDir?.takeIf { it.isNotBlank() } ?: return null
        val versionCode = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
        }.getOrNull() ?: return null
        val loaderPath = File(app.applicationInfo.nativeLibraryDir.orEmpty(), ParasitePaths.LOADER_NAME)
        // 没有 loader（未打包/未解压）→ 注入必然失败，先 fail-closed，不把用户带进半隐藏。
        if (!loaderPath.isFile || loaderPath.length() <= 0L) return null

        val ultraClient = UltraRuntimeClient(rootKeyProvider = {
            runCatching {
                UltraSdkCredentials.read(app) ?: run {
                    if (UltraSdkCredentials.hookWithoutSdk(app)) return@run null
                    dev.maplesu.manager.runtime.ultra.UltraIdentityRecovery.recover(app)
                    UltraSdkCredentials.read(app)
                }
            }.getOrNull()
        })
        val ultraSdkRoot = UltraEnvRootRunner(ultraClient)
        // Ultra-only：隐藏模式的 root 通道只走 SDK（越狱 hook 通道已移除）。
        val ultraRoot = HiddenRootRunner { script -> ultraSdkRoot.run(script) }

        // 恢复包/清单/配置都在 /data/adb（App 无法直接写），全部经**当前模式**的 root 通道。
        val modeRoot = HiddenRootRunner { script ->
            when (ModeRuntime.active) {
                WorkMode.LITE -> ultraRoot.run(script)
                null -> RecoveryCommandResult(-1, "MODE_UNRESOLVED")
            }
        }
        val bundle = RecoveryBundle(RootRecoveryFs(modeRoot), directory = RecoveryPaths.DIR)

        // 「撤销授权所需身份材料」：必须是**本 App 作用域**的 androidId（daemon 进程读不到）。
        // 读不到返回 null，Ultra 激活会在卸载前回滚；恢复不依赖系统作用域 androidId。
        val identity: () -> RecoveryIdentity? = {
            val androidId = runCatching {
                android.provider.Settings.Secure.getString(
                    app.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID,
                )
            }.getOrNull()
            val uid = runCatching { android.os.Process.myUid() }.getOrNull() ?: -1
            RecoveryIdentity.of(androidId = androidId, packageName = app.packageName, uid = uid)
        }

        val ultraDeps = HiddenModeDeps(
            mode = WorkMode.LITE,
            root = ultraRoot,
            suAuth = UltraEnvSuAuth(app),
            state = RootFileHiddenState(ultraRoot),
            // precheck performs the actual id -u once through this same root route.
            rootAvailable = { ultraClient.bridgeLoaded },
            identity = identity,
            prepareBoot = {
                if (UltraSdkSession.current(app) != null) UltraSdkSession.prepareRecovery(app)
                else if (UltraSdkCredentials.hookWithoutSdk(app)) false
                else preparePermanentHiddenRecovery(ultraRoot) {
                    dev.maplesu.manager.runtime.ultra.UltraEnvironmentInstaller.backupKey(app, forceIdentity = true)?.ok == true
                }
            },
        )
        ProductionHiddenModeBackend(
            modeProvider = { ModeRuntime.active },
            deps = mapOf(WorkMode.LITE to ultraDeps),
            hostFacts = AndroidHostFacts(app),
            bundle = bundle,
            probe = HttpHiddenDaemonProbe(),
            managerApkPath = apkPath,
            managerVersionCode = versionCode,
            loaderSourcePath = loaderPath.absolutePath,
            managerPackage = app.packageName,
            logger = { message -> android.util.Log.i("MapleSUHidden", message) },
            managerRemoval = HttpHiddenManagerRemoval(),
        )
    }.getOrNull()
}

/** Explicit permanent preparation must finish before changing the daemon's credential owner. */
internal fun preparePermanentHiddenRecovery(root: HiddenRootRunner, backup: () -> Boolean): Boolean {
    if (!backup()) return false
    val path = dev.maplesu.manager.runtime.ultra.UltraBootSdkRecovery.PATH
    val result = root.run(
        "[ ! -L '$path' ] && rm -f '$path' && [ ! -e '$path' ] && echo MAPLESU_PERMANENT_RECOVERY_READY",
    )
    return result.code == 0 && result.output.trim() == "MAPLESU_PERMANENT_RECOVERY_READY"
}

/** Ultra `run_root_cmd`（uid 0，`u:r:shell:s0`）—— A 模式的**唯一** root 通道。 */
class UltraEnvRootRunner internal constructor(private val execute: (String) -> RecoveryCommandResult) : HiddenRootRunner {
    constructor(client: UltraRuntimeClient) : this({ command ->
        val receipt = client.runRootCommand(command)
        if (!receipt.succeeded) RecoveryCommandResult(-1, "ULTRA_ROOT_FAILED:${receipt.sdkError}")
        else RecoveryCommandResult(0, client.decodeText(receipt.string("result")).orEmpty())
    })

    override fun run(script: String): RecoveryCommandResult {
        val quoted = "'" + script.replace("'", "'\\''") + "'"
        val command = dev.maplesu.manager.runtime.UltraRootCommandReceipt(
            "/system/bin/timeout -k 5 60 /system/bin/sh -c $quoted",
        )
        val receipt = runCatching { execute(command.command) }.getOrNull()
            ?: return RecoveryCommandResult(-1, "ULTRA_ROOT_EXCEPTION")
        if (receipt.code != 0) return receipt
        val result = command.decode(receipt.output)
        return RecoveryCommandResult(result.code, result.output)
    }
}

/**
 * `/data/adb/maplesu/recovery` 的 root 文件系统实现（[RecoveryFs]）。
 *
 * 关键安全点：[deleteRecursively] **只删恢复包自己的条目**，绝不 `rm -rf` 整个目录 ——
 * `recovery/host/<pkg>/` 里是宿主原始 so 的唯一副本，删了就没法复原。
 */
class RootRecoveryFs(private val root: HiddenRootRunner) : RecoveryFs {

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun oct(mode: Int): String = Integer.toOctalString(mode)

    private fun run(script: String): RecoveryCommandResult =
        runCatching { root.run(script) }.getOrElse { RecoveryCommandResult(-1, it.javaClass.simpleName) }

    private fun ok(script: String): Boolean {
        val result = run(script)
        return result.code == 0 && result.output.contains("RF_OK")
    }

    override fun ensureDirectory(path: String, mode: Int): Boolean =
        ok("mkdir -p ${quote(path)} && chmod ${oct(mode)} ${quote(path)} && echo RF_OK")

    override fun exists(path: String): Boolean =
        run("[ -e ${quote(path)} ] && echo RF_YES || echo RF_NO").output.contains("RF_YES")

    override fun copy(source: String, destination: String, mode: Int): Boolean = ok(
        "mkdir -p ${quote(destination.substringBeforeLast('/'))} && " +
            "cp -f ${quote(source)} ${quote(destination)} && chmod ${oct(mode)} ${quote(destination)} && echo RF_OK",
    )

    override fun write(path: String, bytes: ByteArray, mode: Int): Boolean {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val parent = path.substringBeforeLast('/', "")
        return ok(
            "mkdir -p ${quote(parent)} 2>/dev/null; " +
                "printf '%s' '$encoded' | base64 -d > ${quote(path)} && " +
                "chmod ${oct(mode)} ${quote(path)} && echo RF_OK",
        )
    }

    override fun read(path: String): ByteArray? {
        val result = run("base64 ${quote(path)} 2>/dev/null")
        if (result.code != 0) return null
        val text = result.output.filter { !it.isWhitespace() }
        if (text.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(text) }.getOrNull()
    }

    override fun sha256(path: String): String? {
        val value = run("sha256sum ${quote(path)} 2>/dev/null | awk '{print \$1}'").output.trim()
        return value.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
    }

    override fun size(path: String): Long? =
        run("stat -c '%s' ${quote(path)} 2>/dev/null").output.trim().toLongOrNull()

    override fun deleteRecursively(path: String) {
        // 保留 host/：宿主原 so 的备份是解除寄生的唯一复原材料。
        run(
            "rm -rf ${quote(path)}/manifest.json ${quote(path)}/manager.apk " +
                "${quote(path)}/webui.conf ${quote(path)}/assets ${quote(path)}/lib 2>/dev/null",
        )
    }
}

/** PackageManager 宿主事实（只读；过滤规则全在 [ParasitePolicy]）。 */
class AndroidHostFacts(private val context: Context) : HostFactsSource {
    override fun load(packageName: String): HostFactsLookup = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        val libraryDir = info.nativeLibraryDir?.trim().orEmpty()
        val libs = if (libraryDir.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                File(libraryDir).listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && it.name.endsWith(".so") }
                    ?.map { HostLibFile(it.name, it.length()) }
                    ?.toList()
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
        HostFactsLookup.Ok(
            HostFacts(
                packageName = info.packageName,
                isSystem = (info.flags and (ApplicationInfo.FLAG_SYSTEM or
                    ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                abis = listOfNotNull(abiOfDirectory(File(libraryDir).name)),
                nativeLibraryDir = libraryDir,
                extractNativeLibs = (info.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0,
                libFiles = libs,
                uid = info.uid,
            ),
        )
    }.getOrElse { error ->
        if (error is PackageManager.NameNotFoundException) {
            HostFactsLookup.Missing(packageName)
        } else {
            HostFactsLookup.Failed(error.javaClass.simpleName)
        }
    }

    /** 设备上目录名是 `arm64`/`arm`；归一成官方 ABI 名（与宿主页扫描一致）。 */
    private fun abiOfDirectory(name: String): String? = when (name) {
        "arm64" -> HiddenHostPolicy.ARM64
        "arm" -> "armeabi-v7a"
        "" -> null
        else -> name
    }
}

/** Ultra `su_auth` 白名单：grant/revoke 之后**回读**（不是只看进程内缓存）。 */
class UltraEnvSuAuth internal constructor(
    private val read: (String) -> Boolean?,
    private val write: (String, Boolean) -> Boolean,
) : HiddenSuAuth {
    constructor(context: Context) : this(
        read = { pkg ->
            if (dev.maplesu.manager.runtime.authorization.ManagerAuthorizations.usesManagerAuthority(context)) {
                val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
                val snapshot = dev.maplesu.manager.runtime.authorization.SuperuserControl.snapshot(context)
                if (uid == null || snapshot == null || snapshot.corrupt || snapshot.pendingApply()) null
                else !snapshot.revoked(uid) && snapshot.grantOf(uid)?.packageName == pkg
            } else if (UltraAuthorization.refresh(context)) UltraAuthorization.isGranted(pkg) else null
        },
        write = { pkg, allow ->
            if (dev.maplesu.manager.runtime.authorization.ManagerAuthorizations.usesManagerAuthority(context)) {
                val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
                if (uid == null) false
                else {
                    val enabled = !allow || dev.maplesu.manager.runtime.authorization.SuperuserControl
                        .snapshot(context)?.enabled == true
                    val result = if (!enabled) dev.maplesu.manager.runtime.authorization.SuperuserControl.Result.failure("AUTH_DISABLED")
                    else if (allow) dev.maplesu.manager.runtime.authorization.SuperuserControl.grant(context, uid)
                    else dev.maplesu.manager.runtime.authorization.SuperuserControl.revoke(context, uid)
                    if (!allow && result.ok) {
                        dev.maplesu.manager.runtime.authorization.SuperuserControl.killSessions(context, uid)
                    }
                    result.ok && !result.pendingApply
                }
            } else if (allow) UltraAuthorization.grant(context, pkg) else UltraAuthorization.revoke(context, pkg)
        },
    )

    override fun grant(packageName: String): SuAuthOutcome = synchronized(
        dev.maplesu.manager.runtime.authorization.SuperuserControl,
    ) {
        val before = runCatching { read(packageName) }.getOrNull()
            ?: return@synchronized SuAuthOutcome.Failed("allowlist_before_unreadable")
        val written = runCatching { write(packageName, true) }.getOrDefault(false)
        val after = runCatching { read(packageName) }.getOrNull()
        if (!written || after != true) {
            SuAuthOutcome.Failed("allowlist_grant_unverified", rollbackGrant = !before)
        } else if (before) SuAuthOutcome.AlreadyGranted else SuAuthOutcome.Granted
    }

    override fun revoke(packageName: String): SuAuthOutcome = synchronized(
        dev.maplesu.manager.runtime.authorization.SuperuserControl,
    ) {
        val before = runCatching { read(packageName) }.getOrNull()
        val written = runCatching { write(packageName, false) }.getOrDefault(false)
        val after = runCatching { read(packageName) }.getOrNull()
        if (!written || after != false) SuAuthOutcome.Failed("allowlist_revoke_unverified")
        else if (before != false) SuAuthOutcome.Revoked else SuAuthOutcome.AlreadyGranted
    }

    override fun isGranted(packageName: String): Boolean =
        checkNotNull(read(packageName)) { "allowlist_readback_unreadable" }
}

/** A：`/data/adb/maplesu/lite_hidden`（复用既有脚本生成器，写后由调用方回读）。 */
class RootFileHiddenState(
    private val root: HiddenRootRunner,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : HiddenStatePort {
    override fun write(on: Boolean): Boolean {
        val result = run(LiteHiddenPaths.stateScript(write = true, value = if (on) "1" else "0")) ?: return false
        return result.code == 0 && result.output.contains(ParasiteMarkers.LITE_OK)
    }

    override fun read(): Boolean? {
        val result = run(LiteHiddenPaths.stateReadScript()) ?: return null
        if (result.code != 0) return null
        return when (LiteHiddenStateReader.parse(result.output)) {
            LiteHiddenState.ON -> true
            LiteHiddenState.OFF -> false
            LiteHiddenState.UNKNOWN -> null
        }
    }

    /** Only explicit pre-submission admission failures may replay a state command. */
    private fun run(script: String): RecoveryCommandResult? {
        repeat(4) { attempt ->
            if (Thread.currentThread().isInterrupted) return null
            val result = try {
                root.run(script)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: Exception) {
                return null
            }
            val notSubmitted = result.code < 0 && result.output.trim() in setOf(
                "ROOT_EXECUTION_BUSY", "ULTRA_ROOT_FAILED:MAPLESU_SDK_BUSY",
            )
            if (!notSubmitted || attempt == 3) return result
            try {
                pause(150L * (attempt + 1))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        error("unreachable")
    }
}

/**
 * WebUI daemon 真实 HTTP 探活：`GET /x7k9f/<token>/ping`（Host 头必须是 loopback），
 * 要求 200 + 服务名。**与恢复链接同一形状** —— 链接能打开才算通道成立；
 * 只是「端口能 connect」不算成功（端口可能被别的进程占着）。
 */
class HttpHiddenDaemonProbe(
    private val connectTimeoutMs: Int = 1_500,
    private val readTimeoutMs: Int = 2_000,
) : HiddenDaemonProbe {
    override fun ping(port: Int, token: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val request = "GET /${ParasitePaths.WEBUI_PATH_PREFIX}/$token/ping HTTP/1.0\r\n" +
                "Host: 127.0.0.1\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.UTF_8))
                flush()
            }
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            response.startsWith("HTTP/1.1 200") && response.contains("maplesu-liteweb-daemon")
        }
    }.getOrDefault(false)
}
