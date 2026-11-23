package com.maplesu.core.ui.util

import com.topjohnwu.superuser.Shell
import dev.maplesu.manager.installer.InstallCandidateImage
import dev.maplesu.manager.runtime.ultra.UltraOutcome
import dev.maplesu.manager.workmode.service.SwitchOutcome

/*
 * 刷机/安装的结果模型（Ultra 与 Normal 共用）。
 *
 * 原先这三个类型放在 KsuCli.kt 里，导致删除 KSU CLI 会连带打断 Ultra 刷机页。
 * 这里只保留纯数据定义，不依赖 KsuCli 的任何运行时状态。
 */

enum class FlashOutcome {
    /** SDK environment installed and read back; no boot partition was written. */
    ENVIRONMENT_UPDATED,

    /** 分区已写入并回读校验通过，等重启。 */
    WRITTEN,

    /** 只生成了候选镜像（无 root / 无事务 / 内核不匹配），**没有**写盘。 */
    SAVED_ONLY,

    /** 镜像本来就带着这套补丁，无需重复写。 */
    ALREADY_PATCHED,

    /** 写失败但已自动还原原始镜像。 */
    RESTORED,

    /** 写失败且还原也失败：需要救援。 */
    RECOVERY_REQUIRED,

    /** No transaction has started; wait for original images. */
    IMAGES_REQUIRED,

    /** 没进入写入：识别/构建/前置读取失败。 */
    FAILED,
}

/**
 * S5 的三个**粗粒度**阶段。
 *
 * 由 [installBoot] 通过 `onStage` 上报，页面只渲染、不解析自由文本日志
 * （47 报告 R5：修补阶段以前只有中文 `onStdout`，不可判定、不可本地化）。
 */
enum class InstallStage {
    UPGRADING_ENVIRONMENT,

    /** 识别 release、选变体、构建候选、全部摘要校验。 */
    PATCHING,

    /** 事务 prepare → commit → 回读。 */
    WRITING,

    /** 写盘成功，等用户重启。 */
    AWAITING_REBOOT,
}

data class FlashResult(
    val code: Int,
    val err: String,
    val showReboot: Boolean,
    /**
     * 结果态；`null` = 该调用点没有上报（模块安装、还原等旧路径），页面按 [code] 兜底。
     * 只有 [FlashOutcome.WRITTEN] 允许显示"已刷入"。
     */
    val outcome: FlashOutcome? = null,
    val outputPath: String? = null,
    /** The exact candidate used by this installation, not an older workspace image. */
    val bootCandidate: InstallCandidateImage? = null,
    val imagesRequired: SwitchOutcome.ImagesRequired? = null,
    /** Module receipts remain authoritative when an answer is pending, regardless of legacy [code]. */
    val moduleOutcome: dev.maplesu.manager.runtime.ultra.UltraOutcome? = null,
) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}
