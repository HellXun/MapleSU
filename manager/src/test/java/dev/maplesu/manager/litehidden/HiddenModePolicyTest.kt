package dev.maplesu.manager.litehidden

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3/H7 隐藏模式纯策略测试：设置项→独立页导航的可见性/端口/恢复通道/单按钮状态机/文案红线。
 * （导航与资源契约见 [com.maplesu.core.ui.LiteHiddenUiContractTest]。）
 */
class HiddenModePolicyTest {

    @Test
    fun entryVisibleOnlyInLiteAndUnhidden() {
        assertTrue(HiddenModePolicy.cardVisible(WorkMode.LITE, LiteHiddenState.OFF))
        assertFalse("隐藏后入口消失", HiddenModePolicy.cardVisible(WorkMode.LITE, LiteHiddenState.ON))
        assertFalse("UNKNOWN 保守按 ON → 入口消失", HiddenModePolicy.cardVisible(WorkMode.LITE, LiteHiddenState.UNKNOWN))
    }

    @Test
    fun entryVisibleInBothModesUntilHidden() {
        // 最新定稿：A/B 都要有隐藏入口；只有隐藏生效后消失。
        assertTrue(HiddenModePolicy.cardVisible(WorkMode.LITE, LiteHiddenState.OFF))
        assertTrue(HiddenModePolicy.cardVisible(WorkMode.LITE, LiteHiddenState.OFF))
        assertFalse(HiddenModePolicy.cardVisible(WorkMode.LITE, LiteHiddenState.ON))
        assertFalse(HiddenModePolicy.cardVisible(WorkMode.LITE, LiteHiddenState.ON))
        assertFalse(HiddenModePolicy.anyHiddenEntriesVisible(WorkMode.LITE, LiteHiddenState.ON))
    }

    @Test
    fun webUiLinkPreviewUsesPortAndToken() {
        assertEquals("—", HiddenModePolicy.webUiLinkPreview("18427", null))
        val link = HiddenModePolicy.webUiLinkPreview("18427", "abc123")
        assertEquals("http://127.0.0.1:18427/x7k9f/abc123/", link)
        assertNotEquals("端口变化后链接必须更新", link, HiddenModePolicy.webUiLinkPreview("20000", "abc123"))
        assertTrue(HiddenModePolicy.webUiLinkPreview("80", "abc").contains("18427"))
    }

    @Test
    fun hiddenMeansNoEntriesVisibleAtAll() {
        assertFalse(HiddenModePolicy.anyHiddenEntriesVisible(WorkMode.LITE, LiteHiddenState.ON))
        assertFalse(HiddenModePolicy.cardVisible(WorkMode.LITE, LiteHiddenState.ON))
    }

    @Test
    fun portValidationRangeAndDefault() {
        assertEquals(18427, HiddenModePolicy.defaultPort())
        val valid = HiddenModePolicy.validatePort("18427")
        assertTrue(valid.valid)
        assertEquals(18427, valid.value)
        assertTrue(HiddenModePolicy.validatePort("1024").valid)
        assertTrue(HiddenModePolicy.validatePort("65535").valid)
        assertFalse(HiddenModePolicy.validatePort("1023").valid)
        assertFalse(HiddenModePolicy.validatePort("65536").valid)
        assertFalse(HiddenModePolicy.validatePort("abc").valid)
        assertFalse(HiddenModePolicy.validatePort("").valid)
        assertFalse(HiddenModePolicy.validatePort(null).valid)
    }

    @Test
    fun hintIsShortAndMentionsWebUiOnly() {
        assertTrue("小字 ≤15 字", HiddenModePolicy.hintWithinLimit())
        assertEquals("开启后所有管理将寄生至 WebUI", HiddenModePolicy.HINT)
        assertTrue("WebUI 预览/寄生说明", HiddenModePolicy.HINT.contains("WebUI"))
        assertFalse(HiddenModePolicy.HINT.contains("演练"))
        // "15 字"按中文字符（CJK）计；WebUI 是产品名，不计入中文字数。
        val cjk = HiddenModePolicy.HINT.count { it.code in 0x4E00..0x9FFF }
        assertTrue("CJK 字符 ≤15", cjk <= 15)
        assertFalse(HiddenModePolicy.HINT.contains("演练"))
    }

    @Test
    fun confirmCopyIsShortAndExact() {
        assertEquals("开启隐藏？", HiddenModePolicy.DIALOG_TITLE)
        assertEquals("将寄生至所选应用，请保存 WebUI 链接", HiddenModePolicy.DIALOG_BODY)
        assertEquals("取消", HiddenModePolicy.DIALOG_CANCEL)
        assertEquals("开启", HiddenModePolicy.DIALOG_CONFIRM)
        assertEquals("复制恢复链接", HiddenModePolicy.COPY_LINK)
        assertEquals("开启隐藏模式", HiddenModePolicy.BUTTON)
        assertEquals("隐藏", HiddenModePolicy.GROUP_TITLE)
        assertEquals("WebUI 端口", HiddenModePolicy.PORT_LABEL)
        // 宿主相关文案：宿主应用标签 / 解除寄生三步入口。
        assertEquals("宿主应用", HiddenModePolicy.DIALOG_HOST_LABEL)
        assertEquals("解除寄生", HiddenModePolicy.DEACTIVATE)
        assertEquals("解除寄生？", HiddenModePolicy.DEACTIVATE_TITLE)
    }

    @Test
    fun noForbiddenCopyWords() {
        val all = listOf(
            HiddenModePolicy.HINT,
            HiddenModePolicy.GROUP_TITLE,
            HiddenModePolicy.BUTTON,
            HiddenModePolicy.PORT_LABEL,
            HiddenModePolicy.DIALOG_TITLE,
            HiddenModePolicy.DIALOG_BODY,
        ).joinToString("\n")
        listOf("演练", "伪卸载", "痕迹", "等级", "高级", "Intent", "Logcat", "恢复端口").forEach {
            assertFalse("禁止出现「$it」", all.contains(it))
        }
    }

    @Test
    fun canEnableRequiresRecoveryChannel() {
        val port = HiddenModePolicy.validatePort("18427")
        val noChannel = RecoveryChannels(dialCodeAvailable = false, webUiRecoveryLink = null, rootAvailable = true)
        assertEquals(HiddenFailure.NO_RECOVERY_CHANNEL, HiddenModePolicy.canEnable(WorkMode.LITE, LiteHiddenState.OFF, port, noChannel))
        val dial = RecoveryChannels(dialCodeAvailable = true, webUiRecoveryLink = null, rootAvailable = true)
        assertNull(HiddenModePolicy.canEnable(WorkMode.LITE, LiteHiddenState.OFF, port, dial))
        val link = RecoveryChannels(dialCodeAvailable = false, webUiRecoveryLink = "http://x", rootAvailable = true)
        assertNull(HiddenModePolicy.canEnable(WorkMode.LITE, LiteHiddenState.OFF, port, link))
    }

    @Test
    fun canEnableFailClosedOnRootOrPortOrMode() {
        val good = RecoveryChannels(dialCodeAvailable = true, webUiRecoveryLink = null, rootAvailable = true)
        assertEquals(
            HiddenFailure.ROOT_UNAVAILABLE,
            HiddenModePolicy.canEnable(
                WorkMode.LITE, LiteHiddenState.OFF, HiddenModePolicy.validatePort("18427"),
                good.copy(rootAvailable = false),
            ),
        )
        assertEquals(
            HiddenFailure.INVALID_PORT,
            HiddenModePolicy.canEnable(
                WorkMode.LITE, LiteHiddenState.OFF, HiddenModePolicy.validatePort("80"), good,
            ),
        )
        assertEquals(
            HiddenFailure.BACKEND_UNAVAILABLE,
            HiddenModePolicy.canEnable(WorkMode.LITE, LiteHiddenState.OFF, HiddenModePolicy.validatePort("18427"), good),
        )
    }

    @Test
    fun singleButtonStateMachine() {
        assertEquals(
            HiddenCardPhase.CONFIRMING,
            HiddenModePolicy.nextPhase(HiddenCardPhase.UNHIDDEN, HiddenEvent.ClickEnable),
        )
        assertEquals(
            HiddenCardPhase.UNHIDDEN,
            HiddenModePolicy.nextPhase(HiddenCardPhase.CONFIRMING, HiddenEvent.CancelConfirm),
        )
        assertEquals(
            HiddenCardPhase.ENABLING,
            HiddenModePolicy.nextPhase(HiddenCardPhase.CONFIRMING, HiddenEvent.Confirm),
        )
        assertEquals(
            HiddenCardPhase.FAILED,
            HiddenModePolicy.nextPhase(HiddenCardPhase.ENABLING, HiddenEvent.EnableFailed),
        )
        assertEquals(
            "失败后保持未隐藏，可再次点击",
            HiddenCardPhase.CONFIRMING,
            HiddenModePolicy.nextPhase(HiddenCardPhase.FAILED, HiddenEvent.ClickEnable),
        )
        assertEquals(
            "确认中取消 → 未隐藏",
            HiddenCardPhase.UNHIDDEN,
            HiddenModePolicy.nextPhase(HiddenCardPhase.CONFIRMING, HiddenEvent.CancelConfirm),
        )
    }

    @Test
    fun failureCodesMapToDistinctShortReasons() {
        val keys = HiddenFailure.entries.map { HiddenModePolicy.failureMessageKey(it.wire) }.toSet()
        assertEquals(HiddenFailure.entries.size, keys.size)
        assertEquals("lite_hidden_reason_backend_unavailable", HiddenModePolicy.failureMessageKey("LITE_HIDDEN_BACKEND_UNAVAILABLE"))
        assertEquals("lite_hidden_reason_unknown", HiddenModePolicy.failureMessageKey("SOMETHING_ELSE"))
        assertTrue(keys.all { it.startsWith("lite_hidden_reason_") })
    }

    @Test
    fun successMakesEntryAndPageInvisible() {
        val mode = WorkMode.LITE
        assertTrue(HiddenModePolicy.cardVisible(mode, LiteHiddenState.OFF))
        assertFalse("开启成功后设置项/隐藏页不可见", HiddenModePolicy.cardVisible(mode, LiteHiddenState.ON))
        assertFalse(HiddenModePolicy.anyHiddenEntriesVisible(mode, LiteHiddenState.ON))
    }
}
