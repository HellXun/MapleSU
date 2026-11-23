package dev.maplesu.manager.workmode.service

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.ControlPlane
import dev.maplesu.manager.workmode.control.schema.SharedSuperuserFile
import dev.maplesu.manager.workmode.snapshot.SettingsMirror
import dev.maplesu.manager.workmode.storage.ModePaths
import dev.maplesu.manager.workmode.superuser.DefaultSuperuserSync
import dev.maplesu.manager.workmode.superuser.DirectRootScriptRunner
import dev.maplesu.manager.workmode.superuser.RootScriptRunner
import dev.maplesu.manager.workmode.superuser.ExportResult
import dev.maplesu.manager.workmode.superuser.ReconcileResult
import dev.maplesu.manager.workmode.superuser.SuperuserSync
import dev.maplesu.manager.workmode.superuser.SyncState
import dev.maplesu.manager.workmode.superuser.productionSuperuserBackends
import dev.maplesu.manager.workmode.switch.BeginInputs
import dev.maplesu.manager.workmode.switch.HookResult
import dev.maplesu.manager.workmode.switch.ModeSwitchCodes
import dev.maplesu.manager.workmode.switch.SuperuserReconciler
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// ---------------------------------------------------------------------------
// S1 生产 MirrorProvider
// ---------------------------------------------------------------------------

/** 单次导出产物；`code` 非空即失败（绝不空串成功）。 */
data class MirrorArtifact(val file: File?, val code: String = "", val detail: String = "") {
    val ok: Boolean get() = file != null && code.isEmpty()
}

fun interface SettingsMirrorExporter {
    fun export(mode: WorkMode): MirrorArtifact
}

fun interface SuperuserMirrorExporter {
    fun export(mode: WorkMode): MirrorArtifact
}

/**
 * S1 前置镜像的生产实现：
 * - 设置镜像：当前模式 prefs → `SettingsMirror.mirrorable`（键表/敏感键排除零改动）→ JSON 文件；
 * - 共享授权：`SuperuserSync.exportBeforeSwitch`（粒度/墓碑/水位语义不变）→ shared JSON 副本。
 * 任一失败返回明确 code（`PREPARE_EXPORT_FAILED` / `SUPERUSER_*`），不返回空串成功。
 */
class AndroidModeSwitchMirrorProvider(
    private val activeMode: () -> WorkMode? = { ModeRuntime.active },
    private val settingsExporter: SettingsMirrorExporter,
    private val superuserExporter: SuperuserMirrorExporter,
) : ModeSwitchMirrorProvider {

    override fun provide(): BeginInputs {
        val mode = activeMode()
            ?: return BeginInputs(null, null, ModeSwitchCodes.STATE_UNDETERMINED)
        val settings = runCatching { settingsExporter.export(mode) }.getOrElse { error ->
            MirrorArtifact(null, ModeSwitchCodes.PREPARE_EXPORT_FAILED, error.javaClass.simpleName)
        }
        if (!settings.ok) {
            return BeginInputs(null, null, settings.code.ifEmpty { ModeSwitchCodes.PREPARE_EXPORT_FAILED })
        }
        val superuser = runCatching { superuserExporter.export(mode) }.getOrElse { error ->
            MirrorArtifact(null, "SUPERUSER_EXPORT_FAILED", error.javaClass.simpleName)
        }
        if (!superuser.ok) {
            return BeginInputs(null, null, superuser.code.ifEmpty { "SUPERUSER_EXPORT_FAILED" })
        }
        return BeginInputs(superuser.file, settings.file, "")
    }

    companion object {
        /** 生产默认（context 版本）。 */
        fun production(context: Context, runner: RootScriptRunner = DirectRootScriptRunner(context)): AndroidModeSwitchMirrorProvider {
            val control = RootShellControlPlane(context, runner = runner)
            val superuser = AndroidSuperuserMirrorExporter(context, { control.copySharedTo(it) }) { mode ->
                createProductionSuperuserSync(context, mode, control)
            }
            return AndroidModeSwitchMirrorProvider(
                settingsExporter = AndroidSettingsMirrorExporter(context),
                superuserExporter = superuser,
            )
        }
    }
}

/** 当前模式设置镜像：prefs → 过滤 → JSON（原子写 App 私有文件）。 */
class AndroidSettingsMirrorExporter(private val context: Context) : SettingsMirrorExporter {
    override fun export(mode: WorkMode): MirrorArtifact {
        val entries = LinkedHashMap<String, String>()
        for (legacy in ModePaths.PREFS_TABLE.map { it.legacyName }) {
            val name = ModePaths.prefsNameFor(legacy, mode) ?: continue
            val prefs = context.ultraPreferences(name, Context.MODE_PRIVATE)
            prefs.all.forEach { (key, value) ->
                if (value != null) entries["$legacy/$key"] = value.toString()
            }
        }
        val mirror = SettingsMirror.mirrorable(entries)
        val body = JSONObject().apply {
            put("schema", 1)
            put("mode", mode.suffix)
            put("revision", 0L)
            put("entries", JSONObject(mirror as Map<*, *>))
        }
        val directory = File(context.filesDir, "workmode/mirror").apply { mkdirs() }
        val target = File(directory, "settings-mirror-${mode.suffix}.json")
        val temp = File(directory, "${target.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(body.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            return MirrorArtifact(null, ModeSwitchCodes.PREPARE_EXPORT_FAILED, "rename failed")
        }
        return MirrorArtifact(target)
    }
}

/**
 * 共享授权导出：`SuperuserSync.exportBeforeSwitch` 写 shared/superuser.json（root 侧），
 * `copyShared` 把 JSON 只读拷到 App 私有文件供 PFD 传输。
 */
class AndroidSuperuserMirrorExporter(
    private val context: Context,
    private val copyShared: (File) -> Boolean,
    private val syncProvider: (WorkMode) -> SuperuserSync = { createProductionSuperuserSync(context, it) },
) : SuperuserMirrorExporter {
    override fun export(mode: WorkMode): MirrorArtifact {
        val sync = runCatching { syncProvider(mode) }.getOrElse { error ->
            return MirrorArtifact(null, "SUPERUSER_BACKEND_UNAVAILABLE", error.javaClass.simpleName)
        }
        val result = runCatching {
            runBlocking { sync.exportBeforeSwitch(mode, System.currentTimeMillis()) }
        }.getOrElse { error ->
            return MirrorArtifact(null, "SUPERUSER_EXPORT_FAILED", error.javaClass.simpleName)
        }
        return when (result) {
            is ExportResult.Blocked -> MirrorArtifact(null, result.code.ifEmpty { "SUPERUSER_EXPORT_FAILED" }, result.detail)
            is ExportResult.Ok -> {
                val directory = File(context.filesDir, "workmode/mirror").apply { mkdirs() }
                val target = File(directory, "superuser-mirror.json")
                if (!copyShared(target)) {
                    MirrorArtifact(null, "SUPERUSER_EXPORT_FAILED", "shared copy failed")
                } else {
                    MirrorArtifact(target)
                }
            }
        }
    }
}

/** 生产 SuperuserSync 工厂：root 控制面 + 与当前模式匹配的后端（KSU 适配器仅 KSU 模式加载）。 */
fun createProductionSuperuserSync(context: Context, mode: WorkMode,
                                  control: ControlPlane = RootShellControlPlane(context)): SuperuserSync {
    // Ultra-only：没有 KSU 超级用户适配器，后端列表只由 Ultra/Direct 组成。
    val backends = productionSuperuserBackends(context, mode)
    return DefaultSuperuserSync(control, backends)
}

// ---------------------------------------------------------------------------
// S6.4 生产 SuperuserReconciler
// ---------------------------------------------------------------------------

/**
 * `SuperuserSync.reconcileAfterBoot` 的切换侧适配：
 * - 目标 root 通道不可得 → `SUPERUSER_BACKEND_UNAVAILABLE`（fail-closed，不报成功）；
 * - revoke 失败（dangerousRevokeFailures）→ `SUPERUSER_REVOKE_FAILED`（绝不静默）；
 * - verify/apply 失败、BLOCKED/BACKOFF/PARTIAL_PENDING → `VERIFY_SUPERUSER_FAILED`。
 */
class SuperuserSyncReconciler(
    private val syncProvider: (WorkMode) -> SuperuserSync?,
    private val targetRootAvailable: (WorkMode) -> Boolean = { true },
) : SuperuserReconciler {
    override suspend fun reconcileAfterBoot(mode: WorkMode): HookResult {
        if (!runCatching { targetRootAvailable(mode) }.getOrDefault(false)) {
            return HookResult.failed("SUPERUSER_BACKEND_UNAVAILABLE", "target root channel unavailable")
        }
        val sync = runCatching { syncProvider(mode) }.getOrNull()
            ?: return HookResult.failed("SUPERUSER_BACKEND_UNAVAILABLE", "no superuser backend")
        val result: ReconcileResult = runCatching { sync.reconcileAfterBoot(mode) }.getOrElse { error ->
            return HookResult.failed("VERIFY_SUPERUSER_FAILED", error.javaClass.simpleName)
        }
        if (result.dangerousRevokeFailures.isNotEmpty()) {
            return HookResult.failed(
                "SUPERUSER_REVOKE_FAILED",
                result.dangerousRevokeFailures.joinToString(",") { "${it.code}:${it.packageName}" },
            )
        }
        if (result.state == SyncState.BLOCKED || result.state == SyncState.BACKOFF) {
            return HookResult.failed("VERIFY_SUPERUSER_FAILED", "state=${result.state}")
        }
        if (result.failed > 0) {
            return HookResult.failed("VERIFY_SUPERUSER_FAILED",
                "applied=${result.applied} failed=${result.failed}")
        }
        if (result.state == SyncState.PARTIAL_PENDING) {
            return HookResult.failed("VERIFY_SUPERUSER_FAILED", "state=${result.state}")
        }
        return HookResult.OK
    }
}

/** 生产 reconciler：目标通道判定 + 按模式构造 SuSync。 */
fun createProductionSuperuserReconciler(context: Context,
                                       control: ControlPlane = RootShellControlPlane(context)): SuperuserReconciler =
    SuperuserSyncReconciler(
        syncProvider = { mode -> runCatching { createProductionSuperuserSync(context, mode, control) }.getOrNull() },
        targetRootAvailable = { mode ->
            ModeRuntime.active == mode && when (mode) {
                WorkMode.LITE -> true
            }
        },
    )
