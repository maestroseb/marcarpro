package com.maestroseb.marcarpro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Keeps the board's ink between sessions, so closing Marcar Pro (on purpose or not) never
 * loses a lesson. A compact binary file: the whole board is a few hundred KB at most, plus any
 * pictures cut out of the screen (each as a PNG, after its corners) and texts (each as UTF-8, too).
 */
internal object InkStore {
    private const val MAGIC = 0x4D4B5031 // "MKP1"
    private const val MAX_ITEMS = 1_000_000
    private const val MAX_PICTURE = 64 * 1024 * 1024
    private const val MAX_TEXT = 1024 * 1024
    private const val STROKE_HEADER = 23 // tool, colour, size, speed unit, group, 2 flags, point count

    /** Serialises on the calling thread (one pass into one buffer); the caller may write the bytes elsewhere. */
    /** The whole board in one array (tests, and anything that needs it at once). */
    fun encode(strokes: List<Stroke>): ByteArray {
        val parts = encodeParts(strokes)
        val all = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(all, at)
            at += p.size
        }
        return all
    }

    /**
     * The board as pieces to write one after the other: the ink, encoded now, and each picture's
     * PNG as it already is, never copied. Saving after every change, a board with a few pasted
     * cut-outs would otherwise copy megabytes on the main thread each time.
     */
    fun encodeParts(strokes: List<Stroke>): List<ByteArray> {
        val parts = ArrayList<ByteArray>()
        var i = 0
        var first = true
        while (true) {
            // One run of strokes up to and including the next picture (its PNG is a part of its own).
            var size = if (first) 8 else 0
            var j = i
            var picture: ByteArray? = null
            while (j < strokes.size) {
                val s = strokes[j++]
                size += STROKE_HEADER + 12 * s.points.size
                if (s.tool == InkTool.TEXT) size += 4 + textData(s).size
                if (s.tool == InkTool.IMAGE) {
                    picture = pictureData(s)
                    size += 4
                    break
                }
            }
            // Big-endian, the same layout DataOutputStream wrote (old files still load).
            val out = ByteBuffer.allocate(size)
            if (first) {
                out.putInt(MAGIC)
                out.putInt(strokes.size)
                first = false
            }
            for (k in i until j) {
                val s = strokes[k]
                out.put(s.tool.ordinal.toByte())
                out.putInt(s.color)
                out.putFloat(s.size)
                out.putFloat(s.speedUnit)
                out.putInt(s.group)
                out.put(if (s.uniform) 1 else 0)
                out.put(if (s.hasRealPressure) 1 else 0)
                out.putInt(s.points.size)
                for (p in s.points) {
                    out.putFloat(p.x)
                    out.putFloat(p.y)
                    out.putFloat(p.pressure)
                }
                if (s.tool == InkTool.IMAGE) out.putInt(picture!!.size)
                if (s.tool == InkTool.TEXT) {
                    val words = textData(s)
                    out.putInt(words.size)
                    out.put(words)
                }
            }
            parts += out.array()
            if (picture != null) parts += picture
            i = j
            if (i >= strokes.size) return parts
        }
    }

    private fun textData(s: Stroke): ByteArray = s.text.orEmpty().toByteArray(Charsets.UTF_8)

    /** A picture's PNG: made when it was cut out, or now if that hasn't finished yet. */
    private fun pictureData(s: Stroke): ByteArray = s.imageData ?: png(s.image!!).also { s.imageData = it }

    fun png(image: Bitmap): ByteArray =
        java.io.ByteArrayOutputStream().use { out ->
            image.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

    /** Returns the saved strokes, or an empty list when there is nothing (valid) to load. */
    fun decode(data: ByteArray): List<Stroke> = try {
        DataInputStream(ByteArrayInputStream(data)).use { input ->
            if (input.readInt() != MAGIC) return emptyList()
            val count = input.readInt()
            if (count !in 0..MAX_ITEMS) return emptyList()
            List(count) {
                val tool = InkTool.entries[input.readUnsignedByte()]
                val color = input.readInt()
                val size = input.readFloat()
                val speed = input.readFloat()
                val group = input.readInt()
                val uniform = input.readBoolean()
                val realPressure = input.readBoolean()
                val n = input.readInt()
                if (n !in 0..MAX_ITEMS) throw IOException("corrupt")
                val pts = List(n) { InkPoint(input.readFloat(), input.readFloat(), input.readFloat()) }
                val s = if (tool == InkTool.IMAGE) {
                    val len = input.readInt()
                    if (len !in 1..MAX_PICTURE) throw IOException("corrupt")
                    val png = ByteArray(len).also { input.readFully(it) }
                    val image = BitmapFactory.decodeByteArray(png, 0, len) ?: throw IOException("corrupt")
                    Stroke.savedPicture(image, png)
                } else if (tool == InkTool.TEXT) {
                    val len = input.readInt()
                    if (len !in 0..MAX_TEXT) throw IOException("corrupt")
                    val words = ByteArray(len).also { input.readFully(it) }
                    Stroke.savedText(String(words, Charsets.UTF_8), color, size)
                } else {
                    Stroke(tool, color, size, speed)
                }
                s.apply {
                    this.group = group
                    this.uniform = uniform
                    hasRealPressure = realPressure
                    points.addAll(pts)
                    finish()
                }
            }
        }
    } catch (_: IOException) {
        emptyList()
    } catch (_: RuntimeException) {
        // A damaged file (bad tool index, absurd sizes…) loads as an empty board, never a crash.
        emptyList()
    }

    /**
     * Writes atomically and synced to storage: a crash or a power cut mid-write leaves the
     * previous board intact (the panel is often switched off at the wall).
     */
    fun write(file: File, data: ByteArray) = write(file, listOf(data))

    /** Writes [parts] one after the other, all or nothing (the old file stays if it fails). */
    fun write(file: File, parts: List<ByteArray>) {
        val atomic = AtomicFile(file)
        var out: java.io.FileOutputStream? = null
        try {
            out = atomic.startWrite()
            for (p in parts) out.write(p)
            atomic.finishWrite(out)
        } catch (_: IOException) {
            if (out != null) atomic.failWrite(out)
        }
    }

    /** Removes a board's file (and AtomicFile's backup), e.g. once its ink is all gone. */
    fun delete(file: File) = AtomicFile(file).delete()

    fun read(file: File): List<Stroke> {
        val atomic = AtomicFile(file)
        val data = try {
            atomic.readFully()
        } catch (_: IOException) {
            return emptyList()
        }
        return decode(data)
    }
}
