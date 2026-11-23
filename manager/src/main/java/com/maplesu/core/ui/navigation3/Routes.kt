package com.maplesu.core.ui.navigation3

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import com.maplesu.core.ui.screen.flash.FlashIt
import com.maplesu.core.ui.screen.modulerepo.RepoModuleArg
import com.maplesu.core.ui.util.FlashItSerializer
import com.maplesu.core.ui.util.RepoModuleArgSerializer
import com.maplesu.core.ui.util.TemplateInfoSerializer
import com.maplesu.core.ui.viewmodel.TemplateViewModel
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Type-safe navigation keys for miuix-nav.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 */
@Serializable
sealed interface Route : NavKey, Parcelable {
    @Parcelize
    @Serializable
    data object Main : Route

    @Parcelize
    @Serializable
    data object Home : Route

    @Parcelize
    @Serializable
    data object SuperUser : Route

    @Parcelize
    @Serializable
    data object Module : Route

    @Parcelize
    @Serializable
    data object Settings : Route

    @Parcelize
    @Serializable
    data object Appearance : Route

    @Parcelize
    @Serializable
    data object AppearanceImages : Route

    @Parcelize @Serializable data object Wallpaper : Route
    @Parcelize @Serializable data object ThemeStore : Route
    @Parcelize @Serializable data object MyThemes : Route
    @Parcelize @Serializable data object ThemeFont : Route
    @Parcelize @Serializable data object ThemeLayout : Route
    @Parcelize @Serializable data object ThemeMedia : Route

    @Parcelize
    @Serializable
    data object About : Route

    @Parcelize
    @Serializable
    data object ColorPalette : Route

    @Parcelize
    @Serializable
    data object AppProfileTemplate : Route

    @Parcelize
    @Serializable
    data class TemplateEditor(
        @Serializable(with = TemplateInfoSerializer::class) val template: TemplateViewModel.TemplateInfo,
        val readOnly: Boolean
    ) : Route

    @Parcelize
    @Serializable
    data class AppProfile(val uid: Int) : Route

    @Parcelize
    @Serializable
    data object Install : Route

    @Parcelize
    @Serializable
    data class ModuleRepoDetail(@Serializable(with = RepoModuleArgSerializer::class) val module: RepoModuleArg) : Route

    @Parcelize
    @Serializable
    data object ModuleRepo : Route

    @Parcelize
    @Serializable
    data object MapleMarket : Route

    @Parcelize
    @Serializable
    data class Flash(@Serializable(with = FlashItSerializer::class) val flashIt: FlashIt) : Route

    /** H3/H7：Lite 隐藏设置页（仅内部设置项 push，不进 IntentDispatcher）。 */
    @Parcelize
    @Serializable
    data object LiteHidden : Route

    /** H7：WebUI 自检页（仅设置项 push，内部验证用）。 */
    @Parcelize
    @Serializable
    data object WebUiSelfCheck : Route

    /** 切换进度页；switchId 只能由内部 push 传入，不接受 Intent 注入（04 §2.1）。 */
    @Parcelize
    @Serializable
    data class ModeSwitch(
        val switchId: String,
        @Serializable(with = WorkModeRouteSerializer::class)
        val initialTarget: dev.maplesu.manager.workmode.WorkMode? = null,
    ) : Route

    // W16 recovery wizard: 恢复页；switchId 只来自内部 push（不接受 Intent/深链注入）。
    @Parcelize
    @Serializable
    data class Recovery(val switchId: String = "") : Route

    // W16 diagnostics: 诊断/导出页；switchId 可为空（普通诊断）。
    @Parcelize
    @Serializable
    data class Diagnosis(val switchId: String = "") : Route

    // W21/W22 external-root migration: 迁移页；migrationId 可为空（进入即检测）。
    @Parcelize
    @Serializable
    data class Migration(
        val migrationId: String = "",
        @Serializable(with = WorkModeRouteSerializer::class)
        val initialTarget: dev.maplesu.manager.workmode.WorkMode? = null,
        val confirmOnOpen: Boolean = false,
    ) : Route

    @Parcelize
    @Serializable
    data class ExecuteModuleAction(val moduleId: String, val fromShortcut: Boolean = false) : Route
}

/** Route-only serialization; the backend enum and wire protocol stay unchanged. */
object WorkModeRouteSerializer : kotlinx.serialization.KSerializer<dev.maplesu.manager.workmode.WorkMode> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("WorkModeRoute", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: dev.maplesu.manager.workmode.WorkMode) = encoder.encodeString(value.suffix)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): dev.maplesu.manager.workmode.WorkMode =
        requireNotNull(dev.maplesu.manager.workmode.WorkMode.fromWire(decoder.decodeString())) { "Unknown mode route" }
}
