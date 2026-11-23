// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class UltraAuthorizationStateTest {
    private class Port(var packages: List<String>? = emptyList()) : SuAuthPort {
        var error = "OK"
        var persist = true
        var writes = 0
        override fun snapshot() = packages
        override fun add(packageName: String): NativeReceipt {
            writes++
            if (persist && error == "OK") packages = (packages.orEmpty() + packageName).distinct()
            return testReceipt(error)
        }
        override fun remove(packageName: String): NativeReceipt {
            writes++
            if (persist && error == "OK") packages = packages.orEmpty() - packageName
            return testReceipt(error)
        }
    }

    @Test fun okWriteWithoutReadbackMembershipDoesNotGrant() {
        val state = UltraAuthorizationState()
        val port = Port().apply { persist = false }
        assertEquals("READBACK_MISMATCH", state.setGranted(port, "com.example.app", true).error)
        assertFalse(state.snapshot.packages.contains("com.example.app"))
    }

    @Test fun okRemovalWithoutReadbackAbsenceDoesNotRevoke() {
        val port = Port(listOf("com.example.app")).apply { persist = false }
        val state = UltraAuthorizationState()
        assertNull(state.refresh(port))
        assertEquals("READBACK_MISMATCH", state.setGranted(port, "com.example.app", false).error)
        assertEquals(setOf("com.example.app"), state.snapshot.packages)
    }

    @Test fun failedReadbackPreservesPreviousEvidenceWithoutReplayingWrite() {
        val port = Port(listOf("com.example.existing"))
        val state = UltraAuthorizationState()
        state.refresh(port)
        port.persist = false
        port.packages = null
        assertEquals("READBACK_FAILED", state.setGranted(port, "com.example.new", true).error)
        assertEquals(setOf("com.example.existing"), state.snapshot.packages)
        assertEquals(1, port.writes)
    }

    @Test fun readbackReplacesCacheIncludingChangesMadeOutsideManager() {
        val port = Port(listOf("com.example.external"))
        val counts = mutableListOf<Int>()
        val state = UltraAuthorizationState { counts += it }
        assertNull(state.setGranted(port, "com.example.new", true).error)
        assertEquals(setOf("com.example.external", "com.example.new"), state.snapshot.packages)
        assertEquals(listOf(2), counts)
        assertTrue(state.snapshot.loaded)
    }

    @Test fun sdkFailureAndReadFailureDoNotEraseKnownGrants() {
        val port = Port(listOf("com.example.old"))
        val state = UltraAuthorizationState()
        state.refresh(port)
        port.error = "EIO"
        assertEquals("EIO", state.setGranted(port, "com.example.new", true).error)
        port.packages = null
        assertEquals("READ_FAILED", state.refresh(port))
        assertEquals(setOf("com.example.old"), state.snapshot.packages)
    }

    @Test fun committedGrantWithDeliveryFailureReadsBackWithoutRetrying() {
        val stored = Port(listOf("com.example.old"))
        val port = object : SuAuthPort by stored {
            override fun add(packageName: String): NativeReceipt {
                stored.add(packageName)
                return testReceipt("ERR_SU_DELIVERY_INCOMPLETE")
            }
        }
        val state = UltraAuthorizationState()
        assertEquals("ERR_SU_DELIVERY_INCOMPLETE", state.setGranted(port, "com.example.new", true).error)
        assertEquals(setOf("com.example.old", "com.example.new"), state.snapshot.packages)
        assertTrue(state.snapshot.deliveryIncomplete)
        assertEquals(1, stored.writes)
        assertNull(state.refresh(port))
        assertTrue("Reading membership does not prove delivery", state.snapshot.deliveryIncomplete)
        assertNull(state.setGranted(stored, "com.example.new", true).error)
        assertFalse(state.snapshot.deliveryIncomplete)
    }

    @Test fun committedRevocationWithDeliveryFailureShowsStoredMembership() {
        val stored = Port(listOf("com.example.old", "com.example.keep"))
        val port = object : SuAuthPort by stored {
            override fun remove(packageName: String): NativeReceipt {
                stored.remove(packageName)
                return testReceipt("ERR_SU_DELIVERY_INCOMPLETE")
            }
        }
        val state = UltraAuthorizationState()
        assertNull(state.refresh(port))
        assertEquals("ERR_SU_DELIVERY_INCOMPLETE", state.setGranted(port, "com.example.old", false).error)
        assertEquals(setOf("com.example.keep"), state.snapshot.packages)
        assertTrue(state.snapshot.deliveryIncomplete)
        assertEquals(1, stored.writes)
    }

    @Test fun lostCommittedDeliveryReadbackPreservesEvidenceAndFailure() {
        val stored = Port(listOf("com.example.old"))
        val state = UltraAuthorizationState()
        assertNull(state.refresh(stored))
        val port = object : SuAuthPort by stored {
            override fun add(packageName: String): NativeReceipt {
                stored.add(packageName)
                stored.packages = null
                return testReceipt("ERR_SU_DELIVERY_INCOMPLETE")
            }
        }
        assertEquals("ERR_SU_DELIVERY_INCOMPLETE", state.setGranted(port, "com.example.new", true).error)
        assertEquals(setOf("com.example.old"), state.snapshot.packages)
        assertTrue(state.snapshot.deliveryIncomplete)
        assertEquals(1, stored.writes)
    }

    @Test fun alreadyAbsentHostDoesNotRequireAnotherSdkRemoval() {
        val state = UltraAuthorizationState()
        val port = Port(listOf("com.autonavi.minimap", "dev.maplesu.manager"))
        state.refresh(port)
        port.packages = listOf("dev.maplesu.manager")
        port.error = "ERR_MODULE_SU_LIST_NOT_FOUND"

        assertNull(state.setGranted(port, "com.autonavi.minimap", false).error)
        assertEquals("a verified absence must not submit another delete", 0, port.writes)
        assertEquals(setOf("dev.maplesu.manager"), state.snapshot.packages)
        assertTrue(state.snapshot.loaded)
    }

    @Test fun emptyCacheDoesNotSkipRemovingAHostStillInTheSdkSnapshot() {
        val state = UltraAuthorizationState()
        val port = Port(listOf("com.autonavi.minimap", "dev.maplesu.manager"))
        assertNull(state.setGranted(port, "com.autonavi.minimap", false).error)
        assertEquals(1, port.writes)
        assertEquals(setOf("dev.maplesu.manager"), state.snapshot.packages)
    }

    @Test fun unreadableListDoesNotClaimRevocationOrSubmitBlindRemoval() {
        val state = UltraAuthorizationState()
        val port = Port(listOf("com.autonavi.minimap")).apply { persist = false }
        state.refresh(port)
        port.packages = null

        assertEquals("READ_FAILED", state.setGranted(port, "com.autonavi.minimap", false).error)
        assertEquals(0, port.writes)
        assertEquals(setOf("com.autonavi.minimap"), state.snapshot.packages)
    }

    @Test fun actualRemovalFailureRetainsKnownGrantAndIsNotRetried() {
        val state = UltraAuthorizationState()
        val port = Port(listOf("com.autonavi.minimap", "dev.maplesu.manager"))
        state.refresh(port)
        port.error = "EIO"

        assertEquals("EIO", state.setGranted(port, "com.autonavi.minimap", false).error)
        assertEquals(1, port.writes)
        assertTrue("a real SDK failure must remain a failure", "com.autonavi.minimap" in state.snapshot.packages)
    }

    @Test fun retryAfterLostRemovalReadbackDoesNotReplayTheCommittedDelete() {
        val state = UltraAuthorizationState()
        val stored = Port(listOf("com.autonavi.minimap", "dev.maplesu.manager"))
        var loseReadback = false
        val port = object : SuAuthPort by stored {
            override fun remove(packageName: String): NativeReceipt {
                if (packageName !in stored.packages.orEmpty()) return testReceipt("NOT_FOUND")
                val error = stored.remove(packageName).sdkError
                loseReadback = true
                return testReceipt(error)
            }

            override fun snapshot(): List<String>? {
                if (loseReadback) {
                    loseReadback = false
                    return null
                }
                return stored.snapshot()
            }
        }
        assertEquals("READBACK_FAILED", state.setGranted(port, "com.autonavi.minimap", false).error)
        assertNull(state.setGranted(port, "com.autonavi.minimap", false).error)
        assertEquals(1, stored.writes)
        assertEquals(setOf("dev.maplesu.manager"), state.snapshot.packages)
    }

    @Test fun refreshAndWriteReadbackAreOneOrderedTransaction() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val state = UltraAuthorizationState()
        val port = Port()
        val slowRead = object : SuAuthPort by port {
            override fun snapshot(): List<String>? {
                val captured = port.packages
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return captured
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val refresh = pool.submit<String?> { state.refresh(slowRead) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val started = CountDownLatch(1)
            val write = pool.submit<String?> {
                started.countDown()
                state.setGranted(port, "com.example.new", true).error
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            try {
                write.get(150, TimeUnit.MILLISECONDS)
                fail("A mutation interleaved with an unfinished refresh")
            } catch (_: TimeoutException) {
                // The refresh holds the transaction lock until its snapshot is published.
            }
            release.countDown()
            assertNull(refresh.get(5, TimeUnit.SECONDS))
            assertNull(write.get(5, TimeUnit.SECONDS))
            assertEquals(setOf("com.example.new"), state.snapshot.packages)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
