package com.maplesu.core.ui.util

import com.maplesu.core.KernelVersion
import com.maplesu.core.ui.screen.home.HomeUiState
import com.maplesu.core.ui.screen.home.SystemInfo
import com.maplesu.core.ui.util.module.LatestVersionInfo
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ManagerUpdateTest {
    private val download = "https://github.com/FengQiuFQ/MapleSU/releases/download/v5.1.10/MapleSU.apk"

    @Test fun usesMapleSuReleaseEndpointAndReadsKsuStyleAsset() {
        val client = response(200, release()) { requestUrl ->
            assertEquals("https://api.github.com/repos/FengQiuFQ/MapleSU/releases/latest", requestUrl)
        }
        assertEquals(LatestVersionInfo(50110, download, "## Changes\nUpdate fixture"), fetchLatestManagerVersion(client))
    }

    @Test fun newerSameAndOlderVersionCodesUseExistingHomeComparison() {
        val latest = parseManagerRelease(release())
        assertTrue(homeState(latest, 50109).hasUpdate)
        assertFalse(homeState(latest, 50110).hasUpdate)
        assertFalse(homeState(latest, 50111).hasUpdate)
    }

    @Test fun acceptsPlainManagerApkAndMapleSuSemanticReleaseTag() {
        assertEquals(50110, parseManagerRelease(release(name = "manager-release.apk")).versionCode)
        assertEquals(50110, parseManagerRelease(release(name = "MapleSU-5.1.10-release.apk", tag = "5.1.10")).versionCode)
    }

    @Test fun ksuAssetVersionCodeTakesPrecedenceOverReleaseTag() {
        assertEquals(50110, parseManagerRelease(release(tag = "v99.9.9")).versionCode)
    }

    @Test fun noReleaseRateLimitServerFailureAndMalformedResponseStaySilent() {
        for (code in listOf(404, 403, 500)) {
            assertEquals(LatestVersionInfo(), fetchLatestManagerVersion(response(code, "{}")))
        }
        assertEquals(LatestVersionInfo(), fetchLatestManagerVersion(response(200, "not json")))
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("offline fixture") }.build()
        assertEquals(LatestVersionInfo(), fetchLatestManagerVersion(client))
    }

    @Test fun releaseWithoutApkOrUrlHasNoUpdate() {
        assertEquals(LatestVersionInfo(), parseManagerRelease(release(name = "kernel.zip")))
        assertEquals(LatestVersionInfo(), parseManagerRelease(release(url = "")))
        assertEquals(LatestVersionInfo(), parseManagerRelease("{}"))
    }

    @Test fun draftAndPrereleaseAreExcludedLikeTheLatestReleaseEndpoint() {
        for (flag in listOf("draft", "prerelease")) {
            val body = JSONObject(release()).put(flag, true).toString()
            assertEquals(LatestVersionInfo(), parseManagerRelease(body))
        }
    }

    @Test fun invalidAndOverflowingVersionTagsDoNotInventAnUpdate() {
        for (tag in listOf("latest", "v5.1", "v5.100.1", "v9999999999999999999.1.1")) {
            assertEquals(LatestVersionInfo(), parseManagerRelease(release(name = "manager-release.apk", tag = tag)))
        }
    }

    private fun response(code: Int, body: String, onRequest: (String) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            onRequest(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body.toResponseBody()).build()
        }.build()

    private fun release(
        name: String = "MapleSU_v5.1.10_50110-release.apk",
        tag: String = "v5.1.10",
        url: String = download,
    ) = JSONObject().put("tag_name", tag).put("body", "## Changes\nUpdate fixture")
        .put("assets", JSONArray().put(JSONObject().put("name", name).put("browser_download_url", url)))
        .toString()

    private fun homeState(latest: LatestVersionInfo, installed: Long) = HomeUiState(
        kernelVersion = KernelVersion(6, 1, 0), ksuVersion = null, lkmMode = null,
        isLkmBundled = false, isManager = false, isManagerPrBuild = false, isKernelPrBuild = false,
        requiresNewKernel = false, requiresNewManager = false, isRootAvailable = false,
        isSafeMode = false, checkUpdateEnabled = false,
        latestVersionInfo = latest, currentManagerVersionCode = installed,
        kernelUAPIVersion = null, managerUAPIVersion = 1,
        systemInfo = SystemInfo("6.1.0", "5.1.9 (50109)", "fixture", "fixture", "Enforcing", 0),
    )
}
