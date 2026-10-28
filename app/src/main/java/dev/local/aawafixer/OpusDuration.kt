package dev.local.aawafixer

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Durata di un file Ogg/Opus leggendo il granule position dell'ultima pagina
 * Ogg. Nessuna dipendenza, nessun decode: bastano gli ultimi 256 KiB.
 *
 * La durata e' `granule / 48000` secondi; il pre-skip (pochi ms) e' ignorato,
 * molto sotto la tolleranza di matching (1.5 s).
 */
object OpusDuration {
    private const val OPUS_RATE = 48_000L
    private const val TAIL_BYTES = 256 * 1024

    fun fromFile(file: File): Long? {
        if (!file.isFile || file.length() < 27) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                val chunk = minOf(size, TAIL_BYTES.toLong()).toInt()
                val buffer = ByteArray(chunk)
                raf.seek(size - chunk)
                raf.readFully(buffer)
                parseTail(buffer)
            }
        } catch (e: IOException) {
            null
        }
    }

    fun fromUri(context: Context, uri: Uri): Long? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { stream ->
                val size = stream.channel.size()
                if (size < 27) return null
                val chunk = minOf(size, TAIL_BYTES.toLong()).toInt()
                val buffer = ByteArray(chunk)
                stream.channel.position(size - chunk)
                var read = 0
                while (read < chunk) {
                    val count = stream.read(buffer, read, chunk - read)
                    if (count < 0) break
                    read += count
                }
                if (read == chunk) parseTail(buffer) else null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Visibile per i test: cerca l'ultima pagina OggS con granule valido. */
    fun parseTail(tail: ByteArray): Long? {
        var index = tail.size - 27
        while (index >= 0) {
            if (tail[index] == 'O'.code.toByte() &&
                tail[index + 1] == 'g'.code.toByte() &&
                tail[index + 2] == 'g'.code.toByte() &&
                tail[index + 3] == 'S'.code.toByte()
            ) {
                val granule = readGranule(tail, index + 6)
                if (granule > 0) return granule * 1000L / OPUS_RATE
            }
            index--
        }
        return null
    }

    private fun readGranule(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
