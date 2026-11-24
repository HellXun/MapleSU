package com.maplesu.core.ui.util

import com.maplesu.core.ui.screen.install.UltraKernelGenerationPort
import com.maplesu.core.ui.screen.install.InstallBootFlow
import dev.maplesu.manager.ultrakernel.UltraKernelCredential
import dev.maplesu.manager.ultrakernel.UltraKernelGenerateCodes
import dev.maplesu.manager.ultrakernel.UltraKernelGenerateException
import dev.maplesu.manager.ultrakernel.UltraKernelPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 生成端口的生产实现（54 §1/§2）—— 纯 JVM，不需要 Android、设备或 36 MB 内核。
 *
 * 被钉住的四件事：
 *  1. 有凭据 + 有镜像 → 真的调渲染器，并回报**现算**的修补后内核摘要与记录数；
 *  2. 文件预览缺少凭据时延后生成，确认修补后才持久化凭据；
 *  3. 渲染器拒绝（6.1 的 `3/18`）→ 稳定码原样带回来，不被吞成"未接线"或"未知错误"；
 *  4. "当前分区"没有镜像 → 明确的 `deferred`，而不是编一个记录数或先斩后奏地渲染。
 */
class UltraKernelGenerationTest {

    private val release = "6.1.25-android14-11-o-g8b7f8f0a5e4d-ab1234567"
    private val credential = UltraKernelCredential.of("Aa1".repeat(16))

    /**
     * A minimal, *valid* generated plan: one record whose replacement carries the credential and
     * the `/data/<key16>/init` path, applied to a kernel that carries the release banner.
     *
     * It is not a real k22 render (no kallsyms, no recipe) - it only has to satisfy every invariant
     * [UltraKernelPlan] enforces, so the port can be tested against a real plan object.
     */
    private fun fixturePlan(sites: Int = 18, matching: Int = sites, kernelRelease: String = release): UltraKernelPlan {
        val secret = credential.copySecret()
        val path = credential.initPath.toByteArray(Charsets.US_ASCII)
        val replacement = ByteArray(secret.size + path.size + 8)
        secret.copyInto(replacement, 0)
        path.copyInto(replacement, secret.size)
        secret.fill(0)
        val original = ByteArray(replacement.size) { 0x2A }
        val offset = 0x200
        val kernel = ByteArray(offset + original.size + 16)
        "Linux version $kernelRelease\n".toByteArray(Charsets.US_ASCII).copyInto(kernel, 0x40)
        original.copyInto(kernel, offset)
        val record = UltraKernelPlan.PatchRecord("die+0x0", offset.toLong(), original, replacement)
        val plan = UltraKernelPlan.fromRecords(
            kernelRelease = kernelRelease,
            records = listOf(record),
            credential = credential,
            origin = UltraKernelPlan.Origin.GENERATED,
            evidence = UltraKernelPlan.SiteEvidence(matching, sites, emptyList(), emptyList()),
        )
        return plan.withDerived(plan.apply(kernel).derived)
    }

    /** The port only renders for a boot image that exists; a temp file keeps that honest. */
    /** Minimal valid boot image v3: ANDROID! magic, headerSize=1580, kernelSize=32. */
    private fun withBootFile(block: (File) -> Unit) {
        val file = File.createTempFile("ultra-kernel-boot-", ".img")
        try {
            val page = 4096
            val image = ByteArray(page + 32)
            "ANDROID!".toByteArray(Charsets.US_ASCII).copyInto(image, 0)
            image[8] = 32
            image[20] = 0x2C
            image[21] = 0x06
            image[40] = 3
            file.writeBytes(image)
            block(file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aPairedDeviceRendersThePlanAndReportsTheDerivedDigest() {
        val plan = fixturePlan()
        var rendered = 0
        var seenBoot: File? = null
        val port = UltraKernelSourcedGeneration(
            credentials = { credential },
            render = { boot, _ ->
                rendered++
                seenBoot = boot
                plan
            },
        )
        withBootFile { boot ->
            val answer = port.plan(boot, release)
            assertTrue(answer is UltraKernelGenerationPort.GenerationPlan.Available)
            answer as UltraKernelGenerationPort.GenerationPlan.Available
            // 现算的修补后内核摘要，不是"钉死的常量"，也不是含口令的 spec 文本摘要。
            assertEquals(plan.derived?.patchedKernelSha256, answer.specSha256)
            assertEquals(1, answer.patches)
            assertFalse(answer.deferred)
            assertSame(boot, seenBoot)
        }
        assertEquals(1, rendered)
    }

    @Test
    fun selectedImageWithoutACredentialDefersRenderingUntilConfirmation() {
        var rendered = 0
        val port = UltraKernelSourcedGeneration(
            credentials = { null },
            render = { _, _ ->
                rendered++
                error("the renderer must not run without a credential")
            },
        )
        withBootFile { boot ->
            val answer = port.plan(boot, release)
            assertTrue(answer is UltraKernelGenerationPort.GenerationPlan.Available)
            answer as UltraKernelGenerationPort.GenerationPlan.Available
            assertTrue(answer.deferred)
            assertEquals(0, answer.patches)
            assertEquals("", answer.specSha256)
        }
        assertEquals(0, rendered)
    }

    @Test
    fun selected61And510ImagesRemainSelectableWithoutPairingOrDeviceDetection() {
        val previous = InstallBootFlow.generation
        try {
            InstallBootFlow.generation = UltraKernelSourcedGeneration(
                credentials = { null },
                render = { _, _ -> error("preview must defer identity creation") },
            )
            withBootFile { boot ->
                for (imageRelease in listOf(release, "5.10.226-android12-9-o-gb4f7990ee2d5")) {
                    val profile = InstallBootFlow.fromFacts(
                        "boot.img", "6.6.89-android15", UltraKernelFacts(imageRelease), boot,
                    )
                    assertTrue("$imageRelease was blocked by ${profile.stableCode}", profile.selectable)
                    assertEquals(InstallBootFlow.GENERATED_VARIANT_ID, profile.target?.id)
                    assertEquals("", profile.stableCode)
                }
            }
        } finally {
            InstallBootFlow.generation = previous
        }
    }

    @Test
    fun aSiteCoverageRefusalIsReportedWithItsOwnCode() {
        val port = UltraKernelSourcedGeneration(
            credentials = { credential },
            // 6.1.25 的真实形状：地址能重算，但 15 条站点的原始字节不是参考构建的。
            render = { _, _ ->
                throw UltraKernelGenerateException(
                    UltraKernelGenerateCodes.SITE_COVERAGE,
                    "3/18 differing=die+0x0,free_initmem+0x0",
                )
            },
        )
        withBootFile { boot ->
            val answer = port.plan(boot, release)
            answer as UltraKernelGenerationPort.GenerationPlan.Unavailable
            assertEquals(UltraKernelGenerateCodes.SITE_COVERAGE, answer.code)
            assertTrue(answer.code.startsWith("ULTRA_KERNEL_GENERATE_SITE"))
            assertEquals("3/18 differing=die+0x0,free_initmem+0x0", answer.detail)
        }
    }

    @Test
    fun anUnexpectedRendererFailureStillCarriesAStableCode() {
        val port = UltraKernelSourcedGeneration(
            credentials = { credential },
            render = { _, _ -> throw IllegalStateException("ULTRA_KERNEL_GENERATE_KALLSYMS:CANDIDATES:2") },
        )
        withBootFile { boot ->
            val answer = port.plan(boot, release)
            answer as UltraKernelGenerationPort.GenerationPlan.Unavailable
            assertEquals("ULTRA_KERNEL_GENERATE_KALLSYMS", answer.code)
        }
    }

    @Test
    fun theCurrentPartitionSourceIsDeferredInsteadOfRendered() {
        var rendered = 0
        val port = UltraKernelSourcedGeneration(
            credentials = { credential },
            render = { _, _ ->
                rendered++
                error("there is no image to render for the current partition")
            },
        )
        val answer = port.plan(null, release)
        answer as UltraKernelGenerationPort.GenerationPlan.Available
        assertTrue(answer.deferred)
        assertEquals(0, answer.patches)
        assertEquals("", answer.specSha256)
        assertEquals(0, rendered)
        // 一个不存在的路径同样只能"延后"：不能把缺失的镜像当成空镜像去渲染。
        val missing = port.plan(File("does-not-exist.img"), release)
        assertTrue((missing as UltraKernelGenerationPort.GenerationPlan.Available).deferred)
        assertEquals(0, rendered)
    }

    @Test
    fun theCurrentPartitionPreparesItsIdentityAfterConfirmation() {
        val port = UltraKernelSourcedGeneration(credentials = { null }, render = { _, _ -> error("no") })
        val answer = port.plan(null, release)
        answer as UltraKernelGenerationPort.GenerationPlan.Available
        assertTrue(answer.deferred)
        assertEquals(0, answer.patches)
    }
    @Test
    fun confirmedNonUltraPreviewDefersProvisioningWithoutCreatingOrRenderingAKey() {
        var rendered = 0
        var reads = 0
        val port = UltraKernelSourcedGeneration(
            credentials = { reads++; null },
            render = { _, _ -> rendered++; error("preview must not provision") },
        )
        withBootFile { boot ->
            val answer = port.plan(boot, release) as UltraKernelGenerationPort.GenerationPlan.Available
            assertTrue(answer.deferred)
            assertEquals(0, answer.patches)
            assertEquals("", answer.specSha256)
        }
        assertEquals(1, reads)
        assertEquals(0, rendered)
    }

    @Test
    fun a510FilePreviewNeedsNoPairingAndRendersWhenACredentialBecomesAvailable() {
        val release510 = "5.10.226-android12-9-o-gb4f7990ee2d5"
        val plan = fixturePlan(kernelRelease = release510)
        var currentCredential: UltraKernelCredential? = null
        var rendered = 0
        val port = UltraKernelSourcedGeneration(
            credentials = { currentCredential },
            render = { _, actual -> assertSame(credential, actual); rendered++; plan },
        )
        withBootFile { boot ->
            val missing = port.plan(boot, release510) as UltraKernelGenerationPort.GenerationPlan.Available
            assertTrue(missing.deferred)
            assertEquals(0, rendered)
            val deferred = port.plan(boot, release510) as UltraKernelGenerationPort.GenerationPlan.Available
            assertTrue(deferred.deferred)
            assertEquals(0, rendered)
            currentCredential = credential
            val paired = port.plan(boot, release510) as UltraKernelGenerationPort.GenerationPlan.Available
            assertFalse(paired.deferred)
            assertEquals(plan.derived?.patchedKernelSha256, paired.specSha256)
            assertEquals(1, rendered)
        }
    }

}
