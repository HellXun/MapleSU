package dev.maplesu.manager.workmode.gating

import android.util.Log
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import java.util.concurrent.ConcurrentHashMap

/**
 * 启动门控的唯一日志出口。
 *
 * - 只进 logcat（tag = [TAG]），**不进** control/evidence.jsonl（写控制面需要 root，UI 层不该写）。
 * - 采样：同一进程内 `(component, action, reason)` 只记第一次 allow/skip；**deny 每次都记**
 *   （deny 是缺陷信号，重复本身即证据）。
 * - 禁止在 mode.gating 行里输出路径/包名列表/模块 id/token；`k=v` 只允许计数/布尔。
 */
object ModeGate {

    const val TAG = "MapleSUMode"

    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val seenLimit = 64

    fun allow(component: String, reason: ModeGateFormat.Reason = defaultReason(), extras: Map<String, String> = emptyMap()) =
        log(component, ModeGateFormat.Action.ALLOW, reason, extras)

    fun skip(component: String, reason: ModeGateFormat.Reason = defaultReason(), extras: Map<String, String> = emptyMap()) =
        log(component, ModeGateFormat.Action.SKIP, reason, extras)

    fun deny(component: String, reason: ModeGateFormat.Reason = defaultReason(), extras: Map<String, String> = emptyMap()) =
        log(component, ModeGateFormat.Action.DENY, reason, extras)

    fun log(
        component: String,
        action: ModeGateFormat.Action,
        reason: ModeGateFormat.Reason = defaultReason(),
        extras: Map<String, String> = emptyMap(),
    ) {
        runCatching {
            val dedupeKey = "$component|${action.wire}|${reason.wire}"
            if (action != ModeGateFormat.Action.DENY) {
                if (seen.size >= seenLimit) return
                if (!seen.add(dedupeKey)) return
            }
            val line = ModeGateFormat.format(
                ModeGateFormat.Event(
                    component = component,
                    action = action,
                    mode = modeWire(),
                    state = ModeRuntime.latestDetection?.state?.name ?: "UNKNOWN",
                    boot = currentBootId8(),
                    pid = android.os.Process.myPid(),
                    uid = android.os.Process.myUid(),
                    reason = reason,
                    extras = extras,
                )
            )
            when (action) {
                ModeGateFormat.Action.DENY -> Log.w(TAG, line)
                ModeGateFormat.Action.ALLOW -> Log.i(TAG, line)
                ModeGateFormat.Action.SKIP -> Log.i(TAG, line)
            }
        }
    }

    /** 测试用：清空去重表。 */
    internal fun resetSamplingForTest() {
        seen.clear()
    }

    private fun modeWire(): String = when (val mode = ModeRuntime.active) {
        WorkMode.LITE -> "lite"
        null -> "none"
    }

    private fun defaultReason(): ModeGateFormat.Reason = when (ModeRuntime.active) {
        WorkMode.LITE -> ModeGateFormat.Reason.LITE
        null -> ModeGateFormat.Reason.UNDETERMINED
    }

    private fun currentBootId8(): String = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim().lowercase().take(8)
    }.getOrDefault("")
}
