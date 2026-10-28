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
    val summary: String,
)

object WhatsAppUiParser {

    private val PLAY = Regex("(?i)\\b(play|riproduci|ascolta|reproduci|abspielen|lecture)\\b")
    private val VOICE = Regex(
        "(?i)(voice|vocale|nota vocale|nota de voz|mensaje de voz|" +
            "voice message|voice note|audio message|messaggio audio)",
    )
    private val VIDEO = Regex("(?i)\\b(video|filmato)\\b")
    private val MMSS = Regex("^\\s*(\\d{1,3}):([0-5]\\d)\\s*$")
    private val CLOCK_IN_DESC = Regex(
        "(?i)(?:alle|at|inviato|ricevuto|sent|del)\\s+([01]?\\d|2[0-3]):([0-5]\\d)",
    )

    fun isPlayNode(node: UiNode): Boolean {
        if (node.className?.contains("SeekBar", ignoreCase = true) == true) return false
        val label = node.label ?: return false
        if (VIDEO.containsMatchIn(label)) return false
        if (PLAY.containsMatchIn(label)) return true
        val viewId = node.viewId?.lowercase().orEmpty()
        return viewId.contains("play") &&
            (viewId.contains("voice") || viewId.contains("ptt") || viewId.contains("audio"))
    }

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

        val explicitClock: Int? = nodes.firstNotNullOfOrNull { node ->
            node.contentDescription?.let { desc ->
                CLOCK_IN_DESC.find(desc)?.let { match ->
                    match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
                }
            }
        }

        val textCandidates = nodes.mapNotNull { node ->
            val raw = node.text?.trim() ?: return@mapNotNull null
            val match = MMSS.matchEntire(raw) ?: return@mapNotNull null
            val first = match.groupValues[1].toInt()
            val second = match.groupValues[2].toInt()
            TextCandidate(node, first * 60 + second)
        }.filter { it.node.id != play.id }

        val durationFromPlayLabel = play.label?.let { label ->
            Regex("(\\d{1,3}):([0-5]\\d)").find(label)?.let { match ->
                match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
            }
        }

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
        val durationSeconds = durationFromPlayLabel
            ?: durationNode?.let { chosen -> textCandidates.first { it.node.id == chosen.id }.seconds }

        // Se il contentDescription dichiara l'orario, ha priorita'.
        val clockMinutes = explicitClock ?: textCandidates
            .asSequence()
            .filter { it.node.id != durationNode?.id }
            .filter { it.node.centerY > play.centerY + rowTolerance / 2 }
            .maxByOrNull { it.node.bottom.toLong() * 10_000 + it.node.right }
            ?.seconds

        return VoiceNoteUi(
            playNodeId = play.id,
            durationSeconds = durationSeconds,
            clockMinutes = clockMinutes,
            summary = "durata=${durationSeconds?.let(::formatSeconds) ?: "?"} " +
                "ora=${clockMinutes?.let(::formatClock) ?: "?"}",
        )
    }

    fun formatSeconds(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    fun formatClock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    private data class TextCandidate(val node: UiNode, val seconds: Int)
}
