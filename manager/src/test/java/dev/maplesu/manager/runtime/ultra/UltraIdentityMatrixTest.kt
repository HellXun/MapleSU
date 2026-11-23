package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.ultrakernel.UltraKernelCredential
import org.junit.Assert.*
import org.junit.Test

class UltraIdentityMatrixTest {
    private val good = key("Aa1", 4096)
    private val stale = key("Bb2", 8192)
    private fun key(seed: String, offset: Int) = UltraKernelCredential.of(seed.repeat(16))
        .withMetadataDisplacement(offset).copySecret().toString(Charsets.US_ASCII)

    @Test fun everyCandidateSourceRequiresLiveProofAndStopsAtTheFirstVerifiedKey() {
        val candidates = listOf(null, "malformed", stale, good)
        val installs = listOf(emptyList(), listOf(stale), listOf(good), listOf(stale, good),
            listOf("malformed", stale, stale, good, good))
        var cases = 0
        for (local in candidates) for (saved in installs) for (broker in candidates)
            for (backup in candidates) for (profile in listOf("root", "nonroot", "throw-stale", "absent")) {
            cases++
            var current = local
            var writes = 0
            var maintenance = 0
            val reads = mutableListOf<String>()
            val verified = mutableListOf<String>()
            val all = (listOfNotNull(local) + saved + listOfNotNull(broker, backup))
                .filter { it == good || it == stale }.distinct()
            val accepted = if (profile == "root" || profile == "throw-stale") all.firstOrNull { it == good } else null
            val expectedProbes = if (accepted != null) all.take(all.indexOf(accepted) + 1) else all
            val result = UltraIdentityResolution.recover(object : UltraIdentityResolution.Port {
                override fun currentKey() = current
                override fun blocked() = false
                override fun bootId() = "boot"
                override fun rootUid(key: String): Int? {
                    verified += key
                    if (profile == "throw-stale" && key == stale) error("old key read failed")
                    return if (key == good && profile != "absent") { if (profile == "nonroot") 2000 else 0 } else null
                }
                override fun installKeys() = saved.also { reads += "cache" }
                override fun brokerKey() = broker.also { reads += "broker" }
                override fun backupKey() = backup.also { reads += "backup" }
                override fun store(expected: String?, key: String): Boolean {
                    assertEquals(local, expected); current = key; writes++; return true
                }
                override fun accepted(key: String) { assertEquals(good, key); maintenance++ }
            })
            assertEquals("candidate verification order", expectedProbes, verified)
            assertEquals(accepted ?: local, current)
            assertEquals(if (accepted == null) 0 else 1, writes)
            assertEquals(writes, maintenance)
            assertEquals(when {
                accepted == null -> "RECOVERY_ROOT_NOT_VERIFIED"
                accepted == local -> "RECOVERY_LOCAL_KEY_VERIFIED"
                else -> "RECOVERED"
            }, result)
            if (accepted == local && accepted != null) assertTrue(reads.isEmpty())
            if (accepted != null && (accepted == local || accepted in saved)) assertFalse("cached proof stops backup lookups", "broker" in reads || "backup" in reads)
            if (accepted != null && broker == accepted) assertFalse("broker proof stops backup reads", "backup" in reads)
        }
        println("MATRIX key candidates: $cases combinations")
    }

    @Test fun scopePairingAndRootCapabilityUseTheSameCurrentBootAndKey() {
        val keys = listOf(null, good, stale)
        val boots = listOf(null, "", "boot", "old")
        var cases = 0
        for (state in UltraEnvironmentProbe.State.entries) for (proof in listOf(false, true))
            for (currentKey in keys) for (observedKey in keys) for (boot in boots) for (observedBoot in boots)
            for (paused in listOf(false, true)) for (paired in listOf(false, true)) for (hook in listOf(false, true)) {
            cases++
            val valid = currentKey != null && currentKey == observedKey && !boot.isNullOrBlank() && boot == observedBoot && !paused && !hook
            val root = valid && (state == UltraEnvironmentProbe.State.Running || proof && state in
                listOf(UltraEnvironmentProbe.State.Unknown, UltraEnvironmentProbe.State.NotInstalled))
            val snapshot = UltraEnvironmentProbe.scopeSnapshot(UltraEnvironmentProbe.Snapshot(state, kernelRootVerified = proof),
                currentKey, observedKey, boot, observedBoot, paused, hook)
            assertEquals(root, snapshot.rootAvailable)
            var writes = 0
            var maintenance = 0
            val pairing = UltraAutoPair.complete(snapshot, current = { valid }, alreadyPaired = { paired },
                persist = { writes++ }, verifyManagerSu = { true }, maintain = { maintenance++ })
            assertEquals(root, pairing.paired)
            assertEquals(if (root && !paired) 1 else 0, writes)
            assertEquals(if (root) 1 else 0, maintenance)
            if (!valid) assertFalse(snapshot.kernelRootVerified)
        }
        println("MATRIX scope/pairing: $cases combinations")
    }

    @Test fun retryDelaysStopAsSoonAsKernelIdentityIsDecisive() {
        var cases = 0
        for (state in UltraEnvironmentProbe.State.entries) for (proofAt in 1..5)
            for (confirmedAt in 1..5) for (force in listOf(false, true)) {
            cases++
            var reads = 0
            val delays = mutableListOf<Long>()
            val result = UltraEnvironmentProbe.readUntilConfirmed(force,
                read = { reads++; if (reads >= confirmedAt) state else UltraEnvironmentProbe.State.Unknown },
                kernelResolved = { reads >= proofAt }, pause = { delays += it })
            val expected = minOf(4, proofAt, if (state == UltraEnvironmentProbe.State.Unknown) 5 else confirmedAt)
            assertEquals(expected, reads)
            assertEquals(listOf(250L, 500L, 1000L).take(expected - 1), delays)
            assertEquals(if (reads >= confirmedAt) state else UltraEnvironmentProbe.State.Unknown, result)
        }
        println("MATRIX probe budget: $cases combinations")
    }

    @Test fun runningTemporarySdkProofNeverPairsOrMaintainsAHistoricalPermanentKey() {
        val temporaryProof = UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running,
            kernelRootVerified = true)
        for (historicallyPaired in listOf(false, true)) {
            var pairedReads = 0
            var writes = 0
            var maintenance = 0
            val result = UltraAutoPair.complete(temporaryProof,
                // The production guard rechecks temporary ownership before any permanent write.
                current = { false },
                alreadyPaired = { pairedReads++; historicallyPaired },
                persist = { writes++ }, verifyManagerSu = { true }, maintain = { maintenance++ })
            assertFalse(result.paired)
            assertEquals("ULTRA_KEY_CHANGED", result.reason)
            assertEquals(0, pairedReads)
            assertEquals(0, writes)
            assertEquals(0, maintenance)
        }
    }

    @Test fun everyRecoveryPositionChecksScopeAgainBeforeWritingAnyIdentity() {
        var cases = 0
        for (failedKeys in 0..6) for (change in listOf("boot", "key", "pause"))
            for (changeAt in listOf(1, failedKeys + 1)) for (saveOk in listOf(false, true)) {
            cases++
            var boot = "boot"
            var current: String? = null
            var paused = false
            var probes = 0
            var writes = 0
            val candidates = (1..failedKeys).map { key("Cc3", it * 16384) } + good
            val result = UltraIdentityResolution.recover(object : UltraIdentityResolution.Port {
                override fun currentKey() = current
                override fun blocked() = paused
                override fun bootId() = boot
                override fun rootUid(key: String): Int? {
                    if (++probes == changeAt) when (change) {
                        "boot" -> boot = "new-boot"
                        "key" -> current = stale
                        "pause" -> paused = true
                    }
                    return if (key == good) 0 else null
                }
                override fun installKeys() = candidates
                override fun brokerKey(): String? = error("scope change must stop broker lookup")
                override fun backupKey(): String? = error("scope change must stop backup lookup")
                override fun store(expected: String?, key: String): Boolean { writes++; return saveOk }
                override fun accepted(key: String) = fail("scope changed")
            })
            assertEquals("RECOVERY_SCOPE_CHANGED", result)
            assertEquals(changeAt, probes)
            assertEquals(0, writes)
        }
        println("MATRIX recovery races: $cases combinations")
    }

    @Test fun currentHookSupersedesEveryCachedSdkStateWithoutStartingSdkWork() {
        var cases = 0
        var mismatches = 0
        for (state in UltraEnvironmentProbe.State.entries) for (proof in listOf(false, true))
            for (hook in listOf(false, true)) for (refreshAllowed in listOf(false, true)) {
            cases++
            var reads = 0
            var refreshes = 0
            val adapter = dev.maplesu.manager.workmode.detect.probes.UltraEnvironmentAdapter(
                readSnapshot = { reads++; UltraEnvironmentProbe.Snapshot(state, kernelRootVerified = proof) },
                refreshSnapshot = { refreshes++ }, hookActive = { hook })
            val cached = adapter.state()
            val result = adapter.observe(refreshAllowed)
            if (hook) {
                if (cached != dev.maplesu.manager.workmode.detect.UltraState.UNKNOWN ||
                    result.state != dev.maplesu.manager.workmode.detect.UltraState.UNKNOWN ||
                    result.kernelRootVerified || reads != 0 || refreshes != 0) mismatches++
            } else {
                assertEquals(cached, result.state)
                assertEquals(proof, result.kernelRootVerified)
                assertEquals(if (refreshAllowed) 1 else 0, refreshes)
            }
        }
        println("MATRIX Hook/SDK ownership: $cases combinations; $mismatches mismatches")
        assertEquals("a current Hook must replace stale SDK observations and skip SDK refresh", 0, mismatches)
    }
}
