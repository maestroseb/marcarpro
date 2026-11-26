package com.maestroseb.marcarpro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.random.Random

/** The upload to the teacher's Drive in pieces, against a fake resumable upload that drops one piece. */
class DriveUploadTest {

    @Test
    fun aDroppedPieceIsSentAgainAndTheFileArrivesWhole() {
        val data = Random(7).nextBytes(5 * 1024 * 1024 + 12345) // three pieces of 2 MiB
        val received = ByteArrayOutputStream()
        var puts = 0
        var dropped = false
        val server = ServerSocket(0)
        val serving = thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use {
                    val input = DataInputStream(s.getInputStream().buffered())
                    val headers = mutableMapOf<String, String>()
                    var line = input.readLine() ?: return@use
                    while (true) {
                        line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
                    }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { input.readFully(it) }
                    puts++
                    val range = headers["content-range"]!!
                    val total = range.substringAfter('/').toLong()
                    fun reply(code: Int, extra: String = "") {
                        s.getOutputStream().write("HTTP/1.1 $code X\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        s.getOutputStream().flush()
                    }
                    val have = { if (received.size() > 0) "Range: bytes=0-${received.size() - 1}\r\n" else "" }
                    if (range.startsWith("bytes */")) return@use reply(308, have())
                    val start = range.removePrefix("bytes ").substringBefore('-').toLong()
                    // The second piece is lost on the way: the connection just ends.
                    if (start == 2L * 1024 * 1024 && !dropped) { dropped = true; return@use }
                    if (start == received.size().toLong()) received.write(body)
                    if (received.size().toLong() == total) reply(200) else reply(308, have())
                }
            }
        }
        try {
            val f = File.createTempFile("board", ".pdf").apply { writeBytes(data); deleteOnExit() }
            val seen = mutableListOf<Float>()
            RandomAccessFile(f, "r").channel.use { ch ->
                DriveLink.upload(ch, "http://127.0.0.1:${server.localPort}/up", { false }) { seen += it }
            }
            assertArrayEquals(data, received.toByteArray())
            assertTrue(dropped)
            assertEquals(1f, seen.last())
            assertTrue("pieces, a question and the piece again: $puts", puts >= 5)
        } finally {
            server.close()
            serving.join(1000)
        }
    }
}
