package dev.local.aawafixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class VoiceNoteMatcherTest {

    private fun at(hour: Int, minute: Int, day: Int = 28): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun picksDurationMatchClosestToMessageTime() {
        val candidates = listOf(
            MatchCandidate("five-minutes-off", at(18, 25), 24_400),
            MatchCandidate("one-minute-off", at(18, 31), 24_300),
            MatchCandidate("wrong-duration", at(18, 30), 9_000),
        )

        val chosen = VoiceNoteMatcher.choose(candidates, 24_000, 18 * 60 + 30)

        assertEquals("one-minute-off", chosen?.id)
    }

    @Test
    fun acceptsDurationInsideTolerance() {
        val candidates = listOf(
            MatchCandidate("inside", at(10, 0), 25_200),
            MatchCandidate("outside", at(18, 30), 26_000),
        )

        val chosen = VoiceNoteMatcher.choose(candidates, 24_000, 18 * 60 + 30)

        assertEquals("inside", chosen?.id)
    }

    @Test
    fun doesNotGuessWhenNoDurationMatches() {
        val candidates = listOf(
            MatchCandidate("older", at(8, 0), 9_000),
            MatchCandidate("newer", at(20, 0), 30_000),
        )

        assertNull(VoiceNoteMatcher.choose(candidates, 24_000, 18 * 60 + 30))
    }

    @Test
    fun doesNotGuessWhenDurationUnknown() {
        val candidates = listOf(
            MatchCandidate("older", at(8, 0), null),
            MatchCandidate("newer", at(20, 0), null),
        )

        assertNull(VoiceNoteMatcher.choose(candidates, null, null))
    }

    @Test
    fun doesNotMatchOnClockAlone() {
        val candidates = listOf(
            MatchCandidate("same-clock", at(18, 30), 30_000),
        )

        assertNull(VoiceNoteMatcher.choose(candidates, 24_000, 18 * 60 + 30))
    }

    @Test
    fun handlesEmptyCandidates() {
        assertNull(VoiceNoteMatcher.choose(emptyList(), 24_000, 1_110))
    }

    @Test
    fun clockDistanceWrapsAroundMidnight() {
        assertEquals(10, VoiceNoteMatcher.clockDistanceMinutes(at(0, 5), 23 * 60 + 55))
    }
}
