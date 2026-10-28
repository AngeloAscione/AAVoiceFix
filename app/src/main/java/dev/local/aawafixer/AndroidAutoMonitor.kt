package dev.local.aawafixer

import android.content.Context
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

/**
 * Stato ufficiale della connessione ad Android Auto, tramite la libreria
 * AndroidX Car Connection. Il servizio resta in ascolto passivo: nessun
 * polling, nessun processo aggiuntivo.
 */
class AndroidAutoMonitor(
    context: Context,
    private val onChanged: ((Boolean) -> Unit)? = null,
) {
    private val liveData: LiveData<Int> = CarConnection(context.applicationContext).type

    private val observer = Observer<Int> { type ->
        update(type ?: CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
    }

    private var started = false

    @Volatile
    var isConnected: Boolean = false
        private set

    fun start() {
        if (started) return
        started = true
        liveData.observeForever(observer)
    }

    fun stop() {
        if (!started) return
        started = false
        liveData.removeObserver(observer)
    }

    private fun update(type: Int) {
        val connected = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
            type == CarConnection.CONNECTION_TYPE_NATIVE
        if (connected != isConnected) {
            isConnected = connected
            Log.i(AppConfig.TAG, "Android Auto connected=$connected (type=$type)")
            onChanged?.invoke(connected)
        }
    }
}
