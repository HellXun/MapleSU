package dev.maplesu.manager.litehidden

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主候选过滤纯策略（JVM）：用户应用 / 非系统 / arm64 / 有 native 库 / 非自身。
 * 规则顺序固定，任一不满足即排除，且不把"没有候选"当成成功。
 */
class HiddenHostPolicyTest {

    private fun facts(
        packageName: String,
        label: String = "",
        isSystem: Boolean = false,
        abis: List<String> = listOf("arm64-v8a"),
        hasNativeLib: Boolean = true,
    ) = HostAppFacts(packageName, label, isSystem, abis, hasNativeLib)

    private fun eligible(facts: HostAppFacts): Boolean =
        HiddenHostPolicy.evaluate(facts) is HostDecision.Eligible

    // 1
    @Test
    fun arm64UserAppWithNativeLibraryIsEligible() {
        val decision = HiddenHostPolicy.evaluate(facts("com.example.chat", "Chat"))
        assertTrue(decision is HostDecision.Eligible)
        val candidate = (decision as HostDecision.Eligible).candidate
        assertEquals("com.example.chat", candidate.packageName)
        assertEquals("Chat", candidate.displayName)
    }

    // 2
    @Test
    fun systemAppsAreExcluded() {
        val decision = HiddenHostPolicy.evaluate(facts("com.android.settings", isSystem = true))
        assertTrue(decision is HostDecision.Rejected)
        assertEquals(HostReject.SYSTEM_APP, (decision as HostDecision.Rejected).reason)
    }

    // 3
    @Test
    fun selfIsExcluded() {
        val decision = HiddenHostPolicy.evaluate(facts("dev.maplesu.manager", "MapleSU"))
        assertEquals(HostReject.SELF, (decision as HostDecision.Rejected).reason)
        // 自定义 self 集合同样生效（调用方注入的当前包名优先）。
        val custom = HiddenHostPolicy.evaluate(facts("com.example.self"), setOf("com.example.self"))
        assertEquals(HostReject.SELF, (custom as HostDecision.Rejected).reason)
    }

    // 4
    @Test
    fun thirtyTwoBitOnlyIsExcluded() {
        val v7a = HiddenHostPolicy.evaluate(facts("com.example.old", abis = listOf("armeabi-v7a")))
        assertEquals(HostReject.NO_ARM64, (v7a as HostDecision.Rejected).reason)
        val none = HiddenHostPolicy.evaluate(facts("com.example.pure", abis = emptyList()))
        assertEquals(HostReject.NO_ARM64, (none as HostDecision.Rejected).reason)
        // arm64 与 32 位 ABI 同时声明 → 仍算可寄生（设备上按 arm64 加载）。
        assertTrue(eligible(facts("com.example.mixed", abis = listOf("armeabi-v7a", "arm64-v8a"))))
    }

    // 5
    @Test
    fun appWithoutNativeLibraryIsExcluded() {
        val decision = HiddenHostPolicy.evaluate(facts("com.example.java", hasNativeLib = false))
        assertEquals(HostReject.NO_NATIVE_LIB, (decision as HostDecision.Rejected).reason)
    }

    // 6
    @Test
    fun invalidPackageNameIsExcludedBeforeAnythingElse() {
        listOf("", "   ", "nodots", "1com.example", "com..example", "com.example-1").forEach { bad ->
            val decision = HiddenHostPolicy.evaluate(
                facts(bad, isSystem = true, hasNativeLib = true),
            )
            assertEquals("包名「$bad」必须先判无效", HostReject.INVALID_PACKAGE, (decision as HostDecision.Rejected).reason)
        }
    }

    // 7
    @Test
    fun rejectionOrderIsSystemBeforeAbiBeforeNative() {
        // 系统 + 32 位 + 无 native：只报第一个命中的原因（系统），顺序稳定。
        val decision = HiddenHostPolicy.evaluate(
            facts("com.android.app", isSystem = true, abis = listOf("armeabi-v7a"), hasNativeLib = false),
        )
        assertEquals(HostReject.SYSTEM_APP, (decision as HostDecision.Rejected).reason)
        // 非系统但 32 位 + 无 native → 报 ABI。
        val abiFirst = HiddenHostPolicy.evaluate(
            facts("com.example.app", abis = listOf("armeabi-v7a"), hasNativeLib = false),
        )
        assertEquals(HostReject.NO_ARM64, (abiFirst as HostDecision.Rejected).reason)
    }

    // 8
    @Test
    fun candidateListDropsEveryRejectedKindAndKeepsEligibleOnes() {
        val all = listOf(
            facts("com.example.good", "Good"),
            facts("com.android.systemui", isSystem = true),
            facts("dev.maplesu.manager", "MapleSU"),
            facts("com.example.legacy", abis = listOf("armeabi-v7a")),
            facts("com.example.nolib", hasNativeLib = false),
            facts("bad_package"),
            facts("com.example.also", "Also"),
        )
        val candidates = HiddenHostPolicy.candidates(all)
        assertEquals(listOf("com.example.also", "com.example.good"), candidates.map { it.packageName })
        assertEquals(5, HiddenHostPolicy.rejected(all).size)
    }

    // 9
    @Test
    fun candidatesAreSortedByDisplayNameAndDeduplicated() {
        val all = listOf(
            facts("com.b.zzz", "Zeta"),
            facts("com.a.alpha", "alpha"),
            facts("com.a.alpha", "alpha"), // 重复包名
            facts("com.c.mid", "Mid"),
        )
        val candidates = HiddenHostPolicy.candidates(all)
        assertEquals(listOf("com.a.alpha", "com.c.mid", "com.b.zzz"), candidates.map { it.packageName })
        assertEquals(3, candidates.size)
    }

    // 10
    @Test
    fun labelFallsBackToPackageNameInsteadOfInventingOne() {
        val blank = HiddenHostPolicy.evaluate(facts("com.example.nolabel", "   "))
        assertEquals("com.example.nolabel", (blank as HostDecision.Eligible).candidate.displayName)
    }

    // 11
    @Test
    fun isEligibleRechecksSelectionAgainstFreshFacts() {
        val fresh = listOf(facts("com.example.good"))
        assertTrue(HiddenHostPolicy.isEligible("com.example.good", fresh))
        assertFalse("列表里没有的包名不算已选", HiddenHostPolicy.isEligible("com.example.other", fresh))
        val nowSystem = listOf(facts("com.example.good", isSystem = true))
        assertFalse("列表过期后不再可寄生", HiddenHostPolicy.isEligible("com.example.good", nowSystem))
    }

    // 12
    @Test
    fun pickerCopyIsShortAndForbiddenWordFree() {
        val copy = listOf(
            HiddenHostPolicy.PICKER_TITLE,
            HiddenHostPolicy.PICKER_SUBTITLE,
            HiddenHostPolicy.RISK_HINT,
        )
        assertEquals("选择寄生应用", HiddenHostPolicy.PICKER_TITLE)
        assertEquals("作为管理入口的启动载体", HiddenHostPolicy.PICKER_SUBTITLE)
        assertEquals("会修改该应用原生库，可解除", HiddenHostPolicy.RISK_HINT)
        copy.forEach { line ->
            assertTrue("「$line」超过 15 字", line.length <= 15)
            listOf("伪卸载", "等级", "痕迹", "演练", "高级").forEach { word ->
                assertFalse("「$line」含禁用词「$word」", line.contains(word))
            }
        }
    }

    // 13
    @Test
    fun pickerEntryVisibleInBothModesButNotWhenAlreadyHidden() {
        assertTrue(HiddenHostPolicy.canOpenHostPicker(WorkMode.LITE, LiteHiddenState.OFF))
        assertTrue("B 模式同一入口", HiddenHostPolicy.canOpenHostPicker(WorkMode.LITE, LiteHiddenState.OFF))
        assertFalse(HiddenHostPolicy.canOpenHostPicker(WorkMode.LITE, LiteHiddenState.ON))
        assertFalse("UNKNOWN 保守按 ON", HiddenHostPolicy.canOpenHostPicker(WorkMode.LITE, LiteHiddenState.UNKNOWN))
    }

    // 14
    @Test
    fun deactivateEntryRequiresHostAndUnhiddenState() {
        assertTrue(HiddenHostPolicy.deactivateVisible("com.example.chat", hidden = false))
        assertFalse("没有宿主就没有解除入口", HiddenHostPolicy.deactivateVisible("", hidden = false))
        assertFalse(HiddenHostPolicy.deactivateVisible(null, hidden = false))
        assertFalse("隐藏生效后入口消失（恢复走 WebUI）", HiddenHostPolicy.deactivateVisible("com.example.chat", hidden = true))
    }

    // 15
    @Test
    fun rejectWireCodesRoundTrip() {
        HostReject.entries.forEach { reject ->
            assertEquals(reject, HostReject.fromWire(reject.wire))
        }
        assertNull(HostReject.fromWire("HOST_UNKNOWN_REASON"))
        assertNull(HostReject.fromWire(null))
    }
}
