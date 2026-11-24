package com.maplesu.core.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2

class AdvisorRosterTest {
    private val assets = File("src/main/assets/maplesu/advisors")

    private fun advisor(id: String, group: AdvisorGroup) = Advisor(id = id, name = id.uppercase(), group = group)

    @Test fun packagedRosterParsesAndEveryMemberHasAPicture() {
        val members = AdvisorRoster.parse(File(assets, "members.json").readText())
        assertTrue(members.isNotEmpty())
        assertEquals(1, members.count { it.group == AdvisorGroup.AUTHOR })
        assertEquals(members.size, members.map { it.id }.toSet().size)
        for (member in members) {
            assertTrue("avatars/${member.id}.jpg is missing",
                File(assets, "avatars/${member.id}.jpg").isFile || File(assets, "avatars/${member.id}.png").isFile)
            if (member.beta) assertEquals(AdvisorGroup.THANKS, member.group)
        }
    }

    @Test fun authorSitsInTheMiddleAndEveryMemberHasOneSeat() {
        val members = AdvisorRoster.parse(File(assets, "members.json").readText())
        val seats = AdvisorRoster.seats(members)
        assertEquals(members.toSet(), seats.map { it.advisor }.toSet())
        assertEquals(AdvisorGroup.AUTHOR, seats.first().advisor.group)
        assertEquals(0.0, seats.first().distance, 1e-9)
        assertEquals(seats.size, seats.map { "%.4f,%.4f".format(it.x, it.y) }.toSet().size)
        val thanks = seats.filter { it.advisor.group == AdvisorGroup.THANKS }
        if (thanks.size <= 6) assertTrue(thanks.all { it.shell == 1 })
    }

    /** 与 tools/brand/render_advisor_wall.py 的 assign() 对同一份名单的输出逐座一致（README 的墙与应用里的墙相同）。 */
    @Test fun seatsMatchTheReadmeRenderer() {
        val members = (1..11).map { advisor("b$it", AdvisorGroup.BETA) } +
            (1..3).map { advisor("t$it", AdvisorGroup.THANKS) } + advisor("a", AdvisorGroup.AUTHOR)
        val expected = listOf(
            "a" to (0.0 to 0.0), "t1" to (0.5 to -0.866), "b1" to (1.0 to 0.0), "t2" to (0.5 to 0.866),
            "b2" to (-0.5 to 0.866), "t3" to (-1.0 to 0.0), "b3" to (-0.5 to -0.866), "b4" to (0.0 to -1.7321),
            "b5" to (1.5 to -0.866), "b6" to (1.5 to 0.866), "b7" to (0.0 to 1.7321), "b8" to (-1.5 to 0.866),
            "b9" to (-1.5 to -0.866), "b10" to (1.0 to -1.7321), "b11" to (-1.0 to 1.7321),
        )
        val seats = AdvisorRoster.seats(members)
        assertEquals(expected.map { it.first }, seats.map { it.advisor.id })
        expected.zip(seats).forEach { (want, seat) ->
            assertEquals(want.second.first, seat.x, 1e-3)
            assertEquals(want.second.second, seat.y, 1e-3)
        }
        // 三位特别致谢成员在第一圈上相隔 120°。
        val angles = seats.filter { it.advisor.group == AdvisorGroup.THANKS }
            .map { Math.toDegrees(atan2(it.x, -it.y)).let { a -> if (a < 0) a + 360 else a } }
        assertEquals(listOf(30.0, 150.0, 270.0), angles.map { Math.round(it).toDouble() })
    }

    @Test fun lensMatchesTheRendererAndIsTheIntegralOfItsScale() {
        mapOf(0.5f to 0.482861f, 0.9f to 0.749268f, 1.3f to 0.9308f, 1.75f to 0.9875f, 3f to 0.9875f).forEach { (u, r) ->
            assertEquals("radius($u)", r, AdvisorLens.radius(u), 1e-4f)
        }
        val steps = 4000
        val h = 2f / steps
        var area = 0f
        for (i in 0 until steps) {
            area += (AdvisorLens.scale(i * h) + AdvisorLens.scale((i + 1) * h)) / 2f * h
            assertTrue(abs(area - AdvisorLens.radius((i + 1) * h)) < 1e-3f)
        }
        assertEquals(1f, AdvisorLens.scale(AdvisorLens.FLAT), 0f)
        assertEquals(AdvisorLens.EDGE_SCALE, AdvisorLens.scale(1f), 1e-6f)
        assertEquals(0f, AdvisorLens.scale(AdvisorLens.FADE_END), 1e-6f)
    }

    @Test fun brokenEntriesAreSkippedInsteadOfCrashingTheDialog() {
        val members = AdvisorRoster.parse(
            """
            {"members": [
              {"id": "ok", "name": "OK", "group": "author", "tags": ["UI", ""], "github": "someone"},
              {"id": "Bad Id", "name": "X", "group": "beta"},
              {"id": "noname", "name": " ", "group": "beta"},
              {"id": "nogroup", "name": "Y", "group": "guest"},
              {"id": "ok", "name": "Duplicate", "group": "beta"},
              "not an object"
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("ok"), members.map { it.id })
        assertEquals(listOf("UI"), members.single().tags)
        assertEquals(listOf(AdvisorLink.GITHUB to "https://github.com/someone"), members.single().links)
    }

    @Test fun englishFallsBackToTheOriginalRoleAndBetaThanksMembersWearBothBadges() {
        val member = Advisor(id = "x", name = "X", group = AdvisorGroup.THANKS, beta = true, role = "中文")
        assertEquals("中文", member.role(english = true))
        assertEquals("译文", member.copy(roleEn = "译文").role(english = true))
        assertEquals(listOf(AdvisorGroup.THANKS, AdvisorGroup.BETA), member.badges)
        assertEquals(listOf(AdvisorGroup.BETA), advisor("y", AdvisorGroup.BETA).badges)
    }

    @Test fun bothAboutDialogsShowTheWallAfterTheSpecialThanksLine() {
        for ((skin, call) in listOf("Miuix" to "AdvisorWallMiuix(", "Material" to "AdvisorWallMaterial(")) {
            val source = File("src/main/java/com/maplesu/core/ui/screen/home/AboutMapleDialog$skin.kt").readText()
            val thanks = source.indexOf("specialThanksText(stringResource(R.string.about_maple_special_thanks)")
            assertTrue(thanks >= 0 && source.indexOf(call) > thanks)
        }
    }

    @Test fun stringsHaveTheSameKeysInBothLanguages() {
        fun strings(locale: String) = Regex("<string name=\"(\\w+)\">(.*?)</string>")
            .findAll(File("src/main/res/$locale/maplesu_advisors.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        val english = strings("values")
        val chinese = strings("values-zh-rCN")
        assertEquals(english.keys, chinese.keys)
        assertEquals("en", english["advisors_language"])
        assertEquals("zh", chinese["advisors_language"])
    }
}
