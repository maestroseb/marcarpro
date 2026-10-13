package com.maestroseb.marcarpro

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * The panel's own "Crop" (ViewSonic's system bar), used the way the bar uses it. Android gives
 * apps no picture of the PC or HDMI, but that tool is part of the system and can: Marcar Pro
 * starts it, the teacher cuts out and saves as usual, and the saved picture comes to the whiteboard.
 *
 * What the panel does (seen on the IFP7533, 2026-10-13): the bar starts the exported service
 * [SERVICE] (no extras); it broadcasts [ACTION_FINISH] when it closes; a saved cut-out is a JPEG
 * in `Notes/Mark/<date>/` on the shared storage, in the media store. After saving and its
 * "Saved" dialog (closed by [SAVED_CLOSE]) it stays open for more cut-outs until its Exit button ([EXIT_BUTTON]) is pressed.
 */
internal class PanelCrop(private val context: Context) {

    private var receiver: BroadcastReceiver? = null

    /** Whether this panel has ViewSonic's Crop. */
    val available: Boolean
        get() = runCatching {
            context.packageManager.getServiceInfo(ComponentName(PACKAGE, SERVICE), 0)
        }.isSuccess

    val canRead: Boolean
        get() = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Starts the panel's Crop; [onFinish] runs (once) when it closes. False if it couldn't start. */
    fun start(onFinish: () -> Unit): Boolean {
        stop()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                stop()
                onFinish()
            }
        }
        // The panel's broadcast comes from another app: the receiver must take it.
        ContextCompat.registerReceiver(context, r, IntentFilter(ACTION_FINISH), ContextCompat.RECEIVER_EXPORTED)
        receiver = r
        val started = runCatching {
            context.startService(Intent().setComponent(ComponentName(PACKAGE, SERVICE))) != null
        }.getOrDefault(false)
        if (!started) stop()
        return started
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /** One of the Crop's own texts in the panel's language (to recognise its buttons and dialogs). */
    fun label(name: String): String? = runCatching {
        val res = context.packageManager.getResourcesForApplication(PACKAGE)
        @Suppress("DiscouragedApi") // another app's resource: only its name is known
        res.getString(res.getIdentifier(name, "string", PACKAGE))
    }.getOrNull()

    /** The panel's cut-outs saved since [sinceMs] (wall clock), oldest first. */
    fun savedSince(sinceMs: Long): List<Uri> {
        if (!canRead) return emptyList()
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION") // DATA is still the plain way to match a folder on every version
        val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val out = ArrayList<Uri>()
        runCatching {
            context.contentResolver.query(
                images,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DATE_ADDED} >= ? AND $folder LIKE ?",
                arrayOf(((sinceMs - SLACK_MS) / 1000).toString(), "%$FOLDER%"),
                "${MediaStore.Images.Media.DATE_ADDED} ASC",
            )?.use { c ->
                while (c.moveToNext()) out += ContentUris.withAppendedId(images, c.getLong(0))
            }
        }
        return out
    }

    companion object {
        const val PACKAGE = "com.seewo.cropscreen"
        const val SERVICE = "com.seewo.cropscreen.services.CropScreenService"
        const val ACTION_FINISH = "com.seewo.intent.action.CROP_FINISH"
        const val FOLDER = "Notes/Mark/"
        const val EXIT_BUTTON = "crop_float_menu_close"
        const val EXIT_LABEL = "crop_item_close"
        const val SAVED_TITLE = "crop_save_success_title"
        const val SAVED_CLOSE = "crop_save_success_close"
        private const val SLACK_MS = 2000L

        val PERMISSION =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
