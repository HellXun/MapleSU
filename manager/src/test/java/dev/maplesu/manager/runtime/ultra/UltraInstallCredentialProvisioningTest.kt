package dev.maplesu.manager.runtime.ultra

import com.maplesu.core.ui.screen.install.UltraKernelGenerationPort
import com.maplesu.core.ui.util.UltraKernelSourcedGeneration
import dev.maplesu.manager.ultrakernel.UltraKernelCredential
import dev.maplesu.manager.workmode.ActivationSource
import dev.maplesu.manager.workmode.ModeActivation
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class UltraInstallCredentialProvisioningTest {
    @Before fun setup() = ModeRuntime.resetForTest()
    @After fun cleanup() = ModeRuntime.resetForTest()

    private fun record(facts: DetectionFacts) {
        val decision = DetectionRules.decide(facts)
        ModeRuntime.recordDetection(DetectionResult(decision.state, decision.state.group,
            decision.rule, facts.local.bootId, 0, emptyList(), decision.recovery, null))
    }

    @Test fun externalKsuWithSameOrDifferentUapiCanPrepareFirstUltraIdentity() {
        for (uapi in listOf(6, 7, 100)) {
            record(DetectionFacts(
                local = LocalFacts("boot", Long.MAX_VALUE),
                ksu = KsuFacts(responded = true, version = 32650, uapi = uapi, isManager = false),
            ))
            assertNull("An install grant must not activate a MapleSU backend", ModeRuntime.active)
            val port = UltraKernelSourcedGeneration(
                credentials = { null },
                render = { _, _ -> error("preview must not render or create an identity") },
            )
            assertTrue(port.plan(null, "6.12.69-android16") is
                UltraKernelGenerationPort.GenerationPlan.Available)
            var creates = 0
            val policy = UltraInstallCredentialPolicy({ null }, { null }, { null },
                { creates++; "Ab1".repeat(16).toByteArray() }, UltraInstallCredentials::canProvision)
            assertNotNull("Explicit candidate preparation creates the install identity", policy.forCandidate())
            assertEquals(1, creates)
        }
    }

    @Test fun unresolvedDetectionAndExistingUnboundUltraStillRequireTheirOriginalIdentity() {
        assertFalse(UltraInstallCredentials.canProvision())
        record(DetectionFacts(local = LocalFacts("boot", Long.MAX_VALUE)))
        assertFalse(UltraInstallCredentials.canProvision())
        record(DetectionFacts(local = LocalFacts("boot", Long.MAX_VALUE),
            lite = LiteFacts(credentialPresent = true, handshakeOk = false)))
        assertFalse(UltraInstallCredentials.canProvision())
    }

    @Test fun explicitDirectPreparationDoesNotDependOnRuntimeDetection() {
        for (mode in listOf(null, WorkMode.LITE, WorkMode.LITE)) {
            ModeRuntime.resetForTest()
            if (mode != null) ModeRuntime.overrideForTest(ModeActivation.Active(mode, "boot",
                ActivationSource.TEST_OVERRIDE, ModeState.MODE_A_ACTIVE, 0))
            var creates = 0
            val policy = UltraInstallCredentialPolicy({ null }, { null }, { null },
                { creates++; "Ab1".repeat(16).toByteArray() }, UltraInstallCredentials::canProvision)
            val credential = policy.forCandidate()
            assertTrue(UltraKernelCredential.isValid(credential.copySecret().toString(Charsets.US_ASCII)))
            assertEquals(1, creates)
        }
    }

    @Test fun activeUltraDoesNotUseTheForeignKsuProvisioningException() {
        ModeRuntime.overrideForTest(ModeActivation.Active(WorkMode.LITE, "boot",
            ActivationSource.TEST_OVERRIDE, ModeState.MODE_A_ACTIVE, 0))
        record(DetectionFacts(local = LocalFacts("boot", Long.MAX_VALUE),
            ksu = KsuFacts(responded = true, version = 32650, uapi = 7, isManager = false)))
        assertFalse(UltraInstallCredentials.canProvision())
    }

    @Test fun selected61And510ImagesCanPrepareCredentialsInEveryRuntimeMode() {
        val boot = File.createTempFile("ultra-selected-", ".img")
        try {
            for (mode in listOf(null, WorkMode.LITE, WorkMode.LITE)) {
                ModeRuntime.resetForTest()
                if (mode != null) ModeRuntime.overrideForTest(ModeActivation.Active(mode, "boot",
                    ActivationSource.TEST_OVERRIDE, ModeState.MODE_A_ACTIVE, 0))
                val port = UltraKernelSourcedGeneration(
                    credentials = { null },
                    render = { _, _ -> error("preview must defer credential creation") },
                )
                for (release in listOf("6.1.25-android14", "5.10.226-android12")) {
                    assertTrue("$mode blocked $release", port.plan(boot, release) is
                        UltraKernelGenerationPort.GenerationPlan.Available)
                    var creates = 0
                    val policy = UltraInstallCredentialPolicy({ null }, { null }, { null },
                        { creates++; "Ab1".repeat(16).toByteArray() }, UltraInstallCredentials::canProvision)
                    assertNotNull(policy.forCandidate())
                    assertEquals(1, creates)
                }
            }
        } finally {
            boot.delete()
        }
    }
}
