package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.TestFixtures
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.control.schema.SnapshotRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 01 §8.1 `ModeDetectionRulesTest`：表驱动覆盖 01 §2.7 的 12 条裁决顺序。
 */
class ModeDetectionRulesTest {
    private val activeKsu = TestFixtures.ksuFacts(responded = true, version = 100, uapi = 7, isManager = true)

    private fun facts(
        ksu: KsuFacts = TestFixtures.ksuFacts(),
        lite: LiteFacts = TestFixtures.liteFacts(),
        root: RootFacts? = null,
        ageMs: Long = Long.MAX_VALUE,
        lastKnownMode: String? = null,
        installerLastTaskId: String? = null,
    ) = DetectionFacts(
        local = TestFixtures.localFacts(
            ageMs = ageMs,
            lastKnownMode = lastKnownMode,
            installerLastTaskId = installerLastTaskId,
        ),
        ksu = ksu,
        lite = lite,
        root = root,
    )

    private fun fixtures(): List<Pair<String, Pair<DetectionFacts, Pair<ModeState, DetectionRules.Rule>>>> = listOf(
        "case1_ksuActive" to (
            facts(activeKsu, root = TestFixtures.rootFacts(ksudExecutable = true, ksuDirHasContent = true)) to
                (ModeState.MODE_B_ACTIVE to DetectionRules.Rule.R1_KSU_ACTIVE)),
        "case2_ksuKsudMissing" to (
            facts(activeKsu, root = TestFixtures.rootFacts(ksudExecutable = false)) to
                (ModeState.MODE_B_KERNEL_ONLY to DetectionRules.Rule.R2_KSU_KERNEL_ONLY)),
        "case3_ksuNoRootProbe" to (
            facts(activeKsu, root = null) to
                (ModeState.MODE_B_KERNEL_ONLY to DetectionRules.Rule.R2_KSU_KERNEL_ONLY)),
        "case4_ksuBranchUapi7NotManager" to (
            facts(TestFixtures.ksuFacts(responded = true, version = 100, uapi = 7, isManager = false)) to
                (ModeState.INDETERMINATE to DetectionRules.Rule.R3_KSU_BRANCH_UNTRUSTED)),
        "case5_ksuForeignUapi6" to (
            facts(TestFixtures.ksuFacts(responded = true, version = 100, uapi = 6, isManager = false)) to
                (ModeState.FOREIGN_SU to DetectionRules.Rule.R4_FOREIGN_KSU)),
        "case6_liteHandshake" to (
            facts(lite = TestFixtures.liteFacts(credentialPresent = true, handshakeOk = true)) to
                (ModeState.MODE_A_ACTIVE to DetectionRules.Rule.R5_A_HANDSHAKE)),
        "case7_proRunning" to (
            facts(lite = TestFixtures.liteFacts(ultraState = UltraState.RUNNING)) to
                (ModeState.MODE_A_ACTIVE to DetectionRules.Rule.R6_A_ULTRA)),
        "case_proFaultKeepsInstalledUltraAfterDataReset" to (
            facts(lite = TestFixtures.liteFacts(ultraState = UltraState.FAULT), ageMs = 0) to
                (ModeState.MODE_A_ACTIVE to DetectionRules.Rule.R6_A_ULTRA_SAFE_MODE)),
        "case8_jailbreakLease" to (
            facts(lite = TestFixtures.liteFacts(jailbreakLeaseValid = true)) to
                (ModeState.MODE_A_ACTIVE to DetectionRules.Rule.R7_A_JAILBREAK)),
        "case9_credentialHandshakeFailed" to (
            facts(lite = TestFixtures.liteFacts(credentialPresent = true, handshakeOk = false)) to
                (ModeState.MODE_A_INSTALLED_UNBOUND to DetectionRules.Rule.R8_A_DISK_STRONG_UNBOUND)),
        "case10_aAuthorizationTable" to (
            facts(root = TestFixtures.rootFacts(aAuthorizationTable = true)) to
                (ModeState.MODE_A_INSTALLED_UNBOUND to DetectionRules.Rule.R8_A_DISK_STRONG_UNBOUND)),
        "case11_foreignRootMarkers" to (
            facts(root = TestFixtures.rootFacts(channel = RootChannelKind.LITE_DIRECT, foreignManagerMarkers = true)) to
                (ModeState.FOREIGN_SU to DetectionRules.Rule.R10_FOREIGN_ROOT)),
        "case12_weakCredentialNoRoot" to (
            facts(lite = TestFixtures.liteFacts(credentialPresent = true)) to
                (ModeState.MODE_A_INSTALLED_UNBOUND to DetectionRules.Rule.R9_A_DISK_WEAK_UNBOUND)),
        "case13_lastKnownModeNoRoot" to (
            facts(lastKnownMode = "B") to
                (ModeState.MODE_A_INSTALLED_UNBOUND to DetectionRules.Rule.R9_A_DISK_WEAK_UNBOUND)),
        "case14_freshInstallWindow" to (
            facts(ageMs = 60_000L) to
                (ModeState.NOT_INSTALLED to DetectionRules.Rule.R11_FRESH_INSTALL)),
        "case15_oldInstallNoEvidence" to (
            facts(ageMs = 7L * 24 * 3600 * 1000) to
                (ModeState.INDETERMINATE to DetectionRules.Rule.R12_INDETERMINATE)),
        "case16_futureUapiForeign" to (
            facts(TestFixtures.ksuFacts(responded = true, version = 100, uapi = 100, isManager = false)) to
                (ModeState.FOREIGN_SU to DetectionRules.Rule.R4_FOREIGN_KSU)),
        "case17_ksuPrecedesADiskEvidence" to (
            facts(
                TestFixtures.ksuFacts(responded = true, version = 100, uapi = 7, isManager = false),
                root = TestFixtures.rootFacts(aAuthorizationTable = true),
            ) to (ModeState.INDETERMINATE to DetectionRules.Rule.R3_KSU_BRANCH_UNTRUSTED)),
        "case18_proNoKeyNoCredential" to (
            facts(lite = TestFixtures.liteFacts(ultraState = UltraState.NO_KEY), ageMs = 30L * 24 * 3600 * 1000) to
                (ModeState.INDETERMINATE to DetectionRules.Rule.R12_INDETERMINATE)),
    )

    @Test
    fun tableDriven_allDecisionOrders() {
        for ((name, fixture) in fixtures()) {
            val (facts, expected) = fixture
            val decision = DetectionRules.decide(facts)
            assertEquals(name, expected.first, decision.state)
            assertEquals(name, expected.second, decision.rule)
        }
    }

    @Test
    fun handshakeNullIsNotFalse() {
        val decision = DetectionRules.decide(
            facts(lite = TestFixtures.liteFacts(credentialPresent = true, handshakeOk = null), ageMs = 30L * 24 * 3600 * 1000),
        )
        // 未尝试 ≠ 失败：不得落到 R8，应落到 R9（弱痕迹无通道）。
        assertEquals(DetectionRules.Rule.R9_A_DISK_WEAK_UNBOUND, decision.rule)
        assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, decision.state)
    }

    @Test
    fun ksuAlwaysPrecedesADiskEvidence() {
        val decision = DetectionRules.decide(
            facts(
                TestFixtures.ksuFacts(responded = true, version = 100, uapi = 7, isManager = false),
                lite = TestFixtures.liteFacts(credentialPresent = true, handshakeOk = true),
                root = TestFixtures.rootFacts(aAuthorizationTable = true),
            ),
        )
        assertEquals(DetectionRules.Rule.R3_KSU_BRANCH_UNTRUSTED, decision.rule)
        assertNull(decision.state.activeMode)
    }

    @Test
    fun neverReturnsLiteForFakeDevice() {
        for ((name, fixture) in fixtures()) {
            val (facts, _) = fixture
            if (facts.ksu.responded) {
                assertNotEquals(name, WorkMode.LITE, DetectionRules.decide(facts).state.activeMode)
            }
        }
    }

    @Test
    fun notInstalledRequiresFreshWindow() {
        val window = 5 * 60 * 1000L
        assertEquals(
            DetectionRules.Rule.R11_FRESH_INSTALL,
            DetectionRules.decide(facts(ageMs = window)).rule,
        )
        assertEquals(
            DetectionRules.Rule.R12_INDETERMINATE,
            DetectionRules.decide(facts(ageMs = window + 1)).rule,
        )
    }

    @Test
    fun defaultSelectedModeTable() {
        assertEquals(WorkMode.LITE, DetectionRules.defaultSelectedMode(ModeState.MODE_B_ACTIVE))
        assertEquals(WorkMode.LITE, DetectionRules.defaultSelectedMode(ModeState.MODE_B_KERNEL_ONLY))
        assertEquals(WorkMode.LITE, DetectionRules.defaultSelectedMode(ModeState.MODE_A_ACTIVE))
        assertEquals(WorkMode.LITE, DetectionRules.defaultSelectedMode(ModeState.MODE_A_INSTALLED_UNBOUND))
        assertNull(DetectionRules.defaultSelectedMode(ModeState.FOREIGN_SU))
        assertNull(DetectionRules.defaultSelectedMode(ModeState.NOT_INSTALLED))
        assertNull(DetectionRules.defaultSelectedMode(ModeState.INDETERMINATE))
    }

    @Test
    fun activeModeTableRespectsUnbound() {
        assertEquals(WorkMode.LITE, ModeState.MODE_B_ACTIVE.activeMode)
        assertEquals(WorkMode.LITE, ModeState.MODE_B_KERNEL_ONLY.activeMode)
        assertEquals(WorkMode.LITE, ModeState.MODE_A_ACTIVE.activeMode)
        assertNull(ModeState.MODE_A_INSTALLED_UNBOUND.activeMode)
        assertNull(ModeState.FOREIGN_SU.activeMode)
        assertNull(ModeState.NOT_INSTALLED.activeMode)
        assertNull(ModeState.INDETERMINATE.activeMode)
    }

    @Test
    fun groupTableHasSixDecisionGroups() {
        assertEquals(6, DetectionGroup.entries.size)
        assertEquals(DetectionGroup.NONE, ModeState.NOT_INSTALLED.group)
        assertEquals(DetectionGroup.NONE, ModeState.INDETERMINATE.group)
    }

    @Test
    fun recoveryReadinessFromControlFacts() {
        val modes = TestFixtures.modesFile(
            current = "B", last = "A", sharedRevision = 7,
            modes = listOf(
                TestFixtures.modeEntry("A", settingsRevision = 3),
                TestFixtures.modeEntry("B", settingsRevision = 9, snapshot = SnapshotRef("rev-1-B", TestFixtures.HASH_64, 10, 1)),
            ),
        )
        val decision = DetectionRules.decide(facts(root = TestFixtures.rootFacts(modesJson = modes)))
        assertTrue(decision.recovery.controlPlaneReachable)
        assertEquals(WorkMode.LITE, decision.recovery.restorableMode)
        assertEquals(9L, decision.recovery.restorableSettingsRevision)
        assertEquals(7L, decision.recovery.restorableSuperuserRevision)
        assertTrue(decision.recovery.hasSnapshot)
        assertTrue(decision.recovery.blockers.isEmpty())
    }

    @Test
    fun recoveryReadinessWithoutRootListsBlocker() {
        val decision = DetectionRules.decide(facts())
        assertTrue(decision.recovery.blockers.contains(RecoveryReadiness.NO_ROOT_CHANNEL))
    }
}
