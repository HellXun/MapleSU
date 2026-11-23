package com.maplesu.core.ui.screen.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.maplesu.core.R
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.ui.WorkModeStrings

// Presentation and shared announcement mechanism from 7kimisu (GPL-3.0-or-later).
// MapleSU branding and accurate development capability description.
internal const val ANNOUNCEMENT_TITLE = "MapleSU"

internal val ANNOUNCEMENT_TEXT = """
### MapleSU

开发者：MapleAutumn、小酒。

基于 MapleSU 的 Android Root 管理器。
保留 Material 与 Miuix 两套界面，支持主题、壁纸和个性化设置。

感谢 FinaSU、KernelSU、7kimisu 与其他上游项目的作者和贡献者。
""".trimIndent()

internal const val STATUS_CARD_TEXT = "MapleSU"

/** 隐藏模式下不显示工作模式信息。 */
@StringRes
internal fun aboutOverview(showModes: Boolean): Int =
    if (showModes) R.string.about_maple_overview else R.string.about_maple_overview_plain

/** Ultra 的三个要点：与模式介绍小窗同一口径，顺序就是重点顺序。 */
internal val ABOUT_ULTRA_POINTS = listOf(
    R.string.about_maple_ultra_point_kernel,
    R.string.about_maple_ultra_point_selinux,
    R.string.about_maple_ultra_point_side_channel,
)

/** 与模式介绍小窗同款标题，例如 `MapleSU_Ultra · 极致无痕模式`。 */
@Composable
internal fun aboutModeHeading(): String = stringResource(
    R.string.work_mode_intro_title,
    stringResource(WorkModeStrings.modeNameRes(WorkMode.LITE)),
    stringResource(WorkModeStrings.modeTaglineRes(WorkMode.LITE)),
)

/** 致谢的一行。项目名是链接，所以不随语言翻译。 */
internal data class AboutCredit(val name: String, val url: String, @StringRes val role: Int)

internal const val ABOUT_TELEGRAM_URL = "https://t.me/KernelMapleSU"

internal val ABOUT_CREDITS = listOf(
    AboutCredit(
        name = "FinaSU",
        url = "https://github.com/LyraVoid/FinaSU",
        role = R.string.about_maple_credit_finasu,
    ),
    AboutCredit(
        name = "KernelSU",
        url = "https://github.com/tiann/KernelSU",
        role = R.string.about_maple_credit_kernelsu,
    ),
    AboutCredit(
        name = "7kimisu",
        url = "https://github.com/hartgerinktarcila-design/7kimisu",
        role = R.string.about_maple_credit_7kimisu,
    ),
    AboutCredit(
        name = "KernelPatch",
        url = "https://github.com/bmax121/KernelPatch/",
        role = R.string.about_maple_credit_kernelpatch,
    ),
    AboutCredit(
        name = "SKRoot",
        url = "https://github.com/abcz316/SKRoot-linuxKernelRoot",
        role = R.string.about_maple_credit_sdk,
    ),
)

internal fun aboutCreditText(credit: AboutCredit, role: String, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        withLink(aboutCreditLink(credit.url, linkColor)) { append(credit.name) }
        append(" · ")
        append(role)
    }

internal fun aboutCreditLink(url: String, linkColor: Color): LinkAnnotation.Url =
    LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))

internal fun aboutCommunityText(label: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    append(label)
    append('\n')
    withLink(aboutCreditLink(ABOUT_TELEGRAM_URL, linkColor)) { append("Telegram · @KernelMapleSU") }
}

/** 特别致谢里有主页的成员，链接与 README、CREDITS 一致；名字不随语言翻译。 */
internal val SPECIAL_THANKS_LINKS = listOf(
    "有希" to "https://github.com/matsuzaka-yuki",
    "佐佐鱼" to "https://github.com/Yutou04-sa",
    "知秋" to "https://github.com/947409161",
)

/** Only the contributor names are interactive; translated surroundings keep their normal style. */
internal fun specialThanksText(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val names = SPECIAL_THANKS_LINKS
        .mapNotNull { (name, url) -> text.indexOf(name).takeIf { it >= 0 }?.let { start -> Triple(start, name, url) } }
        .sortedBy { it.first }
    var end = 0
    for ((start, name, url) in names) {
        if (start < end) continue
        append(text.substring(end, start))
        withLink(aboutCreditLink(url, linkColor)) { append(name) }
        end = start + name.length
    }
    append(text.substring(end))
}
