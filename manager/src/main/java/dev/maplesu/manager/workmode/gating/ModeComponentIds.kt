package dev.maplesu.manager.workmode.gating

/**
 * 门控点稳定 id 常量表。**禁止**用类名（混淆后会变），**禁止**中文。
 * 新增门控点必须同时加到本表与 04 §1.9 的 component 表。
 */
object ModeComponentIds {
    // L1 Application
    const val APP_ULTRA_AUTOPAIR = "app.pro_autopair"
    const val APP_CRASH_SHELL = "app.crash_shell"
    const val APP_INSTALLER_RETIRE = "app.installer_retire"
    const val APP_MODE_BOOTSTRAP = "app.mode_bootstrap"

    // L2 Activity
    const val MAIN_INSTALL = "main.install"
    const val MAIN_LAUNCHER_ALIAS = "main.launcher_alias"
    const val MAIN_WALLPAPER_SEED = "main.wallpaper_seed"

    // L3 receivers/services/providers
    const val RECEIVER_MODE_BOOT = "receiver.mode_boot"
    const val RECEIVER_LITE_MODULE_BOOT = "receiver.lite_module_boot"
    const val SERVICE_LITE_MODULE_BOOT = "service.lite_module_boot"

    // L0 facade
    const val NATIVES_IS_MANAGER = "natives.is_manager"

    // UI
    const val UI_INSTALL_ROOT_SESSION = "ui.install.root_session"
    const val UI_WORKMODE_ENTRY = "ui.workmode.entry"
    const val UI_WORKMODE_SWITCH = "ui.workmode.switch"
    const val UI_SETTINGS_KSU_ROWS = "ui.settings.ksu_rows"

    /** 受 ModeComponentSwitcher 管控的组件（Manifest 里的类名）。 */
    const val COMPONENT_LITE_MODULE_BOOT_RECEIVER = "dev.maplesu.manager.modules.LiteModuleBootReceiver"
    const val COMPONENT_LITE_MODULE_BOOT_SERVICE = "dev.maplesu.manager.modules.LiteModuleBootService"

    /** B（KSU）下必须禁用的 A 专属组件；A 下不主动 enable（防误开用户手动禁用的组件）。 */
    val KSU_DISABLED_COMPONENTS: List<String> = listOf(
        COMPONENT_LITE_MODULE_BOOT_RECEIVER,
        COMPONENT_LITE_MODULE_BOOT_SERVICE,
    )
}
