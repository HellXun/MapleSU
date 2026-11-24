package com.maplesu.core.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maplesu.core.R
import com.maplesu.core.ui.component.maple.BrandBannerKind
import com.maplesu.core.ui.component.maple.BrandDialogMiuix
import com.maplesu.core.ui.component.maple.BrandIntroLineMiuix
import com.maplesu.core.ui.component.maple.ultraBrandPalette
import com.maplesu.core.ui.theme.isInDarkTheme
import dev.maplesu.manager.workmode.WorkMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 「关于 MapleSU」小窗 · Miuix。
 *
 * @param showModes false 时不显示工作模式信息。
 */
@Composable
internal fun AboutMapleDialogMiuix(show: Boolean, showModes: Boolean, onDismiss: () -> Unit) {
    BrandDialogMiuix(
        show = show,
        kind = BrandBannerKind.MAPLE,
        title = stringResource(R.string.about_maple_title),
        lines = listOf(aboutOverview(showModes)),
        onDismiss = onDismiss,
    ) {
        if (showModes) {
            UltraSectionMiuix()
        }
        BrandIntroLineMiuix(R.string.about_maple_developers)
        Text(
            text = aboutCommunityText(stringResource(R.string.about_maple_community), colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
                .background(colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
        )
        CreditsMiuix()
        Text(
            text = specialThanksText(stringResource(R.string.about_maple_special_thanks), colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
            fontSize = 14.sp,
        )
        AdvisorWallMiuix(Modifier.padding(top = 4.dp))
    }
}

/** 重点段：标题用 Ultra 品牌紫，要点用正文高亮色，方块项目符号呼应点阵。 */
@Composable
private fun UltraSectionMiuix() {
    val palette = ultraBrandPalette(isInDarkTheme())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = aboutModeHeading(),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = palette.wordStart,
        )
        ABOUT_ULTRA_POINTS.forEach { res ->
            Row {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp, end = 8.dp)
                        .size(4.dp)
                        .background(palette.wordEnd),
                )
                Text(text = stringResource(res), fontSize = 14.sp, color = colorScheme.onSurface)
            }
        }
    }
}


@Composable
private fun CreditsMiuix() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.about_maple_credits),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
        )
        ABOUT_CREDITS.forEach { credit ->
            Text(
                text = aboutCreditText(credit, stringResource(credit.role), colorScheme.primary),
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
