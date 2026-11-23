package com.maplesu.core.ui.viewmodel

import dev.maplesu.manager.workmode.WorkMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperuserStartupLoaderTest {
    @Test
    fun resolvedModesLoadTheirActualBackendWithoutWaiting() = runBlocking {
        for (mode in WorkMode.entries) {
            var waits = 0
            var loaded: WorkMode? = null
            val loader = SuperuserStartupLoader({ mode }, { error("already resolved") })
            assertTrue(loader.load({ waits++ }) { loaded = it })
            assertEquals(0, waits)
            assertEquals(mode, loaded)
        }
    }

    @Test
    fun coldRequestWaitsWithoutCallingBackendThenAutomaticallyLoads() = runBlocking {
        val activated = CompletableDeferred<WorkMode?>()
        val events = mutableListOf<String>()
        val loader = SuperuserStartupLoader({ null }, { activated.await() })
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            assertTrue(loader.load({ events += "waiting" }) { events += "backend:${it.name}" })
        }
        assertEquals(listOf("waiting"), events)
        assertTrue(job.isActive)
        activated.complete(WorkMode.LITE)
        job.join()
        assertEquals(listOf("waiting", "backend:LITE"), events)
    }

    @Test
    fun coldNormalActivationDoesNotFallBackToUltra() = runBlocking {
        var loaded: WorkMode? = null
        val loader = SuperuserStartupLoader({ null }, { WorkMode.LITE })
        assertTrue(loader.load({}) { loaded = it })
        assertEquals(WorkMode.LITE, loaded)
    }

    @Test
    fun unresolvedTimeoutIsNotACompletedEmptyListAndDoesNotReadBackend() = runBlocking {
        var calls = 0
        val loader = SuperuserStartupLoader({ null }, { null })
        assertFalse(loader.load({}) { calls++ })
        assertEquals(0, calls)
    }

    @Test
    fun requestAfterInitialTimeoutCanLoadNormally() = runBlocking {
        var active: WorkMode? = null
        var calls = 0
        val loader = SuperuserStartupLoader({ active }, { null })
        assertFalse(loader.load({}) { calls++ })
        active = WorkMode.LITE
        assertTrue(loader.load({ error("no longer waiting") }) { calls++ })
        assertEquals(1, calls)
    }

    @Test
    fun disposingWhileWaitingCancelsWithoutBackendWork() = runBlocking {
        val activated = CompletableDeferred<WorkMode?>()
        var calls = 0
        val loader = SuperuserStartupLoader({ null }, { activated.await() })
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            loader.load({}) { calls++ }
        }
        job.cancelAndJoin()
        activated.complete(WorkMode.LITE)
        assertTrue(job.isCancelled)
        assertEquals(0, calls)
    }

    @Test
    fun backendFailureIsNotReportedAsSuccessfulLoad() = runBlocking {
        val expected = IllegalStateException("BACKEND_UNAVAILABLE")
        val loader = SuperuserStartupLoader({ WorkMode.LITE }, { null })
        val failure = runCatching { loader.load({}) { throw expected } }.exceptionOrNull()
        assertTrue(failure === expected)
    }
}
