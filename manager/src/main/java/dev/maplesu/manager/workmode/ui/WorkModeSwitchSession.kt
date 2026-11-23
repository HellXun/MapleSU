package dev.maplesu.manager.workmode.ui

import dev.maplesu.manager.workmode.service.ClientStatus
import dev.maplesu.manager.workmode.service.SwitchOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local UI coordination; service/journal facts remain authoritative. */
object WorkModeSwitchSession {
    private data class Snapshot(
        val executing: Boolean = false,
        val pending: Boolean = false,
        val switchId: String? = null,
        val failureCode: String = "",
        val failureDetail: String = "",
    )

    @Volatile private var current = Snapshot()
    private val pendingState = MutableStateFlow(false)
    val pendingFlow: StateFlow<Boolean> = pendingState.asStateFlow()
    private var snapshot: Snapshot
        get() = current
        set(value) {
            current = value
            pendingState.value = value.executing || value.pending
        }

    val executing: Boolean get() = snapshot.executing
    val pending: Boolean get() = snapshot.executing || snapshot.pending
    val switchId: String? get() = snapshot.switchId
    val failureCode: String get() = snapshot.failureCode
    val failureDetail: String get() = snapshot.failureDetail

    @Synchronized
    fun begin(switchId: String? = null) {
        snapshot = Snapshot(executing = true, pending = true, switchId = switchId)
    }

    /** Missing/foreign reads never clear an active operation. */
    @Synchronized
    fun observe(status: ClientStatus) {
        val old = snapshot
        if (old.executing && old.switchId != null && old.switchId != status.switchId) return
        snapshot = old.copy(
            switchId = status.switchId.takeIf(String::isNotBlank) ?: old.switchId,
            pending = status.state !in FINISHED_STATES,
            failureCode = old.failureCode.takeIf { old.switchId == status.switchId }.orEmpty(),
            failureDetail = old.failureDetail.takeIf { old.switchId == status.switchId }.orEmpty(),
        )
    }

    @Synchronized
    fun finish(outcome: SwitchOutcome) {
        when (outcome) {
            is SwitchOutcome.AwaitingReboot -> snapshot = Snapshot(pending = true, switchId = outcome.switchId)
            is SwitchOutcome.Verified -> snapshot = Snapshot(switchId = outcome.switchId)
            is SwitchOutcome.Incomplete -> snapshot = Snapshot(
                pending = outcome.status?.let { it.state !in FINISHED_STATES } ?: true,
                switchId = outcome.switchId,
                failureCode = outcome.code,
                failureDetail = outcome.detail,
            )
            is SwitchOutcome.Failed -> snapshot = Snapshot(
                pending = outcome.status?.let { it.state !in FINISHED_STATES } ?: (outcome.switchId != null),
                switchId = outcome.switchId,
                failureCode = outcome.code,
                failureDetail = outcome.detail,
            )
            is SwitchOutcome.Blocked, is SwitchOutcome.ImagesRequired -> snapshot = Snapshot()
        }
    }

    private val FINISHED_STATES = setOf("VERIFIED", "ROLLED_BACK", "FAILED_PRE_WRITE", "CANCELLED")
}
