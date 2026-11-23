package dev.maplesu.manager.workmode

/**
 * 唯一的工作模式。
 *
 * 上游 MapleSU 曾同时携带 Ultra（原 Lite）与 Normal（原 KSU）两套后端；本分支只发布
 * Ultra，Normal 的枚举值、后端与切换链路已整体删除，所以这里只剩一个常量。
 *
 * [suffix] 同时是：prefs 后缀、离线仓目录名、控制面 wire 值。
 * 一经发布不得修改（磁盘目录名与 prefs 后缀，改了等于数据丢失）。
 * 历史上 Ultra 使用 "A"，因此这里保持 "A" 不变。
 *
 * 核心枚举保持纯 Kotlin，便于主机检查。UI 层负责 `WorkMode -> string res` 映射，
 * 对应 `R.string.work_mode_lite`。
 */
enum class WorkMode(
    /** "A"，用于 settings_A / modes/A / modes.json.current。 */
    val suffix: String,
) {
    /** MapleSU_Ultra（唯一受支持的工作模式）。 */
    LITE("A");

    val isLite: Boolean get() = true

    companion object {
        /** 只接受 "A"/"LITE"；其它（含历史值 "B"/"KSU"、null、空串）返回 null。 */
        fun fromWire(value: String?): WorkMode? = when (value?.trim()?.uppercase()) {
            "A", "LITE" -> LITE
            else -> null
        }

        fun fromSuffix(suffix: String?): WorkMode? =
            if (suffix == null) null else entries.firstOrNull { it.suffix == suffix }
    }
}
