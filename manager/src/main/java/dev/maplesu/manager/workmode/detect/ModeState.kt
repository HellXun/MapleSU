package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.WorkMode

/**
 * 六态判定结果。
 *
 * 主文档 §6.1 是六行，但第 6 行同时写了 `NOT_INSTALLED` 与 `INDETERMINATE` 两个名字，
 * 二者 UI 文案与后续动作不同，故枚举拆成 7 个常量、6 个决策组（01 §10 C2）。
 */
enum class ModeState {
    /** KSU 内核在、认主、用户态/模块可用。 */
    MODE_B_ACTIVE,

    /** KSU 内核在、认主，ksud/工作目录缺失。 */
    MODE_B_KERNEL_ONLY,

    /** Lite 凭据握手 UID0 / Ultra Running / 越狱租约。 */
    MODE_A_ACTIVE,

    /** 有 A 不可重建证据但当前无 root 通道。 */
    MODE_A_INSTALLED_UNBOUND,

    /** 已确认外来 Root。 */
    FOREIGN_SU,

    /** 无任何证据。 */
    NOT_INSTALLED,

    /** 有痕迹但不可确认 / 读不到。 */
    INDETERMINATE;

    /** 稳定 wire 值（evidence.jsonl / 脱敏视图用）。 */
    val wire: String get() = name
}

/** 表驱动测试与 UI 分组的键：六组。 */
enum class DetectionGroup { B_ACTIVE, B_KERNEL_ONLY, A_ACTIVE, A_INSTALLED_UNBOUND, FOREIGN_SU, NONE }

val ModeState.group: DetectionGroup
    get() = when (this) {
        ModeState.MODE_B_ACTIVE -> DetectionGroup.B_ACTIVE
        ModeState.MODE_B_KERNEL_ONLY -> DetectionGroup.B_KERNEL_ONLY
        ModeState.MODE_A_ACTIVE -> DetectionGroup.A_ACTIVE
        ModeState.MODE_A_INSTALLED_UNBOUND -> DetectionGroup.A_INSTALLED_UNBOUND
        ModeState.FOREIGN_SU -> DetectionGroup.FOREIGN_SU
        ModeState.NOT_INSTALLED, ModeState.INDETERMINATE -> DetectionGroup.NONE
    }

/**
 * 该状态下"当前 boot 实际在跑的实现"。null = 不允许激活任何后端（主文档:203）。
 *
 * 口径：`MODE_A_INSTALLED_UNBOUND.activeMode == null` —— 装过但不可用 → 不激活（修复向导）。
 */
val ModeState.activeMode: WorkMode?
    get() = when (this) {
        ModeState.MODE_B_ACTIVE, ModeState.MODE_B_KERNEL_ONLY -> null
        ModeState.MODE_A_ACTIVE -> WorkMode.LITE
        ModeState.MODE_A_INSTALLED_UNBOUND -> null
        ModeState.FOREIGN_SU, ModeState.NOT_INSTALLED, ModeState.INDETERMINATE -> null
    }
