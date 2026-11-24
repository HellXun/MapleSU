package dev.maplesu.manager.workmode.control.schema

import kotlinx.serialization.Serializable

/** `control/installed.json`：辅助事实，不是裁决依据。 */
@Serializable
data class InstalledFile(
    val schema: Int = SCHEMA,
    val updatedAt: Long,
    val bootId: String,
    val managerUid: Int,
    val packageName: String,
    val apkSha256: String,
    val signerSha256: List<String>,
    val transports: Transports,
    val kernel: KernelFacts,
    /** "A_DIRECT" | "A_ULTRA" | "B_KSU"。 */
    val writtenBy: String,
) {
    companion object { const val SCHEMA = 1 }
}

@Serializable
data class Transports(val a1: Boolean, val ultra: Boolean)

@Serializable
data class KernelFacts(
    /** 目前恒 null：patch-engine 无内核可查询标记（05:259 L6）。 */
    val liteMarker: String? = null,
    /** 48B 凭据的 sha256（**不是**凭据本身）。 */
    val liteCredentialSha256: String? = null,
    val ksuUapi: Int? = null,
    val ksuVersion: Int? = null,
)

fun InstalledFile.canonicalized(): InstalledFile = copy(signerSha256 = signerSha256.sorted())
