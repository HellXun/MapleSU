package dev.maplesu.manager.workmode.detect

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real detector must not wait for a legacy credential when the live SDK is Running. */
class UltraColdStartDetectorTest {
    private val request = DetectionRequest(DetectionOrigin.AFTER_ROOT_GRANTED, true, true)

    @Test fun verifiedUltraKernelWithUnconfirmedUserspaceSelectsUltraWithoutLegacyOrHook() = runBlocking {
        var refreshed = false
        val result = DefaultModeDetector(
            ultraPort = object : UltraEnvironmentPort {
                override fun state() = UltraState.UNKNOWN
                override fun observe(allowRefresh: Boolean): UltraEnvironmentObservation {
                    refreshed = allowRefresh
                    return UltraEnvironmentObservation(UltraState.UNKNOWN, kernelRootVerified = true)
                }
            },
            liteProbe = LiteCredentialProbe { true to null },
            liteHandshakeProbe = LiteHandshakeProbe { error("Ultra keys must not enter the legacy transport") },
            bootIdSource = BootIdSource { "boot" },
        ).detect(request)
        assertTrue("Phase two must observe the live Ultra channel", refreshed)
        assertEquals(ModeState.MODE_A_ACTIVE, result.state)
        assertEquals(RootChannelKind.LITE_ULTRA, result.rootChannel)
        assertEquals(true, result.trace.single { it.id == "L3" }.ok)
    }

    @Test fun noBootOrAnOldKeyNeverSuppliesTheMissingUltraIdentity() = runBlocking {
        val result = DefaultModeDetector(
            ultraPort = object : UltraEnvironmentPort {
                override fun state() = UltraState.UNKNOWN
                override fun observe(allowRefresh: Boolean) = UltraEnvironmentObservation(UltraState.UNKNOWN)
            },
            bootIdSource = BootIdSource { "boot" },
        ).detect(request)
        assertEquals(ModeState.INDETERMINATE, result.state)
        assertNull(result.rootChannel)
    }

    @Test fun verifiedKernelWithNoUserspaceIsStillUltraButFaultRemainsRecoveryOnly() = runBlocking {
        for (state in listOf(UltraState.NOT_INSTALLED, UltraState.FAULT)) {
            val result = DefaultModeDetector(
                ultraPort = object : UltraEnvironmentPort {
                    override fun state() = state
                    override fun observe(allowRefresh: Boolean) = UltraEnvironmentObservation(state, true)
                },
                bootIdSource = BootIdSource { "boot" },
            ).detect(request)
            assertEquals(ModeState.MODE_A_ACTIVE, result.state)
            assertEquals(if (state == UltraState.FAULT) null else RootChannelKind.LITE_ULTRA, result.rootChannel)
        }
    }

    @Test fun startupAndRespondingKsuNeverRefreshUltraRoot() = runBlocking {
        for (ksu in listOf(KsuFacts(), KsuFacts(true, 100, 7, true))) {
            val requests = if (ksu.responded) listOf(request) else listOf(request.copy(allowRootProbe = false,
                allowLiteExecutor = false))
            for (input in requests) {
                DefaultModeDetector(
                    ksuProbe = KsuKernelProbe { ksu },
                    ultraPort = object : UltraEnvironmentPort {
                        override fun state() = UltraState.UNKNOWN
                        override fun observe(allowRefresh: Boolean): UltraEnvironmentObservation {
                            check(!allowRefresh) { "ULTRA_ROOT_PROBE_BEFORE_CONSENT_OR_ON_KSU" }
                            return UltraEnvironmentObservation(UltraState.UNKNOWN)
                        }
                    },
                ).detect(input)
            }
        }
    }

    @Test fun runningUltraWithLegacyCredentialNeverStartsLegacyHandshake() = runBlocking {
        val calls = mutableListOf<String>()
        val detector = detector(UltraState.RUNNING, calls)
        val result = detector.detect(request)
        assertEquals(ModeState.MODE_A_ACTIVE, result.state)
        assertEquals(DetectionRules.Rule.R6_A_ULTRA, result.rule)
        assertEquals(RootChannelKind.LITE_ULTRA, result.rootChannel)
        assertEquals(listOf("credential", "ultra"), calls)
        assertEquals(RecoveryReadiness.NONE, result.recovery)
        assertEquals("ultraIdentityConfirmedControlPlaneDeferred", result.trace.single { it.id == "M1" }.detail)
        assertNull(result.trace.single { it.id == "L2" }.ok)
    }

    @Test fun faultKeepsUltraWithoutOpeningAnyRootOrLegacyChannel() = runBlocking {
        val calls = mutableListOf<String>()
        val result = detector(UltraState.FAULT, calls).detect(request)
        assertEquals(ModeState.MODE_A_ACTIVE, result.state)
        assertEquals(DetectionRules.Rule.R6_A_ULTRA_SAFE_MODE, result.rule)
        assertNull(result.rootChannel)
        assertEquals(listOf("credential", "ultra"), calls)
        assertEquals(true, result.trace.single { it.id == "L3" }.ok)
    }

    @Test fun uninstalledOrUnknownUltraRetainsLegacyHandshakeAndChannel() = runBlocking {
        for (state in UltraState.entries.filterNot { it == UltraState.RUNNING || it == UltraState.FAULT }) {
            val calls = mutableListOf<String>()
            val result = detector(state, calls).detect(request)
            assertTrue("$state: $calls", "handshake" in calls)
            assertEquals(RootChannelKind.LITE_DIRECT, result.rootChannel)
            assertEquals(DetectionRules.Rule.R5_A_HANDSHAKE, result.rule)
        }
    }

    @Test fun respondingKsuRetainsPriorityWithoutLegacyHandshake() = runBlocking {
        val calls = mutableListOf<String>()
        val result = detector(UltraState.RUNNING, calls,
            KsuFacts(responded = true, version = 100, uapi = 7, isManager = true)).detect(request)
        assertEquals(RootChannelKind.KSU_NATIVE, result.rootChannel)
        assertTrue(calls.none { it == "credential" || it == "handshake" })
    }

    @Test fun startupNeverOpensRootOrAttemptsHandshake() = runBlocking {
        val calls = mutableListOf<String>()
        val result = detector(UltraState.RUNNING, calls).detect(
            DetectionRequest(DetectionOrigin.STARTUP, false, false))
        assertNull(result.rootChannel)
        assertTrue(calls.none { it == "handshake" || it.startsWith("open:") })
    }

    @Test fun failedRootOpenStillRecordsItsActualElapsedTime() = runBlocking {
        var now = 10L
        val result = DefaultModeDetector(
            liteProbe = LiteCredentialProbe { true to null },
            liteHandshakeProbe = LiteHandshakeProbe { true },
            bootIdSource = BootIdSource { "boot" },
            clock = Clock { now },
            rootEvidenceReader = object : RootEvidenceReader {
                override fun open(channel: RootChannelKind): RootEvidenceSession? { now += 450; return null }
            },
        ).detect(request)
        assertEquals(450L, result.trace.single { it.id == "M1" }.elapsedMillis)
        assertEquals(ModeState.MODE_A_ACTIVE, result.state)
    }

    @Test fun confirmedUltraIdentityDefersControlPlaneWithoutReportingRecoveryFailure() = runBlocking {
        val proofs = listOf(
            UltraEnvironmentObservation(UltraState.RUNNING),
            UltraEnvironmentObservation(UltraState.FAULT),
            UltraEnvironmentObservation(UltraState.UNKNOWN, true),
            UltraEnvironmentObservation(UltraState.NOT_INSTALLED, true),
        )
        for (proof in proofs) {
            var rootOpens = 0
            val result = DefaultModeDetector(
                ultraPort = object : UltraEnvironmentPort {
                    override fun state() = proof.state
                    override fun observe(allowRefresh: Boolean) = proof
                },
                bootIdSource = BootIdSource { "boot" },
                rootEvidenceReader = object : RootEvidenceReader {
                    override fun open(channel: RootChannelKind): RootEvidenceSession? {
                        rootOpens++
                        error("Confirmed Ultra identity needs no control-plane batch")
                    }
                },
            ).detect(request)
            assertEquals("$proof", 0, rootOpens)
            assertEquals(ModeState.MODE_A_ACTIVE, result.state)
            assertEquals(when (proof.state) {
                UltraState.RUNNING -> DetectionRules.Rule.R6_A_ULTRA
                UltraState.FAULT -> DetectionRules.Rule.R6_A_ULTRA_SAFE_MODE
                else -> DetectionRules.Rule.R6_A_ULTRA_KERNEL_ONLY
            }, result.rule)
            assertEquals(RecoveryReadiness.NONE, result.recovery)
            assertEquals(if (proof.state == UltraState.FAULT) null else RootChannelKind.LITE_ULTRA, result.rootChannel)
            assertNull(result.trace.single { it.id == "M1" }.ok)
            assertEquals("ultraIdentityConfirmedControlPlaneDeferred", result.trace.single { it.id == "M1" }.detail)
        }
    }

    private fun detector(state: UltraState, calls: MutableList<String>, ksu: KsuFacts = KsuFacts()) =
        DefaultModeDetector(
            ksuProbe = KsuKernelProbe { ksu },
            liteProbe = LiteCredentialProbe { calls += "credential"; true to null },
            liteHandshakeProbe = LiteHandshakeProbe { calls += "handshake"; true },
            ultraPort = UltraEnvironmentPort { calls += "ultra"; state },
            bootIdSource = BootIdSource { "boot" },
            rootEvidenceReader = object : RootEvidenceReader {
                override fun open(channel: RootChannelKind): RootEvidenceSession {
                    calls += "open:$channel"
                    return object : RootEvidenceSession {
                        override fun readControlFile(name: String): String? { calls += "read:$name"; return null }
                        override fun isExecutable(path: String): Boolean { calls += "exec:$path"; return false }
                        override fun isRealDirectory(path: String): Boolean { calls += "dir:$path"; return false }
                        override fun hasAnyEntry(path: String): Boolean { calls += "any:$path"; return false }
                        override fun exists(path: String): Boolean { calls += "exists:$path"; return false }
                        override fun close() { calls += "close" }
                    }
                }
            },
        )
}
