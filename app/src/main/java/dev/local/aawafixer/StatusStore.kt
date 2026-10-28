package dev.local.aawafixer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Piccolo canale di telemetria locale tra servizio e Activity. */
object StatusStore {
    const val KEY_LAST_EVENT = "last_event"
    const val KEY_AUDIO_STATE = "audio_state"

    fun set(context: Context, message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date())
        AppConfig.prefs(context).edit()
            .putString(KEY_LAST_EVENT, "$stamp  $message")
            .apply()
    }

    fun setAudio(context: Context, message: String) {
        AppConfig.prefs(context).edit()
            .putString(KEY_AUDIO_STATE, message)
            .apply()
    }
}
