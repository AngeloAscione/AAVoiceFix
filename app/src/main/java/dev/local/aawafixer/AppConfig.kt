package dev.local.aawafixer

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import java.io.File

object AppConfig {
    const val TAG = "AAWaFixer"
    const val WHATSAPP_PACKAGE = "com.whatsapp"

    const val PREFS_NAME = "aawafixer"
    const val KEY_SAF_TREE = "saf_tree_uri"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun accessibilityComponent(context: Context): String =
        ComponentName(context, WaFixAccessibilityService::class.java).flattenToString()

    fun safTreeUri(context: Context): Uri? =
        prefs(context).getString(KEY_SAF_TREE, null)?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    /** Accesso diretto ai file tramite MANAGE_EXTERNAL_STORAGE. */
    fun hasDirectFileAccess(): Boolean = Environment.isExternalStorageManager()

    /**
     * Directory delle note vocali, dalla piu' recente struttura a quella legacy.
     * WhatsApp riceve i PTT in `Android/media/...`, i file inviati in `Sent/`.
     */
    fun directVoiceDirs(): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        val roots = listOf(
            File(storage, "Android/media/com.whatsapp/WhatsApp/Media"),
            File(storage, "WhatsApp/Media"),
        )
        return roots.flatMap { root ->
            listOf(
                File(root, "WhatsApp Voice Notes"),
                File(root, "WhatsApp Voice Notes/Sent"),
            )
        }.filter { it.isDirectory }
    }
}
