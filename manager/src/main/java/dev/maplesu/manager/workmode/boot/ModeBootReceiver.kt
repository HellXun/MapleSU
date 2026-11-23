package dev.maplesu.manager.workmode.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.gating.ModeComponentIds
import dev.maplesu.manager.workmode.gating.ModeGate
import dev.maplesu.manager.workmode.gating.ModeGateFormat
import kotlinx.coroutines.runBlocking

/**
 * BOOT_COMPLETED 的模式门控入口（两种模式共用）。
 *
 * 职责（本版）：
 * 1) 等激活（[ModeRuntime.awaitActivated]，超时不改成 LITE）；
 * 2) 按激活模式执行组件启停（[ModeComponentSwitcher]）；
 * 3) 记录 `mode.gating`。
 *
 * **不做**：分区写入、模块脚本执行、切换 journal 推进 —— 那些属于
 * ModeSwitchService / LiteModuleBootService / KSU 内核 init.rc，各自在入口二次门控。
 */
class ModeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread(
            {
                try {
                    handle(appContext)
                } catch (error: Exception) {
                    Log.w("MapleSUMode", "ModeBootReceiver failed (ignored)", error)
                } finally {
                    pendingResult.finish()
                }
            },
            "maplesu-mode-boot",
        ).apply { isDaemon = true }.start()
    }

    private fun handle(context: Context) {
        val mode = runBlocking { ModeRuntime.awaitActivated(ACTIVATION_TIMEOUT_MS) }
        when (mode) {
            WorkMode.LITE -> {
                ModeGate.skip(ModeComponentIds.RECEIVER_MODE_BOOT, ModeGateFormat.Reason.LITE)
            }


            null -> {
                // 激活超时：保持未判定，**不**触碰任何组件、不执行任何专属任务。
                ModeGate.deny(
                    ModeComponentIds.RECEIVER_MODE_BOOT,
                    ModeGateFormat.Reason.UNDETERMINED,
                )
            }
        }
    }

    private companion object {
        const val ACTIVATION_TIMEOUT_MS = 5_000L
    }
}
