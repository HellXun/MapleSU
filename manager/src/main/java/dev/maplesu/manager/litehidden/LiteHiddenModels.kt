package dev.maplesu.manager.litehidden

/**
 * Lite（模式 A）的「隐藏模式」状态。
 *
 * **与 KSU 内核 stealth 完全独立**：Lite 没有内核 stealth feature，本状态由 root 写入
 * `/data/adb/maplesu/lite_hidden`；KSU 的隐藏仍走内核 ioctl。两者不互相声称等价。
 *
 * UNKNOWN = 读不到（无 root / 读失败）：按**保守方向**视为 ON（宁可多藏，不可漏出 root 特征）。
 */
enum class LiteHiddenState {
    OFF,
    ON,
    UNKNOWN,
    ;

    /** 生效判定：只有明确读到 OFF 才算关闭。 */
    val isEffectivelyOn: Boolean get() = this != OFF

    companion object {
        /** 文件内容 → 状态：`1`=ON、`0`=OFF、其它/缺失=UNKNOWN（由调用方决定缺失是否算 OFF）。 */
        fun fromWire(raw: String?): LiteHiddenState = when (raw?.trim()?.firstOrNull()) {
            '1' -> ON
            '0' -> OFF
            else -> UNKNOWN
        }
    }
}

/** 痕迹收敛项（L2，用户逐项可选，默认全关）。 */
enum class LiteHiddenTraceItem(val wire: String) {
    RECENT_INSTALL_LOG("recent_install_log"),
    LOGCAT_TAGS("logcat_tags"),
    DEBUG_EXPORTS("debug_exports"),
    INTENT_ALIASES("intent_aliases"),
    BACKGROUND_WRITERS("background_writers"),
    ;

    companion object {
        fun fromWire(value: String?): LiteHiddenTraceItem? =
            entries.firstOrNull { it.wire == value }
    }
}

/** 单条痕迹项的执行结果（诚实报告，不伪造成功）。 */
data class LiteHiddenTraceResult(
    val item: LiteHiddenTraceItem,
    val ok: Boolean,
    val reason: String = "",
)

/** 开关/密令操作的结果（UI 只认这几种，文案见 LiteHiddenCopy）。 */
enum class LiteHiddenOutcome {
    SUCCESS,
    NOT_SUPPORTED,
    NOT_AUTHORIZED,
    FAILED,
}

/** 隐藏生效时各 UI 入口的可见性（纯数据 → 主机可测）。 */
data class LiteHiddenVisibility(
    /** A 且隐藏生效时才允许显示"隐藏设置"卡。 */
    val cardVisible: Boolean = false,
    /** 伪装为「本产品未安装」：首页状态卡/入口按未安装渲染。 */
    val disguisedAsNotInstalled: Boolean = false,
    val showSuperUserEntry: Boolean = true,
    val showModuleEntry: Boolean = true,
    val showRootSettings: Boolean = true,
    val showDirectInstall: Boolean = true,
    val showUltraEnvironment: Boolean = true,
    val showModuleActions: Boolean = true,
    val showRecoveryEntry: Boolean = true,
    /** 桌面图标不动（图标隐藏已废弃，避免死图标锁死）。 */
    val allowLauncherAliasToggle: Boolean = false,
)

/** 不可删除的痕迹（避免不可恢复）：凭证/内核证据/备份/授权表/模块实体。 */
object LiteHiddenForbidden {
    val NAME_PATTERNS: List<String> = listOf(
        "maplesu-credential", dev.maplesu.manager.compat.StoredInputs.CREDENTIAL_STEM, "authorizations.bin",
        "maplesu-ultra", "keystore", "backup", "published-directory",
        "modules", "module-catalog", "module-runtime", "last_crash.txt",
    )
    val PATH_PATTERNS: List<String> = listOf(
        "/data/adb", "/data/misc", "noBackupFilesDir", "secure", "supersu",
    )
}
