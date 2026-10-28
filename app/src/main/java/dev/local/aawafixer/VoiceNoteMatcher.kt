package dev.local.aawafixer

import java.util.Calendar
import kotlin.math.abs

data class MatchCandidate(
    val id: String,
    val lastModified: Long,
    val durationMs: Long?,
)

object VoiceNoteMatcher {
    const val DURATION_TOLERANCE_MS = 1_500L

    /**
     * Regole, nell'ordine:
     * 1. i file con durata entro la tolleranza dal valore mostrato in chat;
     * 2. tra questi, quello con orario di modifica piu' vicino all'orario del
     *    messaggio (distanza circolare sulla giornata), a parita' il piu' recente;
     * 3. se nessun file ha la durata giusta, l'ultimo .opus modificato.
     */
    fun choose(
        candidates: List<MatchCandidate>,
        targetDurationMs: Long?,
        targetClockMinutes: Int?,
        toleranceMs: Long = DURATION_TOLERANCE_MS,
    ): MatchCandidate? {
        if (candidates.isEmpty()) return null

        val durationMatches = if (targetDurationMs == null) {
            emptyList()
        } else {
            candidates.filter { candidate ->
                val duration = candidate.durationMs ?: return@filter false
                abs(duration - targetDurationMs) <= toleranceMs
            }
        }

        if (durationMatches.isNotEmpty()) {
            return durationMatches.sortedWith(
                compareBy<MatchCandidate> {
                    clockDistanceMinutes(it.lastModified, targetClockMinutes)
                }.thenByDescending { it.lastModified },
            ).first()
        }

        return candidates.maxByOrNull { it.lastModified }
    }

    fun clockDistanceMinutes(lastModified: Long, targetClockMinutes: Int?): Int {
        if (targetClockMinutes == null) return 0
        val calendar = Calendar.getInstance().apply { timeInMillis = lastModified }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val raw = abs(minutes - targetClockMinutes)
        return minOf(raw, 24 * 60 - raw)
    }
}
