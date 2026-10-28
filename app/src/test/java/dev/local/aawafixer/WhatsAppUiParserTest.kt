package dev.local.aawafixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppUiParserTest {

    private var nextId = 0

    private fun node(
        viewId: String? = null,
        className: String? = "android.widget.TextView",
        text: String? = null,
        contentDescription: String? = null,
        left: Int = 0,
        top: Int = 0,
        right: Int = 100,
        bottom: Int = 40,
    ): UiNode = UiNode(
        id = nextId++,
        viewId = viewId,
        className = className,
        text = text,
        contentDescription = contentDescription,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    private fun voiceBubble(
        playDescription: String = "Riproduci messaggio vocale",
        durationText: String = "0:24",
    ): List<UiNode> = listOf(
        node(
            viewId = "com.whatsapp:id/voice_note_play",
            className = "android.widget.ImageButton",
            contentDescription = playDescription,
            left = 10,
            top = 100,
            right = 60,
            bottom = 150,
        ),
        node(className = "android.widget.SeekBar", left = 70, top = 115, right = 280, bottom = 135),
        node(text = durationText, left = 300, top = 105, right = 340, bottom = 145),
        node(text = "18:30", left = 400, top = 160, right = 440, bottom = 190),
    )

    @Test
    fun parsesPlayDurationAndClock() {
        val result = WhatsAppUiParser.parse(voiceBubble())

        assertEquals("Riproduci messaggio vocale", "0:24", WhatsAppUiParser.formatSeconds(result!!.durationSeconds!!))
        assertEquals("18:30", WhatsAppUiParser.formatClock(result.clockMinutes!!))
    }

    @Test
    fun durationComesFromPlayDescriptionWhenPresent() {
        val result = WhatsAppUiParser.parse(
            voiceBubble(playDescription = "Riproduci, 0:31"),
        )

        assertEquals(31, result!!.durationSeconds)
        assertEquals(18 * 60 + 30, result.clockMinutes)
    }

    @Test
    fun explicitClockInContentDescriptionWins() {
        val nodes = voiceBubble().map { node ->
            if (node.text == "18:30") {
                node.copy(contentDescription = "Inviato alle 09:05")
            } else {
                node
            }
        }

        val result = WhatsAppUiParser.parse(nodes)!!

        assertEquals(9 * 60 + 5, result.clockMinutes)
    }

    @Test
    fun rejectsVideoPlayButtons() {
        val nodes = voiceBubble(
            playDescription = "Riproduci video",
            durationText = "0:24",
        )

        assertNull(WhatsAppUiParser.parse(nodes))
    }

    @Test
    fun rejectsPlayButtonWithoutVoiceContext() {
        val nodes = listOf(
            node(
                className = "android.widget.ImageButton",
                contentDescription = "Riproduci",
                left = 10,
                top = 100,
                right = 60,
                bottom = 150,
            ),
            node(text = "0:24", left = 300, top = 105, right = 340, bottom = 145),
            node(text = "18:30", left = 400, top = 160, right = 440, bottom = 190),
        )

        assertNull(WhatsAppUiParser.parse(nodes))
    }

    @Test
    fun recognizesWhatsAppVoiceNoteBubbleByViewIds() {
        // Struttura reale del fumetto (WhatsApp 2.26.x, Pixel 9):
        // play a sinistra, waveform, durata sulla stessa riga, orario sotto.
        val nodes = listOf(
            node(
                viewId = "com.whatsapp:id/voice_note_playback_control",
                className = "android.widget.ImageButton",
                contentDescription = "Riproduci",
                left = 630,
                top = 800,
                right = 730,
                bottom = 900,
            ),
            node(
                viewId = "com.whatsapp:id/voice_note_seekbar",
                className = "android.widget.SeekBar",
                left = 740,
                top = 830,
                right = 940,
                bottom = 870,
            ),
            node(
                viewId = "com.whatsapp:id/voice_note_audio_duration",
                text = "0:24",
                left = 950,
                top = 820,
                right = 1020,
                bottom = 880,
            ),
            node(
                viewId = "com.whatsapp:id/date",
                text = "18:30",
                left = 920,
                top = 900,
                right = 1010,
                bottom = 930,
            ),
        )

        val result = WhatsAppUiParser.parse(nodes)!!

        assertEquals(24, result.durationSeconds)
        assertEquals(18 * 60 + 30, result.clockMinutes)
    }

    @Test
    fun voiceViewIdIsEnoughWithoutKeyword() {
        val nodes = listOf(
            node(
                viewId = "com.whatsapp:id/voice_note_play_button",
                className = "android.widget.ImageButton",
                contentDescription = "Riproduci",
                left = 10,
                top = 100,
                right = 60,
                bottom = 150,
            ),
            node(text = "1:05", left = 300, top = 105, right = 340, bottom = 145),
        )

        val result = WhatsAppUiParser.parse(nodes)!!

        assertEquals(65, result.durationSeconds)
        assertNull(result.clockMinutes)
    }
}
