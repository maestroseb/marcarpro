package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri

/**
 * Getting Marcar Pro's accessibility service ([TouchRelayService]) turned on: the panel's own
 * Settings hide Accessibility, so a feature that needs it explains why and opens the right screen.
 *
 * An app installed from an APK file is "restricted" on Android 13+: its accessibility switch is
 * greyed out until "Allow restricted settings" is chosen in its App info (⋮ menu); then App info
 * opens first, and the text says what to do there.
 */
internal object RelaySetup {

    /** Whether Android 13+ still blocks this app's accessibility switch (installed from a file). */
    fun blocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            context.getSystemService(AppOpsManager::class.java)
                .unsafeCheckOpNoThrow(OP_RESTRICTED_SETTINGS, context.applicationInfo.uid, context.packageName) == AppOpsManager.MODE_ERRORED
        }.getOrDefault(false)
    }

    /** Opens App info (when [blocked]) or, as directly as the system allows, the service's switch. */
    fun open(context: Context, blocked: Boolean) {
        if (blocked) {
            start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            return
        }
        // Marcar Pro's own switch (not a public action: some builds refuse it), else the Accessibility list.
        val details = Intent(ACTION_ACCESSIBILITY_DETAILS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, TouchRelayService::class.java).flattenToString())
        if (!start(context, details)) start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private const val ACTION_ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** The app-op behind "Allow restricted settings" (not in the public SDK). */
    private const val OP_RESTRICTED_SETTINGS = "android:access_restricted_settings"
}

/**
 * A card in the middle of a dimmed screen with a message and two buttons. It lives in the
 * layer's own windows: an activity would open under the ink, which catches every touch.
 */
@SuppressLint("ViewConstructor")
internal class PromptView(
    context: Context,
    title: String,
    message: String,
    yes: String,
    no: String,
    private val onAnswer: (Boolean) -> Unit,
) : FrameLayout(context) {

    init {
        setBackgroundColor(0x99101418.toInt())
        isClickable = true
        setOnClickListener { onAnswer(false) } // a tap around the card = "not now"
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(28f), dpi(24f), dpi(28f), dpi(18f))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(22f)
            }
            elevation = dp(12f)
            isClickable = true // taps on the card itself don't close it
        }
        card.addView(TextView(context).apply {
            text = title
            textSize = 22f
            setTextColor(OrbStyle.INK)
            typeface = Fonts.bold(context)
        })
        card.addView(TextView(context).apply {
            text = message
            textSize = 17f
            setTextColor(OrbStyle.LABEL)
            setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })
        val buttons = LinearLayout(context).apply { gravity = Gravity.END }
        buttons.addView(button(no, filled = false) { onAnswer(false) })
        buttons.addView(button(yes, filled = true) { onAnswer(true) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dpi(12f) })
        card.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(22f) })
        addView(card, LayoutParams(dpi(560f), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun button(label: String, filled: Boolean, onTap: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 16f
        typeface = Fonts.bold(context)
        setTextColor(if (filled) Color.WHITE else OrbStyle.TEAL_DARK)
        setPadding(dpi(22f), dpi(12f), dpi(22f), dpi(12f))
        background = GradientDrawable().apply {
            setColor(if (filled) OrbStyle.TEAL else Color.WHITE)
            cornerRadius = dp(24f)
            if (!filled) setStroke(dpi(1.5f), OrbStyle.TEAL)
        }
        isClickable = true
        setOnClickListener { onTap() }
    }
}
