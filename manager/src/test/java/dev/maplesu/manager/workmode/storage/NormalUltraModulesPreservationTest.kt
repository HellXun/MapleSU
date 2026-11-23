package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.switch.FileSwitchJournal
import dev.maplesu.manager.workmode.switch.FileSwitchMoveStage
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NormalUltraModulesPreservationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun switchingAndRollbackKeepKsuPayloadsSettingsAndMarkersLive() {
        val roots = MovePathRoots(File(tmp.root, "live"), File(tmp.root, "store"))
        val files = mapOf(
            "/data/adb/modules/enabled/module.prop" to "id=enabled\nversionCode=3\n",
            "/data/adb/modules/enabled/settings.json" to "{\"keep\":true}",
            "/data/adb/modules/disabled/module.prop" to "id=disabled\n",
            "/data/adb/modules/disabled/disable" to "",
            "/data/adb/modules/removed/remove" to "",
            "/data/adb/modules_update/updated/module.prop" to "id=updated\nversionCode=4\n",
        )
        files.forEach { (path, bytes) ->
            roots.live(path).apply { parentFile!!.mkdirs(); writeText(bytes) }
        }
        val stage = FileSwitchMoveStage(roots)
        val journal = FileSwitchJournal(File(tmp.root, "journal.jsonl"))
        for ((from, to) in listOf(WorkMode.LITE to WorkMode.LITE, WorkMode.LITE to WorkMode.LITE)) {
            val plan = stage.plan("keep-${from.suffix}", from, to)
            assertTrue(plan.validationErrors.toString(), plan.ok)
            assertTrue(stage.moveOut(plan, journal).ok)
            files.forEach { (path, bytes) ->
                assertTrue("missing after moveOut: $path", roots.live(path).isFile)
                assertEquals(bytes, roots.live(path).readText())
            }
            assertTrue(stage.moveIn(plan, journal).ok)
            assertTrue(stage.materialize(plan, journal).ok)
            assertTrue(stage.rollback(plan, journal).ok)
            files.forEach { (path, bytes) -> assertEquals(bytes, roots.live(path).readText()) }
        }
    }
}
