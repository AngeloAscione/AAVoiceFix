package dev.local.aawafixer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.util.ArrayDeque
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fixture catturato da WhatsApp 2.26.41.77 su Pixel 8 (Android 17):
 * la durata non e' un TextView ma sta nel contentDescription del fumetto
 * ("Greg Garofalo, messaggio vocale, 17 secondi, 6:30 PM, riprodotto").
 * La seekbar riporta "tempo trascorso: 8 secondi" e non va usata.
 */
class RealWhatsAppVoiceNoteFixtureTest {

    @Test
    fun parsesDurationAndClockFromRealBubble() {
        val nodes = loadRow()

        val result = WhatsAppUiParser.parse(nodes)!!

        assertEquals(17, result.durationSeconds)
        assertEquals(18 * 60 + 30, result.clockMinutes)
        assertEquals("Greg Garofalo|17|1110", result.signature)
    }

    private fun loadRow(): List<UiNode> {
        val stream = checkNotNull(
            javaClass.getResourceAsStream("/whatsapp_voice_note_row.xml"),
        ) { "fixture mancante" }
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(stream)
        val row = document.documentElement.firstChild as Element

        val queue = ArrayDeque<Element>()
        queue.add(row)
        val bounds = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
        val nodes = mutableListOf<UiNode>()

        while (queue.isNotEmpty()) {
            val element = queue.removeFirst()
            val match = bounds.find(element.getAttribute("bounds"))
            val (left, top, right, bottom) = if (match != null) {
                List(4) { match.groupValues[it + 1].toInt() }
            } else {
                listOf(0, 0, 0, 0)
            }
            nodes += UiNode(
                id = nodes.size,
                viewId = element.getAttribute("resource-id").ifBlank { null },
                className = element.getAttribute("class").ifBlank { null },
                text = element.getAttribute("text").ifBlank { null },
                contentDescription = element.getAttribute("content-desc").ifBlank { null },
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )
            for (index in 0 until element.childNodes.length) {
                (element.childNodes.item(index) as? Element)?.let { queue.add(it) }
            }
        }
        return nodes
    }
}
