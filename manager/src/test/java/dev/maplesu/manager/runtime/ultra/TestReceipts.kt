// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

/**
 * 测试用的 `SuAuthPort` 回执：`error == "OK"` 视为成功，其余按 SDK 错误名构造失败回执。
 *
 * 生产回执只有 `bridgeFailure` 一个工厂，成功回执由 native bridge 直接给出；测试里需要
 * 一个最小的等价物，避免每个 fake 重复构造 11 个字段。
 */
internal fun testReceipt(error: String = "OK"): NativeReceipt = NativeReceipt(
    schemaVersion = 1,
    backend = "ultra",
    bridgeVersion = 1,
    operation = "su_auth",
    ok = error == "OK",
    sdkError = error,
    sdkErrorCode = if (error == "OK") 0 else 1,
    reason = null,
    data = null,
    raw = "",
)
