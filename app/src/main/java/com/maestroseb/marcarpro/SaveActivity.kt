package com.maestroseb.marcarpro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * «Al pendrive»: Android's own "Save as" screen (it lists a USB stick next to the panel's memory),
 * and a copy of the PDF already kept in Downloads › Marcar Pro into the place chosen. Invisible;
 * it finishes as soon as the copy is done or the teacher backs out.
 */
class SaveActivity : Activity() {
    private var source: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = androidx.core.content.IntentCompat.getParcelableExtra(intent, EXTRA_SOURCE, Uri::class.java)
        if (source == null) return finish()
        if (savedInstanceState != null) return
        val pick = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = intent.type ?: "application/pdf"
            putExtra(Intent.EXTRA_TITLE, intent.getStringExtra(EXTRA_NAME))
        }
        if (runCatching { startActivityForResult(pick, PICK) }.isFailure) {
            toast(R.string.share_usb_missing)
            finish()
        }
    }

    @Deprecated("Activity result on a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val target = data?.data
        val from = source
        if (requestCode != PICK || resultCode != RESULT_OK || target == null || from == null) return finish()
        val app = applicationContext
        Executors.newSingleThreadExecutor().execute {
            val ok = runCatching {
                app.contentResolver.openInputStream(from)!!.use { input ->
                    app.contentResolver.openOutputStream(target, "w")!!.use { input.copyTo(it) }
                }
            }.isSuccess
            runOnUiThread {
                toast(if (ok) R.string.share_usb_done else R.string.share_failed)
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) onClosed?.invoke()
    }

    private fun toast(res: Int) = Toast.makeText(applicationContext, res, Toast.LENGTH_LONG).show()

    companion object {
        private const val PICK = 1

        /** Told on the main thread when this screen is gone (saved or not): the ink comes back. */
        var onClosed: (() -> Unit)? = null
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, source: Uri, name: String, mime: String): Intent =
            Intent(context, SaveActivity::class.java)
                .setType(mime)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
