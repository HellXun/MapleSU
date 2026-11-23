package dev.maplesu.manager.workmode

import dev.maplesu.manager.workmode.detect.ModeState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 01 §8.1 `ModeRuntimeTest`：未判定 → 激活一次 + 门控。 */
class ModeRuntimeTest {

    @Before
    fun setUp() = ModeRuntime.resetForTest()

    @After
    fun tearDown() = ModeRuntime.resetForTest()

    private fun armBootId(bootId: String = "boot-1") =
        ModeRuntime.installBootIdProviderForTest { bootId }

    private fun record(state: ModeState) = ModeRuntime.recordDetection(TestFixtures.detection(state))

    @Test
    fun capabilitiesNoneBeforeActivation() {
        assertNull(ModeRuntime.active)
        assertEquals(ModeCapabilities.NONE, ModeRuntime.capabilities)
        assertTrue(ModeRuntime.activation is ModeActivation.Unresolved)
        assertNull(ModeRuntime.latestDetection)
    }

    @Test
    fun activateOnce_sameModeIsIdempotent() {
        armBootId()
        record(ModeState.MODE_A_ACTIVE)
        assertTrue(ModeRuntime.activate(WorkMode.LITE, "boot-1", ActivationSource.BOOT_DETECTION))
        val first = ModeRuntime.activation
        assertTrue(ModeRuntime.activate(WorkMode.LITE, "boot-1", ActivationSource.RESTORE_AFTER_CLEAR))
        assertEquals(first, ModeRuntime.activation)
        assertEquals(ModeCapabilities.forMode(WorkMode.LITE), ModeRuntime.capabilities)
    }

    @Test
    fun activateTwice_differentModeReturnsFalse() {
        armBootId()
        record(ModeState.MODE_A_ACTIVE)
        assertTrue(ModeRuntime.activate(WorkMode.LITE, "boot-1", ActivationSource.BOOT_DETECTION))
        record(ModeState.MODE_B_ACTIVE)
        assertFalse(ModeRuntime.activate(WorkMode.LITE, "boot-1", ActivationSource.PENDING_SWITCH_VERIFIED))
        assertEquals(WorkMode.LITE, ModeRuntime.active)
    }

    @Test
    fun activate_staleBootIdRejected() {
        armBootId("boot-2")
        record(ModeState.MODE_A_ACTIVE)
        assertFalse(ModeRuntime.activate(WorkMode.LITE, "boot-1", ActivationSource.BOOT_DETECTION))
        assertNull(ModeRuntime.active)
    }

    @Test
    fun activate_unknownBootIdRejected() {
        ModeRuntime.installBootIdProviderForTest { "" }
        record(ModeState.MODE_A_ACTIVE)
        assertFalse(ModeRuntime.activate(WorkMode.LITE, "", ActivationSource.BOOT_DETECTION))
        assertNull(ModeRuntime.active)
    }

    @Test
    fun activate_noDetectionRejected() {
        armBootId()
        assertFalse(ModeRuntime.activate(WorkMode.LITE, "boot-1", ActivationSource.BOOT_DETECTION))
        assertNull(ModeRuntime.active)
    }

    @Test
    fun activate_detectionMismatchRejected() {
        armBootId()
        record(ModeState.MODE_B_ACTIVE)
        assertFalse(ModeRuntime.activate(WorkMode.LITE, "boot-1", ActivationSource.BOOT_DETECTION))
        assertNull(ModeRuntime.active)
    }

    @Test
    fun runIfLite_doesNothingWhenUnresolved() {
        assertNull(ModeRuntime.runIfLite { 1 })
        assertNull(ModeRuntime.runIfKsu { 1 })
    }

    @Test
    fun runIfGatesOnActivatedMode() {
        armBootId()
        record(ModeState.MODE_B_ACTIVE)
        assertTrue(ModeRuntime.activate(WorkMode.LITE, "boot-1", ActivationSource.BOOT_DETECTION))
        assertEquals(2, ModeRuntime.runIfKsu { 2 })
        assertNull(ModeRuntime.runIfLite { 1 })
    }

    @Test
    fun requireMode_throwsWhenUnresolved() {
        try {
            ModeRuntime.requireMode(WorkMode.LITE)
            fail("requireMode must throw when unresolved")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("MODE_NOT_RESOLVED"))
        }
    }

    @Test
    fun requireMode_acceptsExpectedMode() {
        armBootId()
        record(ModeState.MODE_A_ACTIVE)
        assertTrue(ModeRuntime.activate(WorkMode.LITE, "boot-1", ActivationSource.BOOT_DETECTION))
        ModeRuntime.requireMode(WorkMode.LITE)
        try {
            ModeRuntime.requireMode(WorkMode.LITE)
            fail("requireMode must throw for mismatched mode")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun awaitActivated_timeoutReturnsNull() = runBlocking {
        assertNull(ModeRuntime.awaitActivated(50))
        assertNull(ModeRuntime.awaitActivated(0))
    }

    @Test
    fun awaitActivated_returnsModeAfterActivation() = runBlocking {
        armBootId()
        record(ModeState.MODE_A_ACTIVE)
        assertTrue(ModeRuntime.activate(WorkMode.LITE, "boot-1", ActivationSource.BOOT_DETECTION))
        assertEquals(WorkMode.LITE, ModeRuntime.awaitActivated(1_000))
    }

    @Test
    fun testOverride_onlyInDebug() {
        assertTrue(ModeRuntime.testOverrideAllowed(debugBuild = true))
        assertFalse(ModeRuntime.testOverrideAllowed(debugBuild = false))
    }

    @Test
    fun overrideForTest_setsCapabilitiesAndActivation() {
        ModeRuntime.overrideForTest(
            ModeActivation.Active(
                mode = WorkMode.LITE,
                bootId = "boot-1",
                source = ActivationSource.TEST_OVERRIDE,
                detectedState = ModeState.MODE_B_ACTIVE,
                activatedAtMillis = 5L,
            ),
        )
        assertEquals(WorkMode.LITE, ModeRuntime.active)
        assertEquals(ModeCapabilities.forMode(WorkMode.LITE), ModeRuntime.capabilities)
        assertEquals(ActivationSource.TEST_OVERRIDE, (ModeRuntime.activation as ModeActivation.Active).source)
    }

    @Test
    fun recordDetectionUpdatesLatestDetection() {
        assertNull(ModeRuntime.latestDetection)
        record(ModeState.INDETERMINATE)
        assertEquals(ModeState.INDETERMINATE, ModeRuntime.latestDetection?.state)
    }
}
