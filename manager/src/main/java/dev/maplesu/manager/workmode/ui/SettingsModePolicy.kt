package dev.maplesu.manager.workmode.ui

import dev.maplesu.manager.workmode.WorkMode

/**
 * 「设置」页可见性 + 数据读取许可的**唯一裁决**（2026-10-31，模式独立性审计）。
 *
 * 背景（真机反馈 + 代码审计）：
 *  - 旧设置页用零散 `if` / `KsuIsValid` / 能力位决定可见性，Ultra（LITE）下仍会读取
 *    KSU 内核隐身（`Stealth.kernelState()` 在 LITE 恒 -1 → UNKNOWN），Normal（KSU）下仍会
 *    构造 Ultra 环境面板（触发 Ultra SDK / 环境探测）；
 *  - 读数失败（UNKNOWN）曾被渲染成"已开启"（用户原话：「隐身模式没开启也显示隐藏模式下已启用了」）。
 *
 * 规则（本类就是规则本身，页面/VM 不再各自判断）：
 *  - [WorkMode.LITE]（界面名 MapleSU_Ultra）：通用 + **Ultra 专属**；
 *  - [WorkMode.KSU]（界面名 MapleSU_Normal）：通用 + **Normal 专属**；
 *  - `null`（未判定）：**只**通用项；任一模式专属 section/row/dataSource 都不可见、不可读。
 *
 * 两层输出：
 *  - [sections] / [rows]：页面**只**按 [shows] 渲染（不是"渲染后隐藏"）；
 *  - [dataSources]：ViewModel/页面**只**按 [mayRead] 读取 —— 另一模式的 IO/JNI/root 探测
 *    根本不会发生，避免 -1 / UNKNOWN 被当成 OFF/ON 再渲染。
 *
 * 本文件是纯 Kotlin（不 import Android / Compose / R）→ 纯 JVM 单测。
 * 资源文案映射在 [SettingsStatusCopy]（只依赖 R 常量，同样可主机测试）。
 */
enum class SettingsSection {
    /** 通用：界面与外观入口 + 外观子页。 */
    APPEARANCE,

    /** 通用：检查模块更新（两模式 ModuleViewModel 都消费，见 docs 矩阵）。 */
    MODULE_UPDATE,

    /** 通用：工作模式入口。 */
    WORK_MODE,

    /** 通用：软重启 + WebView 调试。 */
    GENERAL_TUNING,

    /** 通用：WebUI 自检入口（A/B 后端不同，页面内部按模式分派）。 */
    WEBUI_SELF_CHECK,

    /** 通用：发送日志 / 关于。 */
    LOG_AND_ABOUT,

    /** Ultra 专属：MapleSU_Ultra 工作环境卡（含卸载）。 */
    ULTRA_ULTRA_ENV,

    /** Ultra 专属：隐藏 / 寄生入口（Lite 隐藏，独立页）。 */
    ULTRA_HIDDEN,
    ULTRA_MODULE_POLICY,
}

/** 设置页里的**单个可见行**（section 的叶子；测试与 UI 契约使用）。 */
enum class SettingsRow {
    /** 通用 */
    APPEARANCE_ENTRY,
    PERSONALIZATION_GROUP,
    MODULE_UPDATE_CHECK,
    WORK_MODE_ENTRY,
    SOFT_REBOOT,
    WEBVIEW_DEBUG,
    WEBUI_SELF_CHECK_ENTRY,
    SEND_LOG,
    ABOUT,

    /** Ultra 专属 */
    ULTRA_ENVIRONMENT,
    LITE_HIDDEN,
    ULTRA_MODULE_POLICY_LOAD,
    ULTRA_MODULE_POLICY_HIDE,
}

/**
 * 模式专属**数据读取**的许可位。
 *
 * 页面/VM 在调用底层读取前必须 `policy.mayRead(...)`；未判定时集合为空，
 * 因此另一模式（以及未知模式）的 JNI/root/SDK 读取不会被"顺手"执行。
 */
enum class SettingsDataSource {
    /** Ultra：Ultra 环境 / Ultra SDK 探测（`UltraAutoPair` / `UltraEnvironmentProbe`）。 */
    ULTRA_ENVIRONMENT,

    /** Ultra：Lite 隐藏状态（`LiteHiddenRuntimeProxy.readState()`）。 */
    LITE_HIDDEN,
    ULTRA_MODULE_POLICY,
}

/** 状态行三态：只有**明确 ON**才允许说"已开启"；UNKNOWN 必须说"状态未知/读取失败"。 */
enum class SettingsStatus { ON, OFF, UNKNOWN }

/** 纯函数：section → 行集合（顺序固定；`when` 穷尽，新增 section 编译器强制补行）。 */
fun settingsRowsOf(section: SettingsSection): Set<SettingsRow> = when (section) {
    SettingsSection.APPEARANCE -> setOf(SettingsRow.APPEARANCE_ENTRY, SettingsRow.PERSONALIZATION_GROUP)
    SettingsSection.MODULE_UPDATE -> setOf(SettingsRow.MODULE_UPDATE_CHECK)
    SettingsSection.WORK_MODE -> setOf(SettingsRow.WORK_MODE_ENTRY)
    SettingsSection.GENERAL_TUNING -> setOf(SettingsRow.SOFT_REBOOT, SettingsRow.WEBVIEW_DEBUG)
    SettingsSection.WEBUI_SELF_CHECK -> setOf(SettingsRow.WEBUI_SELF_CHECK_ENTRY)
    SettingsSection.LOG_AND_ABOUT -> setOf(SettingsRow.SEND_LOG, SettingsRow.ABOUT)
    SettingsSection.ULTRA_ULTRA_ENV -> setOf(SettingsRow.ULTRA_ENVIRONMENT)
    SettingsSection.ULTRA_HIDDEN -> setOf(SettingsRow.LITE_HIDDEN)
    SettingsSection.ULTRA_MODULE_POLICY -> setOf(SettingsRow.ULTRA_MODULE_POLICY_LOAD, SettingsRow.ULTRA_MODULE_POLICY_HIDE)
}

/** 纯函数：section → 允许的数据读取（通用 section 无模式专属读取 → 空集）。 */
fun settingsDataSourcesOf(section: SettingsSection): Set<SettingsDataSource> = when (section) {
    SettingsSection.ULTRA_ULTRA_ENV -> setOf(SettingsDataSource.ULTRA_ENVIRONMENT)
    SettingsSection.ULTRA_HIDDEN -> setOf(SettingsDataSource.LITE_HIDDEN)
    SettingsSection.ULTRA_MODULE_POLICY -> setOf(SettingsDataSource.ULTRA_MODULE_POLICY)
    SettingsSection.APPEARANCE,
    SettingsSection.MODULE_UPDATE,
    SettingsSection.WORK_MODE,
    SettingsSection.GENERAL_TUNING,
    SettingsSection.WEBUI_SELF_CHECK,
    SettingsSection.LOG_AND_ABOUT -> emptySet()
}

/**
 * 某个工作模式下的设置页计划（不可变值对象）。
 *
 * 未判定（[mode] == null）时构造出来的就是"只有通用项"的计划 —— 这是页面默认状态，
 * 也是任何读取失败路径的安全默认值。
 */
data class SettingsModePolicy(
    val mode: WorkMode?,
    val sections: Set<SettingsSection>,
    val rows: Set<SettingsRow>,
    val dataSources: Set<SettingsDataSource>,
) {
    /** 模式是否已判定；false 时只允许通用项。 */
    val resolved: Boolean get() = mode != null

    fun shows(section: SettingsSection): Boolean = section in sections

    fun shows(row: SettingsRow): Boolean = row in rows

    /** `true` = 本模式下允许执行该数据读取；未判定/另一模式一律 false。 */
    fun mayRead(source: SettingsDataSource): Boolean = source in dataSources

    companion object {
        /** 两模式都适用、且未判定时仍然显示的行/section。 */
        val COMMON_SECTIONS: Set<SettingsSection> = setOf(
            SettingsSection.APPEARANCE,
            SettingsSection.MODULE_UPDATE,
            SettingsSection.WORK_MODE,
            SettingsSection.GENERAL_TUNING,
            SettingsSection.WEBUI_SELF_CHECK,
            SettingsSection.LOG_AND_ABOUT,
        )

        /** 只有 MapleSU_Ultra（LITE）显示。 */
        val ULTRA_ONLY_SECTIONS: Set<SettingsSection> = setOf(
            SettingsSection.ULTRA_ULTRA_ENV,
            SettingsSection.ULTRA_HIDDEN,
            SettingsSection.ULTRA_MODULE_POLICY,
        )

        val COMMON_ROWS: Set<SettingsRow> = rowsOf(COMMON_SECTIONS)
        val ULTRA_ONLY_ROWS: Set<SettingsRow> = rowsOf(ULTRA_ONLY_SECTIONS)

        /** 纯函数入口：输入 `WorkMode?`，输出该模式可见的 section/row/读取许可。 */
        fun forMode(mode: WorkMode?): SettingsModePolicy {
            val sections: Set<SettingsSection> = when (mode) {
                WorkMode.LITE -> COMMON_SECTIONS + ULTRA_ONLY_SECTIONS
                null -> COMMON_SECTIONS
            }
            return SettingsModePolicy(
                mode = mode,
                sections = sections,
                rows = rowsOf(sections),
                dataSources = dataSourcesOf(sections),
            )
        }

        private fun rowsOf(sections: Set<SettingsSection>): Set<SettingsRow> =
            sections.flatMapTo(LinkedHashSet()) { settingsRowsOf(it) }

        private fun dataSourcesOf(sections: Set<SettingsSection>): Set<SettingsDataSource> =
            sections.flatMapTo(LinkedHashSet()) { settingsDataSourcesOf(it) }
    }
}
