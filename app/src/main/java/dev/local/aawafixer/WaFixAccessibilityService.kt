package dev.local.aawafixer

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    private val main = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    @Volatile
    private var suppressClicksUntil = 0L

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
        player = VoiceNotePlayer(
            context = this,
            onPlayingChanged = { playing ->
                StatusStore.setAudio(this, if (playing) "in riproduzione" else "fermo")
            },
            onError = { message -> StatusStore.set(this, message) },
        )
        monitor = AndroidAutoMonitor(this) { connected ->
            Log.i(AppConfig.TAG, "Android Auto: $connected")
            if (!connected) {
                generation.incrementAndGet()
                player.stop()
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
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        if (event.packageName?.toString() != AppConfig.WHATSAPP_PACKAGE) return

        if (SystemClock.uptimeMillis() < suppressClicksUntil) {
            Log.d(AppConfig.TAG, "click iniettato ignorato")
            return
        }
        if (!monitor.isConnected) return

        val source = event.source ?: return
        val bubble = try {
            locateBubble(source)
        } catch (t: Throwable) {
            Log.e(AppConfig.TAG, "lettura albero WhatsApp fallita", t)
            null
        } ?: return

        val scanGeneration = generation.incrementAndGet()
        val wasActive = player.isActive
        player.stop()
        StatusStore.set(
            this,
            "vocale ${bubble.meta.summary}" + if (wasActive) " — stop richiesto" else "",
        )
        scheduleNativePause(bubble)

        if (wasActive) {
            Log.i(AppConfig.TAG, "secondo click: player fermato")
            return
        }

        val clickAt = SystemClock.uptimeMillis()
        scanExecutor.execute {
            val file = try {
                finder.find(bubble.meta)
            } catch (t: Throwable) {
                Log.e(AppConfig.TAG, "scansione note vocali fallita", t)
                null
            }
            main.postDelayed({
                if (scanGeneration != generation.get()) {
                    Log.d(AppConfig.TAG, "scansione superata da un nuovo click")
                    return@postDelayed
                }
                if (file == null) {
                    Log.w(AppConfig.TAG, "nessun .opus trovato per ${bubble.meta.summary}")
                    StatusStore.set(this, "nessun .opus per ${bubble.meta.summary}")
                    return@postDelayed
                }
                Log.i(
                    AppConfig.TAG,
                    "match ${file.name} mtime=${file.lastModified} durata=${file.durationMs}",
                )
                StatusStore.set(this, "match ${file.name} (${file.durationMs ?: "?"} ms)")
                player.play(file.uri)
            }, (PLAY_DELAY_MS - (SystemClock.uptimeMillis() - clickAt)).coerceAtLeast(0L))
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        generation.incrementAndGet()
        if (initialized) {
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
        val playReference = findPlayReference(source) ?: return null

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
                    val score = (if (meta.durationSeconds != null) 1 else 0) +
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
                    if (bestScore == 2) break
                }
            }
            current = current.parent
            depth++
            if (current?.packageName?.toString() != AppConfig.WHATSAPP_PACKAGE) break
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
        private const val SUPPRESS_WINDOW_MS = 1_500L
        private const val MAX_ANCESTOR_DEPTH = 9
        private const val MAX_PLAY_SEARCH_DEPTH = 4
        private const val MAX_NODES = 300
        private const val MAX_NODE_DEPTH = 12
        private const val MAX_SEARCH_NODES = 600
    }
}
