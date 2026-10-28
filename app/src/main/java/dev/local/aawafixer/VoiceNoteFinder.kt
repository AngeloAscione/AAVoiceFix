package dev.local.aawafixer

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class VoiceNoteFile(
    val uri: Uri,
    val name: String,
    val lastModified: Long,
    val durationMs: Long?,
)

/**
 * Cerca il file .opus corrispondente al vocale cliccato.
 *
 * Sorgenti, in ordine: accesso completo ai file (fast path), altrimenti la
 * tree URI SAF scelta dall'utente. La scansione e' limitata ai file piu'
 * recenti, ordina per mtime e legge la durata solo dei candidati.
 */
class VoiceNoteFinder(private val context: Context) {

    fun find(meta: VoiceNoteUi): VoiceNoteFile? {
        val files = scan()
        if (files.isEmpty()) return null
        val chosen = VoiceNoteMatcher.choose(
            candidates = files.map { MatchCandidate(it.uri.toString(), it.lastModified, it.durationMs) },
            targetDurationMs = meta.durationSeconds?.times(1000L),
            targetClockMinutes = meta.clockMinutes,
        ) ?: return null
        return files.firstOrNull { it.uri.toString() == chosen.id }
    }

    fun newest(): VoiceNoteFile? = scan().maxByOrNull { it.lastModified }

    private fun scan(): List<VoiceNoteFile> {
        val direct = scanDirect()
        if (direct != null) return direct
        return scanSaf()
    }

    private fun scanDirect(): List<VoiceNoteFile>? {
        if (!AppConfig.hasDirectFileAccess(context)) return null
        val found = mutableListOf<File>()
        val roots = AppConfig.directVoiceDirs()
        if (roots.isEmpty()) {
            Log.w(AppConfig.TAG, "directory WhatsApp Voice Notes non trovata")
            return emptyList()
        }
        roots.forEach { walkFile(it, 0, found) }
        return found
            .asSequence()
            .sortedByDescending { it.lastModified() }
            .take(MAX_FILES)
            .map { file ->
                VoiceNoteFile(
                    uri = Uri.fromFile(file),
                    name = file.name,
                    lastModified = file.lastModified(),
                    durationMs = OpusDuration.fromFile(file),
                )
            }
            .toList()
    }

    private fun walkFile(dir: File, depth: Int, out: MutableList<File>) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                walkFile(child, depth + 1, out)
            } else if (child.name.endsWith(".opus", ignoreCase = true)) {
                out += child
            }
        }
    }

    private fun scanSaf(): List<VoiceNoteFile> {
        val tree = AppConfig.safTreeUri(context) ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context, tree) ?: return emptyList()
        val found = mutableListOf<DocumentFile>()
        walkSaf(root, 0, found)
        return found
            .asSequence()
            .sortedByDescending { it.lastModified() }
            .take(MAX_FILES)
            .map { doc ->
                VoiceNoteFile(
                    uri = doc.uri,
                    name = doc.name ?: "voice.opus",
                    lastModified = doc.lastModified(),
                    durationMs = OpusDuration.fromUri(context, doc.uri),
                )
            }
            .toList()
    }

    private fun walkSaf(dir: DocumentFile, depth: Int, out: MutableList<DocumentFile>) {
        if (depth > MAX_DEPTH) return
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                walkSaf(child, depth + 1, out)
            } else if (child.isFile && child.name?.endsWith(".opus", ignoreCase = true) == true) {
                out += child
            }
        }
    }

    companion object {
        const val MAX_FILES = 200
        const val MAX_DEPTH = 3
    }
}
