package com.maestroseb.marcarpro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Keeps Marcar Pro up to date from the GitHub releases of [BuildConfig.UPDATE_REPO], so every
 * panel in a school gets the improvements without anyone touching it.
 *
 * Once a day it asks GitHub for the latest release (tag `v<versionName>`, the APK attached). A
 * newer one is downloaded in the background and checked: same package, same signing key as the
 * installed app (anything else is thrown away). It is installed only when nobody is writing:
 * when the layer starts (the panel boots, the app opens) or after a long time without touches,
 * because installing restarts the app. From Android 12 an app that updates itself does so
 * without asking; the first time, the panel must allow Marcar Pro to install apps (the layer
 * shows a card that takes the teacher there).
 *
 * Everything runs on the caller's background thread; network and disk only.
 */
internal class Updater(context: Context) {

    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("marcar_updates", Context.MODE_PRIVATE)
    private val apk = File(this.context.cacheDir, "update.apk")

    /** Checks GitHub (at most every few hours, or when [force]d) and downloads a newer, valid release. */
    fun checkAndDownload(force: Boolean = false) {
        if (BuildConfig.UPDATE_REPO.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_CHECKED, 0) < CHECK_EVERY_MS) return
        prefs.edit { putLong(KEY_CHECKED, now) }
        runCatching {
            val release = JSONObject(get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"))
            val version = release.getString("tag_name").removePrefix("v").trim()
            if (!isNewer(version, BuildConfig.VERSION_NAME)) {
                apk.delete()
                return
            }
            if (prefs.getString(KEY_READY, null) == version && apk.exists()) return
            val assets = release.getJSONArray("assets")
            val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url") ?: return
            download(url, apk)
            if (isOurs(apk)) {
                prefs.edit { putString(KEY_READY, version) }
                Log.i(TAG, "update $version ready")
            } else {
                Log.w(TAG, "release $version is not signed like this app: ignored")
                apk.delete()
            }
        }.onFailure { Log.i(TAG, "update check failed: ${it.message}") }
    }

    /** True when a downloaded update is waiting to be installed. */
    val ready: Boolean get() = prefs.getString(KEY_READY, null) != null && apk.exists()

    /** What [installIfReady] did. */
    enum class Install { NOTHING, STARTED, NEEDS_PERMISSION }

    /**
     * Installs the waiting update (the app restarts afterwards). [Install.NEEDS_PERMISSION]: the
     * panel doesn't let Marcar Pro install apps yet (the caller asks on screen: a notification
     * would be hidden when the app's notifications are off).
     */
    fun installIfReady(): Install {
        if (!ready) return Install.NOTHING
        if (!context.packageManager.canRequestPackageInstalls()) return Install.NEEDS_PERMISSION
        return runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("marcarpro.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val done = PendingIntent.getBroadcast(
                    context, id, Intent(context, InstallResult::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                prefs.edit { remove(KEY_READY) }
                session.commit(done.intentSender)
            }
            Install.STARTED
        }.onFailure { Log.w(TAG, "install failed", it) }.getOrDefault(Install.NOTHING)
    }

    /** The screen where the teacher lets Marcar Pro install its updates. */
    fun permissionScreen(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun get(url: String): String {
        val c = open(url)
        try {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, to: File) {
        val part = File(to.parentFile, to.name + ".part")
        val c = open(url)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            c.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        if (!part.renameTo(to)) error("can't keep the download")
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true // release files are served from another host
        setRequestProperty("User-Agent", "MarcarPro/${BuildConfig.VERSION_NAME}")
    }

    /** Same package and same signing key as the app installed here. */
    private fun isOurs(file: File): Boolean {
        val pm = context.packageManager
        val downloaded = pm.getPackageArchiveInfo(file.path, SIGNATURES) ?: return false
        if (downloaded.packageName != context.packageName) return false
        val mine = pm.getPackageInfo(context.packageName, SIGNATURES)
        return signers(downloaded) == signers(mine) && signers(mine).isNotEmpty()
    }

    private fun signers(info: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()
        return sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** What the system says about the install; asks the teacher only if it insists on it. */
    class InstallResult : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                Log.w(TAG, "install status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }

    companion object {
        private const val TAG = "MarcarUpdate"
        private const val KEY_CHECKED = "checked"
        private const val KEY_READY = "ready"
        private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000

        @Suppress("DEPRECATION")
        private val SIGNATURES = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

        /** "1.10.0" > "1.9.2": compares the dotted numbers one by one. */
        internal fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
