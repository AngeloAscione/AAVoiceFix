package dev.local.aawafixer

import kotlin.math.abs

/**
 * Snapshot immutabile di un nodo di accessibilita'. La logica di parsing e'
 * pura e testabile su JVM, senza dipendere da AccessibilityNodeInfo.
 */
data class UiNode(
    val id: Int,
    val viewId: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    val label: String?
        get() = contentDescription?.takeIf { it.isNotBlank() } ?: text?.takeIf { it.isNotBlank() }
}

data class VoiceNoteUi(
    val playNodeId: Int,
    val durationSeconds: Int?,
    val clockMinutes: Int?,
    /** Chiave stabile per la cache esatta: mittente|durata|orario. */
    val signature: String,
    val summary: String,
)

object WhatsAppUiParser {

    private val PLAY = Regex("(?i)\\b(play|riproduci|ascolta|reproduci|abspielen|lecture)\\b")
    // Dopo il click WhatsApp cambia subito la label del controllo in "Pausa":
    // l'evento di accessibilita' arriva quando il nodo e' gia' in questo stato.
    private val PAUSE = Regex("(?i)\\b(pausa|pause)\\b")
    private val VOICE = Regex(
        "(?i)(voice|vocale|nota vocale|nota de voz|mensaje de voz|" +
            "voice message|voice note|audio message|messaggio audio)",
    )
    private val VIDEO = Regex("(?i)\\b(video|filmato)\\b")
    private val MMSS = Regex("^\\s*(\\d{1,3}):([0-5]\\d)\\s*$")
    private val CLOCK_IN_DESC = Regex(
        "(?i)(?:alle|at|inviato|ricevuto|sent|del)\\s+([01]?\\d|2[0-3]):([0-5]\\d)",
    )

    // WhatsApp 2.26.x non espone la durata come testo: la scrive nel
    // contentDescription del fumetto ("… messaggio vocale, 17 secondi,
    // 6:30 PM, riprodotto") e l'orario e' in formato 12h.
    // WhatsApp usa U+202F (narrow no-break space) prima di AM/PM: `\s` di
    // Java non lo copre.
    private const val GAP = "[\\s\\u00A0\\u202F]*"
    private val SPOKEN_SECONDS = Regex(
        "(?i)(\\d{1,4})$GAP(?:secondi|secondo|seconds?|secs?)\\b",
    )
    private val SPOKEN_MINUTES = Regex(
        "(?i)(\\d{1,3})$GAP(?:minuti|minuto|minutes?|mins?)\\b",
    )
    private val ELAPSED = Regex("(?i)(trascorso|elapsed|rimanent|remaining|progress)")
    private val CLOCK_12 = Regex("(?i)\\b(\\d{1,2}):([0-5]\\d)$GAP(am|pm)\\b")
    private val CLOCK_24_BARE = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")

    fun isPlayNode(node: UiNode): Boolean {
        if (node.className?.contains("SeekBar", ignoreCase = true) == true) return false
        // Il player fuori chat non espone durata/orario del messaggio:
        // intercettarlo porterebbe a un fallback senza metadati.
        if (node.viewId?.contains("out_of_chat", ignoreCase = true) == true) return false
        val label = node.label ?: return false
        if (VIDEO.containsMatchIn(label)) return false
        if (PLAY.containsMatchIn(label) || PAUSE.containsMatchIn(label)) return true
        val viewId = node.viewId?.lowercase().orEmpty()
        return viewId.contains("play") &&
            (viewId.contains("voice") || viewId.contains("ptt") || viewId.contains("audio"))
    }

    fun isPauseLabel(label: String?): Boolean =
        label != null && PAUSE.containsMatchIn(label)

    fun hasVoiceContext(nodes: List<UiNode>): Boolean = nodes.any { node ->
        val label = node.label.orEmpty()
        VOICE.containsMatchIn(label) ||
            node.viewId?.let { it.contains("voice", true) || it.contains("ptt", true) } == true ||
            node.className?.contains("SeekBar", ignoreCase = true) == true
    }

    /**
     * Trova il pulsante Play e i due testi del fumetto:
     * la durata (vicina al pulsante) e l'orario (in basso a destra).
     */
    fun parse(nodes: List<UiNode>): VoiceNoteUi? {
        val play = nodes.firstOrNull(::isPlayNode) ?: return null
        if (!hasVoiceContext(nodes)) return null

        // Fonte primaria: il contentDescription del fumetto, che contiene
        // durata in parole e orario. La seekbar riporta il tempo trascorso
        // ("tempo trascorso: 8 secondi") e va esclusa.
        val spoken = nodes.asSequence()
            .mapNotNull { node ->
                val desc = node.contentDescription ?: return@mapNotNull null
                if (!VOICE.containsMatchIn(desc) || ELAPSED.containsMatchIn(desc)) {
                    return@mapNotNull null
                }
                // La seekbar riporta il tempo trascorso, non la durata; a
                // runtime la parola "trascorso" puo' sparire dalla desc.
                if (node.className?.contains("SeekBar", ignoreCase = true) == true) {
                    return@mapNotNull null
                }
                if (node.viewId?.contains("seekbar", ignoreCase = true) == true ||
                    node.viewId?.contains("visualizer", ignoreCase = true) == true
                ) {
                    return@mapNotNull null
                }
                val duration = spokenDuration(desc)?.takeIf { it > 0 }
                val clock = parseClock(desc)
                if (duration == null && clock == null) return@mapNotNull null
                SpokenSummary(duration, clock, desc)
            }
            .sortedByDescending {
                (if (it.durationSeconds != null) 1 else 0) +
                    (if (it.clockMinutes != null) 1 else 0)
            }
            .firstOrNull()

        val explicitClock: Int? = nodes.firstNotNullOfOrNull { node ->
            node.contentDescription?.let { desc ->
                CLOCK_IN_DESC.find(desc)?.let { match ->
                    match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
                }
            }
        }

        val textCandidates = nodes.mapNotNull { node ->
            // `description` e' il tempo trascorso mostrato nel fumetto
            // ("0:00"), non la durata totale.
            if (node.viewId?.endsWith("/description") == true) return@mapNotNull null
            val raw = node.text?.trim() ?: return@mapNotNull null
            val match = MMSS.matchEntire(raw) ?: return@mapNotNull null
            val first = match.groupValues[1].toInt()
            val second = match.groupValues[2].toInt()
            val seconds = first * 60 + second
            if (seconds <= 0) return@mapNotNull null
            TextCandidate(node, seconds)
        }.filter { it.node.id != play.id }

        val durationFromPlayLabel = play.label?.let { label ->
            Regex("(\\d{1,3}):([0-5]\\d)").find(label)?.let { match ->
                match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
            }
        }?.takeIf { it > 0 }

        // La durata sta sulla stessa riga del Play (a destra della waveform);
        // l'orario e' sotto. Classificare prima per riga evita che un orario
        // piu' vicino in linea d'aria venga scambiato per durata.
        val rowTolerance = maxOf((play.bottom - play.top) / 2, 32)
        val sameRow = textCandidates.filter {
            abs(it.node.centerY - play.centerY) <= rowTolerance
        }
        val durationNode = if (durationFromPlayLabel == null) {
            (sameRow.ifEmpty { textCandidates })
                .minByOrNull { abs(it.node.centerX - play.centerX) }
                ?.node
        } else {
            null
        }
        val durationSeconds = spoken?.durationSeconds
            ?: durationFromPlayLabel
            ?: durationNode?.let { chosen ->
                textCandidates.first { it.node.id == chosen.id }.seconds
            }

        val dateClock = nodes.firstNotNullOfOrNull { node ->
            val viewId = node.viewId ?: return@firstNotNullOfOrNull null
            if (!viewId.endsWith("/date")) return@firstNotNullOfOrNull null
            parseClock(node.text ?: node.contentDescription ?: "")
        }
        val textClock = textCandidates
            .asSequence()
            .filter { it.node.id != durationNode?.id }
            .filter { it.node.centerY > play.centerY + rowTolerance / 2 }
            .maxByOrNull { it.node.bottom.toLong() * 10_000 + it.node.right }
            ?.seconds
        val clockMinutes = spoken?.clockMinutes ?: explicitClock ?: dateClock ?: textClock

        // Firma stabile del messaggio: mittente + durata + orario. Lo stato
        // ("consegnato"/"riprodotto") cambia e non entra nella chiave.
        val speaker = spoken?.source
            ?.substringBefore(", messaggio vocale")
            ?.substringBefore(", voice message")
            ?.trim()
            ?.trimStart('\u200e')
            .orEmpty()
        val signature = if (speaker.isNotBlank()) {
            "$speaker|$durationSeconds|$clockMinutes"
        } else {
            "bounds=${play.left},${play.top}|$durationSeconds|$clockMinutes"
        }

        return VoiceNoteUi(
            playNodeId = play.id,
            durationSeconds = durationSeconds,
            clockMinutes = clockMinutes,
            signature = signature,
            summary = "durata=${durationSeconds?.let(::formatSeconds) ?: "?"} " +
                "ora=${clockMinutes?.let(::formatClock) ?: "?"}",
        )
    }

    fun formatSeconds(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    fun formatClock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    private fun spokenDuration(text: String): Int? {
        val seconds = SPOKEN_SECONDS.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = SPOKEN_MINUTES.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (seconds == null && minutes == null) return null
        return (minutes ?: 0) * 60 + (seconds ?: 0)
    }

    private fun parseClock(text: String): Int? {
        CLOCK_12.find(text)?.let { match ->
            var hour = match.groupValues[1].toInt() % 12
            if (match.groupValues[3].equals("PM", ignoreCase = true)) hour += 12
            return hour * 60 + match.groupValues[2].toInt()
        }
        // Un fumetto puo' contenere piu' orari: l'orario del messaggio e'
        // l'ultimo (la durata eventuale viene prima).
        return CLOCK_24_BARE.findAll(text).lastOrNull()?.let { match ->
            match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
        }
    }

    private data class TextCandidate(val node: UiNode, val seconds: Int)
    private data class SpokenSummary(
        val durationSeconds: Int?,
        val clockMinutes: Int?,
        val source: String,
    )
}
