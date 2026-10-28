package dev.local.aawafixer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var statusAccessibility: TextView
    private lateinit var statusFiles: TextView
    private lateinit var statusSaf: TextView
    private lateinit var statusAuto: TextView
    private lateinit var statusWhatsApp: TextView
    private lateinit var statusAudio: TextView
    private lateinit var statusLastEvent: TextView

    private lateinit var monitor: AndroidAutoMonitor
    private lateinit var player: VoiceNotePlayer
    private lateinit var finder: VoiceNoteFinder
    private val scanExecutor = Executors.newSingleThreadExecutor()

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                StatusStore.KEY_LAST_EVENT -> updateTelemetry()
                StatusStore.KEY_AUDIO_STATE -> updateTelemetry()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusAccessibility = findViewById(R.id.status_accessibility)
        statusFiles = findViewById(R.id.status_files)
        statusSaf = findViewById(R.id.status_saf)
        statusAuto = findViewById(R.id.status_auto)
        statusWhatsApp = findViewById(R.id.status_whatsapp)
        statusAudio = findViewById(R.id.status_audio)
        statusLastEvent = findViewById(R.id.status_last_event)

        finder = VoiceNoteFinder(this)
        player = VoiceNotePlayer(
            context = this,
            onPlayingChanged = { playing ->
                StatusStore.setAudio(this, if (playing) "in riproduzione (test)" else "fermo")
            },
        )

        monitor = AndroidAutoMonitor(this) { updateAutoStatus() }
        monitor.start()

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_files).setOnClickListener { requestFileAccess() }
        findViewById<Button>(R.id.btn_saf).setOnClickListener { pickSafTree() }
        findViewById<Button>(R.id.btn_clear_saf).setOnClickListener { clearSafTree() }
        findViewById<Button>(R.id.btn_test).setOnClickListener { testPlayback() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { updateAll() }

        AppConfig.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        updateAll()
    }

    override fun onResume() {
        super.onResume()
        updateAll()
    }

    override fun onDestroy() {
        AppConfig.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        monitor.stop()
        player.stop()
        scanExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_SAF) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val granted = try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            AppConfig.prefs(this).edit()
                .putString(AppConfig.KEY_SAF_TREE, uri.toString())
                .apply()
            true
        } catch (e: SecurityException) {
            false
        }
        toast(if (granted) R.string.toast_saf_saved else R.string.toast_saf_failed)
        updateAll()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_READ_STORAGE) updateAll()
    }

    private fun updateAll() {
        val accessibilityOn = isAccessibilityEnabled()
        statusAccessibility.text = "Accessibilità: ${if (accessibilityOn) "ATTIVA" else "DISATTIVA"}"

        val direct = AppConfig.hasDirectFileAccess(this)
        val saf = AppConfig.safTreeUri(this)
        val fileState = when {
            direct -> "accesso completo ai file"
            saf != null -> "solo cartella SAF"
            else -> "nessun accesso"
        }
        statusFiles.text = "File: $fileState"
        statusSaf.text = "Cartella SAF: ${saf?.toString() ?: "—"}"

        updateAutoStatus()
        statusWhatsApp.text = "WhatsApp: ${if (isWhatsAppInstalled()) "installato" else "NON installato"}"
        updateTelemetry()
    }

    private fun updateAutoStatus() {
        statusAuto.text = "Android Auto: ${if (monitor.isConnected) "CONNESSO" else "non connesso"}"
    }

    private fun updateTelemetry() {
        val prefs = AppConfig.prefs(this)
        statusAudio.text = "Player: ${prefs.getString(StatusStore.KEY_AUDIO_STATE, "fermo")}"
        statusLastEvent.text = "Ultimo evento: ${prefs.getString(StatusStore.KEY_LAST_EVENT, "—")}"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!enabled) return false
        val services = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return services.split(':').any {
            it.contains(packageName) && it.contains(WaFixAccessibilityService::class.java.simpleName)
        }
    }

    private fun isWhatsAppInstalled(): Boolean = try {
        packageManager.getPackageInfo(AppConfig.WHATSAPP_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun requestFileAccess() {
        if (AppConfig.hasDirectFileAccess(this)) {
            toast(R.string.toast_files_granted)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            try {
                startActivity(appIntent)
            } catch (e: Exception) {
                val allFiles = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                try {
                    startActivity(allFiles)
                } catch (e2: Exception) {
                    toast(R.string.toast_no_all_files_screen)
                }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_READ_STORAGE)
        }
    }

    private fun pickSafTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        startActivityForResult(intent, REQUEST_SAF)
    }

    private fun clearSafTree() {
        val uri = AppConfig.safTreeUri(this) ?: return
        try {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Gia' revocato: va bene.
        }
        AppConfig.prefs(this).edit().remove(AppConfig.KEY_SAF_TREE).apply()
        updateAll()
    }

    private fun testPlayback() {
        scanExecutor.execute {
            val file = finder.newest()
            runOnUiThread {
                if (file == null) {
                    toast(R.string.toast_no_voice_notes)
                } else {
                    Toast.makeText(
                        this,
                        "Riproduco ${file.name} (${file.durationMs ?: "?"} ms)",
                        Toast.LENGTH_SHORT,
                    ).show()
                    player.play(file.uri)
                }
            }
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_SAF = 1001
        private const val REQUEST_READ_STORAGE = 1002
    }
}
