package com.maplesu.core.ui.screen.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpecialThanksTextTest {
    private fun resource(locale: String, file: String, name: String): String =
        Regex("<string name=\"$name\">(.*?)</string>").find(
            File("src/main/res/$locale/$file.xml").readText())!!.groupValues[1]

    private val aboutLinks = mapOf(
        "有希" to "https://github.com/matsuzaka-yuki",
        "佐佐鱼" to "https://github.com/Yutou04-sa",
        "知秋" to "https://github.com/947409161",
    )
    /** Exactly the names in [expected] link to their homepages; the rest of the sentence stays plain. */
    private fun checkNameLinks(text: String, expected: Map<String, String>) {
        val annotated = specialThanksText(text, Color.Red)
        assertEquals(text, annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(expected, links.associate { annotated.text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url })
        for (link in links) {
            assertEquals(Color.Red, link.item.styles?.style?.color)
            assertEquals(TextDecoration.Underline, link.item.styles?.style?.textDecoration)
        }
        assertTrue("Surrounding text must keep its ordinary style", annotated.spanStyles.isEmpty())
    }

    @Test fun aboutListsTheSpecialThanksAndLinksTheirHomepages() {
        val text = resource("values-zh-rCN", "maplesu_about", "about_maple_special_thanks")
        assertEquals("特别致谢：小酒、晚风、有希、佐佐鱼、知秋。", text)
        checkNameLinks(text, aboutLinks)
    }

    /** README and CREDITS word the sentence in their own language; the names, their order and links must match. */
    @Test fun aboutMatchesTheReadmeAndCredits() {
        val names = resource("values-zh-rCN", "maplesu_about", "about_maple_special_thanks")
            .substringAfter('：').removeSuffix("。").split('、')
        assertEquals(listOf("小酒", "晚风", "有希", "佐佐鱼", "知秋"), names)
        for (doc in listOf("../README.md", "../CREDITS.md")) {
            val source = File(doc).readText()
            val plain = source.replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
            val line = plain.lines().single { line -> names.all { it in line } }
            assertEquals("$doc lists the names in the same order", names, names.sortedBy { line.indexOf(it) })
            aboutLinks.forEach { (person, url) -> assertTrue("$doc links $person", source.contains("[$person]($url)")) }
        }
    }

    @Test fun englishResourcesLinkTheSameNames() {
        checkNameLinks(resource("values", "maplesu_about", "about_maple_special_thanks"), aboutLinks)
    }

    @Test fun contributorLinkReusesTheProjectCreditStyle() {
        val project = aboutCreditText(ABOUT_CREDITS.first(), "role", Color.Red)
            .getLinkAnnotations(0, 7).single().item
        val contributor = specialThanksText("佐佐鱼", Color.Red).getLinkAnnotations(0, 3).single().item
        assertEquals(project.styles, contributor.styles)
    }

    @Test fun bothThemesUseTheSameFontAsTheProjectCredits() {
        val materialCredits = File("src/main/java/com/maplesu/core/ui/screen/home/AboutMapleDialogMaterial.kt")
            .readText().substringAfter("private fun CreditsMaterial()")
        val miuixCredits = File("src/main/java/com/maplesu/core/ui/screen/home/AboutMapleDialogMiuix.kt")
            .readText().substringAfter("private fun CreditsMiuix()")
        assertTrue(materialCredits.contains("style = MaterialTheme.typography.bodyMedium"))
        assertTrue(miuixCredits.contains("fontSize = 14.sp"))
        assertFalse(materialCredits.contains("BasicText"))
        assertFalse(miuixCredits.contains("BasicText"))
        assertFalse(materialCredits.contains("fontFamily ="))
        assertFalse(miuixCredits.contains("fontFamily ="))
    }

    @Test fun bothThemesPlaceTheNameInlineWithoutTheOldSeparateRow() {
        for (skin in listOf("Miuix", "Material")) {
            val source = File("src/main/java/com/maplesu/core/ui/screen/home/AboutMapleDialog$skin.kt").readText()
            assertTrue(source.contains("specialThanksText(stringResource(R.string.about_maple_special_thanks)"))
            assertFalse(source.contains("JailbreakSpecialThanks"))
        }
    }
}
