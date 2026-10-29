package dev.local.aawafixer

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.media.AudioAttributes as PlatformAudioAttributes

/**
 * Riproduce il .opus sul canale MEDIA, usando USAGE_MEDIA +
 * CONTENT_TYPE_SPEECH: e' esattamente il canale che Android Auto proietta
 * sulle casse dell'auto (lo stesso usato da Telegram).
 *
 * L'AudioFocus e' richiesto in modalita' TRANSIENT_MAY_DUCK: la musica in auto
 * si abbassa, non si interrompe. Al termine (o allo stop) focus e player
 * vengono rilasciati.
 */
class VoiceNotePlayer(
    private val context: Context,
    private val onPlayingChanged: ((Boolean) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var player: ExoPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    val isActive: Boolean get() = player != null

    /** @return false se AudioFocus viene negato e il player non parte. */
    fun play(uri: Uri): Boolean {
        stop()

        val platformAttributes = PlatformAudioAttributes.Builder()
            .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
            .setContentType(PlatformAudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(platformAttributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, main)
            .build()

        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(AppConfig.TAG, "AudioFocus negato")
            onError?.invoke("AudioFocus negato da un'altra app")
            return false
        }
        focusRequest = request

        val exo = ExoPlayer.Builder(context.applicationContext).build()
        player = exo
        exo.setAudioAttributes(
            Media3AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ false,
        )
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlayingChanged?.invoke(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    Log.i(AppConfig.TAG, "vocale terminato")
                    stop()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(AppConfig.TAG, "errore di riproduzione", error)
                onError?.invoke("Errore riproduzione: ${error.errorCodeName}")
                stop()
            }
        })
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true
        Log.i(AppConfig.TAG, "riproduco $uri")
        return true
    }

    fun stop() {
        val previous = player
        player = null
        previous?.let {
            it.stop()
            it.release()
        }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        if (previous != null) onPlayingChanged?.invoke(false)
    }

    private fun onFocusChange(change: Int) {
        val exo = player ?: return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> exo.volume = 1f
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> exo.volume = 0.25f
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS -> stop()
        }
    }
}
