package dev.local.aawafixer

import android.os.FileObserver
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Cattura il percorso esatto del `.opus` che WhatsApp apre quando parte il
 * player nativo.
 *
 * Policy (richiesta esplicita): il watcher NON e' permanente. Viene creato
 * quando Android Auto si connette e distrutto alla disconnessione. Osserva
 * solo:
 *  - la radice delle note vocali;
 *  - le due cartelle settimanali piu' recenti (dove finiscono i nuovi file
 *    scaricati);
 *  - le cartelle che contengono un `.opus` modificato negli ultimi 2 giorni.
 *
 * FileObserver usa inotify; su `/storage/emulated/0` (FUSE) gli OPEN dei file
 * nelle directory osservate vengono consegnati. Gli open generati dall'app
 * stessa (scansione durate, player) sono esclusi.
 */
class VoiceNoteOpenWatcher(
    private val rootsProvider: () -> List<File>,
    /** Chiamato sul thread di FileObserver per ogni OPEN accettato. */
    private val onOpen: ((String) -> Unit)? = null,
) {
    private data class Watch(val path: String, val observer: FileObserver)

    private val lock = Any()
    private val watches = mutableListOf<Watch>()
    private val ignoreUntil = HashMap<String, Long>()

    private var lastPath: String? = null
    private var lastAtElapsed: Long = 0
    private var selfAccess = false
    private var acceptAfterElapsed = 0L

    /** Chiamato alla connessione Android Auto. */
    fun start() {
        synchronized(lock) {
            stopLocked()
            val dirs = VoiceNoteTreePolicy.selectWatchDirs(rootsProvider())
            for (dir in dirs) addWatchLocked(dir)
            Log.i(
                AppConfig.TAG,
                "watcher attivo su ${watches.size} cartelle: " +
                    watches.joinToString { File(it.path).name },
            )
        }
    }

    /** Chiamato alla disconnessione Android Auto o alla distruzione. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        watches.forEach { runCatching { it.observer.stopWatching() } }
        watches.clear()
        lastPath = null
        lastAtElapsed = 0
        acceptAfterElapsed = 0L
        ignoreUntil.clear()
    }

    /**
     * Aggiunge solo le directory nuove (settimana successiva, file recenti)
     * senza ricreare i watch esistenti: un restart perderebbe l'evento OPEN
     * appena arrivato.
     */
    fun ensureFresh() {
        synchronized(lock) {
            val have = watches.map { it.path }.toSet()
            VoiceNoteTreePolicy.selectWatchDirs(rootsProvider())
                .filter { it.absolutePath !in have }
                .forEach { addWatchLocked(it) }
        }
    }

    private fun addWatchLocked(dir: File) {
        val observer = object : FileObserver(dir.absolutePath, MASK) {
            override fun onEvent(event: Int, name: String?) {
                if (name == null || !name.endsWith(".opus", ignoreCase = true)) return
                record(File(dir, name).absolutePath)
            }
        }
        runCatching { observer.startWatching() }
            .onSuccess { watches += Watch(dir.absolutePath, observer) }
            .onFailure { Log.w(AppConfig.TAG, "watch fallito: ${dir.absolutePath}", it) }
    }

    /** Inizio accesso ai file da parte nostra: ignora gli eventi. */
    fun beginSelfAccess() {
        synchronized(lock) {
            selfAccess = true
            lastPath = null
            lastAtElapsed = 0
        }
    }

    fun endSelfAccess() {
        synchronized(lock) {
            selfAccess = false
            lastPath = null
            lastAtElapsed = 0
            acceptAfterElapsed = SystemClock.elapsedRealtime() + SELF_ACCESS_GRACE_MS
        }
    }

    /** Ignora gli open del nostro player finche' la traccia non termina. */
    fun ignoreOwn(path: String) {
        synchronized(lock) {
            ignoreUntil[path] = Long.MAX_VALUE
        }
    }

    /** Ignora un singolo open di probe (lettura durata) per un breve periodo. */
    fun ignoreOwnFor(path: String, forMs: Long) {
        synchronized(lock) {
            ignoreUntil[path] = SystemClock.elapsedRealtime() + forMs
        }
    }

    fun releaseOwn(path: String) {
        synchronized(lock) {
            ignoreUntil.remove(path)
        }
    }

    /** Scarta l'evento in attesa (click che ferma la riproduzione). */
    fun clearPending() {
        synchronized(lock) {
            lastPath = null
            lastAtElapsed = 0
        }
    }

    /** Percorso aperto entro maxAgeMs; consumato una sola volta. */
    fun consume(maxAgeMs: Long): String? {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val path = lastPath
            val fresh = path != null && now - lastAtElapsed in 0..maxAgeMs
            lastPath = null
            lastAtElapsed = 0
            return if (fresh) path else null
        }
    }

    private fun record(path: String) {
        val now = SystemClock.elapsedRealtime()
        val accepted: Boolean
        synchronized(lock) {
            val ignoredUntil = ignoreUntil[path] ?: 0
            accepted = ignoredUntil <= now && !selfAccess && now >= acceptAfterElapsed
            if (accepted) {
                lastPath = path
                lastAtElapsed = now
            }
        }
        if (accepted) {
            Log.d(AppConfig.TAG, "OPEN catturato: $path")
            onOpen?.invoke(path)
        }
    }

    companion object {
        private const val MASK = FileObserver.OPEN
        private const val SELF_ACCESS_GRACE_MS = 200L
    }
}
