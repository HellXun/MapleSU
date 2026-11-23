package dev.maplesu.manager.runtime.ultra

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both retained pages must receive SDK transitions independently of their full page probes. */
class UltraSafeModeObservationWiringTest {
    private fun source(name: String): String {
        val path = "src/main/java/$name"
        return listOf(File(path), File("manager/$path")).first { it.isFile }.readText()
    }

    @Test fun bothPagesObserveSdkRecoveryWithoutAnotherTabEntry() {
        for (page in listOf("HomeViewModel", "ModuleViewModel")) {
            val text = source("com/maplesu/core/ui/viewmodel/$page.kt")
            assertTrue("$page must receive Fault -> Running even after a page timeout",
                text.contains("UltraEnvironmentProbe.states.collect"))
        }
    }

    @Test fun homePublicationReconcilesSafetyAfterSlowOrFailedProbes() {
        val text = source("com/maplesu/core/ui/viewmodel/HomeViewModel.kt")
        val publish = text.substringAfter("private fun publishState(").substringBefore("fun refreshEntryFacts(")
        assertTrue("a late page result must not restore an obsolete Fault", publish.contains("withCurrentUltraSafety"))
        val failure = text.substringAfter("private fun resolvedFailure(").substringBefore("private fun publishState(")
        assertTrue("timeouts must still use the current SDK safety observation", failure.contains("withCurrentUltraSafety"))
    }

    @Test fun aRecoveredHomeObservationRequestsRealCapabilityAndModeReads() {
        val text = source("com/maplesu/core/ui/viewmodel/HomeViewModel.kt")
        val observer = text.substringAfter("UltraEnvironmentProbe.states.collect").substringBefore("previous = state")
        assertTrue(observer.contains("state == UltraEnvironmentProbe.State.Running && previous != state"))
        assertTrue(observer.contains("ModeRuntime.active != WorkMode.LITE"))
        assertTrue(observer.contains("ModeBootstrap.retryEnvironmentDetection()"))
        assertTrue(observer.contains("probeAndPublish(rootProbeSettled"))
        assertTrue(observer.contains("recovery?.cancel()"))
        assertTrue(observer.contains("recovery?.isActive != true"))
    }

    @Test fun aRecoveredVisibleModulePageRetriesAnEarlierReadFailure() {
        val text = source("com/maplesu/core/ui/viewmodel/ModuleViewModel.kt")
        val observer = text.substringAfter("UltraEnvironmentProbe.states.collect").substringBefore("previous = state")
        assertTrue(observer.contains("state == UltraEnvironmentProbe.State.Running && previous != state"))
        assertTrue(observer.contains("WorkMode.LITE"))
        assertTrue(observer.contains("isCurrentPage && _uiState.value.loadError != null"))
        assertTrue(observer.contains("fetchModuleList(checkUpdate = false)"))
        assertTrue(observer.contains("fetchJob?.isActive != true"))
    }

    @Test fun blockingAndBackgroundQueriesShareTheBoundedReadRecovery() {
        val text = source("dev/maplesu/manager/runtime/ultra/UltraEnvironmentProbe.kt")
        assertTrue(text.substringAfter("fun requestRefresh(").substringBefore("fun refresh(")
            .contains("probeWithRetry(appContext, force = false)"))
        assertTrue(text.substringAfter("fun refresh(").substringBefore("private fun probeWithRetry(")
            .contains("probeWithRetry(context.applicationContext, force)"))
        assertTrue(text.substringAfter("private fun probeWithRetry(").substringBefore("internal fun readUntilConfirmed(")
            .contains("readUntilConfirmed(force, { probe(context, it) }"))
    }
}
