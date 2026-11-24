package com.maplesu.core.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.ui.text.style.TextOverflow
import com.maplesu.core.ui.util.AppearancePreferences
import com.maplesu.core.ui.util.AppearancePrefsPolicy
import com.maplesu.core.ui.util.WallpaperPrefs
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import com.maplesu.core.R
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode
import com.maplesu.core.theme.*
import com.maplesu.core.ui.screen.settings.ThemeText
import com.maplesu.core.ui.theme.isInDarkTheme

internal data class StatusThemeArt(
    val file: java.io.File?, val opacity: Float, val dim: Float,
    val hideIcon: Boolean = false, val hideText: Boolean = false, val hideMode: Boolean = false,
    val photo: ThemePhoto? = null, val foreground: Color = Color.White,
)
internal val LocalPreparedStatusThemeArt = staticCompositionLocalOf<StatusThemeArt?> { null }

@Composable
internal fun statusThemeArt(): StatusThemeArt {
    LocalPreparedStatusThemeArt.current?.let { return it }
    val j = ThemeRuntime.observe(); val context = LocalContext.current
    val dark = isInDarkTheme()
    val base = ThemeHomeLayout.statusResource(j)
    val s = ThemeSurface.read(j,base,dark)
    val file = remember(j,base) { if (base.isNotEmpty() && s.enabled) ThemeRuntime.file(context,base) else null }
    val photo = rememberThemePhoto(file)?.takeIf { s.opacity > 0f }
    val grid = base == "grid_working_card_background"
    val listMode = layoutUsesListStatus(j)
    return StatusThemeArt(photo?.file,s.opacity,s.dim, grid && s.hideIcon, grid && s.hideTitle,
        grid && s.hideSummary || listMode && j.optBoolean("isListWorkingCardModeHidden"),photo,
        photo?.let { themePhotoForeground(it.luminance,s.dim) } ?: Color.White)
}

private fun layoutUsesListStatus(j: org.json.JSONObject) = j.optString("homeLayoutStyle","circle") == "circle" ||
    j.optString("homeLayoutStyle") == "stats" && j.optString("statsTopLayout","list") == "list"

@Composable
internal fun BoxScope.StatusThemeBackdrop(art: StatusThemeArt) {
    if (art.photo != null) {
        ThemePhotoImage(art.photo,Modifier.matchParentSize().alpha(art.opacity).testTag("home-status-theme-image"))
        Box(Modifier.matchParentSize().background((if (art.foreground == Color.White) Color.Black else Color.White).copy(alpha=art.dim)))
    }
}

internal data class ThemeHomeCounts(val authorized: Int? = null, val modules: Int? = null)
internal val LocalThemeHomeCounts = staticCompositionLocalOf { ThemeHomeCounts() }

@Composable
internal fun homeStatusContainerColor(): Color {
    val wallpaper = LocalThemeWallpaperStyle.current
    val color = if (wallpaper?.darkNeutral ?: isInDarkTheme()) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    return if (wallpaper != null) color.copy(alpha=wallpaper.group) else color
}

@Composable
internal fun homeStatusContentColor(): Color =
    if (LocalThemeWallpaperStyle.current?.darkNeutral ?: isInDarkTheme()) Color(0xFFB8E8C5) else Color(0xFF164A29)

@Composable
internal fun rememberThemeHomeLayout(): ThemeHomeLayout {
    val context = LocalContext.current
    val revision = WallpaperPrefs.observe()
    return remember(context,revision) {
        val prefs = AppearancePreferences.prefs(context)
        ThemeHomeLayout.read(prefs.getString(ThemeHomeLayout.PREFERENCE,null),
            prefs.getBoolean(AppearancePrefsPolicy.HOME_LARGE_CARD,true))
    }
}

/** Original large/compact cards keep their layout. Only the explicit three-card choice adds tiles. */
@Composable
internal fun ThemeHomeStatus(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier,
    status: @Composable () -> Unit) {
    val layout = rememberThemeHomeLayout()
    val art = statusThemeArt()
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val targetWidth = with(androidx.compose.ui.platform.LocalDensity.current) { width.dp.roundToPx() }
    val decoration = com.maplesu.core.ui.util.rememberPreparedStatusDecoration(targetWidth)
    CompositionLocalProvider(LocalPreparedStatusThemeArt provides art,
        com.maplesu.core.ui.util.LocalPreparedStatusDecoration provides decoration) {
        if (layout != ThemeHomeLayout.Three || !state.showInstalledStatus || state.showEnvironmentStatusCard) {
            Box(modifier.testTag("home-original-status")) { status() }
        } else {
            // Match the reference grid: a square hero and two equal tiles sharing its height.
            BoxWithConstraints(modifier.fillMaxWidth().testTag("home-three")) {
                Row(Modifier.fillMaxWidth().height((maxWidth-12.dp)/2),
                    horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    ThemeThreeStatus(state,actions,Modifier.weight(1f).fillMaxHeight())
                    val counts = LocalThemeHomeCounts.current
                    Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        ThemeCountTile(stringResource(R.string.superuser),counts.authorized,
                            Icons.Outlined.Person,Modifier.fillMaxWidth().weight(1f).testTag("home-counter-superuser"))
                        ThemeCountTile(stringResource(R.string.module),counts.modules,
                            Icons.Outlined.Extension,Modifier.fillMaxWidth().weight(1f).testTag("home-counter-module"))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeCountTile(label: String, count: Int?, icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier) {
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxSize().padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            HomeThemeIcon(icon,Modifier.size(20.dp))
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                HomeTileText(label,secondary=true)
                HomeTileText(count?.toString() ?: "—",title=true)
            }
        }
    }
    when (LocalUiMode.current) {
        UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Card(modifier) { content() }
        UiMode.Material -> androidx.compose.material3.Card(modifier,
            shape=androidx.compose.material3.MaterialTheme.shapes.large,
            colors=androidx.compose.material3.CardDefaults.cardColors(containerColor=
                LocalThemeWallpaperStyle.current?.let { androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha=it.group) }
                    ?: androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow)) { content() }
    }
}

@Composable
private fun ThemeThreeStatus(state: HomeUiState, actions: HomeActions, modifier: Modifier) {
    val art = statusThemeArt()
    val revision = WallpaperPrefs.observe()
    val decorationPrefs = remember(revision) { com.maplesu.core.data.repository.SettingsRepositoryImpl() }
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp / 2
    val targetWidth = with(androidx.compose.ui.platform.LocalDensity.current) { width.dp.roundToPx() }
    val decoration = com.maplesu.core.ui.util.rememberStatusDecoration(targetWidth).takeIf { art.photo == null }
    val protectedText = state.showSafeModeBanner || state.isSafeMode
    val title = when {
        state.isSafeMode -> stringResource(R.string.home_safe_mode_title)
        else -> STATUS_CARD_TEXT
    }
    val mode = when (state.lkmMode) { true -> "LKM"; false -> "GKI"; null -> "" }
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().heightIn(min=160.dp).testTag("home-three-status")) {
            StatusThemeBackdrop(art)
            if (decoration != null) {
                val anchor = decorationPrefs.statusDecorationAnchor
                com.maplesu.core.ui.util.StatusDecorationImage(decoration,
                    if (anchor == 3) decorationPrefs.statusDecorationBiasX else 0f,
                    when (anchor) { 1 -> -1f; 2 -> 1f; 3 -> decorationPrefs.statusDecorationBiasY; else -> 0f },
                    decorationPrefs.statusDecorationZoom,Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Color.Black.copy(
                    alpha=maxOf(decorationPrefs.statusDecorationDim,if (decorationPrefs.statusDecorationHideText) 0f else .56f))))
            }
            CompositionLocalProvider(LocalThemePhotoContent provides
                if (decoration != null) Color.White else art.photo?.let { art.foreground } ?: homeStatusContentColor()) {
                if (!art.hideIcon && art.photo == null && decoration == null) Box(
                    Modifier.matchParentSize().offset(27.dp,31.dp).testTag("home-status-default-check"),contentAlignment=Alignment.BottomEnd) {
                    HomeThemeIcon(Icons.Rounded.CheckCircleOutline,
                        Modifier.size(110.dp).testTag("home-three-status-check"),
                        Color(0xFF36D167))
                }
                if (decoration == null || !decorationPrefs.statusDecorationHideText || protectedText) {
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if (!art.hideText || protectedText) HomeTileText(title,title=true)
                    if (mode.isNotEmpty() && !art.hideMode) HomeTileText(mode)
                    if (state.showCustomLkmBadge) HomeTileText(stringResource(R.string.home_lkm_custom))
                }
                }
            }
        }
    }
    val click = {
        if (state.isSafeMode) actions.onManageModules()
        else actions.onInstallClick()
    }
    when (LocalUiMode.current) {
        UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Card(
            modifier=modifier, onClick=click, showIndication=true,
            colors=top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                color=if (art.photo != null || decoration != null) Color.Transparent else homeStatusContainerColor()),
        ) { content() }
        UiMode.Material -> androidx.compose.material3.Surface(
            modifier=modifier.clickable(onClick=click),
            shape=androidx.compose.material3.MaterialTheme.shapes.large,
            color=if (art.photo != null || decoration != null) Color.Transparent else homeStatusContainerColor(),
        ) { content() }
    }
}

@Composable
private fun HomeTileText(text: String, title: Boolean = false, secondary: Boolean = false) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Text(text,
            style=if (title) top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.title3 else top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2,
            color=LocalThemePhotoContent.current ?: if (secondary) top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary else top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface,
            maxLines=if (secondary) 1 else Int.MAX_VALUE,overflow=TextOverflow.Ellipsis)
        UiMode.Material -> androidx.compose.material3.Text(text,
            style=if (title) androidx.compose.material3.MaterialTheme.typography.titleLarge else androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color=LocalThemePhotoContent.current ?: if (secondary) androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
            maxLines=if (secondary) 1 else Int.MAX_VALUE,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeThemeIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, tint: Color? = null) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Icon(icon,null,modifier,
            tint=tint ?: LocalThemePhotoContent.current ?: top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary)
        UiMode.Material -> androidx.compose.material3.Icon(icon,null,modifier,
            tint=tint ?: LocalThemePhotoContent.current ?: androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun themeHomeTitlePhoto(): ThemePhoto? {
    val j = ThemeRuntime.observe(); val context = LocalContext.current
    val file = remember(j) { if (j.optBoolean("isAdvancedTitleStyleEnabled")) ThemeRuntime.file(context,"title_image") else null }
    return rememberThemePhoto(file)
}

@Composable
internal fun ThemeHomeTitleText(default: String): String {
    val j = ThemeRuntime.observe()
    return when (j.optString("titleTextMode","default")) {
        "kernelsu" -> stringResource(com.maplesu.core.R.string.home_title_kernelsu)
        "custom" -> j.optString("titleCustomText").ifBlank { default }
        else -> default
    }
}

@Composable
internal fun ThemeHomeTitle(modifier: Modifier = Modifier, photo: ThemePhoto? = themeHomeTitlePhoto(), fallback: @Composable () -> Unit) {
    val j = ThemeRuntime.observe(); val dark = isInDarkTheme()
    if (photo == null) {
        if (j.optString("titleTextMode","default") == "default") fallback()
        else ThemeText(ThemeHomeTitleText("MapleSU"), modifier, secondary = false)
        return
    }
    val opacity = j.optDouble(if (dark) "titleImageNightOpacity" else "titleImageDayOpacity",1.0).toFloat().coerceIn(0f,1f)
    Box(modifier.height(40.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
        ThemePhotoImage(photo, Modifier.fillMaxWidth().height(40.dp).offset(x=(j.optDouble("titleImageOffsetX",0.0)*100).dp).alpha(opacity),
            description = "MapleSU", scaleType = android.widget.ImageView.ScaleType.FIT_CENTER)
        val dim = j.optDouble("titleImageDim",0.0).toFloat().coerceIn(0f,1f)
        if (dim > 0f) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha=dim)))
    }
}
