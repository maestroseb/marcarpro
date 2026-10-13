package com.maestroseb.marcarpro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect

/**
 * Debug only. `adb shell am broadcast -a com.maestroseb.marcarpro.DEBUG_AREA -n
 * com.maestroseb.marcarpro/.DebugPanelReceiver --ei id 7790 --ei l 0 --ei t 0 --ei r 3840 --ei b 2160
 * --ez cable true` (add `--ez remove true` to drop it). Results are logged under MarcarPanel.
 */
class DebugPanelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val panel = PanelTouch.current ?: return
        if (intent.getBooleanExtra("source", false)) {
            panel.querySource { android.util.Log.i("MarcarPanel", "source reply: $it") }
            return
        }
        val id = intent.getIntExtra("id", 7790)
        if (intent.getBooleanExtra("remove", false)) {
            panel.clearArea(id)
        } else {
            val r = Rect(intent.getIntExtra("l", 0), intent.getIntExtra("t", 0), intent.getIntExtra("r", 3840), intent.getIntExtra("b", 2160))
            panel.setArea(id, r, intent.getBooleanExtra("cable", true))
        }
    }
}
