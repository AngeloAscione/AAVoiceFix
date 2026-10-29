package dev.local.aawafixer

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Intercettore trasparente.
 *
 * 1. agisce solo se CarConnection segnala Android Auto connesso;
 * 2. ascolta i click su com.whatsapp e riconosce il Play di una nota vocale;
 * 3. dopo 350 ms riclicca lo stesso nodo per mettere in pausa il player
 *    nativo di WhatsApp (che instrada l'audio sul canale sbagliato);
 * 4. cerca il .opus corrispondente e lo riproduce su USAGE_MEDIA.
 *
 * I click generati dall'app stessa vengono ignorati tramite una finestra di
 * soppressione, altrimenti la pausa iniettata riattiverebbe il ciclo.
 */
class WaFixAccessibilityService : AccessibilityService() {

    private lateinit var monitor: AndroidAutoMonitor
    private lateinit var player: VoiceNotePlayer
    private lateinit var finder: VoiceNoteFinder
    private lateinit var openWatcher: VoiceNoteOpenWatcher

    private val main = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    @Volatile
    private var suppressClicksUntil = 0L

    @Volatile
    private var suppressAutoUntil = 0L

    private val autoToken = AtomicInteger(0)

    private var currentOwnPath: String? = null
    private var currentSignature: String? = null

    // Cache firma messaggio -> percorso esatto, valida solo per la sessione
    // Android Auto. Serve ai riascolti, dove WhatsApp riusa il player e non
    // genera un nuovo evento OPEN.
    private val exactCache = HashMap<String, String>()

    private data class Bubble(
        val meta: VoiceNoteUi,
        val playNode: AccessibilityNodeInfo,
        val playViewId: String?,
        val playBounds: Rect,
    )

    private class FlatNodes(
        val nodes: List<UiNode>,
        val handles: Map<Int, AccessibilityNodeInfo>,
    )

    private var initialized = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (initialized) return
        initialized = true

        finder = VoiceNoteFinder(this)
        openWatcher = VoiceNoteOpenWatcher(
            rootsProvider = { AppConfig.directVoiceDirs() },
            onOpen = ::onWatcherOpen,
        )
        player = VoiceNotePlayer(
            context = this,
            onPlayingChanged = { playing ->
                StatusStore.setAudio(this, if (playing) "in riproduzione" else "fermo")
                if (!playing) {
                    currentOwnPath?.let { openWatcher.releaseOwn(it) }
                    currentOwnPath = null
                    currentSignature = null
                }
            },
            onError = { message -> StatusStore.set(this, message) },
        )
        monitor = AndroidAutoMonitor(this) { connected ->
            Log.i(AppConfig.TAG, "Android Auto: $connected")
            if (connected) {
                // Watcher creato solo durante la sessione Android Auto.
                openWatcher.start()
            } else {
                generation.incrementAndGet()
                autoToken.incrementAndGet()
                openWatcher.stop()
                player.stop()
                exactCache.clear()
            }
        }
        monitor.start()
        StatusStore.set(
            this,
            "servizio attivo — Android Auto ${if (monitor.isConnected) "connesso" else "non connesso"}",
        )
        Log.i(AppConfig.TAG, "servizio di accessibilita' connesso")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != AppConfig.WHATSAPP_PACKAGE) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            Log.v(AppConfig.TAG, "evento WhatsApp tipo ${event.eventType} ignorato")
            return
        }

        val source = event.source
        // Niente contentDescription: nelle righe di chat puo' contenere il
        // testo del messaggio. Per il vocale i metadati utili sono loggati
        // dopo il parsing (durata/orario).
        Log.d(
            AppConfig.TAG,
            "click WhatsApp class=${event.className} source=${source != null} " +
                "id=${source?.viewIdResourceName}",
        )
        if (SystemClock.uptimeMillis() < suppressClicksUntil) {
            Log.d(AppConfig.TAG, "click iniettato ignorato")
            return
        }
        if (!monitor.isConnected) {
            Log.d(AppConfig.TAG, "click ignorato: Android Auto non connesso")
            return
        }
        if (source == null) {
            Log.w(AppConfig.TAG, "click senza source: nodo non ispezionabile")
            return
        }

        val bubble = try {
            locateBubble(source)
        } catch (t: Throwable) {
            Log.e(AppConfig.TAG, "lettura albero WhatsApp fallita", t)
            null
        }
        if (bubble == null) {
            Log.d(AppConfig.TAG, "fumetto vocale non riconosciuto")
            return
        }

        Log.i(
            AppConfig.TAG,
            "vocale ${bubble.meta.summary} id=${bubble.playViewId} " +
                "bounds=${bubble.playBounds}",
        )
        val scanGeneration = generation.incrementAndGet()
        val wasActive = player.isActive
        // Solo il click sullo stesso vocale ferma; un altro vocale lo
        // sostituisce.
        val sameNote = wasActive && bubble.meta.signature == currentSignature
        suppressAutoUntil = SystemClock.uptimeMillis() + AUTO_OPEN_SUPPRESS_MS
        autoToken.incrementAndGet()
        player.stop()
        StatusStore.set(
            this,
            "vocale ${bubble.meta.summary}" + if (sameNote) " — stop richiesto" else "",
        )
        scheduleNativePause(bubble)

        if (sameNote) {
            openWatcher.clearPending()
            Log.i(AppConfig.TAG, "stesso vocale: player fermato")
            return
        }
        if (wasActive) {
            Log.i(AppConfig.TAG, "switch vocale: fermo il precedente, riproduco il nuovo")
        }

        val clickAt = SystemClock.uptimeMillis()
        // L'open di WhatsApp puo' arrivare pochi ms dopo l'evento di click.
        main.postDelayed({
            if (scanGeneration != generation.get()) return@postDelayed
            openWatcher.ensureFresh()
            val caught = openWatcher.consume(EXACT_OPEN_WINDOW_MS)
            val cached = if (caught == null) {
                exactCache[bubble.meta.signature]?.takeIf { File(it).isFile }
            } else {
                null
            }
            val exactPath = caught ?: cached
            if (exactPath != null) {
                val exactFile = File(exactPath)
                if (exactFile.isFile) {
                    rememberExact(bubble.meta.signature, exactPath)
                    val label = if (caught != null) {
                        "match esatto ${exactFile.name}"
                    } else {
                        "match cache ${exactFile.name}"
                    }
                    schedulePlayback(
                        uri = Uri.fromFile(exactFile),
                        ownFile = exactFile,
                        label = label,
                        signature = bubble.meta.signature,
                        scanGeneration = scanGeneration,
                        clickAt = clickAt,
                    )
                    return@postDelayed
                }
                Log.w(AppConfig.TAG, "file esatto sparito: $exactPath")
            }
            scanFallback(bubble, scanGeneration, clickAt)
        }, EXACT_CHECK_DELAY_MS)
    }

    /** Fallback: durata UI + orario + mtime, senza il cieco "ultimo file". */
    private fun scanFallback(bubble: Bubble, scanGeneration: Int, clickAt: Long) {
        scanExecutor.execute {
            openWatcher.beginSelfAccess()
            val result = try {
                finder.find(bubble.meta)
            } catch (t: Throwable) {
                Log.e(AppConfig.TAG, "scansione note vocali fallita", t)
                null
            } finally {
                openWatcher.endSelfAccess()
            }
            main.post {
                if (scanGeneration != generation.get()) {
                    Log.d(AppConfig.TAG, "scansione superata da un nuovo click")
                    return@post
                }
                val file = result?.file
                if (file == null) {
                    Log.w(AppConfig.TAG, "nessun .opus trovato per ${bubble.meta.summary}")
                    StatusStore.set(this, "nessun .opus per ${bubble.meta.summary}")
                    return@post
                }
                val ownFile = if (file.uri.scheme == "file") {
                    file.uri.path?.let(::File)
                } else {
                    null
                }
                if (ownFile != null) {
                    // Il fallback e' deterministico: memorizzarlo rende
                    // immediati i riascolti (WhatsApp riusa il player e non
                    // genera un nuovo OPEN). La cache vive solo nella sessione
                    // Android Auto.
                    rememberExact(bubble.meta.signature, ownFile.absolutePath)
                }
                schedulePlayback(
                    uri = file.uri,
                    ownFile = ownFile,
                    label = "match ${file.name} durata=${file.durationMs} mtime=${file.lastModified}",
                    signature = bubble.meta.signature,
                    scanGeneration = scanGeneration,
                    clickAt = clickAt,
                )
            }
        }
    }

    private fun rememberExact(signature: String, path: String) {
        if (exactCache.size > 128) exactCache.clear()
        exactCache[signature] = path
    }

    private fun schedulePlayback(
        uri: Uri,
        ownFile: File?,
        label: String,
        signature: String?,
        scanGeneration: Int,
        clickAt: Long,
    ) {
        val delay = (PLAY_DELAY_MS - (SystemClock.uptimeMillis() - clickAt)).coerceAtLeast(0L)
        main.postDelayed({
            if (scanGeneration != generation.get()) return@postDelayed
            Log.i(AppConfig.TAG, label)
            StatusStore.set(this, label)
            // L'ignore va impostato prima dell'avvio: ExoPlayer apre il file
            // su un thread di playback. Lo stop interno del player rilascia
            // il percorso PRECEDENTE, non questo.
            ownFile?.let { openWatcher.ignoreOwn(it.absolutePath) }
            val started = player.play(uri)
            if (started) {
                ownFile?.let { currentOwnPath = it.absolutePath }
                currentSignature = signature
            } else {
                ownFile?.let { openWatcher.releaseOwn(it.absolutePath) }
            }
        }, delay)
    }

    /**
     * Un OPEN senza click = WhatsApp sta riproducendo da solo (auto-advance
     * della nota successiva). Un click imminente annulla questo percorso:
     * la finestra di debounce copre l'arrivo asincrono dell'evento.
     */
    private fun onWatcherOpen(path: String) {
        val token = autoToken.incrementAndGet()
        main.postDelayed({
            if (token != autoToken.get()) return@postDelayed
            if (SystemClock.uptimeMillis() < suppressAutoUntil) return@postDelayed
            if (!monitor.isConnected) return@postDelayed
            handleAutoOpen(path)
        }, AUTO_OPEN_DEBOUNCE_MS)
    }

    private fun handleAutoOpen(path: String) {
        val file = File(path)
        if (!file.isFile || path == currentOwnPath) return

        val bubble = try {
            findPauseControl()?.let { locateBubble(it) }
        } catch (t: Throwable) {
            Log.e(AppConfig.TAG, "auto: lettura albero WhatsApp fallita", t)
            null
        }
        if (bubble != null) {
            clickNodeNow(bubble)
        } else {
            Log.d(AppConfig.TAG, "auto: controllo nativo in pausa non trovato")
        }

        if (player.isActive) {
            Log.i(AppConfig.TAG, "auto: fermo il player precedente")
            player.stop()
        }

        openWatcher.ignoreOwn(file.absolutePath)
        val started = player.play(Uri.fromFile(file))
        if (started) {
            currentOwnPath = file.absolutePath
            currentSignature = bubble?.meta?.signature
            val extra = bubble?.meta?.summary?.let { " ($it)" }.orEmpty()
            Log.i(AppConfig.TAG, "auto-advance ${file.name}$extra")
            StatusStore.set(this, "auto ${file.name}")
        } else {
            openWatcher.releaseOwn(file.absolutePath)
        }
    }

    /** Controllo di pausa della nota nativa attualmente in riproduzione. */
    private fun findPauseControl(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var fallback: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val node = queue.removeFirst()
            visited++
            val label = node.contentDescription?.toString() ?: node.text?.toString()
            if (node.viewIdResourceName?.endsWith("/control_btn") == true &&
                WhatsAppUiParser.isPauseLabel(label)
            ) {
                if (isInsideVoiceRow(node)) return node
                if (fallback == null) fallback = node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return fallback
    }

    private fun isInsideVoiceRow(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 8) {
            val viewId = parent.viewIdResourceName.orEmpty()
            if (viewId.contains("conversation_row_voice_note") ||
                viewId.contains("audio_root_layout")
            ) {
                return true
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    private fun clickNodeNow(bubble: Bubble) {
        suppressClicksUntil = SystemClock.uptimeMillis() + SUPPRESS_WINDOW_MS
        var clicked = bubble.playNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            clicked = findFreshNode(bubble.playViewId, bubble.playBounds)
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        if (clicked) {
            Log.i(AppConfig.TAG, "auto: pausa del player nativo inviata")
        } else {
            Log.w(AppConfig.TAG, "auto: impossibile mettere in pausa il player nativo")
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        generation.incrementAndGet()
        autoToken.incrementAndGet()
        if (initialized) {
            openWatcher.stop()
            monitor.stop()
            player.stop()
        }
        scanExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Individua il pulsante Play del vocale cliccato, poi risale al fumetto
     * che contiene la durata e l'orario. Accettare il primo livello che fa
     * parse non basta: il solo pulsante soddisfa il parser ma non ha i
     * metadati, che stanno nel contenitore padre.
     */
    @Suppress("DEPRECATION")
    private fun locateBubble(source: AccessibilityNodeInfo): Bubble? {
        val playReference = findPlayReference(source)
        if (playReference == null) {
            Log.d(AppConfig.TAG, "nessun pulsante Play nel sottoalbero del click")
            return null
        }

        var current: AccessibilityNodeInfo? = source
        var depth = 0
        var best: Bubble? = null
        var bestScore = -1
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            val flat = flatten(current)
            val meta = WhatsAppUiParser.parse(flat.nodes)
            if (meta != null) {
                val handle = flat.handles[meta.playNodeId]
                val flatPlay = flat.nodes.first { it.id == meta.playNodeId }
                // Solo il Play che appartiene a questo ramo: evita di prendere
                // il primo pulsante di un altro messaggio nel contenitore chat.
                // Il confronto e' per viewId + bounds: i wrapper
                // AccessibilityNodeInfo non sono reference-stable tra sottoalberi.
                if (handle != null && samePlayNode(flatPlay, playReference)) {
                    // La durata pesa piu' dell'orario: un livello con sola
                    // durata non deve perdere contro uno con solo orario.
                    val score = (if (meta.durationSeconds != null) 2 else 0) +
                        (if (meta.clockMinutes != null) 1 else 0)
                    if (score > bestScore) {
                        best = Bubble(
                            meta = meta,
                            playNode = AccessibilityNodeInfo.obtain(handle),
                            playViewId = flatPlay.viewId,
                            playBounds = Rect(
                                flatPlay.left,
                                flatPlay.top,
                                flatPlay.right,
                                flatPlay.bottom,
                            ),
                        )
                        bestScore = score
                    }
                    if (bestScore >= 3) break
                }
            }
            current = current.parent
            depth++
            if (current?.packageName?.toString() != AppConfig.WHATSAPP_PACKAGE) break
        }
        if (best == null) {
            Log.d(AppConfig.TAG, "fumetto trovato ma senza durata/orario riconoscibili")
        }
        return best
    }

    private data class PlayReference(
        val viewId: String?,
        val className: String?,
        val bounds: Rect,
    )

    /** Il click puo' arrivare sul pulsante, su un suo figlio o sul fumetto. */
    private fun findPlayReference(source: AccessibilityNodeInfo): PlayReference? {
        var current: AccessibilityNodeInfo? = source
        var depth = 0
        while (current != null && depth <= MAX_PLAY_SEARCH_DEPTH) {
            val flat = flatten(current)
            val node = flat.nodes.firstOrNull(WhatsAppUiParser::isPlayNode)
            if (node != null) {
                return PlayReference(
                    viewId = node.viewId,
                    className = node.className,
                    bounds = Rect(node.left, node.top, node.right, node.bottom),
                )
            }
            current = current.parent
            depth++
            if (current?.packageName?.toString() != AppConfig.WHATSAPP_PACKAGE) break
        }
        return null
    }

    private fun samePlayNode(node: UiNode, reference: PlayReference): Boolean {
        val bounds = Rect(node.left, node.top, node.right, node.bottom)
        if (bounds != reference.bounds) return false
        return if (reference.viewId != null) {
            node.viewId == reference.viewId
        } else {
            node.className == reference.className
        }
    }

    private fun flatten(root: AccessibilityNodeInfo): FlatNodes {
        val nodes = ArrayList<UiNode>(64)
        val handles = HashMap<Int, AccessibilityNodeInfo>(64)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && nodes.size < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val id = nodes.size
            nodes += UiNode(
                id = id,
                viewId = node.viewIdResourceName,
                className = node.className?.toString(),
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
            )
            handles[id] = node
            if (depth < MAX_NODE_DEPTH) {
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return FlatNodes(nodes, handles)
    }

    /**
     * Il player nativo di WhatsApp parte subito: lo fermiamo con un click
     * sullo stesso nodo dopo una frazione di secondo. Il nodo salvato puo'
     * essere diventato stale, in quel caso lo si ritrova per viewId + bounds.
     */
    private fun scheduleNativePause(bubble: Bubble) {
        val node = bubble.playNode
        val viewId = bubble.playViewId
        val bounds = Rect(bubble.playBounds)

        main.postDelayed({
            suppressClicksUntil = SystemClock.uptimeMillis() + SUPPRESS_WINDOW_MS
            var clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                val fresh = findFreshNode(viewId, bounds)
                clicked = fresh?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            if (clicked) {
                Log.i(AppConfig.TAG, "pausa del player nativo inviata")
            } else {
                Log.w(AppConfig.TAG, "impossibile mettere in pausa il player nativo")
            }
        }, PAUSE_DELAY_MS)
    }

    private fun findFreshNode(viewId: String?, bounds: Rect): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val node = queue.removeFirst()
            visited++
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val sameId = viewId != null && node.viewIdResourceName == viewId
            if (rect == bounds && (sameId || node.isClickable)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return null
    }

    companion object {
        private const val PAUSE_DELAY_MS = 350L
        private const val PLAY_DELAY_MS = 450L
        private const val EXACT_CHECK_DELAY_MS = 120L
        private const val EXACT_OPEN_WINDOW_MS = 2_500L
        private const val AUTO_OPEN_DEBOUNCE_MS = 300L
        private const val AUTO_OPEN_SUPPRESS_MS = 2_000L
        private const val SUPPRESS_WINDOW_MS = 1_500L
        private const val MAX_ANCESTOR_DEPTH = 9
        private const val MAX_PLAY_SEARCH_DEPTH = 4
        private const val MAX_NODES = 300
        private const val MAX_NODE_DEPTH = 12
        private const val MAX_SEARCH_NODES = 600
    }
}
