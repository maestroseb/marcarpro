package com.maestroseb.marcarpro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Puts the ink layer up when the panel boots, so the orb is already there even if the
 * panel starts on the PC, and again after an update (installing a new version stops it).
 * The ViewBoard also sends its own boot broadcast, listened to as well.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in STARTS) return
        if (!Settings.canDrawOverlays(context) || OverlayService.running) return
        val start = Intent(context, OverlayService::class.java).putExtra(OverlayService.EXTRA_BOOT, intent.action != Intent.ACTION_MY_PACKAGE_REPLACED)
        runCatching { ContextCompat.startForegroundService(context, start) }
    }

    private companion object {
        val STARTS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "seewo.intent.action.BOOT_COMPLETED",
        )
    }
}
