package com.maplesu.core.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebootPolicyTest {
    private class Fixture(backend: RebootBackend) {
        val calls = mutableListOf<String>()
        var accepted = true
        val dispatcher = RebootDispatcher(
            backend,
            restartUltra = { calls += "ultra-sdk"; accepted },
            runRoot = { calls += it; accepted },
        )
    }

    @Test fun ultraUsesItsSdkAndNeverEntersKsuOrHardwareReboot() {
        val fixture = Fixture(RebootBackend.ULTRA)
        assertTrue(fixture.dispatcher.execute("soft_reboot"))
        assertEquals(listOf("ultra-sdk"), fixture.calls)
    }

    @Test fun directRestartsFrameworkThroughTheExistingPropertyRoute() {
        val fixture = Fixture(RebootBackend.DIRECT)
        assertTrue(fixture.dispatcher.execute("soft_reboot"))
        assertEquals(listOf("/system/bin/setprop ctl.restart zygote64 || " +
            "/system/bin/setprop ctl.restart zygote"), fixture.calls)
    }

    @Test fun rejectedSoftRestartIsReportedWithoutFallingThroughToAnotherRoute() {
        RebootBackend.entries.forEach { backend ->
            val fixture = Fixture(backend).apply { accepted = false }
            assertFalse(fixture.dispatcher.execute("soft_reboot"))
            assertEquals(1, fixture.calls.size)
        }
    }

    @Test fun verifiedUltraIdentityOutranksCachedRunningEvidence() {
        assertEquals(RebootBackend.ULTRA, RebootDispatcher.backend(ultraRunning = true, verifiedUltraIdentity = true))
        assertEquals(RebootBackend.ULTRA, RebootDispatcher.backend(ultraRunning = false, verifiedUltraIdentity = true))
        assertEquals(RebootBackend.DIRECT, RebootDispatcher.backend(ultraRunning = false, verifiedUltraIdentity = false))
    }

    @Test fun plainRebootUsesTheCurrentRootBackendWithoutAnInstallationLookup() {
        RebootBackend.entries.forEach { backend ->
            val fixture = Fixture(backend)
            assertTrue(fixture.dispatcher.execute(""))
            val expected = "/system/bin/svc power reboot || /system/bin/reboot"
            assertEquals(listOf(expected), fixture.calls)
        }
    }

    @Test fun aRejectedPlainRebootDoesNotBindAHistoryTaskOrStartAnotherRoute() {
        val fixture = Fixture(RebootBackend.ULTRA).apply { accepted = false }
        assertFalse(fixture.dispatcher.execute(""))
        assertEquals(listOf("/system/bin/svc power reboot || /system/bin/reboot"), fixture.calls)
    }

    @Test fun hardwareReasonsRemainDistinctAndPreserveTheSelectedReason() {
        listOf("userspace", "recovery", "bootloader", "download", "edl").forEach { reason ->
            val fixture = Fixture(RebootBackend.DIRECT)
            assertTrue(fixture.dispatcher.execute(reason))
            val expected = (if (reason == "recovery") "/system/bin/input keyevent 26 >/dev/null 2>&1; " else "") +
                "/system/bin/svc power reboot $reason || /system/bin/reboot $reason"
            assertEquals(listOf(expected), fixture.calls)
        }
    }

    @Test fun invalidReasonsNeverEnterAnyPrivilegedOperation() {
        listOf("soft", "reboot,soft_reboot", "recovery; reboot", "bootloader\nreboot", "--help").forEach { reason ->
            val fixture = Fixture(RebootBackend.DIRECT)
            assertFalse(fixture.dispatcher.execute(reason))
            assertTrue(fixture.calls.isEmpty())
        }
    }

    @Test fun failedRootCommandDoesNotReportRebootSuccess() {
        val fixture = Fixture(RebootBackend.DIRECT).apply { accepted = false }
        assertFalse(fixture.dispatcher.execute("bootloader"))
        assertEquals(1, fixture.calls.size)
    }

    @Test fun uninstalledAndStillConfirmingHomeDoNotExposeRebootMenu() {
        assertTrue(RebootDispatcher.showMenu(installed = true, confirming = false))
        assertFalse(RebootDispatcher.showMenu(installed = false, confirming = false))
        assertFalse(RebootDispatcher.showMenu(installed = true, confirming = true))
        assertFalse(RebootDispatcher.showMenu(installed = false, confirming = true))
    }
}
