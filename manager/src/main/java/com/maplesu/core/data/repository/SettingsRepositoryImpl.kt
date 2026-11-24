package com.maplesu.core.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.maplesu.core.Natives
import com.maplesu.core.ksuApp
import com.maplesu.core.ui.UiMode
import com.maplesu.core.ui.screen.modulerepo.RepoSort
import com.maplesu.core.ui.util.AppearancePrefsPolicy
import com.maplesu.core.ui.util.ThemeLock
import dev.maplesu.manager.workmode.storage.SettingsPrefsRouter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.SecureRandom

private const val KEY_USE_SOFT_REBOOT = "soft_reboot"

/**
 * Prefer soft reboot: always for ultra-only flows, or when the setting is enabled.
 *
 * P0 配置隔离：读取走 [SettingsPrefsRouter]（A=`settings` / B=`settings_B` /
 * 未判定按 01 §3.1 的提示与 A 证据回退，绝不默认 A）。
 */
fun isSoftRebootPreferred(): Boolean =
    SettingsPrefsRouter(ksuApp).prefs()
        .getBoolean(KEY_USE_SOFT_REBOOT, false)

class SettingsRepositoryImpl : SettingsRepository {

    private companion object {
        private const val INTENT_TOKEN_KEY = "intent_token"
        private val secureRandom = SecureRandom()
    }

    /**
     * P0：设置总仓按模式路由（A 原名 / B `_B`）。每次访问重新解析——模式在进程内
     * 激活后不再翻转（切换模式会重启进程），因此语义等价于固定 prefs。
     */
    private val prefs get() = SettingsPrefsRouter(ksuApp).prefs()

    private val appearance get() = com.maplesu.core.ui.util.AppearancePreferences.prefs(ksuApp)

    override var homeLargeCard: Boolean
        get() = appearance.getBoolean(AppearancePrefsPolicy.HOME_LARGE_CARD, true)
        set(value) = appearance.edit {
            putBoolean(AppearancePrefsPolicy.HOME_LARGE_CARD, value)
            remove("home_card_layout")
        }

    override fun applyComicPreset(enabled: Boolean) {
        appearance.edit {
            remove("home_card_layout")
            AppearancePrefsPolicy.comicPreset(enabled).forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                }
            }
        }
    }

    override var uiMode: String
        get() = appearance.getString("ui_mode", UiMode.DEFAULT_VALUE) ?: UiMode.DEFAULT_VALUE
        set(value) {
            val shared = appearance
            shared.edit {
                putString("ui_mode", value)
                if (UiMode.fromValue(value) == UiMode.Miuix) {
                    AppearancePrefsPolicy.MIUIX_EFFECT_KEYS.forEach { key ->
                        if (!shared.contains(key)) putBoolean(key, true)
                    }
                }
            }
        }

    private fun miuixEffect(key: String): Boolean = AppearancePrefsPolicy.effectiveMiuixEffect(
        stored = if (appearance.contains(key)) appearance.getBoolean(key, false) else null,
        isMiuix = UiMode.fromValue(uiMode) == UiMode.Miuix,
    )

    override var checkUpdate: Boolean
        get() = prefs.getBoolean("check_update", true)
        set(value) = prefs.edit { putBoolean("check_update", value) }

    override var checkModuleUpdate: Boolean
        get() = prefs.getBoolean("module_check_update", true)
        set(value) = prefs.edit { putBoolean("module_check_update", value) }

    override var themeMode: Int
        // 主题锁定:固定成「跟随系统」,谁也改不了(见 util/ThemeLock.kt)
        get() = if (ThemeLock.ENABLED) ThemeLock.THEME_MODE else appearance.getInt("color_mode", 0)
        set(value) {
            if (ThemeLock.ENABLED) return
            appearance.edit { putInt("color_mode", value) }
        }

    override var miuixMonet: Boolean
        get() = if (ThemeLock.ENABLED) ThemeLock.MIUIX_MONET else appearance.getBoolean("miuix_monet", false)
        set(value) {
            if (ThemeLock.ENABLED) return
            appearance.edit { putBoolean("miuix_monet", value) }
        }

    override var keyColor: Int
        get() = appearance.getInt("key_color", 0)
        set(value) = appearance.edit { putInt("key_color", value) }

    override var colorStyle: String
        get() = appearance.getString("color_style", PaletteStyle.TonalSpot.name) ?: PaletteStyle.TonalSpot.name
        set(value) = appearance.edit { putString("color_style", value) }

    override var colorSpec: String
        get() = appearance.getString("color_spec", ColorSpec.SpecVersion.SPEC_2025.name) ?: ColorSpec.SpecVersion.SPEC_2025.name
        set(value) = appearance.edit { putString("color_spec", value) }

    override var wallpaperPath: String
        get() = appearance.getString("wallpaper_path", "") ?: ""
        set(value) = appearance.edit { putString("wallpaper_path", value) }

    override var wallpaperKind: String
        get() = appearance.getString("wallpaper_kind", "none") ?: "none"
        set(value) = appearance.edit { putString("wallpaper_kind", value) }

    // 横屏专用壁纸(v0.13.160)。老数据里没有这两个 key → 默认空/none
    // → 横屏沿用竖屏那份,和老版本行为完全一致。
    override var wallpaperLandPath: String
        get() = appearance.getString("wallpaper_land_path", "") ?: ""
        set(value) = appearance.edit { putString("wallpaper_land_path", value) }

    override var wallpaperLandKind: String
        get() = appearance.getString("wallpaper_land_kind", "none") ?: "none"
        set(value) = appearance.edit { putString("wallpaper_land_kind", value) }

    override var wallpaperDim: Float
        get() = appearance.getFloat("wallpaper_dim", 0.2f)
        set(value) = appearance.edit { putFloat("wallpaper_dim", value) }

    override var wallpaperBlur: Float
        get() = appearance.getFloat("wallpaper_blur", 0f)
        set(value) = appearance.edit { putFloat("wallpaper_blur", value) }

    // 「界面透明化」恢复：默认关（AppearancePrefsPolicy.DEFAULT_TRANSLUCENT）；
    // 已存的值原样沿用，只有从未保存过时才会在"选壁纸"那一刻自动打开。
    override var uiTranslucent: Boolean
        get() = AppearancePrefsPolicy.effectiveTranslucent(
            if (appearance.contains("ui_translucent")) appearance.getBoolean("ui_translucent", false) else null
        )
        set(value) = appearance.edit { putBoolean("ui_translucent", value) }

    override val uiTranslucentChosen: Boolean
        get() = appearance.contains("ui_translucent")

    override var uiTranslucentAlpha: Float
        get() = AppearancePrefsPolicy.normalizeTranslucentAlpha(
            appearance.getFloat("ui_translucent_alpha", AppearancePrefsPolicy.DEFAULT_TRANSLUCENT_ALPHA)
        )
        set(value) = appearance.edit {
            putFloat("ui_translucent_alpha", AppearancePrefsPolicy.normalizeTranslucentAlpha(value))
        }

    // 🗑 2026-10-29：用户要求删掉「用壁纸的主色」这个功能（连同控制按钮）。
    //    读值恒为 false（取色链路因此永不启用），写值保留兼容旧版本回滚。
    override var wallpaperSeed: Boolean
        get() = false
        set(value) { appearance.edit { putBoolean("wallpaper_seed", value) } }

    override var wallpaperSeedColor: Int
        get() = appearance.getInt("wallpaper_seed_color", 0)
        set(value) = appearance.edit { putInt("wallpaper_seed_color", value) }

    override var statusDecorationAnchor: Int
        get() = appearance.getInt("status_decoration_anchor", 0)
        set(value) = appearance.edit { putInt("status_decoration_anchor", value) }

    override var statusDecorationDim: Float
        // 默认 0.55 = 原来的固定渐变强度，升级后视觉不变
        get() = appearance.getFloat("status_decoration_dim", 0.35f)
        set(value) = appearance.edit { putFloat("status_decoration_dim", value) }

    override var statusDecorationHideText: Boolean
        get() = appearance.getBoolean("status_decoration_hide_text", false)
        set(value) = appearance.edit { putBoolean("status_decoration_hide_text", value) }

    override var statusDecorationBiasX: Float
        get() = appearance.getFloat("status_decoration_bias_x", 0f)
        set(value) = appearance.edit { putFloat("status_decoration_bias_x", value) }

    override var statusDecorationBiasY: Float
        get() = appearance.getFloat("status_decoration_bias_y", 0f)
        set(value) = appearance.edit { putFloat("status_decoration_bias_y", value) }

    override var statusDecorationZoom: Float
        get() = appearance.getFloat("status_decoration_zoom", 1f)
        set(value) = appearance.edit { putFloat("status_decoration_zoom", value) }

    override var navIconCircle: Boolean
        get() = appearance.getBoolean("nav_icon_circle", true)
        set(value) = appearance.edit { putBoolean("nav_icon_circle", value) }

    // 🗑 2026-10-29：用户要求删掉「导航图标着色」（连同控制按钮）。恒为 false。
    override var navIconTint: Boolean
        get() = false
        set(value) { appearance.edit { putBoolean("nav_icon_tint", value) } }

    override var snowEnabled: Boolean
        get() = appearance.getBoolean("snow_enabled", true)
        set(value) = appearance.edit { putBoolean("snow_enabled", value) }

    override var snowSize: Float
        get() = appearance.getFloat("snow_size", 1f)
        set(value) = appearance.edit { putFloat("snow_size", value) }

    override var snowSpeed: Float
        get() = appearance.getFloat("snow_speed", 1f)
        set(value) = appearance.edit { putFloat("snow_speed", value) }

    override var trollRainEnabled: Boolean
        get() = appearance.getBoolean("troll_rain_enabled", false)
        set(value) = appearance.edit { putBoolean("troll_rain_enabled", value) }

    override var trollRainSize: Float
        get() = appearance.getFloat("troll_rain_size", 1f)
        set(value) = appearance.edit { putFloat("troll_rain_size", value) }

    override var trollRainSpeed: Float
        get() = appearance.getFloat("troll_rain_speed", 1f)
        set(value) = appearance.edit { putFloat("troll_rain_speed", value) }

    override var gravityEnabled: Boolean
        get() = appearance.getBoolean("gravity_enabled", true)
        set(value) = appearance.edit { putBoolean("gravity_enabled", value) }

    override var gravityMode: Int
        get() = appearance.getInt("gravity_mode", 0)
        set(value) = appearance.edit { putInt("gravity_mode", value) }

    override var stealthCode: String
        get() = prefs.getString("stealth_code", "70707") ?: "70707"
        set(value) = prefs.edit { putString("stealth_code", value) }

    // 网页管理器开关（只是界面状态；服务与口令都在 ksud 侧）
    // ⚠️ 键名保留 v0.13.77 的 web_terminal_enabled：换键会让老用户的开关被重置。
    override var webAdminEnabled: Boolean
        get() = prefs.getBoolean("web_terminal_enabled", false)
        set(value) = prefs.edit { putBoolean("web_terminal_enabled", value) }

    // 🗑 2026-10-29：用户要求删掉「界面扁平化」这个功能（连控制按钮）。
    //    读值恒为 false（由 AppearancePrefsPolicy 统一收口）：旧键 `flat_home`
    //    仍会读出来以兼容旧值，但 true 也不会让界面变扁平；写值保留方便旧版本回滚。
    override var flatHome: Boolean
        get() = AppearancePrefsPolicy.effectiveFlatUi(appearance.getBoolean("flat_home", false))
        set(value) { appearance.edit { putBoolean("flat_home", value) } }

    override var enablePredictiveBack: Boolean
        get() = miuixEffect("enable_predictive_back")
        set(value) = appearance.edit { putBoolean("enable_predictive_back", value) }

    override var enableSwipeDismiss: Boolean
        get() = appearance.getBoolean("enable_swipe_dismiss", true)
        set(value) = appearance.edit { putBoolean("enable_swipe_dismiss", value) }

    override var pagerInterceptionMode: Int
        get() = appearance.getInt("pager_interception_mode", 1)
        set(value) = appearance.edit { putInt("pager_interception_mode", value.coerceIn(0, 2)) }

    override var enableBlur: Boolean
        get() = if (ThemeLock.ENABLED) ThemeLock.ENABLE_BLUR else miuixEffect("enable_blur")
        set(value) {
            if (ThemeLock.ENABLED) return
            appearance.edit { putBoolean("enable_blur", value) }
        }

    override var enableFloatingBottomBar: Boolean
        get() = if (ThemeLock.ENABLED) ThemeLock.FLOATING_BOTTOM_BAR else miuixEffect("enable_floating_bottom_bar")
        set(value) {
            if (ThemeLock.ENABLED) return
            appearance.edit { putBoolean("enable_floating_bottom_bar", value) }
        }

    override var enableFloatingBottomBarBlur: Boolean
        get() = if (ThemeLock.ENABLED) ThemeLock.FLOATING_BOTTOM_BAR_BLUR else miuixEffect("enable_floating_bottom_bar_blur")
        set(value) {
            if (ThemeLock.ENABLED) return
            appearance.edit { putBoolean("enable_floating_bottom_bar_blur", value) }
        }

    override var enableNavigationBadge: Boolean
        get() = if (ThemeLock.ENABLED) ThemeLock.NAVIGATION_BADGE else appearance.getBoolean("enable_navigation_badge", true)
        set(value) {
            if (ThemeLock.ENABLED) return
            appearance.edit { putBoolean("enable_navigation_badge", value) }
        }

    override var navigationRailExpanded: Boolean
        get() = appearance.getBoolean("nav_rail_expanded", false)
        set(value) = appearance.edit { putBoolean("nav_rail_expanded", value) }

    override var pageScale: Float
        get() = appearance.getFloat("page_scale", 1.0f)
        set(value) = appearance.edit { putFloat("page_scale", value) }

    override var moduleDescriptionMaxLines: Int
        get() = appearance.getInt("module_description_max_lines", 4)
        set(value) = appearance.edit { putInt("module_description_max_lines", value) }

    override var enableWebDebugging: Boolean
        get() = prefs.getBoolean("enable_web_debugging", false)
        set(value) = prefs.edit { putBoolean("enable_web_debugging", value) }

    override var moduleSortEnabledFirst: Boolean
        get() = prefs.getBoolean("module_sort_enabled_first", false)
        set(value) = prefs.edit { putBoolean("module_sort_enabled_first", value) }

    override var moduleSortActionFirst: Boolean
        get() = prefs.getBoolean("module_sort_action_first", false)
        set(value) = prefs.edit { putBoolean("module_sort_action_first", value) }

    override var moduleRepoSortOrder: String
        get() = RepoSort.fromStored(
            name = prefs.getString("module_repo_sort", null),
            legacyOrdinal = if (prefs.contains("module_repo_sort_order")) prefs.getInt("module_repo_sort_order", 0) else null,
        ).name
        set(value) = prefs.edit {
            putString("module_repo_sort", value)
            remove("module_repo_sort_order")
        }

    override var superuserShowSystemApps: Boolean
        get() = prefs.getBoolean("show_system_apps", false)
        set(value) = prefs.edit { putBoolean("show_system_apps", value) }

    override var superuserShowOnlyPrimaryUserApps: Boolean
        get() = prefs.getBoolean("show_only_primary_user_apps", false)
        set(value) = prefs.edit { putBoolean("show_only_primary_user_apps", value) }

    override var superuserSortOption: Int
        get() = prefs.getInt("superuser_sort_option", 0)
        set(value) = prefs.edit { putInt("superuser_sort_option", value) }

    override var suLogFilters: Set<String>?
        get() = prefs.getStringSet("sulog_filters", null)?.toSet()
        set(filters) = prefs.edit { putStringSet("sulog_filters", filters) }

    override var useSoftReboot: Boolean
        get() = prefs.getBoolean(KEY_USE_SOFT_REBOOT, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_SOFT_REBOOT, value) }

    override val intentToken: String
        get() {
        val existing = prefs.getString(INTENT_TOKEN_KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val token = ByteArray(32).also(secureRandom::nextBytes)
            .joinToString(separator = "") { "%02x".format(it) }
        prefs.edit { putString(INTENT_TOKEN_KEY, token) }
        return token
    }

}
