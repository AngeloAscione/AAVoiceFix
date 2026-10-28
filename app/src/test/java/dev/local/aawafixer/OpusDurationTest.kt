package dev.local.aawafixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class OpusDurationTest {

    private fun oggPage(granule: Long): ByteArray {
        val page = ByteArray(27)
        "OggS".toByteArray().copyInto(page, 0)
        page[4] = 0
        page[5] = 4
        for (index in 0 until 8) {
            page[6 + index] = ((granule shr (8 * index)) and 0xFF).toByte()
        }
        return page
    }

    @Test
    fun readsGranuleOfLastPage() {
        val tail = oggPage(48_000 * 10) + oggPage(48_000 * 24 + 24_000)

        assertEquals(24_500L, OpusDuration.parseTail(tail))
    }

    @Test
    fun skipsInvalidTrailingPage() {
        val tail = oggPage(48_000 * 7) + oggPage(-1L)

        assertEquals(7_000L, OpusDuration.parseTail(tail))
    }

    @Test
    fun returnsNullWithoutOggHeader() {
        assertNull(OpusDuration.parseTail(ByteArray(64)))
    }

    @Test
    fun readsDurationFromRealFileLayout() {
        val file = File.createTempFile("voice", ".opus").apply {
            writeBytes(ByteArray(50) + oggPage(48_000 * 3 + 12_000))
        }
        try {
            assertEquals(3_250L, OpusDuration.fromFile(file))
        } finally {
            file.delete()
        }
    }
}
