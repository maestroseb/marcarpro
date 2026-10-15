package com.maestroseb.marcarpro

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Invisible launcher: tapping the app icon puts the ink layer up and gets out of the way.
 * It only shows anything the first time, to ask for the permissions the layer needs.
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private var sentToSettings = false

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startOverlay() }

    // From the layer: to bring the panel's own cut-outs, Marcar Pro must see the panel's pictures.
    private val askImages =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            when {
                granted -> startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_PANEL_CROP))
                // Refused before: Android no longer asks, the switch is in the app's info.
                !shouldShowRequestPermissionRationale(PanelCrop.PERMISSION) -> {
                    Toast.makeText(this, R.string.images_in_settings, Toast.LENGTH_LONG).show()
                    runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                    }
                }
            }
            finishQuietly()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        sentToSettings = savedInstanceState?.getBoolean(KEY_SENT) ?: false
        if (savedInstanceState != null) return
        if (intent?.action == ACTION_ASK_IMAGES) askImages.launch(PanelCrop.PERMISSION) else launch()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SENT, sentToSettings)
    }

    override fun onRestart() {
        super.onRestart()
        // Back from the overlay setting: start if it was granted, otherwise just leave.
        if (sentToSettings) {
            sentToSettings = false
            if (Settings.canDrawOverlays(this)) launch() else finishQuietly()
        }
    }

    private fun launch() {
        when {
            !Settings.canDrawOverlays(this) -> requestOverlayPermission()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.askedNotifications &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                // Once only: the notification carries the Stop button.
                prefs.askedNotifications = true
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> startOverlay()
        }
    }

    private fun startOverlay() {
        // Opened by the teacher: the layer shows what is still missing to set up.
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SETUP))
        finishQuietly()
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                sentToSettings = true
                return
            } catch (_: ActivityNotFoundException) {
                // Some whiteboard firmwares strip this screen; try the next one.
            }
        }
        Toast.makeText(this, R.string.permission_unavailable, Toast.LENGTH_LONG).show()
        finishQuietly()
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val KEY_SENT = "sent_to_settings"
        const val ACTION_ASK_IMAGES = "com.maestroseb.marcarpro.action.ASK_IMAGES"
    }
}
