package com.maplesu.core.ui.component.maple

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.maplesu.core.ui.theme.isInDarkTheme
import dev.maplesu.manager.workmode.WorkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 三个品牌弹窗各自的 Banner。同一张点阵，三种材质：
 *  - [ULTRA]：方格，沿用首页字样的紫色与流动；
 *  - [NORMAL]：圆点，跟随用户主题色，缓慢起伏；
 *  - [MAPLE]：微缩文字，字形里印满署名，字尾一片枫叶。
 */
enum class BrandBannerKind { ULTRA, NORMAL, MAPLE }

/** 工作模式对应的 Banner；`when` 写全，新增模式时编译器强制补分支。 */
internal fun brandBannerKind(mode: WorkMode): BrandBannerKind = when (mode) {
    WorkMode.LITE -> BrandBannerKind.ULTRA
}

/** Banner 的全部可调参数，装机目视后只改这里。 */
internal object BrandBannerTokens {
    // ── 尺寸 ─────────────────────────────────────────────────
    const val HEIGHT_RATIO = 0.27f
    val MIN_HEIGHT = 86.dp
    val MAX_HEIGHT = 118.dp

    /** 窗口矮于这个高度时不画 Banner，空间留给正文和按钮（横屏、小窗）。 */
    const val MIN_WINDOW_HEIGHT_DP = 520

    // ── 开场 ─────────────────────────────────────────────────
    /** 等弹窗入场走完大半再开始，两段动画不抢。 */
    const val REVEAL_DELAY_MS = 120L
    const val REVEAL_DURATION_MS = UltraBrandTokens.INTRO_DURATION_MS

    /** 关闭系统动画时定格在这一刻：亮斑分布最均匀。 */
    const val STILL_TIME_SECONDS = 3.4f

    // ── 方格（Ultra）─────────────────────────────────────────
    val SQUARE_PITCH = 3.2.dp
    val SQUARE_CELL = 2.25.dp

    /** 大字是主角、上面不压文字，浅色下不必像顶栏那样压低点阵。 */
    const val SQUARE_GAIN = 1.5f
    const val SQUARE_BLOOM_ALPHA_DARK = 0.55f
    const val SQUARE_BLOOM_ALPHA_LIGHT = 0.22f

    // ── 圆点（Normal）────────────────────────────────────────
    val DOT_PITCH = 4.dp
    val DOT_MIN_RADIUS = 0.55.dp
    val DOT_RADIUS_RANGE = 1.15.dp
    val DOT_SWELL_WAVELENGTH = 210.dp
    val DOT_SWELL_SPEED = 20.dp
    const val DOT_LEVELS = 5
    const val DOT_BLOOM_ALPHA_DARK = 0.22f
    const val DOT_BLOOM_ALPHA_LIGHT = 0.1f

    // ── 微缩文字（MapleSU）───────────────────────────────────
    val MICRO_ROW = 3.7.dp
    val MICRO_TEXT = 3.35.dp

    /** 相邻行错开，署名不会排成竖列。 */
    val MICRO_ROW_SHIFT = 37.3.dp

    /** 字样右缘到枫叶之间、枫叶自身都按 Banner 高度取比例。 */
    const val LEAF_SIZE_RATIO = 0.4f
    const val LEAF_GAP_RATIO = 0.05f
    const val LEAF_SWAY_SECONDS = 5.2f
    const val LEAF_TILT_DEGREES = 19.5f
    const val LEAF_SWAY_DEGREES = 4f
    val LEAF_BOB = 1.2.dp

    val SHEEN_HALF_WIDTH = 70.dp
    val SHEEN_SPEED = 58.dp

    /** 流光两次之间留白的路程。 */
    val SHEEN_REST = 440.dp

    // ── 辉光（仅 Miuix）────────────────────────────────────
    val BLOOM_RADIUS = 12.dp
}

/** 字形在列数 × 行数的网格里铺开；[left]、[top] 让网格在 Banner 内居中。 */
@Immutable
internal data class BrandBannerGrid(
    val pitch: Int,
    val cols: Int,
    val rows: Int,
    val left: Float,
    val top: Float,
)

internal fun brandBannerGrid(widthPx: Int, heightPx: Int, pitchPx: Int): BrandBannerGrid {
    val pitch = pitchPx.coerceAtLeast(2)
    val cols = widthPx / pitch
    val rows = heightPx / pitch
    return BrandBannerGrid(
        pitch = pitch,
        cols = cols,
        rows = rows,
        left = (widthPx - cols * pitch) / 2f,
        top = (heightPx - rows * pitch) / 2f,
    )
}

/** 高度随宽度走，并收在上下限之内：窄屏不挤正文，平板不撑满弹窗。 */
internal fun brandBannerHeightPx(widthPx: Int, minPx: Int, maxPx: Int): Int =
    (widthPx * BrandBannerTokens.HEIGHT_RATIO).roundToInt().coerceIn(minPx, maxPx)

/**
 * 弹窗顶部的品牌 Banner。
 *
 * 本身不带底色，直接画在弹窗表面上，所以四种皮肤与明暗组合都不需要各做一张图。
 * 贴图在后台线程烘好；帧时间只在绘制阶段读取，动画不触发重组。
 *
 * @param accent [BrandBannerKind.NORMAL] 使用的主题色，其余两种用品牌色
 * @param soft Miuix 传 true：有辉光、边缘柔和；Material 传 false：无辉光、边缘利落
 */
@Composable
fun BrandBanner(
    kind: BrandBannerKind,
    accent: Color,
    soft: Boolean,
    modifier: Modifier = Modifier,
) {
    if (LocalConfiguration.current.screenHeightDp < BrandBannerTokens.MIN_WINDOW_HEIGHT_DP) return

    val dark = isInDarkTheme()
    val pxPerDp = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val art by produceState<BrandBannerArt?>(null, kind, accent, soft, dark, size, pxPerDp) {
        if (size.width <= 0 || size.height <= 0) return@produceState
        val target = size
        value = withContext(Dispatchers.Default) {
            bakeBrandBanner(kind, target.width, target.height, pxPerDp, dark, soft, accent.toArgb())
        }
    }

    val ready = art != null
    val reveal = remember { Animatable(0f) }
    val time = remember { mutableFloatStateOf(BrandBannerTokens.STILL_TIME_SECONDS) }
    val motionEnabled = rememberMotionEnabled()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val ticking = ready && motionEnabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    LaunchedEffect(ready, motionEnabled) {
        if (!ready) return@LaunchedEffect
        if (!motionEnabled) {
            reveal.snapTo(1f)
            return@LaunchedEffect
        }
        delay(BrandBannerTokens.REVEAL_DELAY_MS)
        reveal.animateTo(1f, tween(BrandBannerTokens.REVEAL_DURATION_MS, easing = LinearEasing))
    }
    LaunchedEffect(ticking) {
        if (ticking) runBrandClock(time)
    }

    Spacer(
        modifier = modifier
            .brandBannerSize()
            .onSizeChanged { size = it }
            .graphicsLayer()
            .drawBehind {
                art?.draw(drawContext.canvas.nativeCanvas, time.floatValue, reveal.value)
            },
    )
}

private fun Modifier.brandBannerSize(): Modifier = layout { measurable, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val height = brandBannerHeightPx(
        widthPx = width,
        minPx = BrandBannerTokens.MIN_HEIGHT.roundToPx(),
        maxPx = BrandBannerTokens.MAX_HEIGHT.roundToPx(),
    )
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}
