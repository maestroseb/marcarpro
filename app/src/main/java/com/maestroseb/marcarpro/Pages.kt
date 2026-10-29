package com.maestroseb.marcarpro

import java.security.MessageDigest

/**
 * Which "page" the ink belongs to. Every web page open in a supported browser gets its own
 * board (like the pages of Smart Ink); everything else shares the general board.
 */
internal object PageKeys {
    const val GENERAL = ""

    /**
     * The endless whiteboard, whatever is on screen underneath: this key is the quick board's
     * first page, and every whiteboard page starts with it (see [Boards.pageKey]).
     */
    const val WHITEBOARD = "whiteboard:"

    fun isWhiteboard(key: String) = key.startsWith(WHITEBOARD)

    /** Chromium browsers: they all name their address bar `<package>:id/url_bar`. */
    val BROWSERS = setOf(
        "com.brave.browser",
        "com.android.chrome",
        "com.chrome.beta",
        "org.chromium.chrome",
        "com.microsoft.emmx",
        "com.kiwibrowser.browser",
    )

    /**
     * Turns the address bar text into a page key, or null when it does not name a page
     * (empty, or a search being typed). Scheme, "www.", the #fragment and a trailing slash
     * don't make a different page.
     */
    fun fromAddressBar(text: CharSequence?): String? {
        var t = text?.toString()?.trim().orEmpty()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        t = t.substringBefore('#')
        t = t.removePrefix("https://").removePrefix("http://")
        val slash = t.indexOf('/').let { if (it < 0) t.length else it }
        val host = t.substring(0, slash).lowercase().removePrefix("www.")
        if (host.isEmpty() || ('.' !in host && ':' !in host && host != "localhost")) return null
        return "web:$host" + t.substring(slash).trimEnd('/')
    }

    /** File for a page's ink; page addresses themselves are never written to disk. */
    fun fileName(key: String): String = if (key == GENERAL) {
        "board.mkp"
    } else if (key == WHITEBOARD) {
        "whiteboard.mkp"
    } else if (key.startsWith(WHITEBOARD)) {
        // A whiteboard page ("whiteboard:<board>:<page>"): its own name, never taken for an old
        // web page's ink (those go after a month untouched; boards have their own rules).
        "wb_" + key.removePrefix(WHITEBOARD).replace(':', '_') + ".mkp"
    } else {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        "page_" + digest.take(12).joinToString("") { "%02x".format(it) } + ".mkp"
    }
}
