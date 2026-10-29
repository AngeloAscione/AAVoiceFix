package dev.local.aawafixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class VoiceNoteTreePolicyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun millis(date: LocalDate): Long =
        date.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun dir(parent: File, name: String): File =
        File(parent, name).apply { mkdirs() }

    @Test
    fun watchesTodayAndYesterdayWeeksPlusRoot() {
        val now = millis(LocalDate.of(2026, 10, 29)) // ISO 202644
        val root = dir(temp.root, "WhatsApp Voice Notes")
        val todayWeek = dir(root, "202644")
        val previousWeek = dir(root, "202643")
        val oldWeek = dir(root, "202601")

        val selected = VoiceNoteTreePolicy.selectWatchDirs(listOf(root), now)
            .map { it.absolutePath }
            .toSet()

        assertTrue(selected.contains(root.absolutePath))
        assertTrue(selected.contains(todayWeek.absolutePath))
        assertTrue(selected.contains(previousWeek.absolutePath))
        assertFalse(selected.contains(oldWeek.absolutePath))
    }

    @Test
    fun watchesBothWeeksAcrossTheWeekBoundary() {
        // 2026-11-02 e' lunedi: ieri (domenica) e' ancora la settimana 44.
        val now = millis(LocalDate.of(2026, 11, 2)) // ISO 202645
        val root = dir(temp.root, "WhatsApp Voice Notes")
        val todayWeek = dir(root, "202645")
        val yesterdayWeek = dir(root, "202644")

        val keys = VoiceNoteTreePolicy.weekKeys(now, ZoneId.systemDefault())
        val selected = VoiceNoteTreePolicy.selectWatchDirs(listOf(root), now)
            .map { it.absolutePath }
            .toSet()

        assertEquals(setOf("202645", "202644"), keys)
        assertTrue(selected.contains(todayWeek.absolutePath))
        assertTrue(selected.contains(yesterdayWeek.absolutePath))
    }

    @Test
    fun keepsTwoNewestNumericFoldersAsSafetyNet() {
        val now = millis(LocalDate.of(2026, 10, 29))
        val root = dir(temp.root, "WhatsApp Voice Notes")
        val todayWeek = dir(root, "202644")
        val nextWeek = dir(root, "202645") // creata da WhatsApp in anticipo
        val oldWeek = dir(root, "202601")

        val selected = VoiceNoteTreePolicy.selectWatchDirs(listOf(root), now)
            .map { it.absolutePath }
            .toSet()

        assertTrue(selected.contains(todayWeek.absolutePath))
        assertTrue(selected.contains(nextWeek.absolutePath))
        assertFalse(selected.contains(oldWeek.absolutePath))
    }

    @Test
    fun ignoresNonExistentRoots() {
        assertEquals(
            emptyList<File>(),
            VoiceNoteTreePolicy.selectWatchDirs(
                listOf(File(temp.root, "missing")),
                millis(LocalDate.of(2026, 10, 29)),
            ),
        )
    }
}
