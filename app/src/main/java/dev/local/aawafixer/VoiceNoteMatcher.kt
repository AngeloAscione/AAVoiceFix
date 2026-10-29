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
     * Fallback usato solo quando FileObserver non ha catturato l'open esatto:
     * 1. solo file con durata entro la tolleranza dal valore mostrato in chat;
     * 2. tra questi, quello con mtime piu' vicino all'orario del messaggio
     *    (distanza circolare sulla giornata), a parita' il piu' recente.
     *
     * Niente fallback all'ultimo file: dopo i test sul campo riprodurre un
     * vocale sbagliato e' peggio che non riprodurre nulla. Il chiamante
     * registra il caso nel log e nella telemetria.
     */
    fun choose(
        candidates: List<MatchCandidate>,
        targetDurationMs: Long?,
        targetClockMinutes: Int?,
        toleranceMs: Long = DURATION_TOLERANCE_MS,
    ): MatchCandidate? {
        if (candidates.isEmpty() || targetDurationMs == null) return null

        return durationMatches(candidates, targetDurationMs, toleranceMs).sortedWith(
            compareBy<MatchCandidate> {
                clockDistanceMinutes(it.lastModified, targetClockMinutes)
            }.thenByDescending { it.lastModified },
        ).firstOrNull()
    }

    private fun durationMatches(
        candidates: List<MatchCandidate>,
        targetDurationMs: Long?,
        toleranceMs: Long = DURATION_TOLERANCE_MS,
    ): List<MatchCandidate> {
        if (targetDurationMs == null) return emptyList()
        return candidates.filter { candidate ->
            val duration = candidate.durationMs ?: return@filter false
            abs(duration - targetDurationMs) <= toleranceMs
        }
    }

    fun clockDistanceMinutes(lastModified: Long, targetClockMinutes: Int?): Int {
        if (targetClockMinutes == null) return 0
        val calendar = Calendar.getInstance().apply { timeInMillis = lastModified }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val raw = abs(minutes - targetClockMinutes)
        return minOf(raw, 24 * 60 - raw)
    }
}
