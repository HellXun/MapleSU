package dev.maplesu.manager.workmode.detect.probes

import android.content.Context
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials
import dev.maplesu.manager.runtime.ultra.UltraSdkSession
import dev.maplesu.manager.workmode.detect.UltraEnvironmentPort
import dev.maplesu.manager.workmode.detect.UltraEnvironmentObservation
import dev.maplesu.manager.workmode.detect.UltraState

/**
 * 首帧只读缓存；完整探测在排除 KSU 冲突后核实当前 SDK 身份。
 */
class UltraEnvironmentAdapter internal constructor(
    private val readSnapshot: () -> UltraEnvironmentProbe.Snapshot,
    private val refreshSnapshot: () -> Unit,
    private val hookActive: () -> Boolean,
    private val readManagerOwnerBound: () -> Boolean = { false },
) : UltraEnvironmentPort {
    constructor(context: Context) : this(
        readSnapshot = { UltraEnvironmentProbe.currentSnapshot(context.applicationContext) },
        refreshSnapshot = { UltraEnvironmentProbe.refresh(context.applicationContext, force = false) },
        hookActive = { false },
        // Ultra-only：hook 形态已移除。
        readManagerOwnerBound = {
            val app = context.applicationContext
            fun eligible() = UltraSdkCredentials.read(app) == null &&
                !UltraSdkCredentials.suspended(app) && UltraSdkSession.current(app) == null
            eligible() && UltraRuntimeClient().queryManagerOwnerIdentity().succeeded && eligible()
        },
    )

    override fun state(): UltraState = if (hookActive()) UltraState.UNKNOWN else map(readSnapshot().state)

    override fun observe(allowRefresh: Boolean): UltraEnvironmentObservation = observe(allowRefresh, false)

    override fun observe(allowRefresh: Boolean, allowOwnerQuery: Boolean): UltraEnvironmentObservation {
        if (hookActive()) return UltraEnvironmentObservation(UltraState.UNKNOWN)
        if (allowRefresh) refreshSnapshot()
        val observation = readSnapshot()
        val ownerBound = allowOwnerQuery && !observation.kernelRootVerified && readManagerOwnerBound()
        return UltraEnvironmentObservation(map(observation.state), observation.kernelRootVerified, ownerBound)
    }

    private fun map(state: UltraEnvironmentProbe.State): UltraState = when (state) {
        UltraEnvironmentProbe.State.Running -> UltraState.RUNNING
        UltraEnvironmentProbe.State.NotInstalled -> UltraState.NOT_INSTALLED
        UltraEnvironmentProbe.State.NoKey -> UltraState.NO_KEY
        UltraEnvironmentProbe.State.Fault -> UltraState.FAULT
        UltraEnvironmentProbe.State.Unknown -> UltraState.UNKNOWN
    }
}
