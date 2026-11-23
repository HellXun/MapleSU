package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.compat.ultraPreferences

import android.content.Context
import android.content.SharedPreferences
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import java.io.File

/**
 * 设置总仓（`settings`）的 A/B 路由策略（01 §3.1/§3.2，P0 配置隔离）。
 *
 * 规则（禁止默认 A）：
 * - `ModeRuntime.active` 已判定：LITE → `settings`（A 原名，逐字节等价）；
 * - 未判定：中性 prefs 的 `active_mode` **提示**优先（上次模式）；
 *   无提示时只有**明确的 A 证据**（no-backup 目录里有 48B 凭据文件）才读 A 原名，
 *   否则用中性 prefs（绝不把"未判定"当 A）。
 */
object SettingsPrefsPolicy {
    /** A（LITE）保持原名（01 §3.2）。 */
    const val A_NAME = "settings"

    /** 旧 Normal 的独立文件；只为把遗留安装 prefs 读回来，不再写入。 */
    const val B_NAME = "settings_B"

    fun nameFor(active: WorkMode?): String? = when (active) {
        WorkMode.LITE -> A_NAME
        null -> null
    }

    /** 未判定时的回退名；结果只会是 A / 中性 prefs。 */
    fun unresolvedName(lastKnown: WorkMode?, liteEvidencePresent: Boolean): String = when {
        lastKnown == WorkMode.LITE -> A_NAME
        // 明确的 A 证据（已配对过 Lite）：保持 A 行为逐字节等价。
        liteEvidencePresent -> A_NAME
        // 既无提示又无 A 证据：用中性 prefs，禁止默认 A。
        else -> ModePaths.NEUTRAL_PREFS
    }

    /** A 证据 = no-backup 目录里有凭据密文（不读取/不解密，仅存在性）。 */
    fun hasLiteEvidence(noBackupFilesDir: File): Boolean =
        listOf("maplesu-credential.bin", dev.maplesu.manager.compat.StoredInputs.CREDENTIAL_FILE).any {
            File(noBackupFilesDir, it).isFile
        }
}

/**
 * Android 门面：解析"当前设置总仓应该用哪个 prefs 文件"。
 *
 * 每次访问都重新解析（进程内 `ModeRuntime.active` 激活后不再翻转，切换模式会重启进程），
 * 因此调用方可以把 [prefs] 当普通 `SharedPreferences` 使用。
 */
class SettingsPrefsRouter(private val context: Context) {

    @JvmOverloads
    fun prefsName(active: WorkMode? = ModeRuntime.active): String {
        SettingsPrefsPolicy.nameFor(active)?.let { return it }
        return SettingsPrefsPolicy.unresolvedName(
            lastKnown = lastKnownMode(),
            liteEvidencePresent = SettingsPrefsPolicy.hasLiteEvidence(context.noBackupFilesDir),
        )
    }

    /** Java 调用方（如 SuFilePathHandler）可直接 `new SettingsPrefsRouter(ctx).prefs()`。 */
    @JvmOverloads
    fun prefs(active: WorkMode? = ModeRuntime.active): SharedPreferences =
        context.ultraPreferences(prefsName(active), Context.MODE_PRIVATE)

    /** 中性 prefs 的"上次模式"提示（只作提示，不作为裁决）。 */
    private fun lastKnownMode(): WorkMode? {
        val neutral = context.ultraPreferences(ModePaths.NEUTRAL_PREFS, Context.MODE_PRIVATE)
        return WorkMode.fromWire(neutral.getString(ModePaths.KEY_ACTIVE_MODE, null))
    }
}
