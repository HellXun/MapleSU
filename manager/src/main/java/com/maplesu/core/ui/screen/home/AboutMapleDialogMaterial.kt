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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maplesu.core.R
import com.maplesu.core.ui.component.maple.BrandBannerKind
import com.maplesu.core.ui.component.maple.BrandDialogMaterial
import com.maplesu.core.ui.component.maple.BrandIntroLineMaterial
import com.maplesu.core.ui.component.maple.ultraBrandPalette
import com.maplesu.core.ui.theme.isInDarkTheme
import dev.maplesu.manager.workmode.WorkMode

/**
 * 「关于 MapleSU」小窗 · Material。
 *
 * @param showModes false 时不显示工作模式信息。
 */
@Composable
internal fun AboutMapleDialogMaterial(showModes: Boolean, onDismiss: () -> Unit) {
    BrandDialogMaterial(
        kind = BrandBannerKind.MAPLE,
        title = stringResource(R.string.about_maple_title),
        lines = listOf(aboutOverview(showModes)),
        onDismiss = onDismiss,
    ) {
        if (showModes) {
            UltraSectionMaterial()
            NormalSectionMaterial()
        }
        BrandIntroLineMaterial(R.string.about_maple_developers)
        Text(
            text = aboutCommunityText(stringResource(R.string.about_maple_community), MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        CreditsMaterial()
        AdvisorWallMaterial(Modifier.padding(top = 4.dp))
    }
}

/** 重点段：标题用 Ultra 品牌紫，要点用正文高亮色，方块项目符号呼应点阵。 */
@Composable
private fun UltraSectionMaterial() {
    val palette = ultraBrandPalette(isInDarkTheme())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = aboutModeHeading(WorkMode.LITE),
            style = MaterialTheme.typography.bodyMedium,
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
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun NormalSectionMaterial() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = aboutModeHeading(WorkMode.KSU),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.about_maple_normal),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CreditsMaterial() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.about_maple_credits),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ABOUT_CREDITS.forEach { credit ->
            Text(
                text = aboutCreditText(credit, stringResource(credit.role), MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
