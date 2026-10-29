package dev.local.aawafixer

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields

/**
 * Quali cartelle osservare durante la sessione Android Auto.
 *
 * Policy: solo i vocali degli ultimi 2 giorni e i nuovi download.
 * WhatsApp salva i PTT in cartelle settimanali `YYYYWW` (ISO), quindi si
 * osservano:
 *  - la radice (file scritti direttamente);
 *  - le cartelle della settimana di oggi e di ieri (anche se ancora vuote:
 *    e' li' che arrivano i nuovi download);
 *  - come rete di sicurezza, le due cartelle numeriche piu' recenti esistenti.
 *
 * Nessuna scansione delle mtime all'avvio: la selezione e' O(1).
 */
object VoiceNoteTreePolicy {
    private const val ALWAYS_WATCH_DIRS = 2
    private const val RECENT_DAYS = 2

    fun selectWatchDirs(
        roots: List<File>,
        now: Long = System.currentTimeMillis(),
    ): List<File> {
        val weekKeys = weekKeys(now)
        val selected = ArrayList<File>()
        for (root in roots) {
            if (!root.isDirectory) continue
            selected += root

            val subdirs = root.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                .orEmpty()

            subdirs.filter { it.name in weekKeys }.forEach { selected += it }
            subdirs
                .filter { it.name.toIntOrNull() != null }
                .sortedByDescending { it.name.toInt() }
                .take(ALWAYS_WATCH_DIRS)
                .forEach { selected += it }
        }
        return selected.distinctBy { it.absolutePath }
    }

    /** Chiavi `YYYYWW` ISO della data corrente e dei giorni precedenti. */
    internal fun weekKeys(
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Set<String> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0 until RECENT_DAYS).map { offset ->
            val date = today.minusDays(offset.toLong())
            val weekYear = date.get(WeekFields.ISO.weekBasedYear())
            val week = date.get(WeekFields.ISO.weekOfWeekBasedYear())
            "%d%02d".format(weekYear, week)
        }.toSet()
    }
}
