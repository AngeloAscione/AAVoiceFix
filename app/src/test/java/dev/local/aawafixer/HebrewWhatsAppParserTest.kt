package dev.local.aawafixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Struttura reale di WhatsApp 2.26.45 in ebraico (uiautomator dump). */
class HebrewWhatsAppParserTest {

    private var nextId = 0
    private fun node(viewId: String, cls: String, desc: String?, l: Int, t: Int, r: Int, b: Int) =
        UiNode(nextId++, viewId, cls, null, desc, l, t, r, b)

    private fun bubble(playDesc: String) = listOf(
        node("com.whatsapp:id/main_layout", "android.widget.LinearLayout",
            "‏את/ה, הודעה קולית, 32 שניות, 0:40, הופעלה", 100, 80, 900, 260),
        node("com.whatsapp:id/control_btn", "android.widget.ImageButton",
            "‏$playDesc", 150, 120, 230, 200),
        node("com.whatsapp:id/audio_seekbar", "android.widget.SeekBar",
            "‏הודעה קולית, הזמן שעבר: 0 שניות", 250, 140, 700, 180),
        node("com.whatsapp:id/date", "android.widget.TextView", null, 750, 210, 850, 250),
    )

    @Test
    fun parsesHebrewPlay() {
        val r = WhatsAppUiParser.parse(bubble("השמעת ההודעה הקולית"))
        assertNotNull(r)
        assertEquals(32, r!!.durationSeconds)
        assertEquals(40, r.clockMinutes)
        assertTrue(r.signature, r.signature.startsWith("את/ה|32|"))
    }

    @Test
    fun parsesHebrewPause() {
        assertNotNull(WhatsAppUiParser.parse(bubble("השהיית ההודעה הקולית")))
    }
}
