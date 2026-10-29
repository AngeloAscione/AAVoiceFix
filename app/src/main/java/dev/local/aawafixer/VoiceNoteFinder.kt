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

    data class FindResult(val file: VoiceNoteFile?)

    fun find(meta: VoiceNoteUi): FindResult {
        // Senza durata UI il fallback non puo' essere affidabile: inutile
        // scansionare 200 file per poi non riprodurre nulla.
        val targetDurationMs = meta.durationSeconds?.times(1000L)
            ?: return FindResult(null)
        val files = scan()
        if (files.isEmpty()) return FindResult(null)

        val candidates = files.map {
            MatchCandidate(it.uri.toString(), it.lastModified, it.durationMs)
        }
        val chosen = VoiceNoteMatcher.choose(
            candidates = candidates,
            targetDurationMs = targetDurationMs,
            targetClockMinutes = meta.clockMinutes,
        ) ?: return FindResult(null)

        return FindResult(files.firstOrNull { it.uri.toString() == chosen.id })
    }

    fun newest(): VoiceNoteFile? = scan().maxByOrNull { it.lastModified }

    private fun scan(): List<VoiceNoteFile> {
        val direct = scanDirect()
        if (direct != null) return direct
        return scanSaf()
    }

    private fun scanDirect(): List<VoiceNoteFile>? {
        if (!AppConfig.hasDirectFileAccess(context)) return null
        val roots = AppConfig.directVoiceDirs()
        if (roots.isEmpty()) {
            Log.w(AppConfig.TAG, "directory WhatsApp Voice Notes non trovata")
            return emptyList()
        }
        val found = mutableListOf<File>()
        for (root in roots) {
            collectDirect(root, found)
            if (found.size >= MAX_FILES) break
        }
        return found
            .distinctBy { it.absolutePath }
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
    }

    /**
     * WhatsApp organizza i PTT in sottocartelle settimanali (`202643`, ...).
     * Si visitano prima le piu' recenti (nome numerico decrescente) e ci si
     * ferma appena raccolti abbastanza file: 286 cartelle scansionate per
     * intero renderebbero l'intercettazione lenta.
     */
    private fun collectDirect(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isFile && child.name.endsWith(".opus", ignoreCase = true)) out += child
        }
        val subdirs = children
            .filter { it.isDirectory }
            .sortedWith(
                compareByDescending<File> { weekFolderKey(it.name) }
                    .thenByDescending { it.lastModified() },
            )
            .take(MAX_FOLDERS)

        for (subdir in subdirs) {
            if (out.size >= MAX_FILES) return
            val subChildren = subdir.listFiles() ?: continue
            for (child in subChildren) {
                if (child.isFile && child.name.endsWith(".opus", ignoreCase = true)) out += child
            }
            // Alcune versioni annidano un ulteriore livello.
            for (nested in subChildren.filter { it.isDirectory }) {
                if (out.size >= MAX_FILES) return
                nested.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".opus", ignoreCase = true)) out += file
                }
            }
        }
    }

    /** `202643` -> 202643, cosi' l'ordine decrescente e' il piu' recente. */
    private fun weekFolderKey(name: String): Int = name.toIntOrNull() ?: -1

    private fun scanSaf(): List<VoiceNoteFile> {
        val tree = AppConfig.safTreeUri(context) ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context, tree) ?: return emptyList()
        val found = mutableListOf<DocumentFile>()
        collectSaf(root, found)
        return found
            .distinctBy { it.uri.toString() }
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
    }

    private fun collectSaf(dir: DocumentFile, out: MutableList<DocumentFile>) {
        val children = dir.listFiles()
        for (child in children) {
            if (child.isFile && child.name?.endsWith(".opus", ignoreCase = true) == true) {
                out += child
            }
        }
        val subdirs = children
            .filter { it.isDirectory }
            .sortedWith(
                compareByDescending<DocumentFile> { weekFolderKey(it.name.orEmpty()) }
                    .thenByDescending { it.lastModified() },
            )
            .take(MAX_FOLDERS)

        for (subdir in subdirs) {
            if (out.size >= MAX_FILES) return
            for (child in subdir.listFiles()) {
                if (child.isFile && child.name?.endsWith(".opus", ignoreCase = true) == true) {
                    out += child
                }
            }
        }
    }

    companion object {
        const val MAX_FILES = 200
        const val MAX_FOLDERS = 40
    }
}
