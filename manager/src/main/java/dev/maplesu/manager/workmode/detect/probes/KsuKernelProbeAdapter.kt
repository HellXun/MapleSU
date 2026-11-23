package dev.maplesu.manager.workmode.detect.probes

import dev.maplesu.manager.workmode.detect.KsuFacts
import dev.maplesu.manager.workmode.detect.KsuKernelProbe

/**
 * KSU 内核探针适配器。
 *
 * 上游经同树 JNI（`manager/src/ksu/java/.../KsuNatives.kt`）读取 Normal 后端的内核事实。
 * 本分支只发布 Ultra，KSU 后端源码与 `libmaplesu_ksu.so` 已整体删除，因此这里始终返回
 * [KsuNativeSnapshot.UNAVAILABLE]——探测层据此把 Normal 相关事实记为 unknown，判定的
 * 结论只会落到 Ultra。**不加载任何 JNI、不打开任何设备节点。**
 */
object KsuKernelProbeAdapter : KsuKernelProbe {
    override fun probe(): KsuFacts {
        val snapshot = currentSnapshot()
        check(snapshot.available) { "KSU_PROBE_UNAVAILABLE" }
        return KsuProbeMapping.from(snapshot)
    }

    internal fun currentSnapshot(): KsuNativeSnapshot = KsuNativeSnapshot.UNAVAILABLE
}

/** native 侧只读快照（与 libsu/JNI 解耦，便于主机测试）。 */
data class KsuNativeSnapshot(
    val available: Boolean,
    val version: Int,
    val kernelUapi: Int,
    val isManager: Boolean,
    val isLkm: Boolean,
) {
    companion object {
        val UNAVAILABLE = KsuNativeSnapshot(
            available = false,
            version = -1,
            kernelUapi = -1,
            isManager = false,
            isLkm = false,
        )
    }
}

/**
 * 快照 → [KsuFacts] 的**纯映射**：
 * - native 不可用 → 全 unknown（`KsuFacts()` 默认值，version=-1）；
 * - 可用但版本 <= 0 → `responded=false`（内核未响应 supercall）；
 * - 可用且版本 > 0 → `responded=true`，uapi/isManager/isLkm 原样透传。
 */
object KsuProbeMapping {
    /** Match full detection: a responding foreign KSU kernel is not MapleSU_Normal. */
    fun normalStatus(snapshot: KsuNativeSnapshot): Boolean? {
        if (!snapshot.available) return null
        return snapshot.version > 0 && snapshot.isManager
    }

    fun from(snapshot: KsuNativeSnapshot): KsuFacts {
        if (!snapshot.available) return KsuFacts()
        return KsuFacts(
            responded = snapshot.version > 0,
            version = snapshot.version,
            uapi = snapshot.kernelUapi,
            isManager = snapshot.isManager,
            isLkm = snapshot.isLkm,
        )
    }
}
