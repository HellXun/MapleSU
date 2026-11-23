package dev.maplesu.manager.workmode.migration

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 05 §1.5 判定树 / §1.9 阻断条件（T-01…T-10）。 */
class MigrationDetectorTest {

    private fun detect(probe: ExternalRootProbe, inventory: PackageInventory = PackageInventory()) =
        MigrationDetector.detect(probe, inventory)

    @Test
    fun detectsKsuUpstreamFromDataDirAndStockBackup() {
        val probe = Fixtures.probe(
            files = mapOf(MigrationPaths.KSU_DIR to Fixtures.dir(MigrationPaths.KSU_DIR)),
            tools = mapOf("ksud" to Fixtures.tool("/data/adb/ksud", true, "KernelSU 0.9.5 (uapi: 6)")),
            partitions = mapOf(
                "init_boot" to Fixtures.partition("init_boot", setOf(ImageMarker.KSU_KO)),
            ),
            stockBackups = mapOf("init_boot" to Fixtures.backup("init_boot")),
        )
        val info = detect(probe)
        assertEquals(Family.KSU_UPSTREAM, info.family)
        assertTrue(info.supported)
        assertEquals(RestoreStrategy.KSU_STOCK_COPY, info.restoreStrategy)
        assertEquals(setOf("init_boot"), info.patchedPartitions)
        assertEquals(6, info.uapi)
        assertEquals(setOf("init_boot"), info.cleanImagePartitions)
        assertFalse(info.bestEffort)
    }

    @Test
    fun detectsSevenkByDirectoryAndMarker() {
        val probe = Fixtures.probe(
            files = mapOf(MigrationPaths.SEVENK_DIR to Fixtures.dir(MigrationPaths.SEVENK_DIR)),
            partitions = mapOf(
                "init_boot" to Fixtures.partition(
                    "init_boot",
                    setOf(ImageMarker.KSU_KO, ImageMarker.SEVENK_KO),
                ),
            ),
            stockBackups = mapOf("init_boot" to Fixtures.backup("init_boot")),
        )
        assertEquals(Family.KSU_7K, detect(probe).family)
    }

    @Test
    fun detectsSevenkCompatSymlinkAsSevenk() {
        val probe = Fixtures.probe(
            files = mapOf(
                MigrationPaths.KSU_DIR to Fixtures.fileFact(
                    MigrationPaths.KSU_DIR, isSymlink = true, linkTarget = "/data/adb/sevenk",
                ),
            ),
        )
        assertEquals(Family.KSU_7K, detect(probe).family)
    }

    @Test
    fun detectsMagiskFromImageBackupMarker() {
        val probe = Fixtures.probe(
            files = mapOf(MigrationPaths.MAGISK_DIR to Fixtures.dir(MigrationPaths.MAGISK_DIR)),
            tools = mapOf("magisk" to Fixtures.tool("/data/adb/magisk/magisk", true, "30.7:MAGISK")),
            partitions = mapOf(
                "init_boot" to Fixtures.partition("init_boot", setOf(ImageMarker.MAGISK_BACKUP)),
            ),
            stockBackups = mapOf("init_boot" to Fixtures.backup("init_boot")),
        )
        val info = detect(probe)
        assertEquals(Family.MAGISK, info.family)
        assertEquals(RestoreStrategy.MAGISK_STOCK_GZ, info.restoreStrategy)
    }

    @Test
    fun apatchIsKnownButNotSupportedAndNotBlocking() {
        val info = detect(Fixtures.probe(suVersion = "APatch 0.11.2"))
        assertEquals(Family.APATCH, info.family)
        assertFalse(info.supported)
        assertTrue(info.bestEffort)
        assertTrue(info.blockers.isEmpty())
    }

    @Test
    fun unknownManagerIsMigratableBestEffort() {
        val info = detect(Fixtures.probe(suUid = 0, suVersion = "some-su"))
        assertEquals(Family.UNKNOWN, info.family)
        assertTrue(info.bestEffort)
        assertTrue(info.blockers.isEmpty())
    }

    @Test
    fun noRootShortCircuitsWithBlocker() {
        val info = detect(Fixtures.probe(suUid = 2000, device = Fixtures.device(externalRootAvailable = false)))
        assertEquals(Family.UNKNOWN, info.family)
        assertTrue(info.blockers.any { it is Blocker.NoRoot })
    }

    @Test
    fun coexistingKsuAndMagiskEvidenceDegradesToUnknown() {
        val probe = Fixtures.probe(
            files = mapOf(
                MigrationPaths.KSU_DIR to Fixtures.dir(MigrationPaths.KSU_DIR),
                MigrationPaths.MAGISK_DIR to Fixtures.dir(MigrationPaths.MAGISK_DIR),
            ),
            partitions = mapOf(
                "init_boot" to Fixtures.partition("init_boot", setOf(ImageMarker.KSU_KO)),
                "boot" to Fixtures.partition("boot", setOf(ImageMarker.MAGISK_BACKUP)),
            ),
        )
        val info = detect(probe)
        assertEquals(Family.UNKNOWN, info.family)
        assertTrue(info.bestEffort)
        assertEquals(RestoreStrategy.GENERIC_STOCK_SCAN, info.restoreStrategy)
        assertTrue(info.evidence.any { it.kind == EvidenceKind.IMAGE_MARKER })
    }

    @Test
    fun ownManagerPackageIsNeverAnUninstallTarget() {
        val inventory = PackageInventory(
            listOf(
                Fixtures.packageFacts(10123, MigrationPaths.SELF_PACKAGE),
                Fixtures.packageFacts(10124, "me.weishu.kernelsu"),
            ),
        )
        val info = detect(Fixtures.probe(), inventory)
        assertEquals(listOf("me.weishu.kernelsu"), info.managerPackages)
    }

    @Test
    fun ownControlPlaneMarksAlreadyInstalled() {
        val probe = Fixtures.probe(ownControlPlanePresent = true)
        val info = detect(probe)
        assertTrue(info.blockers.any { it is Blocker.OwnManagerInstalled })
        val eligibility = MigrationDetector.eligibility(
            info, probe.device,
            SpaceEstimator.estimate(
                mapOf("boot" to 100L, "init_boot" to 100L),
                emptySet(), setOf("boot"), 0, 0, 1_000L,
            ),
        )
        assertFalse(eligibility.enabled)
    }

    @Test
    fun currentRootButAmbiguousEvidenceStillNoBlockers() {
        val info = detect(Fixtures.probe(suUid = 0))
        assertTrue(
            info.blockers.isEmpty(),
        )
        assertNull(info.uapi)
    }

    @Test
    fun eligibilityBlocksKnownFamilyWithoutCleanImage() {
        val info = ExternalRootInfo(
            family = Family.KSU_UPSTREAM,
            patchedPartitions = setOf("init_boot"),
            supported = true,
            cleanImagePartitions = emptySet(),
        )
        val space = SpaceEstimator.estimate(
            mapOf("boot" to 100L, "init_boot" to 100L),
            setOf("init_boot"), setOf("boot"), 0, 0, 1_000L,
        )
        val eligibility = MigrationDetector.eligibility(info, Fixtures.device(), space)
        assertFalse(eligibility.enabled)
        assertTrue(eligibility.blockers.any { it is Blocker.CleanImageUnavailable })
    }

    @Test
    fun eligibilityAllowsUnknownWithoutCleanImageAsBestEffort() {
        val info = ExternalRootInfo(
            family = Family.UNKNOWN,
            patchedPartitions = setOf("init_boot"),
            bestEffort = true,
            cleanImagePartitions = emptySet(),
        )
        // 空间必须满足 05 §2.7 的 required（含 64MiB 安全余量 + 2×分区），否则会被 NoSpace 阻断。
        val space = SpaceEstimator.estimate(
            mapOf("boot" to 100L, "init_boot" to 100L),
            setOf("init_boot"), setOf("boot"), 0, 0, 256L * 1024 * 1024,
        )
        val eligibility = MigrationDetector.eligibility(info, Fixtures.device(), space)
        // UNKNOWN 管理器无干净镜像不阻断（D16）；只有已知家族才 CleanImageUnavailable。
        assertTrue(eligibility.enabled)
        assertFalse(eligibility.blockers.any { it is Blocker.CleanImageUnavailable })
        assertEquals(WorkMode.LITE, eligibility.recommendedTarget)
    }

    @Test
    fun eligibilityBlocksSpaceAndPendingTransaction() {
        val info = ExternalRootInfo(family = Family.MAGISK, supported = true)
        val space = SpaceEstimator.estimate(
            mapOf("boot" to 100L), emptySet(), setOf("boot"), 0, 0, 1L,
        )
        val eligibility = MigrationDetector.eligibility(
            info,
            Fixtures.device(pendingTransaction = true),
            space,
        )
        assertFalse(eligibility.enabled)
        assertTrue(eligibility.blockers.any { it is Blocker.NoSpace })
        assertTrue(eligibility.blockers.any { it is Blocker.PendingTransaction })
    }

    @Test
    fun recommendsKsuForKsuFamilyWhenTargetAllowed() {
        val info = ExternalRootInfo(
            family = Family.KSU_UPSTREAM,
            patchedPartitions = setOf("init_boot"),
            supported = true,
            cleanImagePartitions = setOf("init_boot"),
        )
        val space = SpaceEstimator.estimate(
            mapOf("boot" to 100L, "init_boot" to 100L),
            setOf("init_boot"), setOf("init_boot"), 0, 0, 1_000L,
        )
        val eligibility = MigrationDetector.eligibility(info, Fixtures.device(), space)
        assertEquals(WorkMode.LITE, eligibility.recommendedTarget)
        assertEquals(MigrationDetector.TARGET_REASON_KSU, eligibility.recommendedReason)
    }

    @Test
    fun unavailableProbeNeverFabricatesEvidence() {
        val probe = UnavailableExternalRootProbeReader.read()
        assertEquals(-1, probe.suUid)
        assertFalse(probe.device.externalRootAvailable)
        assertTrue(detect(probe).blockers.any { it is Blocker.NoRoot })
    }
}
