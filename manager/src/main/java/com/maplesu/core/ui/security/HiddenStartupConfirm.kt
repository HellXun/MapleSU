package com.maplesu.core.ui.security

import android.content.Context
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.litehidden.LiteHiddenState
import dev.maplesu.manager.workmode.storage.ModePaths
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.detect.ModeState
import dev.maplesu.manager.workmode.detect.probes.EnvironmentCheckProduction

/**
 * 隐藏/寄生的**确认窗口**（2026-10-31 用户要求）。
 *
 * 问题：寄生成功后打开 App 会先显示「正在运行」，过一会儿才翻成「未安装」——这段时间
 * App 在**暴露自己仍是管理器**。要求：在「寄生结果」与「运行环境（工作模式/根）」都确认
 * 之前，首页只显示既有的确认提示
 * （`home_status_checking` / `home_status_checking_summary`「正在确认 MapleSU 运行环境，请稍候」）。
 *
 * 三条输入（全部可注入 → 纯 JVM 可测）：
 *  - [transitionPending]：本次进程里隐藏/寄生事务正在执行（写盘标记，进程被杀也不会误判）；
 *  - [hiddenObserved]：进程内是否拿到过**可信**隐藏状态（prefs 种子也算，用于首帧不闪）；
 *  - [hiddenSeedOn] / [realObservation]：上次已知隐藏开着，但本进程还没做**真实 root 读取**。
 *
 * 规则（保守优先）：
 *  1. 事务进行中 → 确认中；
 *  2. 还没拿到任何隐藏状态 → 确认中；
 *  3. 上次已知隐藏 ON，但本进程还没真实读过 → 确认中（先别画「运行」，否则就是自曝）；
 *  4. 其余 → 已确认（正常机器：直接画运行；隐藏机器：由隐藏伪装路径画「未安装」）。
 */
object HiddenStartupConfirm {

    @Volatile private var transitionActive = false

    const val PREF_PENDING = "lite_hidden_transition_pending"
    private const val PREF_NORMAL_STEALTH = "normal_last_stealth"

    /** Normal 的展示种子与 Ultra 隐藏独立；-1 表示首次尚未确认。 */
    fun normalState(context: Context): Int = runCatching {
        context.getSharedPreferences(ModePaths.NEUTRAL_PREFS, Context.MODE_PRIVATE)
            .getInt(PREF_NORMAL_STEALTH, -1)
    }.getOrDefault(-1)

    fun rememberNormalState(context: Context, state: Int) {
        if (state !in 0..1) return
        runCatching {
            val prefs = context.getSharedPreferences(ModePaths.NEUTRAL_PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(PREF_NORMAL_STEALTH, -1) != state) {
                prefs.edit().putInt(PREF_NORMAL_STEALTH, state).apply()
                HiddenFactsSignal.notifyChanged()
            }
        }
    }

    private fun displayMode(context: Context): WorkMode? {
        val snapshot = EnvironmentCheckProduction.store(context).snapshot()
        return ModeRuntime.active ?: snapshot?.mode
    }

    fun confirming(
        transitionPending: Boolean,
        hiddenObserved: Boolean,
        hiddenSeedOn: Boolean,
        realObservation: Boolean,
    ): Boolean = when {
        transitionPending -> true
        !hiddenObserved -> true
        hiddenSeedOn && !realObservation -> true
        else -> false
    }

    /** 读/写「事务进行中」标记（中性 prefs；仅布尔，不含包名/凭据/路径）。 */
    fun isPending(context: Context): Boolean = runCatching {
        context.getSharedPreferences(ModePaths.NEUTRAL_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_PENDING, false)
    }.getOrDefault(false)

    @Synchronized
    fun setPending(context: Context, pending: Boolean) {
        transitionActive = pending
        runCatching {
            val prefs = context.getSharedPreferences(ModePaths.NEUTRAL_PREFS, Context.MODE_PRIVATE)
            if (pending) prefs.edit().putBoolean(PREF_PENDING, true).commit()
            else prefs.edit().remove(PREF_PENDING).commit()
        }
        HiddenFactsSignal.notifyChanged()
    }

    /** 组合判据（生产侧一次算完，UI 只消费结果）。 */
    fun current(context: Context): Boolean = runCatching {
        val snapshot = EnvironmentCheckProduction.store(context).snapshot()
        if (snapshot != null && snapshot.mode == null) return false
        if (false) return normalState(context) == -1
        // 确认未安装时没有 Ultra 隐藏状态可读，不让无 root 设备停在确认窗口。
        if (snapshot == null && ModeRuntime.detectionComplete && ModeRuntime.latestDetection?.state == ModeState.NOT_INSTALLED) {
            return false
        }
        // A protected SDK may not answer the hidden-state read. Keep known hiding/pending
        // transitions, but do not lock a visible recovery environment behind that read.
        if (dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe.isSafeMode(context) &&
            !isPending(context) && !LiteHiddenRuntime.lastKnownOn() &&
            LiteHiddenRuntime.currentState() != LiteHiddenState.ON) return false
        confirming(
            transitionPending = isPending(context),
            hiddenObserved = LiteHiddenRuntime.hasObservation(),
            hiddenSeedOn = LiteHiddenRuntime.lastKnownOn() || LiteHiddenRuntime.currentState() == LiteHiddenState.ON,
            realObservation = LiteHiddenRuntime.hasRealObservation(),
        )
    }.getOrDefault(true)

    fun masking(context: Context): Boolean = runCatching {
        val mode = displayMode(context)
        if (false) normalState(context) == 1
        else masks(LiteHiddenRuntime.currentState(), LiteHiddenRuntime.lastKnownOn(), mode)
    }.getOrDefault(true)

    /**
     * 隐藏是否生效（决定首页是否走「未安装」伪装）。
     *
     * **关键区分**：UNKNOWN（读失败）只有在 last-known 种子是 ON 时才按隐藏处理。
     * 旧实现把 UNKNOWN 一律当隐藏 → 一次启动时 root 读取失败就让整个 App 显示「未安装」，
     * 正常用户会被吓到（2026-10-31 真机复现）。种子 OFF + UNKNOWN = "没证据说隐藏"，
     * 不伪装；种子 ON + UNKNOWN = 很可能隐藏，保守伪装。
     */
    fun masks(state: LiteHiddenState, lastKnownOn: Boolean, active: WorkMode?): Boolean {
        val hidden = state == LiteHiddenState.ON ||
            (state == LiteHiddenState.UNKNOWN && lastKnownOn)
        return hidden && (active == null || active == WorkMode.LITE)
    }

    /**
     * Ultra 通道激活后确认隐藏；失败保留已知结果与有限重试机会。
     * 只在调用方给的 IO 线程执行；成功后停止启动重试，失败最多尝试三次。
     */
    fun ensureConfirmedOnce(context: Context) {
        synchronized(LiteHiddenController) {
            if (ModeRuntime.active != WorkMode.LITE) return
            if (!LiteHiddenRuntime.needsRealRead() && !(isPending(context) && !transitionActive)) return
            LiteHiddenRuntime.markRealReadAttempted()
            val state = runCatching { LiteHiddenController.readState() }.getOrNull()
            synchronized(this) {
                if (!transitionActive && state in setOf(LiteHiddenState.ON, LiteHiddenState.OFF)) {
                    setPending(context, false)
                }
            }
        }
    }

    /** 模式未判定时按「可能隐藏」处理（冷启动首帧 mode=null 不能先把隐藏机器画成运行）。 */
    fun masksWithMode(active: WorkMode?, hiddenOn: Boolean): Boolean =
        hiddenOn && (active == null || active == WorkMode.LITE)
}
